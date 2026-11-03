package app.foctuner.logs

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A position in a log: [row] is the sample it came from, so any other column can colour or label it. */
data class TrackPoint(val row: Int, val latitude: Double, val longitude: Double, val altitude: Double?)

/** A point on a flat map, in metres east and north of the track's centre. */
data class MapPoint(val east: Double, val north: Double)

class GpsTrack(val points: List<TrackPoint>) {
    /** Length along the track in metres. */
    val distance: Double by lazy { points.zipWithNext { a, b -> metersBetween(a.latitude, a.longitude, b.latitude, b.longitude) }.sum() }

    /** The points on a flat map centred on the middle of the track. Accurate over the few kilometres a ride covers. */
    val map: List<MapPoint> by lazy {
        val lat = (points.minOf { it.latitude } + points.maxOf { it.latitude }) / 2
        val lon = (points.minOf { it.longitude } + points.maxOf { it.longitude }) / 2
        val perDegree = EARTH_RADIUS * PI / 180
        points.map { MapPoint((it.longitude - lon) * perDegree * cos(lat.toRadians()), (it.latitude - lat) * perDegree) }
    }

    companion object {
        /**
         * The positions of [table] that are fixes: coordinates in range and not 0/0, an accuracy above zero
         * and within [maxAccuracy] metres when the log has one (VESC Tool drops those the same way), and a new
         * fix time when the log has one, since rows repeat the last fix between updates. Null with fewer than
         * two points.
         */
        fun from(table: LogTable, maxAccuracy: Double = DEFAULT_MAX_ACCURACY): GpsTrack? {
            val lat = table.column(LogKeys.LATITUDE) ?: return null
            val lon = table.column(LogKeys.LONGITUDE) ?: return null
            val alt = table.column(LogKeys.ALTITUDE)
            val accuracy = table.column(LogKeys.HORIZONTAL_ACCURACY)
            val fixTime = table.column(LogKeys.TIME_GNSS)
            val points = ArrayList<TrackPoint>()
            var lastFix = Double.NaN
            for (i in lat.indices) {
                if (lat[i] !in -90.0..90.0 || lon[i] !in -180.0..180.0 || (lat[i] == 0.0 && lon[i] == 0.0)) continue
                if (accuracy != null && (accuracy[i] <= 0.0 || accuracy[i] > maxAccuracy)) continue
                if (fixTime != null) {
                    if (fixTime[i] < 0.0 || fixTime[i] == lastFix) continue
                    lastFix = fixTime[i]
                }
                points += TrackPoint(i, lat[i], lon[i], alt?.get(i))
            }
            return if (points.size >= 2) GpsTrack(points) else null
        }

        const val DEFAULT_MAX_ACCURACY = 30.0
    }
}

/** Great-circle distance in metres. */
fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1).toRadians()
    val dLon = (lon2 - lon1).toRadians()
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS * asin(sqrt(a))
}

private fun Double.toRadians() = this * PI / 180

private const val EARTH_RADIUS = 6_371_008.8

object Gpx {
    /**
     * [track] as a GPX 1.1 track named [name]. With a [start] instant every point gets the time of its sample, so
     * other tools can show speed and time; without one the points carry no time.
     */
    fun write(name: String, table: LogTable, track: GpsTrack, start: Instant?, out: Appendable) {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"FOCTuner\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        out.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        val seconds = table.seconds
        for (p in track.points) {
            out.append(String.format(Locale.ROOT, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">", p.latitude, p.longitude))
            p.altitude?.let { out.append(String.format(Locale.ROOT, "<ele>%.2f</ele>", it)) }
            if (start != null) {
                val at = start.plusMillis((seconds[p.row] * 1000).toLong())
                out.append("<time>").append(DateTimeFormatter.ISO_INSTANT.format(at)).append("</time>")
            }
            out.append("</trkpt>\n")
        }
        out.append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
