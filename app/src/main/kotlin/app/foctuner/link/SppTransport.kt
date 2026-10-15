package app.foctuner.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import app.foctuner.transport.StreamTransport
import app.foctuner.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * A VESC behind a Bluetooth Classic serial module (HC-05 and friends) over RFCOMM. The module must be
 * paired in Android's Bluetooth settings first; the app does not pair. Needs BLUETOOTH_CONNECT on Android 12+.
 */
@SuppressLint("MissingPermission")
class SppTransport private constructor(socket: BluetoothSocket) :
    Transport by StreamTransport(socket.inputStream, socket.outputStream, socket::close, MAX_WRITE_SIZE) {

    companion object {
        private val SERIAL_PORT_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private const val MAX_WRITE_SIZE = 1024

        /** @throws IOException when the module is out of range, not paired, or refuses the connection. */
        suspend fun connect(context: Context, address: String): SppTransport = withContext(Dispatchers.IO) {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                ?: throw IOException("Bluetooth is unavailable")
            adapter.cancelDiscovery()
            val socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERIAL_PORT_UUID)
            try {
                socket.connect()
                SppTransport(socket)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
        }
    }
}
