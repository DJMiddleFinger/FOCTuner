package app.foctuner.ui.tools

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import app.foctuner.R
import app.foctuner.ui.tools.bms.BmsTool
import app.foctuner.ui.tools.control.ControlTool
import app.foctuner.ui.tools.gnss.GnssTool
import app.foctuner.ui.tools.imu.ImuTool
import app.foctuner.ui.tools.io.IoBoardTool
import app.foctuner.ui.tools.psw.PowerSwitchTool
import app.foctuner.ui.tools.ride.RideTool
import app.foctuner.ui.tools.setup.AdcMappingTool
import app.foctuner.ui.tools.setup.BldcDetectionTool
import app.foctuner.ui.tools.setup.DirectionTool
import app.foctuner.ui.tools.setup.EncoderDetectionTool
import app.foctuner.ui.tools.setup.FocDetectionTool
import app.foctuner.ui.tools.setup.FocWizardTool
import app.foctuner.ui.tools.setup.HallDetectionTool
import app.foctuner.ui.tools.setup.ImuCalibrationTool
import app.foctuner.ui.tools.setup.InputWizardTool
import app.foctuner.ui.tools.setup.PpmMappingTool
import app.foctuner.ui.tools.firmware.FirmwareTool
import app.foctuner.ui.tools.stats.StatsTool

/** Everything under the Tools tab, in the order listed. */
enum class Tool(@param:StringRes val title: Int, @param:StringRes val summary: Int) {
    Stats(R.string.tool_stats, R.string.tool_stats_summary),
    Control(R.string.tool_control, R.string.tool_control_summary),
    Ride(R.string.tool_ride, R.string.tool_ride_summary),
    Imu(R.string.tool_imu, R.string.tool_imu_summary),
    Bms(R.string.tool_bms, R.string.tool_bms_summary),
    Gnss(R.string.tool_gnss, R.string.tool_gnss_summary),
    PowerSwitch(R.string.tool_psw, R.string.tool_psw_summary),
    IoBoard(R.string.tool_io, R.string.tool_io_summary),
    FocWizard(R.string.tool_foc_wizard, R.string.tool_foc_wizard_summary),
    FocDetection(R.string.tool_foc_detection, R.string.tool_foc_detection_summary),
    HallDetection(R.string.tool_hall_detection, R.string.tool_hall_detection_summary),
    EncoderDetection(R.string.tool_encoder_detection, R.string.tool_encoder_detection_summary),
    BldcDetection(R.string.tool_bldc_detection, R.string.tool_bldc_detection_summary),
    Direction(R.string.tool_direction, R.string.tool_direction_summary),
    InputWizard(R.string.tool_input_wizard, R.string.tool_input_wizard_summary),
    PpmMapping(R.string.tool_ppm_mapping, R.string.tool_ppm_mapping_summary),
    AdcMapping(R.string.tool_adc_mapping, R.string.tool_adc_mapping_summary),
    ImuCalibration(R.string.tool_imu_calibration, R.string.tool_imu_calibration_summary),
    Firmware(R.string.tool_firmware, R.string.tool_firmware_summary),
}

@Composable
fun ToolContent(tool: Tool, context: ToolContext) {
    when (tool) {
        Tool.Stats -> StatsTool(context)
        Tool.Control -> ControlTool(context)
        Tool.Ride -> RideTool(context)
        Tool.Imu -> ImuTool(context)
        Tool.Bms -> BmsTool(context)
        Tool.Gnss -> GnssTool(context)
        Tool.PowerSwitch -> PowerSwitchTool(context)
        Tool.IoBoard -> IoBoardTool(context)
        Tool.FocWizard -> FocWizardTool(context)
        Tool.FocDetection -> FocDetectionTool(context)
        Tool.HallDetection -> HallDetectionTool(context)
        Tool.EncoderDetection -> EncoderDetectionTool(context)
        Tool.BldcDetection -> BldcDetectionTool(context)
        Tool.Direction -> DirectionTool(context)
        Tool.InputWizard -> InputWizardTool(context)
        Tool.PpmMapping -> PpmMappingTool(context)
        Tool.AdcMapping -> AdcMappingTool(context)
        Tool.ImuCalibration -> ImuCalibrationTool(context)
        Tool.Firmware -> FirmwareTool(context)
    }
}
