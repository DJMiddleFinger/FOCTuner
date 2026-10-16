package app.foctuner.ui.tools.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.ConfigKind
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.ImuCalibration
import app.foctuner.vesc.calibrateImu
import app.foctuner.vesc.updateConfig
import app.foctuner.vesc.withImuCalibration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class ImuCalibrationState(val yaw: String = "0", val calibration: ImuCalibration? = null, val applied: Boolean = false)

/** The firmware's IMU calibration (COMM_GET_IMU_CALIBRATION): mounting rotation and gyro offsets, stored in the app config. */
class ImuCalibrationModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(ImuCalibrationState())
    val state: StateFlow<ImuCalibrationState> = _state.asStateFlow()
    val runner = SetupRunner(context)

    fun editYaw(text: String) = _state.update { it.copy(yaw = text) }

    fun calibrate() {
        val yaw = parseNumber(_state.value.yaw) ?: return
        runner.start(spins = false) {
            val calibration = context.controller.calibrateImu(yaw)
            _state.update { it.copy(calibration = calibration, applied = false) }
        }
    }

    fun apply() {
        val calibration = _state.value.calibration ?: return
        runner.start(spins = false) {
            context.controller.updateConfig(ConfigKind.APPCONF, context.baseConfig(ConfigKind.APPCONF)) { it.withImuCalibration(calibration) }
            _state.update { it.copy(applied = true) }
        }
    }
}

@Composable
fun ImuCalibrationTool(context: ToolContext) {
    val model = context.model { ImuCalibrationModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val run by model.runner.state.collectAsStateWithLifecycle()
    val busy = run is RunState.Running
    var confirmCalibrate by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.setup_imu_intro), Modifier.padding(Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
        NumberRow(stringResource(R.string.setup_imu_yaw), state.yaw, "°", model::editYaw, !busy)
        ActionRow { OutlineButton(stringResource(R.string.setup_imu_calibrate), { confirmCalibrate = true }, enabled = !busy && parseNumber(state.yaw) != null) }
        RunStatus(run, stringResource(R.string.setup_imu_calibrating), model.runner::stop)
        state.calibration?.let { cal ->
            SectionHeader(stringResource(R.string.setup_results))
            ValueRow(stringResource(R.string.setup_imu_roll), cal.roll.fixed(2), "°")
            ValueRow(stringResource(R.string.setup_imu_pitch), cal.pitch.fixed(2), "°")
            ValueRow(stringResource(R.string.setup_imu_yaw), cal.yaw.fixed(2), "°")
            cal.gyroOffsets.forEachIndexed { i, v -> ValueRow(stringResource(R.string.setup_imu_gyro_offset, "XYZ"[i]), v.fixed(3), "°/s") }
            cal.accelOffsets.forEachIndexed { i, v -> ValueRow(stringResource(R.string.setup_imu_accel_offset, "XYZ"[i]), v.fixed(3), "g") }
            if (state.applied) Notice(stringResource(R.string.setup_written_app))
            ActionRow { OutlineButton(stringResource(R.string.setup_apply_app), { confirmApply = true }, enabled = !busy) }
        }
    }
    if (confirmCalibrate) {
        ConfirmDialog(
            stringResource(R.string.setup_imu_calibrate),
            stringResource(R.string.setup_imu_calibrate_body),
            stringResource(R.string.setup_start),
            { confirmCalibrate = false; model.calibrate() },
            { confirmCalibrate = false },
        )
    }
    if (confirmApply) {
        ConfirmDialog(
            stringResource(R.string.setup_apply_app),
            stringResource(R.string.setup_apply_imu_body),
            stringResource(R.string.setup_write),
            { confirmApply = false; model.apply() },
            { confirmApply = false },
        )
    }
}
