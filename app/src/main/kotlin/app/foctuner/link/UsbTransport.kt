package app.foctuner.link

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import app.foctuner.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * A VESC (or VESC Express) plugged in over USB-OTG. These enumerate as CDC-ACM serial devices, which Android
 * lets us drive directly: the bulk endpoints of the CDC data interface carry the packet stream.
 *
 * Use [open], which asks for the per-device USB permission when the app does not have it yet.
 * Bytes received are buffered until [received] is collected, which has a single consumer.
 */
class UsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val port: CdcPort,
) : Transport {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closedByUs = false

    private val reader = scope.launch { readUntilClosed() }

    override val received: Flow<ByteArray> = incoming.receiveAsFlow()

    override val maxWriteSize: Int = MAX_TRANSFER_SIZE

    override suspend fun write(bytes: ByteArray) {
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                var offset = 0
                while (offset < bytes.size) {
                    val chunk = minOf(bytes.size - offset, MAX_TRANSFER_SIZE)
                    val sent = connection.bulkTransfer(port.bulkOut, bytes, offset, chunk, WRITE_TIMEOUT_MS)
                    if (sent <= 0) throw IOException("USB write failed")
                    offset += sent
                }
            }
        }
    }

    override suspend fun close() {
        closedByUs = true
        reader.join()
        withContext(Dispatchers.IO) {
            connection.releaseInterface(port.data)
            port.control?.let { connection.releaseInterface(it) }
            connection.close()
        }
        incoming.close()
        scope.cancel()
    }

    /**
     * `bulkTransfer` reports a timeout and a failure (device unplugged) both as -1, and an infinite timeout
     * cannot be interrupted by closing the connection. So read with a short timeout and treat a -1 that came
     * back well before the timeout as a failure.
     */
    private fun readUntilClosed() {
        val buffer = ByteArray(MAX_TRANSFER_SIZE)
        while (!closedByUs) {
            val started = SystemClock.elapsedRealtime()
            val n = connection.bulkTransfer(port.bulkIn, buffer, buffer.size, READ_TIMEOUT_MS)
            when {
                n > 0 -> incoming.trySend(buffer.copyOf(n))
                n < 0 && SystemClock.elapsedRealtime() - started < READ_TIMEOUT_MS / 2 -> {
                    incoming.close(IOException("USB device disconnected").takeUnless { closedByUs })
                    return
                }
            }
        }
    }

    companion object {
        private const val MAX_TRANSFER_SIZE = 16384
        private const val READ_TIMEOUT_MS = 100
        private const val WRITE_TIMEOUT_MS = 2000
        private const val CONTROL_TIMEOUT_MS = 1000
        private const val CDC_ACM_SUBCLASS = 2
        private const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val DTR = 0x01

        /** Whether [device] has the CDC-ACM interfaces this transport needs. */
        fun isSerialDevice(device: UsbDevice): Boolean = device.cdcPort() != null

        /**
         * Asks for permission if needed, claims the CDC interfaces and raises DTR (some firmwares only send
         * while the host is "connected").
         *
         * @throws IOException when permission is denied, the device is not CDC-ACM, or it cannot be opened.
         */
        suspend fun open(context: Context, device: UsbDevice): UsbTransport {
            val manager = context.getSystemService(UsbManager::class.java)
            val port = device.cdcPort() ?: throw IOException("Not a USB serial device")
            if (!manager.requestPermission(context, device)) throw IOException("USB permission denied")
            return withContext(Dispatchers.IO) {
                val connection = manager.openDevice(device) ?: throw IOException("Cannot open the USB device")
                try {
                    val control = port.control ?: port.data
                    if (!connection.claimInterface(port.data, true) || !connection.claimInterface(control, true)) {
                        throw IOException("Cannot claim the USB serial interface")
                    }
                    // A device that rejects this request is still usable, so the result is not checked.
                    connection.controlTransfer(
                        REQUEST_TYPE_CLASS_INTERFACE_OUT, SET_CONTROL_LINE_STATE, DTR, control.id, null, 0, CONTROL_TIMEOUT_MS,
                    )
                    UsbTransport(connection, port)
                } catch (e: IOException) {
                    connection.close()
                    throw e
                }
            }
        }

        private fun UsbDevice.cdcPort(): CdcPort? {
            val interfaces = (0 until interfaceCount).map(::getInterface)
            val control = interfaces.firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_COMM && it.interfaceSubclass == CDC_ACM_SUBCLASS
            }
            for (data in interfaces.filter { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }) {
                val bulk = (0 until data.endpointCount).map(data::getEndpoint)
                    .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                val bulkIn = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
                val bulkOut = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
                if (bulkIn != null && bulkOut != null) return CdcPort(control, data, bulkIn, bulkOut)
            }
            return null
        }
    }

    private class CdcPort(
        val control: UsbInterface?,
        val data: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )
}
