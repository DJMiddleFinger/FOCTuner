package app.foctuner.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import app.foctuner.transport.LinkDevice
import app.foctuner.transport.LinkKind
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException

/**
 * One emission per advertisement from a device offering the VESC UART service, so the same device repeats
 * with a fresh RSSI. Scanning stops when the collector is cancelled. Fails if Bluetooth is off or Android
 * refuses the scan, which it does after five starts within 30 seconds.
 */
@SuppressLint("MissingPermission")
internal fun bleDevices(context: Context): Flow<LinkDevice> = callbackFlow {
    val scanner = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner
        ?: throw IOException("Bluetooth is off")
    val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleTransport.SERVICE_UUID)).build()
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            trySend(LinkDevice(LinkKind.BLE, address, result.scanRecord?.deviceName ?: address, result.rssi))
        }

        override fun onScanFailed(errorCode: Int) {
            close(IOException("BLE scan failed (error $errorCode)"))
        }
    }
    scanner.startScan(listOf(filter), settings, callback)
    awaitClose { scanner.stopScan(callback) }
}
