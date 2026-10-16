package app.foctuner.vesc

import app.foctuner.config.Config
import app.foctuner.config.ConfigKind

/**
 * Reads [kind], applies [transform], writes it and returns what the controller stored. [base] supplies the layout
 * and the fields the controller doesn't keep.
 */
suspend fun VescController.updateConfig(kind: ConfigKind, base: Config, transform: (Config) -> Config): Config {
    writeConfig(kind, transform(readConfig(kind, base)))
    return readConfig(kind, base)
}

/** The motor controllers on the bus (not BMSs or other modules) with their firmware, as VESC Tool's setup wizards list them. */
suspend fun VescClient.canVescs(): List<Pair<Int, FirmwareInfo>> = pingCan().mapNotNull { id ->
    try {
        can(id).firmwareInfo().takeIf { it.hardwareType == HardwareType.VESC }?.let { id to it }
    } catch (e: VescTimeoutException) {
        null
    }
}

/**
 * Current controller gains for a closed-loop bandwidth of 1 / [timeConstantUs] and the observer gain, as VESC
 * Tool calculates them. [observerGain] is in the unit VESC Tool shows; the config stores it times 1e6.
 */
data class FocGains(val kp: Float, val ki: Float, val observerGain: Float)

fun focGains(resistance: Float, inductance: Float, linkage: Float, timeConstantUs: Float): FocGains {
    val bandwidth = 1f / (timeConstantUs * 1e-6f)
    return FocGains(inductance * bandwidth, resistance * bandwidth, 1e-3f / (linkage * linkage))
}

/**
 * The FOC detection results and the gains that follow from them. [motorTemp] (°C), when known, becomes the base
 * temperature for temperature compensation.
 */
fun Config.withMotorParameters(rl: ResistanceInductance, linkage: Float, gains: FocGains, motorTemp: Float?): Config {
    val c = with("foc_motor_r", rl.resistance.toDouble())
        .with("foc_motor_l", rl.inductance.toDouble())
        .with("foc_motor_ld_lq_diff", rl.ldLqDiff.toDouble())
        .with("foc_motor_flux_linkage", linkage.toDouble())
        .with("foc_current_kp", gains.kp.toDouble())
        .with("foc_current_ki", gains.ki.toDouble())
        .with("foc_observer_gain", gains.observerGain * 1e6)
    return if (motorTemp != null) c.with("foc_temp_comp_base_temp", motorTemp.toDouble()) else c
}

fun Config.withFocHallTable(hall: FocHallTable): Config =
    hall.table.foldIndexed(this) { i, c, angle -> c.with("foc_hall_table__$i", angle.toLong()) }

fun Config.withEncoder(encoder: EncoderParams): Config =
    with("foc_encoder_offset", encoder.offset.toDouble())
        .with("foc_encoder_ratio", encoder.ratio.toDouble())
        .with("foc_encoder_inverted", encoder.inverted)

/** The sensorless parameters and, when the halls were read, their commutation table. */
fun Config.withBldcParams(params: BldcParams): Config {
    val c = with("sl_cycle_int_limit", params.cycleIntLimit.toDouble()).with("sl_bemf_coupling_k", params.bemfCouplingK.toDouble())
    if (params.halls != HallSensors.Detected) return c
    return params.hallTable.foldIndexed(c) { i, acc, step -> acc.with("hall_table__$i", step.toLong()) }
}

/**
 * Battery cutoff from `si_battery_type` and `si_battery_cells`, per cell as VESC Tool's wizard sets it. [cautious]
 * stops earlier and suits everything except balance vehicles, where cutting power is the bigger risk. Battery
 * types other than Li-ion and LiFePO4 are left alone.
 */
fun Config.withBatteryCut(cautious: Boolean): Config {
    val (start, end) = when (int("si_battery_type")) {
        0L -> if (cautious) 3.4 to 3.0 else 2.7 to 2.5
        1L -> if (cautious) 2.9 to 2.6 else 2.5 to 2.2
        else -> return this
    }
    val cells = int("si_battery_cells")
    return with("l_battery_cut_start", start * cells).with("l_battery_cut_end", end * cells)
}
