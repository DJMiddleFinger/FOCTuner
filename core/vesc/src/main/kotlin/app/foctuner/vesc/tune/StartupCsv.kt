package app.foctuner.vesc.tune

import java.util.Locale

/**
 * Every start of a run as CSV (semicolon separated, one row a start, in the order they ran within each candidate):
 * what was tried, how the start went, its cost and the terms of it, and the conditions around it. Meant for setting
 * the cost weights against what the motor did: note which starts looked or sounded bad and compare.
 */
object StartupCsv {
    private val parameters = ProbeParam.entries

    val header: String = (
        listOf("candidate", "stage", "changed", "start", "direction", "current_a", "outcome", "reach_s", "progress", "cost") +
            listOf(
                "cost_time", "cost_reentries", "cost_handover", "cost_handover_torque", "cost_reverse", "cost_roughness",
                "cost_angle", "cost_following", "cost_phase_current", "cost_observer_jitter",
            ) +
            listOf(
                "openloop_entries", "openloop_s", "handover_dip", "handover_torque", "following", "angle_error_rms_deg",
                "angle_error_max_deg", "reverse_erpm", "roughness", "tracking_error", "duty_peak", "lambda_est_ratio",
                "lambda_bemf_ratio", "phase_peak_ratio", "phase_ripple", "observer_reversals", "samples",
                "fault", "voltage_v", "voltage_min_v", "input_current_peak_a", "temp_fet_c", "temp_motor_c",
            ) +
            parameters.map { it.configName }
        ).joinToString(";")

    /** [candidates] in the order they ran, then the verification's pairs (baseline start first in each). */
    fun of(candidates: List<CandidateResult>, verification: Verification?): String = buildString {
        append(header).append('\n')
        candidates.forEachIndexed { index, candidate ->
            candidate.trials.forEachIndexed { i, trial -> row(index + 1, i + 1, trial) }
        }
        if (verification != null) {
            val label = candidates.size + 1
            var n = 0
            for ((a, b) in verification.baseline.zip(verification.best)) {
                row(label, ++n, a, "baseline")
                row(label, ++n, b, "best")
            }
        }
    }

    private fun StringBuilder.row(candidate: Int, start: Int, t: TrialResult, verifying: String? = null) {
        val m = t.metrics
        val c = t.breakdown
        val end = t.record.end
        val cells = listOf(
            candidate.toString(),
            t.candidate.stage.name.lowercase(Locale.ROOT) + (verifying?.let { "_$it" } ?: ""),
            t.candidate.changed?.configName.orEmpty(),
            start.toString(),
            t.record.spec.direction.toString(),
            num(kotlin.math.abs(t.record.spec.current)),
            m.outcome.name.lowercase(Locale.ROOT),
            num(m.reachSeconds),
            num(m.progress),
            num(t.cost),
            num(c?.time), num(c?.reentries), num(c?.handover), num(c?.handoverTorque), num(c?.reverse), num(c?.roughness),
            num(c?.angle), num(c?.following), num(c?.phaseCurrent), num(c?.observerJitter),
            m.openLoopEntries.toString(), num(m.openLoopSeconds), num(m.handoverDip), num(m.handoverTorque), num(m.following),
            num(m.angleErrorRms), num(m.angleErrorMax), num(m.reverseErpm), num(m.roughness), num(m.trackingError), num(m.dutyPeak),
            num(m.lambdaEstRatio), num(m.lambdaBemfRatio), num(m.phaseCurrents?.peakRatio), num(m.phaseCurrents?.ripple),
            m.observerReversals?.toString().orEmpty(), m.samples.toString(),
            end.fault.toString(), num(end.voltage), num(t.voltageMin), num(t.inputCurrentPeak), num(end.fetTemp), num(end.motorTemp),
        ) + parameters.map { num(t.candidate.params[it]) }
        append(cells.joinToString(";")).append('\n')
    }

    /** Plain decimal with a point, up to 6 significant digits; empty for a missing value. */
    private fun num(v: Double?): String = when {
        v == null || !v.isFinite() -> ""
        else -> String.format(Locale.ROOT, "%.6g", v).let { if ('e' in it || '.' !in it) it else it.trimEnd('0').trimEnd('.') }
    }
}
