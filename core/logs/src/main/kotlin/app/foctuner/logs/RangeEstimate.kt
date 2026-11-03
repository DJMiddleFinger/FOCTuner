package app.foctuner.logs

/**
 * Battery left on arrival from the vehicle's own consumption.
 *
 * ponytail: flat-ground average over everything ridden so far; ignores elevation, speed, wind and temperature.
 * Add an elevation term (route profile × mass × g / efficiency) if hilly routes come out wrong.
 */
object RangeEstimate {
    /** Below this much history the average is noise, and the caller's default is used. */
    const val MIN_HISTORY_M = 5_000.0

    /** Net Wh per km from the lifetime totals (regen counted back), or null with too little history. */
    fun whPerKm(lifetime: Lifetime): Double? =
        if (lifetime.distance < MIN_HISTORY_M) null else ((lifetime.whUsed - lifetime.whRegen) / (lifetime.distance / 1000)).takeIf { it > 0 }

    /**
     * Battery percent left after [km] at [whPerKm], given [batteryWh] left now at [batteryLevel] (0..1, the
     * controller's estimate). Null when the level is too low to infer the pack size. Negative means it won't make it.
     */
    fun arrivalPercent(batteryWh: Double, batteryLevel: Double, km: Double, whPerKm: Double): Double? {
        if (batteryLevel < 0.02 || batteryWh <= 0) return null
        val capacity = batteryWh / batteryLevel
        return (batteryWh - km * whPerKm) / capacity * 100
    }
}
