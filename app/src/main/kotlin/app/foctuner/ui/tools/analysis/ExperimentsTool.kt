package app.foctuner.ui.tools.analysis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.setup.parseNumber
import app.foctuner.vesc.ExperimentKind
import app.foctuner.vesc.ExperimentPlan
import app.foctuner.vesc.ExperimentSample
import app.foctuner.vesc.runExperiment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@Immutable
data class ExperimentsState(
    val running: ExperimentPlan? = null,
    val progress: Float = 0f,
    val samples: List<ExperimentSample> = emptyList(),
    val failed: String? = null,
)

/** VESC Tool's Experiments page: a duty, current or speed sweep with the controller's values logged along it. */
class ExperimentsModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(ExperimentsState())
    val state: StateFlow<ExperimentsState> = _state.asStateFlow()
    private var job: Job? = null

    fun run(plan: ExperimentPlan) {
        if (job?.isActive == true) return
        _state.update { ExperimentsState(running = plan) }
        job = context.scope.launch {
            try {
                context.controller.runExperiment(plan, SAMPLE_INTERVAL) { sample ->
                    _state.update {
                        it.copy(samples = it.samples + sample, progress = (sample.seconds / (plan.duration.inWholeMilliseconds / 1000.0)).toFloat())
                    }
                }
                _state.update { it.copy(running = null, progress = 1f) }
            } catch (e: IOException) {
                _state.update { it.copy(running = null, failed = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Stops the sweep; the runner releases the motor on its way out. */
    fun stop() {
        job?.cancel()
        _state.update { it.copy(running = null) }
    }

    private companion object {
        val SAMPLE_INTERVAL = 100.milliseconds
    }
}

@Composable
fun ExperimentsTool(context: ToolContext) {
    val model = context.model { ExperimentsModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    DisposableEffect(model) { onDispose { model.stop() } }
    var kind by rememberSaveable { mutableStateOf(ExperimentKind.Duty) }
    var from by rememberSaveable(kind) { mutableStateOf(defaults(kind).first) }
    var to by rememberSaveable(kind) { mutableStateOf(defaults(kind).second) }
    var step by rememberSaveable(kind) { mutableStateOf(defaults(kind).third) }
    var stepTime by rememberSaveable { mutableStateOf("1") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val android = LocalContext.current
    val plan = remember(kind, from, to, step, stepTime) {
        val f = parseNumber(from)?.toDouble()
        val t = parseNumber(to)?.toDouble()
        val s = parseNumber(step)?.toDouble()
        val st = parseNumber(stepTime)?.toDouble()
        if (f == null || t == null || s == null || st == null || st <= 0) null else runCatching { ExperimentPlan(kind, f, t, s, (st * 1000).toLong().milliseconds) }.getOrNull()
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching { android.contentResolver.openOutputStream(uri, "wt")?.use { it.write(experimentCsv(state.samples).toByteArray()) } }
    }
    val running = state.running != null
    val field = Modifier.fillMaxWidth().padding(horizontal = Spacing.x2)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Notice(stringResource(R.string.experiments_warning), body = stringResource(R.string.experiments_warning_body), tone = NoticeTone.Warning)
        ChoiceRow(ExperimentKind.entries, kind, { stringResource(kindLabel(it)) }, { kind = it }, enabled = !running)
        val unit = stringResource(kindUnit(kind))
        OutlinedTextField(from, { from = it }, field, enabled = !running, singleLine = true, label = { Text(stringResource(R.string.experiments_from, unit)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(to, { to = it }, field, enabled = !running, singleLine = true, label = { Text(stringResource(R.string.experiments_to, unit)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(step, { step = it }, field, enabled = !running, singleLine = true, label = { Text(stringResource(R.string.experiments_step, unit)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(stepTime, { stepTime = it }, field, enabled = !running, singleLine = true, label = { Text(stringResource(R.string.experiments_step_time)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        plan?.let {
            Text(
                stringResource(R.string.experiments_summary, it.steps, it.duration.inWholeSeconds),
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                style = FocTheme.type.supporting,
                color = FocTheme.colors.textSecondary,
            )
        }
        Row(Modifier.padding(horizontal = Spacing.x1)) {
            if (running) {
                OutlineButton(stringResource(R.string.experiments_stop), model::stop, Modifier.padding(Spacing.x1))
            } else {
                OutlineButton(stringResource(R.string.experiments_run), { confirm = true }, Modifier.padding(Spacing.x1), enabled = plan != null)
                if (state.samples.isNotEmpty()) PlainButton(stringResource(R.string.experiments_save), { save.launch("experiment.csv") })
            }
        }
        if (running) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(Spacing.x2), color = FocTheme.colors.accent, trackColor = FocTheme.colors.outline)
        state.failed?.let { Notice(stringResource(R.string.experiments_failed), body = it, tone = NoticeTone.Fault) }
        if (state.samples.size >= 2) {
            Hairline()
            val plots = remember(state.samples) { experimentPlots(state.samples) }
            for ((title, plot) in plots) {
                SectionHeader(title)
                ScriptPlotChart(plot)
            }
        }
    }
    if (confirm && plan != null) {
        ConfirmDialog(
            title = stringResource(R.string.experiments_confirm_title),
            body = stringResource(R.string.experiments_confirm_body, plan.duration.inWholeSeconds),
            confirm = stringResource(R.string.experiments_run),
            dismiss = stringResource(R.string.experiments_cancel),
            onConfirm = {
                confirm = false
                model.run(plan)
            },
            onDismiss = { confirm = false },
        )
    }
}

private fun defaults(kind: ExperimentKind): Triple<String, String, String> = when (kind) {
    ExperimentKind.Duty -> Triple("0.05", "0.3", "0.05")
    ExperimentKind.Current -> Triple("1", "10", "1")
    ExperimentKind.Rpm -> Triple("1000", "10000", "1000")
}

private fun kindLabel(kind: ExperimentKind): Int = when (kind) {
    ExperimentKind.Duty -> R.string.experiments_duty
    ExperimentKind.Current -> R.string.experiments_current
    ExperimentKind.Rpm -> R.string.experiments_rpm
}

private fun kindUnit(kind: ExperimentKind): Int = when (kind) {
    ExperimentKind.Duty -> R.string.experiments_unit_duty
    ExperimentKind.Current -> R.string.experiments_unit_current
    ExperimentKind.Rpm -> R.string.experiments_unit_rpm
}

private fun experimentPlots(samples: List<ExperimentSample>): List<Pair<String, ScriptPlot>> {
    val t = FloatArray(samples.size) { samples[it].seconds.toFloat() }
    fun s(name: String, pick: (ExperimentSample) -> Float) = PlotSeries(name, t, FloatArray(samples.size) { pick(samples[it]) })
    return listOf(
        "Current" to ScriptPlot("s", "A", listOf(s("Motor") { it.currentMotor }, s("Input") { it.currentIn })),
        "Power" to ScriptPlot("s", "W", listOf(s("Input power") { it.power })),
        "Speed" to ScriptPlot("s", "ERPM", listOf(s("ERPM") { it.erpm })),
        "Duty" to ScriptPlot("s", "%", listOf(s("Duty") { it.duty * 100f })),
        "Temperature" to ScriptPlot("s", "°C", listOf(s("MOSFET") { it.tempMosfet }, s("Motor") { it.tempMotor })),
        "Voltage" to ScriptPlot("s", "V", listOf(s("Input") { it.voltage })),
    )
}

internal fun experimentCsv(samples: List<ExperimentSample>): String = buildString {
    append("seconds;commanded;current_in;current_motor;power;voltage;erpm;duty;temp_mosfet;temp_motor\n")
    for (s in samples) {
        append(listOf(s.seconds, s.commanded, s.currentIn, s.currentMotor, s.power, s.voltage, s.erpm, s.duty, s.tempMosfet, s.tempMotor).joinToString(";")).append('\n')
    }
}
