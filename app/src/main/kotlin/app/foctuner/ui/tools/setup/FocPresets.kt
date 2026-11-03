package app.foctuner.ui.tools.setup

import androidx.annotation.DrawableRes
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

/** How a motor is built, with the picture and one line that tells the kinds apart. */
enum class MotorKind(@param:DrawableRes val image: Int, @param:StringRes val hint: Int) {
    Outrunner(R.drawable.img_motor_outrunner, R.string.setup_motor_kind_outrunner),
    Inrunner(R.drawable.img_motor_inrunner, R.string.setup_motor_kind_inrunner),
    Hub(R.drawable.img_motor_hub, R.string.setup_motor_kind_hub),
    Edf(R.drawable.img_motor_edf, R.string.setup_motor_kind_edf),
}

/**
 * Motor size classes from VESC Tool's FOC wizard. [maxPowerLoss] (W) sets the detection and current limits, so a
 * class much larger than the real motor can burn it. [imageScale] draws the [kind]'s picture smaller for smaller
 * classes.
 */
enum class MotorPreset(
    @param:StringRes val title: Int,
    val kind: MotorKind,
    val imageScale: Float,
    val maxPowerLoss: Float,
    val openloopErpm: Float,
    val sensorlessErpm: Float,
    val hfiStart: Boolean,
    val poles: Long,
) {
    MiniOutrunner(R.string.setup_motor_mini_outrunner, MotorKind.Outrunner, 0.5f, 20f, 1400f, 4000f, false, 14),
    SmallOutrunner(R.string.setup_motor_small_outrunner, MotorKind.Outrunner, 0.65f, 50f, 1400f, 4000f, false, 14),
    MediumOutrunner(R.string.setup_motor_medium_outrunner, MotorKind.Outrunner, 0.82f, 120f, 700f, 4000f, true, 14),
    LargeOutrunner(R.string.setup_motor_large_outrunner, MotorKind.Outrunner, 1f, 400f, 700f, 4000f, false, 14),
    SmallInrunner(R.string.setup_motor_small_inrunner, MotorKind.Inrunner, 0.65f, 50f, 1400f, 4000f, false, 2),
    MediumInrunner(R.string.setup_motor_medium_inrunner, MotorKind.Inrunner, 0.82f, 140f, 1400f, 4000f, false, 4),
    LargeInrunner(R.string.setup_motor_large_inrunner, MotorKind.Inrunner, 1f, 400f, 1000f, 4000f, false, 4),
    EBikeHub(R.string.setup_motor_ebike_hub, MotorKind.Hub, 1f, 150f, 300f, 2000f, false, 46),
    EdfInrunner(R.string.setup_motor_edf_inrunner, MotorKind.Edf, 0.65f, 110f, 1400f, 4000f, false, 6),
}
