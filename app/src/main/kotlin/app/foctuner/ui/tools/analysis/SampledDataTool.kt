package app.foctuner.ui.tools.analysis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.lisp.PlotSeries
import app.foctuner.ui.tools.lisp.ScriptPlot
import app.foctuner.ui.tools.lisp.ScriptPlotChart
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.vesc.AdcSample
import app.foctuner.vesc.SampleMode
import app.foctuner.vesc.sampleAdc
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Immutable
data class SampledState(
    val mode: SampleMode = SampleMode.Now,
    val length: Int = 500,
    val decimation: Int = 1,
    val running: Boolean = false,
    val received: Int = 0,
    val samples: List<AdcSample>? = null,
    val failed: String? = null,
)

/** VESC Tool's Sampled Data page: the controller's raw phase currents and voltages, PWM cycle by PWM cycle. */
class SampledDataModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(SampledState())
    val state: StateFlow<SampledState> = _state.asStateFlow()
    private var job: Job? = null

    fun setMode(mode: SampleMode) = _state.update { it.copy(mode = mode) }
    fun setLength(length: Int) = _state.update { it.copy(length = length) }
    fun setDecimation(decimation: Int) = _state.update { it.copy(decimation = decimation) }

    fun sample() {
        if (job?.isActive == true) return
        val s = _state.value
        _state.update { it.copy(running = true, received = 0, failed = null) }
        job = context.scope.launch {
            try {
                val waiting = if (s.mode == SampleMode.Now || s.mode == SampleMode.SendLast) 3.seconds else 60.seconds
                val samples = context.controller.sampleAdc(s.mode, s.length, s.decimation, waitForFirst = waiting) { n ->
                    _state.update { it.copy(received = n) }
                }
                _state.update { it.copy(running = false, samples = samples.takeIf { it.isNotEmpty() } ?: it.samples, failed = if (samples.isEmpty()) NOTHING else null) }
            } catch (e: IOException) {
                _state.update { it.copy(running = false, failed = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(running = false) }
    }

    companion object {
        const val NOTHING = "nothing"
    }
}

@Composable
fun SampledDataTool(context: ToolContext) {
    val model = context.model { SampledDataModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val android = LocalContext.current
    val samples = state.samples
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && samples != null) {
            runCatching { android.contentResolver.openOutputStream(uri, "wt")?.use { it.write(samplesCsv(samples, state.decimation).toByteArray()) } }
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.sampled_intro),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = FocTheme.colors.textSecondary,
        )
        SectionHeader(stringResource(R.string.sampled_when))
        ChoiceRow(
            listOf(SampleMode.Now, SampleMode.Start, SampleMode.TriggerStart, SampleMode.TriggerFault, SampleMode.SendLast),
            state.mode,
            { stringResource(modeLabel(it)) },
            model::setMode,
            enabled = !state.running,
        )
        SectionHeader(stringResource(R.string.sampled_samples))
        ChoiceRow(listOf(100, 250, 500, 1000), state.length, { it.toString() }, model::setLength, enabled = !state.running)
        SectionHeader(stringResource(R.string.sampled_decimation))
        ChoiceRow(listOf(1, 2, 4, 8, 16), state.decimation, { stringResource(R.string.sampled_every, it) }, model::setDecimation, enabled = !state.running)
        Row(Modifier.padding(horizontal = Spacing.x1)) {
            if (state.running) {
                PlainButton(stringResource(R.string.sampled_stop), model::stop)
            } else {
                OutlineButton(stringResource(R.string.sampled_start), model::sample, Modifier.padding(Spacing.x1))
            }
            if (samples != null && !state.running) PlainButton(stringResource(R.string.sampled_save), { save.launch("samples.csv") })
        }
        if (state.running) {
            Text(
                stringResource(if (state.received == 0 && state.mode != SampleMode.Now) R.string.sampled_waiting else R.string.sampled_receiving, state.received),
                Modifier.padding(horizontal = Spacing.x2),
                style = FocTheme.type.supporting,
                color = FocTheme.colors.text,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(Spacing.x2), color = FocTheme.colors.accent, trackColor = FocTheme.colors.outline)
        }
        state.failed?.let {
            Notice(
                stringResource(R.string.sampled_failed),
                body = if (it == SampledDataModel.NOTHING) stringResource(R.string.sampled_nothing) else it,
                tone = NoticeTone.Warning,
            )
        }
        if (samples != null) {
            Hairline()
            val plots = remember(samples, state.decimation) { plots(samples, state.decimation) }
            SectionHeader(stringResource(R.string.sampled_currents))
            ScriptPlotChart(plots.first)
            SectionHeader(stringResource(R.string.sampled_voltages))
            ScriptPlotChart(plots.second, Modifier.padding(bottom = Spacing.x2))
        }
    }
}

private fun modeLabel(mode: SampleMode): Int = when (mode) {
    SampleMode.Now -> R.string.sampled_now
    SampleMode.Start -> R.string.sampled_on_start
    SampleMode.TriggerStart -> R.string.sampled_around_start
    SampleMode.TriggerFault -> R.string.sampled_before_fault
    else -> R.string.sampled_last
}

/** Time of each sample in ms: decimated PWM cycles at the switching frequency the sample reports. */
private fun times(samples: List<AdcSample>, decimation: Int): FloatArray =
    FloatArray(samples.size) { i -> samples[i].index * decimation * 1000f / samples[i].switchingFrequency.coerceAtLeast(1f) }

private fun plots(samples: List<AdcSample>, decimation: Int): Pair<ScriptPlot, ScriptPlot> {
    val t = times(samples, decimation)
    fun series(name: String, pick: (AdcSample) -> Float) = PlotSeries(name, t, FloatArray(samples.size) { pick(samples[it]) })
    val currents = ScriptPlot(
        "ms", "A",
        listOf(series("Phase 1") { it.current1 }, series("Phase 2") { it.current2 }, series("Phase 3") { it.current3 }, series("Total") { it.currentTotal }),
    )
    val voltages = ScriptPlot(
        "ms", "V",
        listOf(series("Phase 1") { it.voltage1 }, series("Phase 2") { it.voltage2 }, series("Phase 3") { it.voltage3 }, series("Input") { it.voltageZero }),
    )
    return currents to voltages
}

internal fun samplesCsv(samples: List<AdcSample>, decimation: Int): String = buildString {
    append("index;time_ms;current1;current2;current3;voltage1;voltage2;voltage3;voltage_in;current_total;f_sw;status;phase\n")
    val t = times(samples, decimation)
    samples.forEachIndexed { i, s ->
        append(listOf(s.index, t[i], s.current1, s.current2, s.current3, s.voltage1, s.voltage2, s.voltage3, s.voltageZero, s.currentTotal, s.switchingFrequency, s.status, s.phase).joinToString(";"))
        append('\n')
    }
}
