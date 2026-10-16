package app.foctuner.ui.tools

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import app.foctuner.R
import app.foctuner.ui.tools.control.ControlTool
import app.foctuner.ui.tools.imu.ImuTool
import app.foctuner.ui.tools.setup.SetupTool
import app.foctuner.ui.tools.stats.StatsTool

/** Everything under the Tools tab, in the order listed. */
enum class Tool(@param:StringRes val title: Int, @param:StringRes val summary: Int) {
    Stats(R.string.tool_stats, R.string.tool_stats_summary),
    Control(R.string.tool_control, R.string.tool_control_summary),
    Setup(R.string.tool_setup, R.string.tool_setup_summary),
    Imu(R.string.tool_imu, R.string.tool_imu_summary),
}

@Composable
fun ToolContent(tool: Tool, context: ToolContext) {
    when (tool) {
        Tool.Stats -> StatsTool(context)
        Tool.Control -> ControlTool(context)
        Tool.Setup -> SetupTool(context)
        Tool.Imu -> ImuTool(context)
    }
}
