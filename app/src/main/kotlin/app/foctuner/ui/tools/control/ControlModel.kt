package app.foctuner.ui.tools.control

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.foctuner.R
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.DEFAULT_ESTOP_DURATION
import app.foctuner.vesc.PositionDisplay
import app.foctuner.vesc.RealtimeValues
import app.foctuner.vesc.VescException
import app.foctuner.vesc.emergencyStop
import app.foctuner.vesc.rotorPositions
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the value field sets, with VESC Tool's range, step and default for it. */
enum class ControlMode(
    @param:StringRes val title: Int,
    val unit: String,
    val decimals: Int,
    val step: Float,
    val max: Float,
    val default: Float,
) {
    Current(R.string.control_mode_current, "A", 1, 0.5f, 500f, 3f),
    Duty(R.string.control_mode_duty, "", 2, 0.01f, 1f, 0.2f),
    Rpm(R.string.control_mode_rpm, "ERPM", 0, 100f, 400_000f, 5_000f),
    Position(R.string.control_mode_position, "°", 1, 1f, 360f, 0f),
    Handbrake(R.string.control_mode_handbrake, "A", 1, 0.5f, 500f, 3f),
    ;

    fun clamp(value: Float): Float = value.coerceIn(0f, max)
}

/** The buttons under the value field. Position and handbrake only have [Hold]. */
enum class ControlAction { Reverse, Forward, Brake, Hold }

@Immutable
data class ControlState(
    /** The user has read what the buttons do and enabled them. */
    val armed: Boolean = false,
    val mode: ControlMode = ControlMode.Current,
    val setpoints: Map<ControlMode, Float> = ControlMode.entries.associateWith { it.default },
    /** The command in effect, or null when the motor is released. */
    val running: ControlAction? = null,
    /** Lifting the finger leaves the command running until Release, STOP or leaving the page. */
    val keepRunning: Boolean = false,
    /** Seconds the controller still ignores motor commands after STOP. */
    val stopSeconds: Int = 0,
    val values: RealtimeValues? = null,
    val failed: Boolean = false,
    val rotorMode: PositionDisplay = PositionDisplay.NONE,
    val rotorAngle: Float? = null,
) {
    val canCommand: Boolean get() = armed && stopSeconds == 0
}

/**
 * VESC Tool's manual motor control (desktop toolbar and mobile "Controls"): hold a button to run the motor with
 * the chosen command, lift to release; STOP sends COMM_MOTOR_ESTOP and the controls stay disabled while the
 * controller ignores commands. Commands are sent in order through one queue so a release can't overtake the
 * command it ends. Works on the selected controller.
 */
class ControlModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(ControlState())
    val state: StateFlow<ControlState> = _state.asStateFlow()

    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var countdown: Job? = null
    private var rotor: Job? = null

    init {
        context.scope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (e: VescException) {
                    _state.update { it.copy(failed = true) }
                }
            }
        }
    }

    fun arm() = _state.update { it.copy(armed = true) }

    fun select(mode: ControlMode) {
        if (_state.value.running != null) release()
        _state.update { it.copy(mode = mode) }
    }

    fun setSetpoint(mode: ControlMode, value: Float) = _state.update { it.copy(setpoints = it.setpoints + (mode to mode.clamp(value))) }

    fun setKeepRunning(on: Boolean) {
        _state.update { it.copy(keepRunning = on) }
        if (!on && _state.value.running != null) release()
    }

    fun press(action: ControlAction) {
        val s = _state.value
        if (!s.canCommand) return
        val value = s.setpoints.getValue(s.mode)
        val motor = context.controller.motor
        _state.update { it.copy(running = action, failed = false) }
        commands.trySend {
            when (s.mode) {
                ControlMode.Current -> when (action) {
                    ControlAction.Reverse -> motor.setCurrent(-value)
                    ControlAction.Brake -> motor.setBrakeCurrent(value)
                    else -> motor.setCurrent(value)
                }
                ControlMode.Duty -> motor.setDuty(
                    when (action) {
                        ControlAction.Reverse -> -value
                        ControlAction.Brake -> 0f
                        else -> value
                    },
                )
                ControlMode.Rpm -> motor.setRpm(
                    when (action) {
                        ControlAction.Reverse -> -value.toInt()
                        ControlAction.Brake -> 0
                        else -> value.toInt()
                    },
                )
                ControlMode.Position -> motor.setPosition(value)
                ControlMode.Handbrake -> motor.setHandbrake(value)
            }
        }
    }

    /** The finger left the button. */
    fun lift() {
        if (!_state.value.keepRunning) release()
    }

    fun release() {
        if (_state.value.running == null) return
        _state.update { it.copy(running = null) }
        commands.trySend { context.controller.motor.release() }
    }

    /** STOP: whatever is queued is dropped, then the ESTOP goes out and the controls wait it out. */
    fun stop() {
        while (commands.tryReceive().isSuccess) Unit
        _state.update { it.copy(running = null) }
        commands.trySend { context.controller.emergencyStop(DEFAULT_ESTOP_DURATION) }
        countdown?.cancel()
        countdown = context.scope.launch {
            for (left in DEFAULT_ESTOP_DURATION.inWholeSeconds downTo 1) {
                _state.update { it.copy(stopSeconds = left.toInt()) }
                delay(1_000)
            }
            _state.update { it.copy(stopSeconds = 0) }
        }
    }

    /** The page went away or the app went to the background: nothing keeps running unattended. */
    fun leave() {
        release()
        showRotor(PositionDisplay.NONE)
    }

    fun showRotor(mode: PositionDisplay) {
        if (mode == _state.value.rotorMode) return
        rotor?.cancel()
        _state.update { it.copy(rotorMode = mode, rotorAngle = null) }
        if (mode == PositionDisplay.NONE) return
        rotor = context.scope.launch {
            try {
                context.controller.rotorPositions(mode).collect { angle -> _state.update { it.copy(rotorAngle = angle) } }
            } catch (e: VescException) {
                _state.update { it.copy(failed = true, rotorMode = PositionDisplay.NONE) }
            }
        }
    }

    suspend fun refresh() {
        try {
            val values = context.controller.values()
            _state.update { it.copy(values = values, failed = false) }
        } catch (e: VescException) {
            _state.update { it.copy(failed = true) }
        }
    }
}
