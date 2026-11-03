package app.foctuner.ui.session

import app.foctuner.vesc.FirmwareInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UpdateStage {
    /** Writing the image: the old firmware still runs and an interrupted upload can be continued. */
    Uploading,

    /** The controller was told to install; it restarts by itself and must not lose power. */
    Installing,

    /** The link closed during an update; the session is opening it again. */
    Reconnecting,
}

/** What the controller reported when it answered again after an update, and what it ran before. */
data class UpdateOutcome(val before: String, val after: FirmwareInfo)

/**
 * Held while a firmware upload runs. The session stops polling the controller (VESC Tool stops all polling
 * too), and the header, tabs and CAN selection are locked so that nothing else talks to a controller that
 * is busy writing flash, and nobody leaves the page by accident. If the link drops while it is held, the
 * session reconnects instead of ending.
 */
class UpdateLock {
    private val _stage = MutableStateFlow<UpdateStage?>(null)
    val stage: StateFlow<UpdateStage?> = _stage.asStateFlow()

    private val _outcome = MutableStateFlow<UpdateOutcome?>(null)

    /** Set when the session reconnected to a controller that was installing an update. */
    val outcome: StateFlow<UpdateOutcome?> = _outcome.asStateFlow()

    val held: Boolean get() = _stage.value != null

    fun hold(stage: UpdateStage) {
        _stage.value = stage
    }

    fun release() {
        _stage.value = null
    }

    fun report(outcome: UpdateOutcome?) {
        _outcome.value = outcome
    }
}
