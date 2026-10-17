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
import app.foctuner.ui.tools.lisp.LispTool
import app.foctuner.ui.tools.stats.StatsTool

enum class ToolSection(@param:StringRes val title: Int) {
    Setup(R.string.tools_section_setup),
    Live(R.string.tools_section_live),
    Hardware(R.string.tools_section_hardware),
    System(R.string.tools_section_system),
}

/** Everything under the Tools tab, grouped by [section] in the order listed. */
enum class Tool(val section: ToolSection, @param:StringRes val title: Int, @param:StringRes val summary: Int) {
    FocWizard(ToolSection.Setup, R.string.tool_foc_wizard, R.string.tool_foc_wizard_summary),
    FocDetection(ToolSection.Setup, R.string.tool_foc_detection, R.string.tool_foc_detection_summary),
    HallDetection(ToolSection.Setup, R.string.tool_hall_detection, R.string.tool_hall_detection_summary),
    EncoderDetection(ToolSection.Setup, R.string.tool_encoder_detection, R.string.tool_encoder_detection_summary),
    BldcDetection(ToolSection.Setup, R.string.tool_bldc_detection, R.string.tool_bldc_detection_summary),
    Direction(ToolSection.Setup, R.string.tool_direction, R.string.tool_direction_summary),
    InputWizard(ToolSection.Setup, R.string.tool_input_wizard, R.string.tool_input_wizard_summary),
    PpmMapping(ToolSection.Setup, R.string.tool_ppm_mapping, R.string.tool_ppm_mapping_summary),
    AdcMapping(ToolSection.Setup, R.string.tool_adc_mapping, R.string.tool_adc_mapping_summary),
    ImuCalibration(ToolSection.Setup, R.string.tool_imu_calibration, R.string.tool_imu_calibration_summary),
    Control(ToolSection.Live, R.string.tool_control, R.string.tool_control_summary),
    Ride(ToolSection.Live, R.string.tool_ride, R.string.tool_ride_summary),
    Stats(ToolSection.Live, R.string.tool_stats, R.string.tool_stats_summary),
    Imu(ToolSection.Live, R.string.tool_imu, R.string.tool_imu_summary),
    Bms(ToolSection.Hardware, R.string.tool_bms, R.string.tool_bms_summary),
    Gnss(ToolSection.Hardware, R.string.tool_gnss, R.string.tool_gnss_summary),
    PowerSwitch(ToolSection.Hardware, R.string.tool_psw, R.string.tool_psw_summary),
    IoBoard(ToolSection.Hardware, R.string.tool_io, R.string.tool_io_summary),
    Lisp(ToolSection.System, R.string.tool_lisp, R.string.tool_lisp_summary),
    Firmware(ToolSection.System, R.string.tool_firmware, R.string.tool_firmware_summary),
    ;

    /** Setup tools and manual control command the motor themselves (demo mode pauses its ride for them). */
    val drivesMotor: Boolean get() = section == ToolSection.Setup || this == Control
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
        Tool.Lisp -> LispTool(context)
    }
}
