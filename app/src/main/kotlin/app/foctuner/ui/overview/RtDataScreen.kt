package app.foctuner.ui.overview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.foctuner.R
import app.foctuner.ui.components.TabStrip
import app.foctuner.ui.theme.FocAnimatedContent
import app.foctuner.ui.theme.lateral
import app.foctuner.ui.tools.Tool
import app.foctuner.ui.tools.ToolContent
import app.foctuner.ui.tools.ToolContext

private val pages = listOf(null to R.string.rt_realtime, Tool.Ride to R.string.rt_ride, Tool.Imu to R.string.tool_imu, Tool.Stats to R.string.tool_stats)

/** VESC Tool's RT Data tab: live values, then the ride, IMU and statistics pages, each behind a sub-tab. */
@Composable
fun RtDataScreen(overview: OverviewState?, tools: ToolContext?, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        TabStrip(pages.map { stringResource(it.second) }, selected, { selected = it })
        FocAnimatedContent(
            target = selected,
            label = "rt-page",
            modifier = Modifier.weight(1f),
            transition = { lateral(if (targetState > initialState) 1 else -1) },
        ) { index ->
            Box(Modifier.fillMaxSize()) {
                val tool = pages[index].first
                when {
                    tool == null -> overview?.let { OverviewScreen(it) }
                    tools != null -> ToolContent(tool, tools)
                }
            }
        }
    }
}
