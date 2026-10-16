package app.foctuner.ui.connect

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.foctuner.link.Links
import app.foctuner.transport.LinkDevice
import app.foctuner.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import app.foctuner.transport.LinkKind as TransportKind

/** Scanning and device lists for the connect screen. Opening a link is done by the session. */
class ConnectViewModel(application: Application) : AndroidViewModel(application) {
    private val links = Links(application)
    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private var found = emptyMap<String, LinkDevice>()

    /** Devices keep the place where they first appeared, so rows don't jump around as signal strength changes. */
    private val firstSeen = HashMap<String, Int>()
    private var scanJob: Job? = null

    val missingBluetoothPermissions: List<String> get() = links.missingPermissions(BLUETOOTH)

    fun scan() {
        scanJob?.cancel()
        val status = bluetoothStatus()
        val kinds = if (status == BluetoothStatus.Ready) BLUETOOTH + WIRED_AND_NETWORK else WIRED_AND_NETWORK
        _state.update { it.copy(bluetoothStatus = status, scanning = true) }
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(SCAN_TIME) {
                    links.scan(kinds).collect { devices ->
                        found = devices.associateBy { it.key }
                        devices.forEach { firstSeen.putIfAbsent(it.key, firstSeen.size) }
                        _state.update { state ->
                            state.copy(
                                bluetooth = devices.entries(TransportKind.BLE, TransportKind.BLUETOOTH_CLASSIC),
                                usb = devices.entries(TransportKind.USB),
                                network = devices.entries(TransportKind.TCP),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(bluetoothStatus = bluetoothStatus()) }
            }
            _state.update { it.copy(scanning = false, scanFinished = true) }
        }
    }

    fun device(entry: DeviceEntry): LinkDevice? = found[entry.key]

    fun deviceForAddress(address: String): LinkDevice? {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':', "").toIntOrNull()
        if (host.isBlank()) return null
        return if (port == null) LinkDevice.tcp(address) else LinkDevice.tcp(host, port)
    }

    suspend fun open(device: LinkDevice): Transport = links.open(device)

    private fun bluetoothStatus(): BluetoothStatus {
        val packages = getApplication<Application>().packageManager
        return when {
            !packages.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> BluetoothStatus.Unsupported
            missingBluetoothPermissions.isNotEmpty() ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BluetoothStatus.NeedsPermission else BluetoothStatus.NeedsLocationPermission
            !links.bluetoothEnabled -> BluetoothStatus.Off
            else -> BluetoothStatus.Ready
        }
    }

    private fun List<LinkDevice>.entries(vararg kinds: TransportKind) = filter { it.kind in kinds }
        .sortedBy { firstSeen[it.key] }
        .map { DeviceEntry(it.key, it.name, it.id, it.kind.toLinkKind(), it.rssi) }

    private companion object {
        val SCAN_TIME = 15.seconds
        val BLUETOOTH = setOf(TransportKind.BLE, TransportKind.BLUETOOTH_CLASSIC)
        val WIRED_AND_NETWORK = setOf(TransportKind.USB, TransportKind.TCP)
    }
}

internal fun TransportKind.toLinkKind() = when (this) {
    TransportKind.BLE, TransportKind.BLUETOOTH_CLASSIC -> LinkKind.Bluetooth
    TransportKind.USB -> LinkKind.Usb
    TransportKind.TCP -> LinkKind.Network
}
