package app.foctuner.ui.tools.refloat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.parts.Meter
import app.foctuner.ui.tools.parts.MeterRow
import app.foctuner.ui.tools.parts.ReadingRow
import app.foctuner.vesc.Refloat
import app.foctuner.vesc.refloatHandtest
import app.foctuner.vesc.refloatInfo
import app.foctuner.vesc.refloatLock
import app.foctuner.vesc.refloatRealtime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

@Immutable
data class RefloatState(
    val info: Refloat.Info? = null,
    val data: Refloat.Realtime? = null,
    /** True once Refloat failed to answer the first requests. */
    val absent: Boolean = false,
    val stale: Boolean = false,
)

/** What Refloat's VESC Tool screen shows that matters on the trail, natively: state, footpad, angles, setpoints. */
class RefloatModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(RefloatState())
    val state: StateFlow<RefloatState> = _state.asStateFlow()

    suspend fun poll() {
        if (_state.value.info == null) {
            val info = try {
                context.controller.refloatInfo()
            } catch (e: IOException) {
                null
            }
            _state.update { it.copy(info = info, absent = info == null) }
            if (info == null) return
        }
        while (true) {
            val data = try {
                context.controller.refloatRealtime()
            } catch (e: IOException) {
                null
            }
            _state.update { if (data != null) it.copy(data = data, stale = false) else it.copy(stale = true) }
            delay(POLL_MS)
        }
    }

    fun retry() = _state.update { RefloatState() }

    fun lock(locked: Boolean) = context.scope.launch { runCatching { context.controller.refloatLock(locked) } }
    fun handtest(on: Boolean) = context.scope.launch { runCatching { context.controller.refloatHandtest(on) } }

    private companion object {
        const val POLL_MS = 100L
    }
}

@Composable
fun RefloatTool(context: ToolContext, onOpenSettings: () -> Unit = {}) {
    val model = context.model { RefloatModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model, state.absent, state.info == null) { if (!state.absent) model.poll() }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.absent) {
            Notice(
                stringResource(R.string.refloat_absent),
                body = stringResource(R.string.refloat_absent_body),
                action = stringResource(R.string.refloat_retry),
                onAction = model::retry,
            )
            return@Column
        }
        val info = state.info
        val d = state.data
        if (info != null) ReadingRow(stringResource(R.string.refloat_version), "${info.name} ${info.version}")
        if (state.stale) Notice(stringResource(R.string.refloat_stale), tone = NoticeTone.Warning)
        if (d != null) {
            SectionHeader(stringResource(R.string.refloat_section_state))
            ReadingRow(stringResource(R.string.refloat_state), stringResource(stateLabel(d.state)))
            if (d.state?.running == true && d.tiltback >= 3) {
                Notice(stringResource(R.string.refloat_tiltback, stringResource(tiltbackLabel(d.tiltback))), tone = NoticeTone.Warning)
            }
            if (d.beep != 0) Notice(stringResource(R.string.refloat_alert, stringResource(beepLabel(d.beep))), tone = NoticeTone.Warning)
            ReadingRow(stringResource(R.string.refloat_footpad), stringResource(footpadLabel(d.footpad)))
            MeterRow(
                Meter(stringResource(R.string.refloat_adc_left), d.adcLeft.fixed(2), "V"),
                Meter(stringResource(R.string.refloat_adc_right), d.adcRight.fixed(2), "V"),
            )
            Hairline()
            MeterRow(
                Meter(stringResource(R.string.refloat_pitch), d.pitch.fixed(1), "°"),
                Meter(stringResource(R.string.refloat_roll), d.roll.fixed(1), "°"),
                Meter(stringResource(R.string.refloat_current), d.balanceCurrent.fixed(1), "A"),
            )
            Hairline()
            SectionHeader(stringResource(R.string.refloat_section_setpoints))
            ReadingRow(stringResource(R.string.refloat_setpoint), d.setpoint.fixed(2), "°")
            ReadingRow(stringResource(R.string.refloat_atr), d.atrSetpoint.fixed(2), "°")
            ReadingRow(stringResource(R.string.refloat_brake_tilt), d.brakeTiltSetpoint.fixed(2), "°")
            ReadingRow(stringResource(R.string.refloat_torque_tilt), d.torqueTiltSetpoint.fixed(2), "°")
            ReadingRow(stringResource(R.string.refloat_turn_tilt), d.turnTiltSetpoint.fixed(2), "°")
            ReadingRow(stringResource(R.string.refloat_remote_tilt), d.remoteSetpoint.fixed(2), "°")

            SectionHeader(stringResource(R.string.refloat_section_controls))
            val running = d.state?.running == true
            val locked = d.state == Refloat.State.Disabled
            OutlineButton(
                stringResource(if (locked) R.string.refloat_unlock else R.string.refloat_lock),
                { confirm = if (locked) "unlock" else "lock" },
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                enabled = !running,
            )
            OutlineButton(
                stringResource(if (d.handtest) R.string.refloat_handtest_off else R.string.refloat_handtest_on),
                { if (d.handtest) model.handtest(false) else confirm = "handtest" },
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                enabled = !running && !locked,
            )
            OutlineButton(stringResource(R.string.refloat_settings), onOpenSettings, Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1))
        }
    }
    when (confirm) {
        "lock", "unlock" -> ConfirmDialog(
            title = stringResource(if (confirm == "lock") R.string.refloat_lock_title else R.string.refloat_unlock_title),
            body = stringResource(if (confirm == "lock") R.string.refloat_lock_body else R.string.refloat_unlock_body),
            confirm = stringResource(if (confirm == "lock") R.string.refloat_lock else R.string.refloat_unlock),
            dismiss = stringResource(R.string.refloat_cancel),
            onConfirm = {
                model.lock(confirm == "lock")
                confirm = null
            },
            onDismiss = { confirm = null },
        )
        "handtest" -> ConfirmDialog(
            title = stringResource(R.string.refloat_handtest_title),
            body = stringResource(R.string.refloat_handtest_body),
            confirm = stringResource(R.string.refloat_handtest_on),
            dismiss = stringResource(R.string.refloat_cancel),
            onConfirm = {
                model.handtest(true)
                confirm = null
            },
            onDismiss = { confirm = null },
        )
    }
}

private fun stateLabel(state: Refloat.State?): Int = when (state) {
    Refloat.State.Startup -> R.string.refloat_state_startup
    Refloat.State.Running -> R.string.refloat_state_running
    Refloat.State.RunningTiltback -> R.string.refloat_state_tiltback
    Refloat.State.RunningWheelslip -> R.string.refloat_state_wheelslip
    Refloat.State.RunningUpsideDown -> R.string.refloat_state_upside_down
    Refloat.State.RunningFlywheel -> R.string.refloat_state_flywheel
    Refloat.State.FaultAnglePitch -> R.string.refloat_state_pitch
    Refloat.State.FaultAngleRoll -> R.string.refloat_state_roll
    Refloat.State.FaultSwitchHalf -> R.string.refloat_state_switch_half
    Refloat.State.FaultSwitchFull -> R.string.refloat_state_switch_full
    Refloat.State.FaultStartup -> R.string.refloat_state_ready
    Refloat.State.FaultReverse -> R.string.refloat_state_reverse
    Refloat.State.FaultQuickstop -> R.string.refloat_state_quickstop
    Refloat.State.Charging -> R.string.refloat_state_charging
    Refloat.State.Disabled -> R.string.refloat_state_disabled
    null -> R.string.refloat_state_unknown
}

private fun tiltbackLabel(code: Int): Int = when (code) {
    3 -> R.string.refloat_tb_duty
    4 -> R.string.refloat_tb_hv
    5 -> R.string.refloat_tb_lv
    6 -> R.string.refloat_tb_temp
    7 -> R.string.refloat_tb_speed
    else -> R.string.refloat_tb_error
}

private fun beepLabel(code: Int): Int = when (code) {
    1 -> R.string.refloat_beep_lv
    2 -> R.string.refloat_beep_hv
    3 -> R.string.refloat_beep_fet
    4 -> R.string.refloat_beep_motor
    5 -> R.string.refloat_beep_current
    6 -> R.string.refloat_beep_duty
    7 -> R.string.refloat_beep_sensors
    8 -> R.string.refloat_beep_lowbatt
    9 -> R.string.refloat_beep_idle
    11 -> R.string.refloat_beep_speed
    else -> R.string.refloat_beep_other
}

private fun footpadLabel(f: Refloat.Footpad): Int = when (f) {
    Refloat.Footpad.None -> R.string.refloat_footpad_none
    Refloat.Footpad.Half -> R.string.refloat_footpad_half
    Refloat.Footpad.Both -> R.string.refloat_footpad_both
}
