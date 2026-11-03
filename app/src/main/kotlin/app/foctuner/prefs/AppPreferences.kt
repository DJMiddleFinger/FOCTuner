package app.foctuner.prefs

import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Distances and speeds as the ride dashboard and statistics show them. */
enum class Units(val perKm: Float, val distance: String, val speed: String, val efficiency: String) {
    Metric(1f, "km", "km/h", "Wh/km"),
    Imperial(0.621371192f, "mi", "mph", "Wh/mi"),
    ;

    /** [metersPerSecond] in this unit's speed. */
    fun speed(metersPerSecond: Float): Float = metersPerSecond * 3.6f * perKm

    companion object {
        private val MILES = setOf("US", "GB", "LR", "MM")

        /** VESC Tool picks imperial units from the locale on first start. */
        fun forLocale(locale: Locale): Units = if (locale.country in MILES) Imperial else Metric
    }
}

/**
 * The app's own settings, as opposed to the controller's. Defaults follow VESC Tool where it has the same
 * setting (its mobile defaults where desktop and mobile differ).
 */
data class AppPreferences(
    val units: Units = Units.forLocale(Locale.getDefault()),
    /** How long STOP makes the controllers ignore motor commands; 0 only releases the motors. */
    val stopSeconds: Int = 5,
    /** COMM_GET_VALUES polling for the overview. */
    val realtimeHz: Int = 10,
    val replMaxLines: Int = 5_000,
    val keepScreenOn: Boolean = true,
    val scanCanOnConnect: Boolean = true,
    /** Select the CAN controller that was selected last time behind the same attached controller. */
    val reconnectLastCan: Boolean = true,
    /** [INTRO_VERSION] of the first-run notice the user last accepted, 0 if never. */
    val introAccepted: Int = 0,
    /** Route planner: use [routeWhPerKm] and [routeMassKg] instead of the measured consumption and the default weight. */
    val routeOverride: Boolean = false,
    /** Null (left blank) keeps the measured consumption even with [routeOverride] on. */
    val routeWhPerKm: Float? = null,
    /** Rider and vehicle; null (left blank) keeps the default even with [routeOverride] on. */
    val routeMassKg: Float? = null,
) {
    val stopDuration: Duration get() = stopSeconds.seconds

    companion object {
        val STOP_CHOICES = listOf(0, 1, 2, 5, 10, 30, 60)
        val REALTIME_CHOICES = listOf(2, 5, 10, 20, 50)
        val REPL_CHOICES = listOf(1_000, 2_000, 5_000, 10_000, 20_000)
    }
}

/** Raise when the first-run notice says something new; everyone sees it again once. */
const val INTRO_VERSION = 1
