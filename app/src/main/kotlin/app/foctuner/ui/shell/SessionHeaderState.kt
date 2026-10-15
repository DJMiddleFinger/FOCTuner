package app.foctuner.ui.shell

import androidx.compose.runtime.Immutable

@Immutable
data class SessionHeaderState(
    val deviceName: String,
    val hardwareName: String,
    val firmware: String,
    val linkLabel: String,
    /** Values are arriving; false while the link is quiet. */
    val live: Boolean,
    /** CAN id of the controller commands go to, when it isn't the attached one. */
    val targetCanId: Int? = null,
)
