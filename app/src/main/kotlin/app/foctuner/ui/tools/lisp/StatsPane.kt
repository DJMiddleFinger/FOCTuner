package app.foctuner.ui.tools.lisp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.NO_VALUE
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.Sparkline
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.round

/** CPU, heap and memory use and the numeric global bindings (VESC Tool's stats panel and binding plot). */
@Composable
fun StatsPane(model: LispModel, workspace: LispWorkspace, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    val scope = rememberCoroutineScope()
    val stats = model.stats
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .padding(horizontal = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.lisp_poll), Modifier.padding(horizontal = Spacing.x1), color = colors.textSecondary, style = FocTheme.type.label)
            PollMode.entries.forEach { mode ->
                Choice(stringResource(mode.label), selected = workspace.pollMode == mode) { workspace.pollMode = mode }
            }
            Box(Modifier.weight(1f))
            PlainButton(stringResource(R.string.lisp_refresh), { scope.launch { model.refresh(all = workspace.pollMode != PollMode.Vt) } })
        }
        if (workspace.pollMode != PollMode.Off) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touchTarget)
                    .padding(horizontal = Spacing.x1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.lisp_poll_rate), Modifier.padding(horizontal = Spacing.x1), color = colors.textSecondary, style = FocTheme.type.label)
                POLL_RATES.forEach { rate ->
                    Choice(stringResource(R.string.lisp_hz, rate), selected = workspace.pollRate == rate) { workspace.pollRate = rate }
                }
            }
        }
        Hairline()
        if (model.statsUnanswered) {
            Notice(stringResource(R.string.lisp_stats_none), body = stringResource(R.string.lisp_stats_none_body))
        }
        model.scriptPlot?.let { plot ->
            SectionHeader(stringResource(R.string.lisp_script_plot)) {
                PlainButton(stringResource(R.string.lisp_clear), model::clearScriptPlot)
            }
            ScriptPlotChart(plot)
        }
        SectionHeader(stringResource(R.string.lisp_usage))
        Meter(R.string.lisp_cpu, stats?.cpuUse)
        Meter(R.string.lisp_heap, stats?.heapUse)
        Meter(R.string.lisp_memory, stats?.memoryUse)
        SectionHeader(stringResource(R.string.lisp_bindings))
        val bindings = stats?.bindings.orEmpty()
        if (bindings.isEmpty()) {
            Text(
                stringResource(if (stats == null) R.string.lisp_bindings_unknown else R.string.lisp_bindings_none),
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                color = colors.textTertiary,
                style = FocTheme.type.supporting,
            )
        } else {
            Text(stringResource(R.string.lisp_bindings_hint), Modifier.padding(horizontal = Spacing.x2), color = colors.textTertiary, style = FocTheme.type.caption)
        }
        bindings.forEach { binding ->
            val plotted = model.plotted[binding.name]
            Column(
                Modifier
                    .fillMaxWidth()
                    .semantics { selected = plotted != null }
                    .clickable(role = Role.Checkbox) { model.togglePlot(binding.name) }
                    .padding(horizontal = Spacing.x2),
            ) {
                Row(Modifier.heightIn(min = Spacing.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                    Text(binding.name, Modifier.weight(1f), color = if (plotted != null) colors.accent else colors.text, style = FocTheme.type.code)
                    Text(bindingValue(binding.value), color = colors.text, style = FocTheme.type.value)
                }
                if (plotted != null) {
                    Sparkline(
                        plotted,
                        capacity = PLOT_SECONDS * workspace.pollRate.coerceAtLeast(1) + 1,
                        minSpan = 1e-3f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(bottom = Spacing.x1),
                    )
                }
            }
            Hairline(Modifier.padding(start = Spacing.x2))
        }
    }
}

@Composable
private fun Meter(label: Int, percent: Float?) {
    val colors = FocTheme.colors
    Column(Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(label), Modifier.weight(1f), color = colors.text, style = FocTheme.type.body)
            Text(percent?.fixed(1) ?: NO_VALUE, color = colors.text, style = FocTheme.type.value)
            Text(" %", color = colors.textTertiary, style = FocTheme.type.unit)
        }
        Box(
            Modifier
                .padding(top = Spacing.half)
                .fillMaxWidth()
                .height(4.dp)
                .background(colors.surfaceHigh),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(((percent ?: 0f) / 100f).coerceIn(0f, 1f))
                    .background(colors.accent),
            )
        }
    }
}

/** A selectable text option; the selection is marked with the accent. */
@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = FocTheme.colors
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .heightIn(min = Spacing.touchTarget)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.x1),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, Modifier.padding(vertical = Spacing.half), color = if (selected) colors.text else colors.textSecondary, style = FocTheme.type.label)
        Box(
            Modifier
                .height(2.dp)
                .fillMaxWidth()
                .background(if (selected) colors.accent else colors.background),
        )
    }
}

private val PollMode.label: Int
    get() = when (this) {
        PollMode.Off -> R.string.lisp_poll_off
        PollMode.Vt -> R.string.lisp_poll_vt
        PollMode.All -> R.string.lisp_poll_all
    }

/** Whole numbers without decimals (most bindings are counters and flags), others with four. */
private fun bindingValue(value: Float): String =
    if (value == round(value) && abs(value) < 1e7f) value.fixed(0) else value.fixed(4)

private val POLL_RATES = listOf(1, 2, 5, 10, 20)
private const val PLOT_SECONDS = 5
