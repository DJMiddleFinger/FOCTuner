package app.foctuner.ui.tools.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamType
import app.foctuner.config.ParamValue
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.NO_VALUE
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.settings.display
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.lisp.PlotSeries
import app.foctuner.ui.tools.lisp.ScriptPlot
import app.foctuner.ui.tools.lisp.ScriptPlotChart
import app.foctuner.vesc.FaultCode
import app.foctuner.vesc.updateConfig
import app.foctuner.vesc.tune.CandidateResult
import app.foctuner.vesc.tune.Finding
import app.foctuner.vesc.tune.ProbeParam
import app.foctuner.vesc.tune.StartupAnalysis
import app.foctuner.vesc.tune.StartupTuner
import app.foctuner.vesc.tune.TrialOutcome
import app.foctuner.vesc.tune.TrialResult
import app.foctuner.vesc.tune.TuneAbort
import app.foctuner.vesc.tune.TuneAbortedException
import app.foctuner.vesc.tune.TuneDefaults
import app.foctuner.vesc.tune.TuneProgress
import app.foctuner.vesc.tune.TuneRefusal
import app.foctuner.vesc.tune.TuneRefusedException
import app.foctuner.vesc.tune.TuneReport
import app.foctuner.vesc.tune.TuneSettings
import app.foctuner.vesc.tune.TuneStage
import app.foctuner.vesc.tune.applyUntilReboot
import app.foctuner.vesc.tune.withProbeValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

enum class TuneApplied { Stored, UntilReboot }

@Immutable
data class StartupTuneState(
    val config: Config? = null,
    val voltage: Double? = null,
    val refusal: TuneRefusal? = null,
    val current: String = "",
    val target: String = "",
    val timeLimit: String = "3",
    val thorough: Boolean = false,
    val capture: Boolean = true,
    val stage: TuneStage? = null,
    val done: Int = 0,
    val planned: Int = 0,
    val lastTrial: TrialResult? = null,
    val candidates: List<CandidateResult> = emptyList(),
    val best: CandidateResult? = null,
    val report: TuneReport? = null,
    val aborted: TuneAbort? = null,
    val applied: TuneApplied? = null,
) {
    /** The test as typed, or null while a field doesn't hold a usable number. */
    val settings: TuneSettings?
        get() {
            val current = parseNumber(current)?.toDouble()?.takeIf { it > 0 } ?: return null
            val target = parseNumber(target)?.toDouble()?.takeIf { it > 0 && it <= TuneSettings.MAX_TARGET_ERPM } ?: return null
            val limit = parseNumber(timeLimit)?.toDouble()?.takeIf { it in 0.5..20.0 } ?: return null
            return TuneSettings(
                current = current,
                targetErpm = target,
                timeLimit = (limit * 1000).roundToInt().milliseconds,
                thorough = thorough,
                capturePhaseCurrents = capture,
                trialsPerCandidate = 3,
                verifyPairs = if (thorough) 4 else 3,
            )
        }
}

/** The startup tuner for the tool's controller: preflight from its motor config, the run, and applying the result. */
class StartupTuneModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(StartupTuneState())
    val state: StateFlow<StartupTuneState> = _state.asStateFlow()
    val runner = SetupRunner(context)

    init {
        context.scope.launch { load() }
    }

    private suspend fun load() {
        try {
            val config = context.controller.readConfig(ConfigKind.MCCONF, context.baseConfig(ConfigKind.MCCONF))
            val voltage = context.controller.values().voltageInput.toDouble()
            _state.update {
                it.copy(
                    config = config,
                    voltage = voltage,
                    refusal = StartupTuner.refusal(config, context.firmware),
                    current = it.current.ifEmpty { TuneDefaults.current(config).fixed(1) },
                    target = it.target.ifEmpty { TuneDefaults.target(config, voltage).fixed(0) },
                )
            }
        } catch (e: IOException) {
            // The fields stay empty; reading again happens on the next start.
        }
    }

    fun edit(transform: (StartupTuneState) -> StartupTuneState) = _state.update(transform)

    fun start() {
        val settings = _state.value.settings ?: return
        _state.update { it.copy(stage = null, done = 0, planned = 0, lastTrial = null, candidates = emptyList(), best = null, report = null, aborted = null, applied = null) }
        runner.start(spins = true) {
            val config = context.controller.readConfig(ConfigKind.MCCONF, context.baseConfig(ConfigKind.MCCONF))
            _state.update { it.copy(config = config, refusal = StartupTuner.refusal(config, context.firmware)) }
            val tuner = StartupTuner(context.controller, context.firmware, config, settings, motorIndex())
            _state.update { it.copy(planned = tuner.plannedTrials) }
            try {
                val report = tuner.run(::progress)
                _state.update { it.copy(report = report, stage = null) }
            } catch (e: TuneRefusedException) {
                _state.update { it.copy(refusal = e.reason, stage = null) }
            } catch (e: TuneAbortedException) {
                _state.update { it.copy(aborted = e.reason, stage = null) }
            }
        }
    }

    private fun progress(p: TuneProgress) = _state.update {
        when (p) {
            is TuneProgress.Stage -> it.copy(stage = p.stage, planned = p.plannedTrials)
            is TuneProgress.Trial -> it.copy(lastTrial = p.result, done = p.done, planned = p.plannedTrials)
            is TuneProgress.CandidateDone -> it.copy(candidates = it.candidates + p.result, best = p.best)
        }
    }

    /** Writes the recommended changes to the motor config (flash). */
    fun apply() {
        val changes = _state.value.report?.changes?.associate { (param, _, value) -> param to value } ?: return
        runner.start(spins = false) {
            context.controller.updateConfig(ConfigKind.MCCONF, context.baseConfig(ConfigKind.MCCONF)) { it.withProbeValues(changes) }
            _state.update { it.copy(applied = TuneApplied.Stored) }
        }
    }

    /** Sets the recommended changes in RAM only, to ride them before storing. */
    fun useUntilReboot() {
        val changes = _state.value.report?.changes?.associate { (param, _, value) -> param to value } ?: return
        runner.start(spins = false) {
            applyUntilReboot(context.controller, motorIndex(), changes)
            _state.update { it.copy(applied = TuneApplied.UntilReboot) }
        }
    }

    fun discard() = _state.update { it.copy(report = null, candidates = emptyList(), best = null, lastTrial = null, applied = null, aborted = null) }

    /** 2 for the second motor of a dual board, which reports the attached board's UUID with the last byte plus one. */
    private suspend fun motorIndex(): Int {
        if (context.controller === context.client) return 1
        val attached = context.client.firmwareInfo().uuid
        val own = context.firmware.uuid
        if (attached.length != own.length || attached.length < 2) return 1
        val last = attached.takeLast(2).toIntOrNull(16) ?: return 1
        val second = (last + 1) and 0xFF
        return if (own.dropLast(2) == attached.dropLast(2) && own.takeLast(2).toIntOrNull(16) == second) 2 else 1
    }
}

@Composable
fun StartupTuneTool(context: ToolContext) {
    val model = context.model { StartupTuneModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val run by model.runner.state.collectAsStateWithLifecycle()
    val busy = run is RunState.Running
    var confirm by remember { mutableStateOf<TuneConfirm?>(null) }
    val config = state.config

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.tune_intro), Modifier.padding(Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
        state.refusal?.let { RefusalNotice(it) }

        if (config != null) {
            SectionHeader(stringResource(R.string.tune_current_settings))
            for (param in ProbeParam.entries) {
                if (param.configName !in config.definition.params) continue
                val definition = config.definition.param(param.configName)
                ValueRow(definition.longName, paramText(config, param, config.number(param.configName)))
            }
        }

        SectionHeader(stringResource(R.string.tune_test))
        NumberRow(stringResource(R.string.tune_current), state.current, "A", { v -> model.edit { it.copy(current = v) } }, !busy)
        NumberRow(stringResource(R.string.tune_target), state.target, "ERPM", { v -> model.edit { it.copy(target = v) } }, !busy)
        val poles = config?.let { runCatching { it.int("si_motor_poles") }.getOrNull() }
        val target = parseNumber(state.target)
        if (poles != null && poles > 1 && target != null) {
            Text(
                stringResource(R.string.tune_mechanical, (target / (poles / 2)).fixed(0)),
                Modifier.padding(horizontal = Spacing.x2),
                color = FocTheme.colors.textTertiary,
                style = FocTheme.type.caption,
            )
        }
        NumberRow(stringResource(R.string.tune_time_limit), state.timeLimit, "s", { v -> model.edit { it.copy(timeLimit = v) } }, !busy)
        SwitchRow(stringResource(R.string.tune_thorough), state.thorough, { v -> model.edit { it.copy(thorough = v) } }, !busy)
        SwitchRow(stringResource(R.string.tune_capture), state.capture, { v -> model.edit { it.copy(capture = v) } }, !busy)
        val settings = state.settings
        val plan = remember(settings, config) { if (settings != null && config != null) planned(settings, config, context) else null }
        plan?.let { (starts, minutes) -> ValueRow(pluralStringResource(R.plurals.tune_plan, starts, starts, minutes), "") }
        ActionRow {
            OutlineButton(
                stringResource(R.string.tune_start),
                { confirm = TuneConfirm.Start },
                enabled = !busy && settings != null && config != null && StartupTuner.refusal(config, context.firmware) == null,
            )
        }

        val label = state.stage?.let { stage ->
            stringResource(R.string.tune_progress, stringResource(stageLabel(stage)), state.done, maxOf(state.planned, state.done))
        } ?: stringResource(R.string.tune_running)
        RunStatus(run, label, model.runner::stop)
        if (busy && state.planned > 0) {
            LinearProgressIndicator(
                progress = { (state.done.toFloat() / state.planned).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.x2),
                color = FocTheme.colors.accent,
                trackColor = FocTheme.colors.surfaceHigh,
            )
        }
        state.aborted?.let { AbortNotice(it) }

        state.lastTrial?.let { trial ->
            SectionHeader(stringResource(R.string.tune_last_start))
            Text(
                outcomeText(trial),
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                color = FocTheme.colors.text,
                style = FocTheme.type.body,
            )
            val plots = remember(trial) { trialPlots(trial) }
            for ((title, plot) in plots) {
                Text(stringResource(title), Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.half), color = FocTheme.colors.textSecondary, style = FocTheme.type.caption)
                ScriptPlotChart(plot)
            }
        }

        val report = state.report
        if (report != null || state.candidates.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tune_results))
        }
        if (report != null && config != null) {
            if (!report.restored) Notice(stringResource(R.string.tune_not_restored), tone = NoticeTone.Warning)
            val verification = report.verification
            if (report.recommended != null && verification != null) {
                Notice(
                    stringResource(R.string.tune_better),
                    body = pluralStringResource(
                        R.plurals.tune_better_body,
                        verification.best.size,
                        verification.bestScore?.fixed(2) ?: NO_VALUE,
                        verification.baselineScore?.fixed(2) ?: NO_VALUE,
                        verification.best.size,
                    ),
                )
                for ((param, from, to) in report.changes) {
                    ValueRow(config.definition.param(param.configName).longName, stringResource(R.string.tune_change, paramText(config, param, from), paramText(config, param, to)))
                }
                when (state.applied) {
                    TuneApplied.Stored -> Notice(stringResource(R.string.setup_written_motor))
                    TuneApplied.UntilReboot -> Notice(stringResource(R.string.tune_tried))
                    null -> Unit
                }
                ActionRow {
                    OutlineButton(stringResource(R.string.tune_apply), { confirm = TuneConfirm.Apply }, enabled = !busy && state.applied != TuneApplied.Stored)
                    PlainButton(stringResource(R.string.tune_try), model::useUntilReboot, enabled = !busy && state.applied == null)
                    PlainButton(stringResource(R.string.tune_discard), model::discard, enabled = !busy)
                }
            } else {
                Notice(stringResource(R.string.tune_no_better), body = stringResource(R.string.tune_no_better_body))
            }
            for (finding in report.findings) FindingNotice(finding)
        }
        if (config != null && state.candidates.isNotEmpty()) {
            Text(stringResource(R.string.tune_candidates), Modifier.padding(start = Spacing.x2, top = Spacing.x2, end = Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.caption)
            for (candidate in state.candidates) CandidateRow(candidate, config, best = candidate === state.best)
            Text(stringResource(R.string.tune_score_note), Modifier.padding(Spacing.x2), color = FocTheme.colors.textTertiary, style = FocTheme.type.caption)
        }
        Hairline()
        Text(stringResource(R.string.tune_sources), Modifier.padding(Spacing.x2), color = FocTheme.colors.textTertiary, style = FocTheme.type.caption)
    }

    when (confirm) {
        TuneConfirm.Start -> {
            val s = state.settings
            val (starts, minutes) = (if (s != null && config != null) planned(s, config, context) else null) ?: (0 to 0)
            SpinDialog(
                pluralStringResource(R.plurals.tune_confirm, starts, starts, state.target, state.current, minutes),
                { confirm = null; model.start() },
                { confirm = null },
            )
        }
        TuneConfirm.Apply -> ConfirmDialog(
            stringResource(R.string.tune_apply_title),
            stringResource(R.string.tune_apply_body),
            stringResource(R.string.setup_write),
            { confirm = null; model.apply() },
            { confirm = null },
        )
        null -> Unit
    }
}

private enum class TuneConfirm { Start, Apply }

/**
 * Starts and minutes the run takes, about 2.8 s a start (rest, drive, braking and the exchanges in between); null for a
 * config the tuner refuses, whose layout may lack what it reads.
 */
private fun planned(settings: TuneSettings, config: Config, context: ToolContext): Pair<Int, Int>? {
    if (StartupTuner.refusal(config, context.firmware) != null) return null
    val starts = StartupTuner(context.controller, context.firmware, config, settings).plannedTrials
    return starts to maxOf(1, (starts * 2.8 / 60).roundToInt())
}

@Composable
private fun CandidateRow(result: CandidateResult, config: Config, best: Boolean) {
    val candidate = result.candidate
    val what = candidate.changed?.let { param ->
        "${config.definition.param(param.configName).longName} ${paramText(config, param, candidate.params.getValue(param))}"
    } ?: stringResource(stageLabel(candidate.stage))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.row)
            .padding(horizontal = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(what, Modifier.weight(1f), color = FocTheme.colors.text, style = FocTheme.type.body)
        if (best) Text(stringResource(R.string.tune_best_marker) + "  ", color = FocTheme.colors.accent, style = FocTheme.type.caption)
        Text(
            stringResource(R.string.tune_candidate_score, result.score?.fixed(2) ?: NO_VALUE, result.reached, result.trials.size),
            color = FocTheme.colors.textSecondary,
            style = FocTheme.type.value,
        )
    }
    Hairline(Modifier.padding(start = Spacing.x2))
}

@Composable
private fun outcomeText(trial: TrialResult): String {
    val m = trial.metrics
    return when (m.outcome) {
        TrialOutcome.REACHED -> stringResource(R.string.tune_outcome_reached, m.targetErpm.fixed(0), (m.reachSeconds ?: 0.0).fixed(2))
        TrialOutcome.TIMED_OUT -> stringResource(R.string.tune_outcome_timed_out, (m.progress * 100).roundToInt().coerceIn(0, 100))
        TrialOutcome.FAULT -> stringResource(R.string.tune_outcome_fault, FaultCode.fromCode(trial.record.end.fault)?.name ?: trial.record.end.fault.toString())
        else -> stringResource(R.string.tune_outcome_other, m.outcome.name.lowercase())
    }
}

/** The last start: speed with the open-loop sequence as a band, and q current against its set point. */
private fun trialPlots(trial: TrialResult): List<Pair<Int, ScriptPlot>> {
    val samples = trial.record.samples
    if (samples.size < 2) return emptyList()
    val dir = trial.record.spec.direction
    val t = FloatArray(samples.size) { samples[it].seconds.toFloat() }
    val band = (trial.record.spec.targetErpm * 0.2).toFloat()
    val openLoop = FloatArray(samples.size) {
        if (abs(StartupAnalysis.wrap(samples[it].phaseMotor - samples[it].phaseObserver)) > StartupAnalysis.OVERRIDE_DEGREES) band else 0f
    }
    return listOf(
        R.string.tune_speed to ScriptPlot(
            "s", "ERPM",
            listOf(PlotSeries("ERPM", t, FloatArray(samples.size) { (samples[it].erpm * dir).toFloat() }), PlotSeries("Open loop", t, openLoop)),
        ),
        R.string.tune_current_chart to ScriptPlot(
            "s", "A",
            listOf(
                PlotSeries("q", t, FloatArray(samples.size) { (samples[it].iq * dir).toFloat() }),
                PlotSeries("set", t, FloatArray(samples.size) { (samples[it].iqSet * dir).toFloat() }),
            ),
        ),
    )
}

/** A probe parameter's value as the Settings screen shows it (display scale, decimals, suffix, enum names). */
private fun paramText(config: Config, param: ProbeParam, value: Double): String {
    val definition = config.definition.param(param.configName)
    val v = if (definition.type == ParamType.DOUBLE) ParamValue.DoubleValue(value) else ParamValue.IntValue(Math.round(value))
    return definition.display(v, "")
}

private fun Config.number(name: String): Double = when (val v = get(name)) {
    is ParamValue.DoubleValue -> v.value
    is ParamValue.IntValue -> v.value.toDouble()
    else -> 0.0
}

private fun stageLabel(stage: TuneStage): Int = when (stage) {
    TuneStage.BASELINE -> R.string.tune_stage_baseline
    TuneStage.RESISTANCE -> R.string.tune_stage_resistance
    TuneStage.FLUX_LINKAGE -> R.string.tune_stage_flux
    TuneStage.OBSERVER_TYPE -> R.string.tune_stage_type
    TuneStage.OBSERVER_GAIN -> R.string.tune_stage_gain
    TuneStage.OPENLOOP_ERPM -> R.string.tune_stage_openloop
    TuneStage.OPENLOOP_LOCK_TIME -> R.string.tune_stage_lock
    TuneStage.OPENLOOP_RAMP_TIME -> R.string.tune_stage_ramp
    TuneStage.OPENLOOP_TIME -> R.string.tune_stage_time
    TuneStage.VERIFY -> R.string.tune_stage_verify
}

@Composable
private fun RefusalNotice(reason: TuneRefusal) {
    val (title, body) = when (reason) {
        TuneRefusal.FIRMWARE_TOO_OLD -> R.string.tune_refused_firmware to R.string.tune_refused_firmware_body
        TuneRefusal.NOT_FOC -> R.string.tune_refused_not_foc to R.string.tune_refused_not_foc_body
        TuneRefusal.NOT_SENSORLESS -> R.string.tune_refused_sensored to R.string.tune_refused_sensored_body
        TuneRefusal.NOT_DETECTED -> R.string.tune_refused_not_detected to R.string.tune_refused_not_detected_body
        TuneRefusal.FAULT_ACTIVE -> R.string.tune_refused_fault to R.string.tune_refused_fault_body
        TuneRefusal.MOTOR_TURNING -> R.string.tune_refused_turning to R.string.tune_refused_turning_body
        TuneRefusal.LOW_VOLTAGE -> R.string.tune_refused_voltage to R.string.tune_refused_voltage_body
        TuneRefusal.TOO_HOT -> R.string.tune_refused_hot to R.string.tune_refused_hot_body
        TuneRefusal.NO_PROBE -> R.string.tune_refused_probe to R.string.tune_refused_probe_body
        TuneRefusal.CONFIG_MISMATCH -> R.string.tune_refused_mismatch to R.string.tune_refused_mismatch_body
    }
    Notice(stringResource(title), body = stringResource(body), tone = NoticeTone.Warning)
}

@Composable
private fun AbortNotice(reason: TuneAbort) {
    val title = when (reason) {
        TuneAbort.TEMPERATURE -> R.string.tune_aborted_temperature
        TuneAbort.VOLTAGE -> R.string.tune_aborted_voltage
        TuneAbort.KILL_SWITCH -> R.string.tune_aborted_kill_switch
        TuneAbort.REPEATED_FAULTS -> R.string.tune_aborted_faults
        TuneAbort.NOT_AT_REST -> R.string.tune_aborted_rest
        TuneAbort.PROBE_LOST -> R.string.tune_aborted_lost
    }
    Notice(stringResource(title), body = stringResource(R.string.tune_aborted_body), tone = NoticeTone.Warning)
}

@Composable
private fun FindingNotice(finding: Finding) {
    when (finding) {
        is Finding.ResistanceMismatch -> Notice(
            stringResource(R.string.tune_finding_resistance),
            body = stringResource(R.string.tune_finding_resistance_body, (finding.measured * 1e3).fixed(2), (finding.configured * 1e3).fixed(2)) +
                if (finding.phaseFilters) "" else " " + stringResource(R.string.tune_finding_resistance_rough),
            tone = NoticeTone.Warning,
        )
        is Finding.FluxLinkageMismatch -> Notice(
            stringResource(R.string.tune_finding_flux),
            body = stringResource(R.string.tune_finding_flux_body, (finding.estimated * 1e3).fixed(3), (finding.configured * 1e3).fixed(3)),
            tone = NoticeTone.Warning,
        )
        is Finding.Faults -> Notice(
            stringResource(R.string.tune_finding_faults),
            body = (finding.codes.joinToString(", ") { FaultCode.fromCode(it)?.name ?: it.toString() } + (finding.log?.let { "\n\n$it" } ?: "")),
            tone = NoticeTone.Fault,
        )
        is Finding.DutyLimited -> Notice(
            stringResource(R.string.tune_finding_duty),
            body = stringResource(R.string.tune_finding_duty_body, (finding.peak * 100).roundToInt()),
            tone = NoticeTone.Warning,
        )
        is Finding.ObserverLosesMotor -> Notice(
            stringResource(R.string.tune_finding_lost),
            body = stringResource(R.string.tune_finding_lost_body, (finding.entries - 1).coerceAtLeast(0.0).fixed(1)),
            tone = NoticeTone.Warning,
        )
        is Finding.StartsBackwards -> Notice(
            stringResource(R.string.tune_finding_backwards),
            body = stringResource(R.string.tune_finding_backwards_body, finding.erpm.fixed(0)),
        )
        is Finding.OpenLoopSlips -> Notice(
            stringResource(R.string.tune_finding_slips),
            body = stringResource(R.string.tune_finding_slips_body, (finding.ratio * 100).roundToInt()),
            tone = NoticeTone.Warning,
        )
        is Finding.CurrentPeaks -> Notice(
            stringResource(R.string.tune_finding_peaks),
            body = stringResource(R.string.tune_finding_peaks_body, finding.ratio.fixed(1)),
        )
    }
}
