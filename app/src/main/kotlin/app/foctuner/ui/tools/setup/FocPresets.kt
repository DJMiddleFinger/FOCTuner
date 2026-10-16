package app.foctuner.ui.tools.setup

import androidx.annotation.StringRes
import app.foctuner.R

/**
 * What the vehicle is, from VESC Tool's FOC wizard: [dutyStart] (`l_duty_start`), [faultStopMs]
 * (`m_fault_stop_time_ms`), [bmsLimitMode] (`bms.limit_mode`), whether the battery cutoff is set cautiously, and
 * whether HFI start may replace sensorless start.
 */
enum class UsagePreset(
    @param:StringRes val title: Int,
    val dutyStart: Double,
    val faultStopMs: Long,
    val bmsLimitMode: Long,
    val cautiousBatteryCut: Boolean,
    val hfiStart: Boolean,
) {
    Generic(R.string.setup_usage_generic, 1.0, 500, 3, true, false),
    ESkate(R.string.setup_usage_eskate, 0.85, 50, 3, true, true),
    Balance(R.string.setup_usage_balance, 1.0, 50, 0, false, false),
    Propeller(R.string.setup_usage_propeller, 1.0, 500, 3, true, false),
}

/**
 * Motor size classes from VESC Tool's FOC wizard. [maxPowerLoss] (W) sets the detection and current limits, so a
 * class much larger than the real motor can burn it.
 */
enum class MotorPreset(
    @param:StringRes val title: Int,
    val maxPowerLoss: Float,
    val openloopErpm: Float,
    val sensorlessErpm: Float,
    val hfiStart: Boolean,
    val poles: Long,
) {
    MiniOutrunner(R.string.setup_motor_mini_outrunner, 20f, 1400f, 4000f, false, 14),
    SmallOutrunner(R.string.setup_motor_small_outrunner, 50f, 1400f, 4000f, false, 14),
    MediumOutrunner(R.string.setup_motor_medium_outrunner, 120f, 700f, 4000f, true, 14),
    LargeOutrunner(R.string.setup_motor_large_outrunner, 400f, 700f, 4000f, false, 14),
    SmallInrunner(R.string.setup_motor_small_inrunner, 50f, 1400f, 4000f, false, 2),
    MediumInrunner(R.string.setup_motor_medium_inrunner, 140f, 1400f, 4000f, false, 4),
    LargeInrunner(R.string.setup_motor_large_inrunner, 400f, 1000f, 4000f, false, 4),
    EBikeHub(R.string.setup_motor_ebike_hub, 150f, 300f, 2000f, false, 46),
    EdfInrunner(R.string.setup_motor_edf_inrunner, 110f, 1400f, 4000f, false, 6),
}
