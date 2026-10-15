package app.foctuner.ui.controllers

import androidx.compose.runtime.Immutable

@Immutable
data class CanDevice(
    val canId: Int,
    /** The controller the phone's link is attached to; the others are reached by CAN forwarding. */
    val local: Boolean,
    val hardwareName: String,
    val firmware: String,
    /** Custom firmware or package name, if the device reports one. */
    val firmwareName: String = "",
)

@Immutable
data class ControllersState(
    val devices: List<CanDevice> = emptyList(),
    val selectedCanId: Int? = null,
    val scanning: Boolean = false,
    val scanned: Boolean = false,
)
