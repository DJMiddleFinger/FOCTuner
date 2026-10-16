package app.foctuner.ui.tools

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import app.foctuner.R
import app.foctuner.ui.tools.setup.BldcDetectionTool
import app.foctuner.ui.tools.setup.DirectionTool
import app.foctuner.ui.tools.setup.EncoderDetectionTool
import app.foctuner.ui.tools.setup.FocDetectionTool
import app.foctuner.ui.tools.setup.FocWizardTool
import app.foctuner.ui.tools.setup.HallDetectionTool
import app.foctuner.ui.tools.stats.StatsTool

/** Everything under the Tools tab, in the order listed. */
enum class Tool(@param:StringRes val title: Int, @param:StringRes val summary: Int) {
    Stats(R.string.tool_stats, R.string.tool_stats_summary),
    FocWizard(R.string.tool_foc_wizard, R.string.tool_foc_wizard_summary),
    FocDetection(R.string.tool_foc_detection, R.string.tool_foc_detection_summary),
    HallDetection(R.string.tool_hall_detection, R.string.tool_hall_detection_summary),
    EncoderDetection(R.string.tool_encoder_detection, R.string.tool_encoder_detection_summary),
    BldcDetection(R.string.tool_bldc_detection, R.string.tool_bldc_detection_summary),
    Direction(R.string.tool_direction, R.string.tool_direction_summary),
}

@Composable
fun ToolContent(tool: Tool, context: ToolContext) {
    when (tool) {
        Tool.Stats -> StatsTool(context)
        Tool.FocWizard -> FocWizardTool(context)
        Tool.FocDetection -> FocDetectionTool(context)
        Tool.HallDetection -> HallDetectionTool(context)
        Tool.EncoderDetection -> EncoderDetectionTool(context)
        Tool.BldcDetection -> BldcDetectionTool(context)
        Tool.Direction -> DirectionTool(context)
    }
}
