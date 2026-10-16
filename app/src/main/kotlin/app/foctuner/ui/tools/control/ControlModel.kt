package app.foctuner.ui.tools.control

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.foctuner.R
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.PositionDisplay
import app.foctuner.vesc.RealtimeValues
import app.foctuner.vesc.VescException
import app.foctuner.vesc.rotorPositions
import app.foctuner.vesc.setServoPosition
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    /** Last position sent to the servo output, 0..1; null until moved. */
    val servo: Float? = null,
) {
    val canCommand: Boolean get() = armed && stopSeconds == 0
}

/**
 * VESC Tool's manual motor control (desktop toolbar and mobile "Controls"): hold a button to run the motor with
 * the chosen command, lift to release; STOP is the client's emergency stop and the controls stay disabled while
 * the controllers ignore commands. Works on the selected controller.
 *
 * A held button resends its command every [RESEND] with a [LEASE], so if the UI stalls the client releases the
 * motor by itself. Order is kept by the session, which runs commands, releases and the stop one at a time.
 */
class ControlModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(ControlState())
    val state: StateFlow<ControlState> = _state.asStateFlow()

    private var holding: Job? = null
    private var countdown: Job? = null
    private var rotor: Job? = null

    fun arm() = _state.update { it.copy(armed = true) }

    fun select(mode: ControlMode) {
        release()
        _state.update { it.copy(mode = mode) }
    }

    fun setSetpoint(mode: ControlMode, value: Float) = _state.update { it.copy(setpoints = it.setpoints + (mode to mode.clamp(value))) }

    fun setKeepRunning(on: Boolean) {
        _state.update { it.copy(keepRunning = on) }
        if (!on) release()
    }

    fun press(action: ControlAction) {
        val s = _state.value
        if (!s.canCommand) return
        val command = command(s.mode, action, s.setpoints.getValue(s.mode))
        _state.update { it.copy(running = action, failed = false) }
        holding?.cancel()
        holding = launch {
            while (true) {
                command(LEASE)
                delay(RESEND)
            }
        }
    }

    /** The finger left the button: release, or with keep-running send the command once more without a lease. */
    fun lift() {
        val s = _state.value
        val action = s.running ?: return
        if (!s.keepRunning) {
            release()
            return
        }
        holding?.cancel()
        val command = command(s.mode, action, s.setpoints.getValue(s.mode))
        holding = launch { command(null) }
    }

    fun release() {
        holding?.cancel()
        if (_state.value.running == null) return
        _state.update { it.copy(running = null) }
        launch { context.controller.motor.release() }
    }

    fun stop() {
        holding?.cancel()
        _state.update { it.copy(running = null) }
        launch { context.client.emergencyStop(STOP_DURATION) }
        countdown?.cancel()
        countdown = context.scope.launch {
            for (left in STOP_DURATION.inWholeSeconds downTo 1) {
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

    /** The servo output isn't a motor command: no keepalive, no release, it stays where it was put. */
    fun setServo(position: Float) {
        _state.update { it.copy(servo = position) }
        launch { context.controller.setServoPosition(position) }
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

    private fun command(mode: ControlMode, action: ControlAction, value: Float): suspend (Duration?) -> Unit {
        val motor = context.controller.motor
        return { lease ->
            when (mode) {
                ControlMode.Current -> when (action) {
                    ControlAction.Reverse -> motor.setCurrent(-value, lease)
                    ControlAction.Brake -> motor.setBrakeCurrent(value, lease)
                    else -> motor.setCurrent(value, lease)
                }
                ControlMode.Duty -> motor.setDuty(
                    when (action) {
                        ControlAction.Reverse -> -value
                        ControlAction.Brake -> 0f
                        else -> value
                    },
                    lease,
                )
                ControlMode.Rpm -> motor.setRpm(
                    when (action) {
                        ControlAction.Reverse -> -value.toInt()
                        ControlAction.Brake -> 0
                        else -> value.toInt()
                    },
                    lease,
                )
                ControlMode.Position -> motor.setPosition(value, lease)
                ControlMode.Handbrake -> motor.setHandbrake(value, lease)
            }
        }
    }

    private fun launch(block: suspend () -> Unit): Job = context.scope.launch {
        try {
            block()
        } catch (e: VescException) {
            _state.update { it.copy(failed = true) }
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

private val RESEND = 100.milliseconds
private val LEASE = 300.milliseconds

/** VESC Tool's default `estop_ms`. */
private val STOP_DURATION = 5.seconds
