package app.foctuner.ui.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UpdateStage {
    /** Writing the image: the old firmware still runs and an interrupted upload can be continued. */
    Uploading,

    /** The controller was told to install; it restarts by itself and must not lose power. */
    Installing,
}

/**
 * Held while a firmware upload runs. The session stops polling the controller (VESC Tool stops all polling
 * too), and the header, tabs and CAN selection are locked so that nothing else talks to a controller that
 * is busy writing flash, and nobody leaves the page by accident.
 */
class UpdateLock {
    private val _stage = MutableStateFlow<UpdateStage?>(null)
    val stage: StateFlow<UpdateStage?> = _stage.asStateFlow()

    val held: Boolean get() = _stage.value != null

    fun hold(stage: UpdateStage) {
        _stage.value = stage
    }

    fun release() {
        _stage.value = null
    }
}
