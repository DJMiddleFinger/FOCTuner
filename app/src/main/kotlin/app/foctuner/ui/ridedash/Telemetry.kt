package app.foctuner.ui.ridedash


/** One combined sample across one or two controllers. */
data class Telemetry(
    val timeMs: Long,
    val voltage: Double,
    val batteryCurrent: Double,
    val motorCurrent: Double,
    val duty: Double,
    val erpm: Double,
    /** °C, or null when the VESC has no reading (see [sensorTemp]). */
    val tempFet: Double?,
    val tempMotor: Double?,
    val ahUsed: Double,
    val ahCharged: Double,
    val whUsed: Double,
    val whCharged: Double,
    val tachoAbs: Long,
    val fault: Int,
    val controllers: Int,
    /** Stabilized state of charge filled in by the repository; NaN = use raw voltage. */
    val batteryPct: Double = Double.NaN,
)

/**
 * A VESC reports exactly 0.0 for a temperature it isn't measuring — e.g. motor sensor type
 * "Disabled" returns the (default 0) override value — and far out-of-range values for an
 * unplugged thermistor. Treat both as "no reading".
 */
fun sensorTemp(celsius: Double): Double? =
    if (celsius == 0.0 || celsius < -50.0 || celsius > 250.0) null else celsius

object Battery {
    private val curve = listOf(
        3.00 to 0.0, 3.30 to 5.0, 3.50 to 15.0, 3.60 to 25.0, 3.70 to 45.0,
        3.80 to 60.0, 3.90 to 72.0, 4.00 to 84.0, 4.10 to 94.0, 4.20 to 100.0,
    )

    /** Rough Li-ion state of charge from resting cell voltage. Sags under load. */
    fun percent(cellVoltage: Double): Double {
        if (cellVoltage <= curve.first().first) return 0.0
        if (cellVoltage >= curve.last().first) return 100.0
        for (i in 1 until curve.size) {
            val (v1, p1) = curve[i]
            if (cellVoltage <= v1) {
                val (v0, p0) = curve[i - 1]
                return p0 + (p1 - p0) * (cellVoltage - v0) / (v1 - v0)
            }
        }
        return 100.0
    }
}
