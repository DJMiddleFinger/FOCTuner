package app.foctuner.logs

import kotlin.math.abs
import kotlin.math.max

/** One look at the vehicle, from COMM_GET_VALUES_SETUP. Counters are since the controller booted. */
data class TripSample(
    val timeMs: Long,
    /** m/s, signed. */
    val speed: Float,
    /** Metres travelled, both directions counted. */
    val distance: Float,
    val wattHours: Float,
    val wattHoursCharged: Float,
    val uptimeMs: Long,
    /** The controller's stored odometer in metres. */
    val odometer: Long,
)

data class Trip(
    val startMs: Long,
    val endMs: Long,
    val distance: Double,
    val whUsed: Double,
    val whRegen: Double,
    val maxSpeed: Double,
    /** Time spent actually moving, without the stops. */
    val movingMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

/** Everything the app has seen this controller do, which a firmware update or a reset of the controller doesn't touch. */
data class Lifetime(
    val distance: Double = 0.0,
    val whUsed: Double = 0.0,
    val whRegen: Double = 0.0,
    val movingMs: Long = 0,
)

/** [seeded] is false until the first sample, which starts the lifetime distance at the controller's odometer. */
data class TripLedger(
    val lifetime: Lifetime = Lifetime(),
    val trips: List<Trip> = emptyList(),
    val seeded: Boolean = false,
)

data class TripRules(
    /** m/s above which the vehicle counts as moving (3.6 km/h). */
    val startSpeed: Float = 1f,
    /** m/s below which it stands still. */
    val stopSpeed: Float = 0.5f,
    /** Moving this long without a break makes a trip, so pushing the vehicle a metre doesn't. */
    val startAfterMs: Long = 3_000,
    /** Standing still this long ends the trip; a red light doesn't. */
    val endAfterMs: Long = 120_000,
    /** No sample for this long (link or app gone) ends the trip at the last motion. */
    val maxGapMs: Long = 30_000,
    val minDistance: Double = 50.0,
    val minDurationMs: Long = 30_000,
)

/** A trip that has started and not ended. */
data class TripProgress(val startMs: Long, val distance: Double, val whUsed: Double, val whRegen: Double, val maxSpeed: Double, val movingMs: Long)

/**
 * Finds trips in a stream of [TripSample]s by motion and keeps the lifetime totals. Distances and energy are the
 * controller's counters, added up between samples; a counter that went back (the controller restarted) or a gap in
 * the samples adds nothing to the trip, only the distance of a gap with no restart to the lifetime.
 */
class TripTracker(private val rules: TripRules = TripRules(), ledger: TripLedger = TripLedger()) {
    var ledger: TripLedger = ledger
        private set

    private var previous: TripSample? = null
    private var active: Accumulator? = null
    private var candidate: Accumulator? = null

    val current: TripProgress? get() = active?.progress()

    fun accept(s: TripSample): TripLedger {
        val p = previous
        previous = s
        if (!ledger.seeded) ledger = ledger.copy(lifetime = ledger.lifetime.copy(distance = s.odometer.toDouble()), seeded = true)
        val step = p?.takeIf { s.uptimeMs >= it.uptimeMs && s.timeMs >= it.timeMs }
        val dt = step?.let { s.timeMs - it.timeMs } ?: 0L
        val gap = step == null || dt > rules.maxGapMs
        val dDistance = step?.let { max(0f, s.distance - it.distance).toDouble() } ?: 0.0
        val dUsed = step?.let { max(0f, s.wattHours - it.wattHours).toDouble() } ?: 0.0
        val dRegen = step?.let { max(0f, s.wattHoursCharged - it.wattHoursCharged).toDouble() } ?: 0.0
        val moving = abs(s.speed) >= rules.stopSpeed

        ledger = ledger.copy(
            lifetime = ledger.lifetime.copy(
                distance = ledger.lifetime.distance + dDistance,
                whUsed = ledger.lifetime.whUsed + dUsed,
                whRegen = ledger.lifetime.whRegen + dRegen,
                movingMs = ledger.lifetime.movingMs + if (moving && !gap) dt else 0L,
            ),
        )

        if (gap) {
            close()
            candidate = if (abs(s.speed) >= rules.startSpeed) Accumulator(s.timeMs).also { it.moved(s) } else null
            return ledger
        }

        val trip = active
        if (trip != null) {
            trip.add(dDistance, dUsed, dRegen)
            if (moving) {
                trip.moved(s, dt)
            } else if (s.timeMs - trip.lastMovingMs >= rules.endAfterMs) {
                close()
            }
            return ledger
        }

        if (abs(s.speed) >= rules.startSpeed) {
            val c = candidate ?: Accumulator(p?.timeMs ?: s.timeMs)
            candidate = c
            c.add(dDistance, dUsed, dRegen)
            c.moved(s, dt)
            if (s.timeMs - c.startMs >= rules.startAfterMs) {
                active = c
                candidate = null
            }
        } else {
            candidate = null
        }
        return ledger
    }

    /** Takes the controller's odometer as the lifetime distance, for when the user says it is the right one. */
    fun adoptOdometer(meters: Long) {
        ledger = ledger.copy(lifetime = ledger.lifetime.copy(distance = meters.toDouble()), seeded = true)
    }

    /** Ends the trip in progress, for when the link or the app goes away. */
    fun finish(): TripLedger {
        close()
        candidate = null
        previous = null
        return ledger
    }

    private fun close() {
        val a = active ?: return
        active = null
        val p = a.progress()
        val trip = Trip(p.startMs, a.lastMovingMs, p.distance, p.whUsed, p.whRegen, p.maxSpeed, p.movingMs)
        if (trip.distance >= rules.minDistance && trip.durationMs >= rules.minDurationMs) {
            ledger = ledger.copy(trips = ledger.trips + trip)
        }
    }

    private class Accumulator(val startMs: Long) {
        var lastMovingMs = startMs
        private var distance = 0.0
        private var whUsed = 0.0
        private var whRegen = 0.0
        private var maxSpeed = 0.0
        private var movingMs = 0L

        fun add(dDistance: Double, dUsed: Double, dRegen: Double) {
            distance += dDistance
            whUsed += dUsed
            whRegen += dRegen
        }

        fun moved(s: TripSample, dt: Long = 0) {
            lastMovingMs = s.timeMs
            maxSpeed = max(maxSpeed, abs(s.speed).toDouble())
            movingMs += dt
        }

        fun progress() = TripProgress(startMs, distance, whUsed, whRegen, maxSpeed, movingMs)
    }
}

/** The ledger as text, one record a line, for a file per controller. */
object TripLedgerCodec {
    private const val HEADER = "foctuner-trips 1"

    fun encode(ledger: TripLedger): String = buildString {
        appendLine(HEADER)
        appendLine("seeded;${if (ledger.seeded) 1 else 0}")
        ledger.lifetime.let { appendLine("lifetime;${it.distance};${it.whUsed};${it.whRegen};${it.movingMs}") }
        for (t in ledger.trips) appendLine("trip;${t.startMs};${t.endMs};${t.distance};${t.whUsed};${t.whRegen};${t.maxSpeed};${t.movingMs}")
    }

    /** Null for text that isn't a ledger; lines it doesn't understand are skipped. */
    fun decode(text: String): TripLedger? {
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return null
        var ledger = TripLedger()
        val trips = ArrayList<Trip>()
        for (line in lines.drop(1)) {
            val c = line.split(';')
            try {
                when (c[0]) {
                    "seeded" -> ledger = ledger.copy(seeded = c[1] == "1")
                    "lifetime" -> ledger = ledger.copy(lifetime = Lifetime(c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toLong()))
                    "trip" -> trips += Trip(c[1].toLong(), c[2].toLong(), c[3].toDouble(), c[4].toDouble(), c[5].toDouble(), c[6].toDouble(), c[7].toLong())
                }
            } catch (e: NumberFormatException) {
                continue
            } catch (e: IndexOutOfBoundsException) {
                continue
            }
        }
        return ledger.copy(trips = trips)
    }
}
