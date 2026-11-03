package app.foctuner.logs

/**
 * Battery left on arrival from the vehicle's own consumption.
 *
 * The average over everything ridden so far, plus [hillWh] for the route's own climbs and descents.
 * ponytail: ignores speed, wind and temperature.
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
    fun arrivalPercent(batteryWh: Double, batteryLevel: Double, km: Double, whPerKm: Double, extraWh: Double = 0.0): Double? {
        if (batteryLevel < 0.02 || batteryWh <= 0) return null
        val capacity = batteryWh / batteryLevel
        return (batteryWh - km * whPerKm - extraWh) / capacity * 100
    }

    /**
     * Wh to lift [massKg] (rider and vehicle) up [climbM] through the drivetrain, less what regen gets back coming
     * down [descentM]. Negative on a route that is mostly downhill.
     */
    fun hillWh(massKg: Double, climbM: Double, descentM: Double): Double =
        massKg * G * (climbM / DRIVE_EFFICIENCY - descentM * REGEN_EFFICIENCY) / 3600

    /**
     * Up to [maxPoints] points spread evenly along [line] (lat, lon pairs), first and last included, to look up
     * heights for. At most one point every [minStepM] metres.
     */
    fun samples(line: List<Pair<Double, Double>>, maxPoints: Int = 100, minStepM: Double = 30.0): List<Pair<Double, Double>> {
        if (line.size < 2) return line
        val legs = line.zipWithNext { a, b -> metersBetween(a.first, a.second, b.first, b.second) }
        val total = legs.sum()
        val step = maxOf(total / (maxPoints - 1), minStepM)
        val out = mutableListOf(line.first())
        var at = step
        var start = 0.0
        legs.forEachIndexed { i, len ->
            while (at < start + len && out.size < maxPoints - 1) {
                val f = (at - start) / len
                val (a, b) = line[i] to line[i + 1]
                out += (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
                at += step
            }
            start += len
        }
        return out + line.last()
    }

    /** Metres climbed and descended over [heights], ignoring wiggles smaller than [noiseM] from the height data. */
    fun climbAndDescent(heights: List<Double>, noiseM: Double = 2.0): Pair<Double, Double> {
        var climb = 0.0
        var descent = 0.0
        var ref = heights.firstOrNull() ?: return 0.0 to 0.0
        for (h in heights) {
            val change = h - ref
            if (change >= noiseM) climb += change else if (-change >= noiseM) descent -= change else continue
            ref = h
        }
        return climb to descent
    }

    private const val G = 9.81
    private const val DRIVE_EFFICIENCY = 0.8

    /** Conservative: much of a descent is coasted or braked mechanically. */
    private const val REGEN_EFFICIENCY = 0.3
}
