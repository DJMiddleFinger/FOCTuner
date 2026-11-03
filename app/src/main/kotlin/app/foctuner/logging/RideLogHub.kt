package app.foctuner.logging

import app.foctuner.vesc.RecordingStatus
import app.foctuner.vesc.VescController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the screens show about the ride log; [failure] is the reason the last recording ended early. */
data class RideLogState(
    val recording: Boolean = false,
    val status: RecordingStatus = RecordingStatus(),
    val locationAllowed: Boolean = true,
    val lastFile: String? = null,
    val failure: Failure? = null,
) {
    enum class Failure { LinkLost, NoConnection }
}

/**
 * Between the connection (which lives in the session view model) and the foreground service that records: the
 * connection says which controller a ride log reads, the service says how the recording is going. The service
 * can outlive a screen, so this is process-wide.
 */
object RideLogHub {
    class Source(val controller: VescController, val demo: Boolean)

    @Volatile
    var source: Source? = null
        private set

    private val _state = MutableStateFlow(RideLogState())
    val state: StateFlow<RideLogState> = _state.asStateFlow()

    fun attach(source: Source?) {
        this.source = source
    }

    fun update(change: (RideLogState) -> RideLogState) = _state.update(change)
}
