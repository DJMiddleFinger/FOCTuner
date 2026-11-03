package app.foctuner.ui.tools

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import app.foctuner.R
import app.foctuner.ui.tools.analysis.CanAnalyzerTool
import app.foctuner.ui.tools.analysis.ExperimentsTool
import app.foctuner.ui.tools.analysis.MotorComparisonTool
import app.foctuner.ui.tools.analysis.SampledDataTool
import app.foctuner.ui.tools.bms.BmsTool
import app.foctuner.ui.tools.bridge.BridgeTool
import app.foctuner.ui.tools.canbus.CanBusTool
import app.foctuner.ui.tools.configtools.BackupsTool
import app.foctuner.ui.tools.configtools.MultiSettingsTool
import app.foctuner.ui.tools.configtools.ProfilesTool
import app.foctuner.ui.tools.control.ControlTool
import app.foctuner.ui.tools.gnss.GnssTool
import app.foctuner.ui.tools.imu.ImuTool
import app.foctuner.ui.tools.io.IoBoardTool
import app.foctuner.ui.tools.programmers.DisplayTool
import app.foctuner.ui.tools.programmers.EspProgrammerScreen
import app.foctuner.ui.tools.programmers.SwdTool
import app.foctuner.ui.tools.psw.PowerSwitchTool
import app.foctuner.ui.tools.radio.RadioTool
import app.foctuner.ui.tools.refloat.RefloatTool
import app.foctuner.ui.tools.ride.RideTool
import app.foctuner.ui.tools.route.RouteTool
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
import app.foctuner.ui.tools.logs.LogsTool
import app.foctuner.ui.tools.packages.PackageSettingsTool
import app.foctuner.ui.tools.packages.PackagesTool
import app.foctuner.ui.tools.stats.StatsTool

/** The Start page's groups, after VESC Tool's; [Tab] tools have a top-level tab of their own instead. */
enum class ToolSection(@param:StringRes val title: Int) {
    Configuration(R.string.tools_section_configuration),
    Detection(R.string.tools_section_detection),
    Operations(R.string.tools_section_operations),
    Hardware(R.string.tools_section_hardware),
    Tab(R.string.tools_section_tab),
}

/** Every tool, grouped by [section] in the order listed; [icon] pictures what the tool does. */
enum class Tool(val section: ToolSection, @param:StringRes val title: Int, @param:StringRes val summary: Int, @param:DrawableRes val icon: Int) {
    FocWizard(ToolSection.Configuration, R.string.tool_foc_wizard, R.string.tool_foc_wizard_summary, R.drawable.ic_tool_wizard),
    InputWizard(ToolSection.Configuration, R.string.tool_input_wizard, R.string.tool_input_wizard_summary, R.drawable.ic_tool_joystick),
    ImuCalibration(ToolSection.Configuration, R.string.tool_imu_calibration, R.string.tool_imu_calibration_summary, R.drawable.ic_tool_level),
    Direction(ToolSection.Configuration, R.string.tool_direction, R.string.tool_direction_summary, R.drawable.ic_tool_rotate),
    Backups(ToolSection.Configuration, R.string.tool_backups, R.string.tool_backups_summary, R.drawable.ic_history),
    Profiles(ToolSection.Configuration, R.string.tool_profiles, R.string.tool_profiles_summary, R.drawable.ic_set_star),
    MultiSettings(ToolSection.Configuration, R.string.tool_multi, R.string.tool_multi_summary, R.drawable.ic_nav_controllers),
    Radio(ToolSection.Configuration, R.string.tool_radio, R.string.tool_radio_summary, R.drawable.ic_set_radio),
    FocDetection(ToolSection.Detection, R.string.tool_foc_detection, R.string.tool_foc_detection_summary, R.drawable.ic_tool_sine),
    HallDetection(ToolSection.Detection, R.string.tool_hall_detection, R.string.tool_hall_detection_summary, R.drawable.ic_tool_magnet),
    EncoderDetection(ToolSection.Detection, R.string.tool_encoder_detection, R.string.tool_encoder_detection_summary, R.drawable.ic_tool_encoder),
    BldcDetection(ToolSection.Detection, R.string.tool_bldc_detection, R.string.tool_bldc_detection_summary, R.drawable.ic_tool_trapezoid),
    PpmMapping(ToolSection.Detection, R.string.tool_ppm_mapping, R.string.tool_ppm_mapping_summary, R.drawable.ic_tool_pulses),
    AdcMapping(ToolSection.Detection, R.string.tool_adc_mapping, R.string.tool_adc_mapping_summary, R.drawable.ic_tool_slider),
    Control(ToolSection.Operations, R.string.tool_control, R.string.tool_control_summary, R.drawable.ic_tool_play),
    Firmware(ToolSection.Operations, R.string.tool_firmware, R.string.tool_firmware_summary, R.drawable.ic_tool_firmware),
    SampledData(ToolSection.Operations, R.string.tool_sampled, R.string.tool_sampled_summary, R.drawable.ic_tool_sine),
    Experiments(ToolSection.Operations, R.string.tool_experiments, R.string.tool_experiments_summary, R.drawable.ic_curve),
    MotorComparison(ToolSection.Operations, R.string.tool_compare, R.string.tool_compare_summary, R.drawable.ic_tool_sine),
    Bridge(ToolSection.Operations, R.string.tool_bridge, R.string.tool_bridge_summary, R.drawable.ic_nav_terminal),
    Gnss(ToolSection.Hardware, R.string.tool_gnss, R.string.tool_gnss_summary, R.drawable.ic_tool_location),
    PowerSwitch(ToolSection.Hardware, R.string.tool_psw, R.string.tool_psw_summary, R.drawable.ic_tool_power),
    IoBoard(ToolSection.Hardware, R.string.tool_io, R.string.tool_io_summary, R.drawable.ic_tool_chip),
    CanBus(ToolSection.Hardware, R.string.tool_canbus, R.string.tool_canbus_summary, R.drawable.ic_tool_bus),
    Swd(ToolSection.Hardware, R.string.tool_swd, R.string.tool_swd_summary, R.drawable.ic_tool_chip),
    EspProgrammer(ToolSection.Hardware, R.string.tool_esp, R.string.tool_esp_summary, R.drawable.ic_tool_firmware),
    DisplayFonts(ToolSection.Hardware, R.string.tool_display, R.string.tool_display_summary, R.drawable.ic_tool_chip),
    CanAnalyzer(ToolSection.Hardware, R.string.tool_can_analyzer, R.string.tool_can_analyzer_summary, R.drawable.ic_tool_bus),
    Ride(ToolSection.Tab, R.string.tool_ride, R.string.tool_ride_summary, R.drawable.ic_nav_overview),
    Stats(ToolSection.Tab, R.string.tool_stats, R.string.tool_stats_summary, R.drawable.ic_curve),
    Imu(ToolSection.Tab, R.string.tool_imu, R.string.tool_imu_summary, R.drawable.ic_tool_axes),
    Bms(ToolSection.Tab, R.string.tool_bms, R.string.tool_bms_summary, R.drawable.ic_tool_battery),
    Lisp(ToolSection.Tab, R.string.tool_lisp, R.string.tool_lisp_summary, R.drawable.ic_tool_lambda),
    Route(ToolSection.Operations, R.string.tool_route, R.string.tool_route_summary, R.drawable.ic_tool_location),
    Logs(ToolSection.Operations, R.string.tool_logs, R.string.tool_logs_summary, R.drawable.ic_curve),
    Packages(ToolSection.Operations, R.string.tool_packages, R.string.tool_packages_summary, R.drawable.ic_tool_lambda),
    PackageSettings(ToolSection.Configuration, R.string.tool_pkgset, R.string.tool_pkgset_summary, R.drawable.ic_nav_settings),
    Refloat(ToolSection.Operations, R.string.tool_refloat, R.string.tool_refloat_summary, R.drawable.ic_tool_level),
    ;

    /** Setup tools and manual control command the motor themselves (demo mode pauses its ride for them). */
    val drivesMotor: Boolean get() = section == ToolSection.Detection || this in setOf(FocWizard, InputWizard, ImuCalibration, Direction, Control, Experiments)
}

/** [onOpenTool] switches to another tool, for tools that link to one. */
@Composable
fun ToolContent(tool: Tool, context: ToolContext, onOpenTool: (Tool) -> Unit = {}) {
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
        Tool.Logs -> LogsTool(context)
        Tool.Route -> RouteTool(context)
        Tool.Backups -> BackupsTool(context)
        Tool.Profiles -> ProfilesTool(context)
        Tool.MultiSettings -> MultiSettingsTool(context)
        Tool.Radio -> RadioTool(context)
        Tool.CanBus -> CanBusTool(context)
        Tool.Bridge -> BridgeTool(context)
        Tool.SampledData -> SampledDataTool(context)
        Tool.Experiments -> ExperimentsTool(context)
        Tool.CanAnalyzer -> CanAnalyzerTool(context)
        Tool.MotorComparison -> MotorComparisonTool(context)
        Tool.Swd -> SwdTool(context)
        Tool.EspProgrammer -> EspProgrammerScreen()
        Tool.DisplayFonts -> DisplayTool()
        Tool.Packages -> PackagesTool(context, onOpenRefloat = { onOpenTool(Tool.Refloat) })
        Tool.Refloat -> RefloatTool(context, onOpenSettings = { onOpenTool(Tool.PackageSettings) })
        Tool.PackageSettings -> PackageSettingsTool(context)
    }
}
