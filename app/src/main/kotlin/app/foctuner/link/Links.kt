package app.foctuner.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.foctuner.transport.LinkDevice
import app.foctuner.transport.LinkKind
import app.foctuner.transport.TcpBeacon
import app.foctuner.transport.TcpTransport
import app.foctuner.transport.Transport
import app.foctuner.transport.visibleDevices
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import java.io.IOException

/**
 * Finding VESCs and connecting to them, over whichever link they are on. The connect screen asks
 * [missingPermissions], collects [scan], and hands the chosen device to [open].
 */
class Links(context: Context) {
    private val context = context.applicationContext
    private val usb = this.context.getSystemService(UsbManager::class.java)
    private val bluetooth = this.context.getSystemService(BluetoothManager::class.java)

    val bluetoothEnabled: Boolean get() = bluetooth.adapter?.isEnabled == true

    /**
     * Runtime permissions the given links need on this Android version. BLE scanning on Android 11 and older
     * also needs the system location switch on, which no permission reflects. USB asks per device when opened.
     */
    fun permissionsFor(kinds: Set<LinkKind> = ALL_KINDS): List<String> {
        val ble = LinkKind.BLE in kinds
        val classic = LinkKind.BLUETOOTH_CLASSIC in kinds
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> buildList {
                if (ble) add(Manifest.permission.BLUETOOTH_SCAN)
                if (ble || classic) add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            ble -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyList()
        }
    }

    fun missingPermissions(kinds: Set<LinkKind> = ALL_KINDS): List<String> =
        permissionsFor(kinds).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /**
     * The VESCs currently around, updated as they appear and disappear: BLE devices in range, serial USB
     * devices plugged in, paired Bluetooth Classic serial modules, and WiFi devices broadcasting their
     * address. Runs until cancelled and fails if a requested link cannot scan, e.g. Bluetooth is off or its
     * permission is missing, so check [bluetoothEnabled] and [missingPermissions] first.
     */
    fun scan(kinds: Set<LinkKind> = ALL_KINDS): Flow<List<LinkDevice>> {
        val sources = buildList {
            if (LinkKind.BLE in kinds) add(bleDevices(context))
            if (LinkKind.USB in kinds) add(polled(::usbDevices))
            if (LinkKind.BLUETOOTH_CLASSIC in kinds) add(polled(::pairedSerialModules))
            if (LinkKind.TCP in kinds) add(TcpBeacon.listen())
        }
        return merge(*sources.toTypedArray()).visibleDevices()
    }

    /**
     * Connects to [device]. Closing the returned transport ends the connection. A USB device brings up the
     * system permission dialog the first time.
     *
     * @throws IOException when the device cannot be reached or refuses the connection.
     */
    suspend fun open(device: LinkDevice): Transport = when (device.kind) {
        LinkKind.BLE -> BleTransport.connect(context, device.id)
        LinkKind.USB -> UsbTransport.open(context, usb.deviceList[device.id] ?: throw IOException("USB device was unplugged"))
        LinkKind.BLUETOOTH_CLASSIC -> SppTransport.connect(context, device.id)
        LinkKind.TCP -> TcpTransport.connect(device.id.substringBeforeLast(':'), device.id.substringAfterLast(':').toInt())
    }

    private fun usbDevices(): List<LinkDevice> =
        usb.deviceList.values.filter(UsbTransport::isSerialDevice).map {
            val name = it.productName ?: "USB serial"
            LinkDevice(LinkKind.USB, it.deviceName, "$name (%04x:%04x)".format(it.vendorId, it.productId))
        }

    /** Paired devices that could be a serial module; headsets, phones and the like are left out. */
    @SuppressLint("MissingPermission")
    private fun pairedSerialModules(): List<LinkDevice> =
        bluetooth.adapter?.bondedDevices.orEmpty()
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE && it.isUncategorized() }
            .map { LinkDevice(LinkKind.BLUETOOTH_CLASSIC, it.address, it.name ?: it.address) }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.isUncategorized(): Boolean =
        when (bluetoothClass?.majorDeviceClass) {
            null, BluetoothClass.Device.Major.UNCATEGORIZED, BluetoothClass.Device.Major.MISC -> true
            else -> false
        }

    /** Re-reads [snapshot] regularly so devices that stay put keep counting as seen. */
    private fun polled(snapshot: () -> List<LinkDevice>): Flow<LinkDevice> = flow {
        while (true) {
            snapshot().forEach { emit(it) }
            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        val ALL_KINDS = LinkKind.entries.toSet()
        const val POLL_INTERVAL_MS = 2_000L
    }
}
