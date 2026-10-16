package app.foctuner.ui.tools.io

import androidx.compose.runtime.Immutable
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.IoBoardValues
import app.foctuner.vesc.VescException
import app.foctuner.vesc.VescTimeoutException
import app.foctuner.vesc.ioBoard
import app.foctuner.vesc.setIoBoardDigital
import app.foctuner.vesc.setIoBoardPwm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class IoBoardState(
    val boardId: Int = DEFAULT_BOARD_ID,
    val values: IoBoardValues? = null,
    /** The controller had nothing from that board (no reply). */
    val missing: Boolean = false,
    val failed: Boolean = false,
    val digitalChannel: Int = 0,
    val pwmChannel: Int = 0,
    val pwmDuty: Float = 0f,
)

/** A VESC IO board on the selected controller's CAN bus: inputs as the controller last heard them, outputs on demand. */
class IoBoardModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(IoBoardState())
    val state: StateFlow<IoBoardState> = _state.asStateFlow()

    fun setBoard(id: Int) = _state.update { it.copy(boardId = id, values = null, missing = false) }
    fun setDigitalChannel(channel: Int) = _state.update { it.copy(digitalChannel = channel) }
    fun setPwmChannel(channel: Int) = _state.update { it.copy(pwmChannel = channel) }
    fun setPwmDuty(duty: Float) = _state.update { it.copy(pwmDuty = duty) }

    fun setDigital(on: Boolean) {
        val s = _state.value
        send { context.controller.setIoBoardDigital(s.boardId, s.digitalChannel, on) }
    }

    fun applyPwm() {
        val s = _state.value
        send { context.controller.setIoBoardPwm(s.boardId, s.pwmChannel, s.pwmDuty) }
    }

    suspend fun refresh() {
        val id = _state.value.boardId
        try {
            val values = context.controller.ioBoard(id)
            _state.update { if (it.boardId == id) it.copy(values = values, missing = false, failed = false) else it }
        } catch (e: VescTimeoutException) {
            _state.update { if (it.boardId == id) it.copy(values = null, missing = true) else it }
        } catch (e: VescException) {
            _state.update { it.copy(failed = true) }
        }
    }

    private fun send(block: suspend () -> Unit) {
        context.scope.launch {
            try {
                block()
            } catch (e: VescException) {
                _state.update { it.copy(failed = true) }
            }
        }
    }
}

private const val DEFAULT_BOARD_ID = 40
