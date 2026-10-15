package app.foctuner.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import app.foctuner.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * A VESC reached over BLE through the Nordic UART service (VESC Express, the VESC BLE dongle, HM-10 style
 * modules flashed for VESC). The app writes to the RX characteristic and the controller answers with
 * notifications on the TX characteristic.
 *
 * Needs BLUETOOTH_CONNECT on Android 12+; [app.foctuner.link.Links.missingPermissions] says what is missing.
 * Bytes received are buffered until [received] is collected, which has a single consumer.
 */
@SuppressLint("MissingPermission")
class BleTransport private constructor(
    private val context: Context,
    private val device: BluetoothDevice,
    private val requestedMtu: Int,
) : Transport {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val ready = CompletableDeferred<Unit>()
    private val disconnected = CompletableDeferred<Unit>()
    private val writeLock = Mutex()

    @Volatile
    private var pendingWrite: CompletableDeferred<Int>? = null

    @Volatile
    private var mtu = DEFAULT_MTU

    @Volatile
    private var closedByUs = false

    @Volatile
    private var discoveryStarted = false

    private lateinit var gatt: BluetoothGatt
    private lateinit var rx: BluetoothGattCharacteristic
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

    override val received: Flow<ByteArray> = incoming.receiveAsFlow()

    /** The negotiated ATT MTU minus the 3 byte ATT header. */
    override val maxWriteSize: Int get() = mtu - ATT_HEADER_SIZE

    override suspend fun write(bytes: ByteArray) {
        writeLock.withLock {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + maxWriteSize, bytes.size)
                writeChunk(bytes.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    override suspend fun close() {
        closedByUs = true
        if (::gatt.isInitialized) {
            gatt.disconnect()
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { disconnected.await() }
            gatt.close()
        }
        incoming.close()
    }

    /**
     * Only one GATT operation may be in flight, so wait for the write callback before the next chunk. The
     * wait is not cancellable (a late callback would otherwise be taken for the next chunk's) but times
     * out, because some phones occasionally never deliver it.
     */
    private suspend fun writeChunk(chunk: ByteArray) {
        val done = CompletableDeferred<Int>()
        pendingWrite = done
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rx, chunk, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                rx.writeType = writeType
                rx.value = chunk
                gatt.writeCharacteristic(rx)
            }
        }
        if (!started) throw IOException("BLE write was rejected")
        val status = withContext(NonCancellable) { withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } }
            ?: throw IOException("BLE write timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("BLE write failed (status $status)")
    }

    private suspend fun open(timeoutMs: Long) {
        // Deprecated only in favour of an API 37 overload; this one works on every supported version.
        @Suppress("DEPRECATION")
        val connection = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = connection ?: throw IOException("Bluetooth is unavailable")
        withTimeoutOrNull(timeoutMs) { ready.await() } ?: throw IOException("BLE connection timed out")
    }

    private fun discoverServices(g: BluetoothGatt) {
        if (discoveryStarted) return
        discoveryStarted = true
        g.discoverServices()
    }

    private fun failSetup(g: BluetoothGatt, error: IOException) {
        ready.completeExceptionally(error)
        g.disconnect()
    }

    private fun enableNotifications(g: BluetoothGatt, tx: BluetoothGattCharacteristic) {
        val cccd = tx.getDescriptor(CLIENT_CONFIG_UUID)
        if (cccd == null || !g.setCharacteristicNotification(tx, true)) {
            failSetup(g, IOException("Cannot subscribe to the VESC UART notifications"))
            return
        }
        val value = if (tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
        if (!started) failSetup(g, IOException("Cannot subscribe to the VESC UART notifications"))
    }

    private fun linkLost(g: BluetoothGatt, status: Int) {
        val error = IOException("BLE link lost (status $status)")
        ready.completeExceptionally(error)
        pendingWrite?.completeExceptionally(error)
        incoming.close(error.takeUnless { closedByUs })
        disconnected.complete(Unit)
        g.close()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(requestedMtu)) discoverServices(g)
            } else {
                linkLost(g, status)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleTransport.mtu = mtu
            discoverServices(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            val rxChar = service?.getCharacteristic(RX_UUID)
            val txChar = service?.getCharacteristic(TX_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || rxChar == null || txChar == null) {
                failSetup(g, IOException("The device has no VESC UART service"))
                return
            }
            rx = rxChar
            if (rxChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) {
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            enableNotifications(g, txChar)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready.complete(Unit)
            } else {
                failSetup(g, IOException("Subscribing to the VESC UART notifications failed (status $status)"))
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            incoming.trySend(characteristic.value.copyOf())
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEFAULT_MTU = 23
        private const val ATT_HEADER_SIZE = 3
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val DISCONNECT_TIMEOUT_MS = 1_000L
        private const val WRITE_TIMEOUT_MS = 5_000L

        /**
         * Connects to the device with the given MAC address and subscribes to its notifications.
         * [requestedMtu] is a request: the peripheral decides, and Android 14+ starts its own exchange
         * (up to 517) on connect whatever is passed here.
         *
         * @throws IOException when the device cannot be reached, has no VESC UART service, or the link drops
         * during setup.
         */
        suspend fun connect(context: Context, address: String, requestedMtu: Int = 247): BleTransport {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                ?: throw IOException("Bluetooth is unavailable")
            val transport = BleTransport(context.applicationContext, adapter.getRemoteDevice(address), requestedMtu)
            try {
                transport.open(CONNECT_TIMEOUT_MS)
            } catch (e: Throwable) {
                withContext(NonCancellable) { transport.close() }
                throw e
            }
            return transport
        }
    }
}
