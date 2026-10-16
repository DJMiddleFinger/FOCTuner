package app.foctuner.sim

/**
 * COMM_SET_CURRENT_REL: the firmware scales by `l_current_max` when the duty cycle is near zero or has the same
 * sign, by `|l_current_min|` otherwise; the simulated motor uses ±[CURRENT_LIMIT] for both.
 */
internal fun relativeCurrent(fraction: Float): Float = fraction * CURRENT_LIMIT

private const val CURRENT_LIMIT = 60f
