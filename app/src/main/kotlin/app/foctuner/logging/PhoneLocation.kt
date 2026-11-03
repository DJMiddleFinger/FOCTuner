package app.foctuner.logging

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import app.foctuner.logs.GeoFix
import kotlin.math.cos
import kotlin.math.sin

/** Where the phone is; [latest] is the most recent fix, null while there is none. */
interface FixSource {
    fun latest(): GeoFix?

    fun close()
}

/** The phone's GPS receiver. Needs the location permission; check it before calling. */
class GpsFixSource @SuppressLint("MissingPermission") constructor(context: Context) : FixSource, LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)

    @Volatile
    private var fix: GeoFix? = null

    init {
        if (LocationManager.GPS_PROVIDER in manager.allProviders) {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_MS, 0f, this, Looper.getMainLooper())
        }
    }

    override fun latest(): GeoFix? = fix

    override fun close() = manager.removeUpdates(this)

    override fun onLocationChanged(location: Location) {
        fix = GeoFix(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.takeIf { it.hasAltitude() }?.altitude,
            speed = location.takeIf { it.hasSpeed() }?.speed,
            accuracy = location.takeIf { it.hasAccuracy() }?.accuracy,
            verticalAccuracy = location.takeIf { it.hasVerticalAccuracy() }?.verticalAccuracyMeters,
            timeMs = location.time,
        )
    }

    private companion object {
        const val UPDATE_MS = 1_000L
    }
}

/** Demo mode has no satellites: a loop of about 120 m radius ridden at 5.5 m/s, a fix a second. */
class DemoFixSource(private val clock: () -> Long = System::currentTimeMillis) : FixSource {
    override fun latest(): GeoFix {
        val ms = clock() / 1000 * 1000
        val angle = ms / 1000.0 * SPEED / RADIUS
        return GeoFix(
            latitude = CENTRE_LAT + RADIUS * sin(angle) / METERS_PER_DEGREE,
            longitude = CENTRE_LON + RADIUS * (cos(angle) - 1) / (METERS_PER_DEGREE * cos(Math.toRadians(CENTRE_LAT))),
            altitude = 12.0,
            speed = SPEED.toFloat(),
            accuracy = 4f,
            verticalAccuracy = 6f,
            timeMs = ms,
        )
    }

    override fun close() = Unit

    private companion object {
        const val CENTRE_LAT = 57.70887
        const val CENTRE_LON = 11.97456
        const val RADIUS = 120.0
        const val SPEED = 5.5
        const val METERS_PER_DEGREE = 111_320.0
    }
}
