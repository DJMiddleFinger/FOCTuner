package app.foctuner.ui.connect

import androidx.compose.runtime.Immutable

enum class LinkKind { Bluetooth, Usb, Network }

@Immutable
data class DeviceEntry(
    /** Stable across scans: MAC address, host:port or USB device path. */
    val key: String,
    val name: String,
    /** Shown in mono under the name, so two boards with the same name can be told apart. */
    val address: String,
    val link: LinkKind,
    val rssi: Int? = null,
    /** Epoch millis of the last successful connection; set for entries in the recent list. */
    val lastConnectedAt: Long? = null,
    val pinned: Boolean = false,
)

enum class BluetoothStatus { Ready, Off, NeedsPermission, NeedsLocationPermission, Unsupported }

@Immutable
data class ConnectState(
    /** Pinned first, then by last connection. */
    val recent: List<DeviceEntry> = emptyList(),
    val bluetoothStatus: BluetoothStatus = BluetoothStatus.Ready,
    val bluetooth: List<DeviceEntry> = emptyList(),
    val usb: List<DeviceEntry> = emptyList(),
    val network: List<DeviceEntry> = emptyList(),
    val scanning: Boolean = false,
    /** A scan has finished at least once, so "nothing found" is a result and not just "not yet". */
    val scanFinished: Boolean = false,
    /** [DeviceEntry.key] or [DEMO_KEY] while a connection is being set up. */
    val connectingKey: String? = null,
    val failure: String? = null,
)

const val DEMO_KEY = "demo"
