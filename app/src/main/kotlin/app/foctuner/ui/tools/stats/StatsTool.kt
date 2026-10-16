package app.foctuner.ui.tools.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.NO_VALUE
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.RideStats
import app.foctuner.vesc.VescException
import app.foctuner.vesc.resetStats
import app.foctuner.vesc.stats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class StatsState(val stats: RideStats? = null, val failed: Boolean = false, val resetting: Boolean = false)

/** Reads the firmware's ride statistics (VESC Tool's "Statistics" page). */
class StatsModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(StatsState())
    val state: StateFlow<StatsState> = _state.asStateFlow()

    suspend fun refresh() {
        try {
            val stats = context.controller.stats()
            _state.update { it.copy(stats = stats, failed = false) }
        } catch (e: VescException) {
            _state.update { it.copy(failed = true) }
        }
    }

    fun reset() {
        if (_state.value.resetting) return
        _state.update { it.copy(resetting = true) }
        context.scope.launch {
            try {
                context.controller.resetStats()
                refresh()
            } catch (e: VescException) {
                _state.update { it.copy(failed = true) }
            } finally {
                _state.update { it.copy(resetting = false) }
            }
        }
    }
}

@Composable
fun StatsTool(context: ToolContext) {
    val model = context.model { StatsModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) {
        while (true) {
            model.refresh()
            delay(1_000)
        }
    }
    StatsContent(state, model::reset)
}

@Composable
private fun StatsContent(state: StatsState, onReset: () -> Unit) {
    val s = state.stats
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.failed) Notice(stringResource(R.string.stats_no_reply), tone = NoticeTone.Warning)
        SectionHeader(stringResource(R.string.stats_section))
        StatRow(R.string.stats_speed, s?.let { (it.speedAvg * MS_TO_KMH).fixed(1) }, s?.let { (it.speedMax * MS_TO_KMH).fixed(1) }, "km/h")
        StatRow(R.string.stats_power, s?.powerAvg?.fixed(0), s?.powerMax?.fixed(0), "W")
        StatRow(R.string.stats_current, s?.currentAvg?.fixed(1), s?.currentMax?.fixed(1), "A")
        StatRow(R.string.stats_temp_mosfet, s?.tempMosfetAvg?.fixed(1), s?.tempMosfetMax?.fixed(1), "°C")
        StatRow(R.string.stats_temp_motor, s?.tempMotorAvg?.fixed(1), s?.tempMotorMax?.fixed(1), "°C")
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.row)
                .padding(horizontal = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.stats_time), Modifier.weight(1f), color = FocTheme.colors.text, style = FocTheme.type.body)
            Text(s?.seconds?.let(::duration) ?: NO_VALUE, color = FocTheme.colors.text, style = FocTheme.type.value)
        }
        Hairline()
        OutlineButton(stringResource(R.string.stats_reset), onReset, Modifier.padding(Spacing.x2), enabled = !state.resetting)
    }
}

@Composable
private fun StatRow(label: Int, average: String?, peak: String?, unit: String) {
    val colors = FocTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.row)
            .padding(horizontal = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f), color = colors.text, style = FocTheme.type.body)
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.stats_average, average ?: NO_VALUE, unit), color = colors.text, style = FocTheme.type.value)
            Text(stringResource(R.string.stats_peak, peak ?: NO_VALUE, unit), color = colors.textTertiary, style = FocTheme.type.caption)
        }
    }
    Hairline(Modifier.padding(start = Spacing.x2))
}

private fun duration(seconds: Float): String {
    val total = seconds.toLong()
    return "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
}

private const val MS_TO_KMH = 3.6f
