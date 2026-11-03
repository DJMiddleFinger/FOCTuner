package app.foctuner.logs

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpsTrackTest {
    private fun gps(lat: DoubleArray, lon: DoubleArray, accuracy: DoubleArray? = null, fix: DoubleArray? = null): LogTable {
        val n = lat.size
        val fields = mutableListOf(LogField(LogKeys.TIME, "Time", "s", 3, timestamp = true), LogField(LogKeys.LATITUDE, "Lat", "°", 7), LogField(LogKeys.LONGITUDE, "Lon", "°", 7))
        val columns = mutableListOf(DoubleArray(n) { 36_000.0 + it }, lat, lon)
        if (accuracy != null) { fields += LogField(LogKeys.HORIZONTAL_ACCURACY, "Acc", "m"); columns += accuracy }
        if (fix != null) { fields += LogField(LogKeys.TIME_GNSS, "Fix", "s", 3, timestamp = true); columns += fix }
        return LogTable(fields, columns)
    }

    @Test
    fun keepsOnlyRealFixes() {
        val table = gps(
            lat = doubleArrayOf(0.0, 57.0, 57.0001, 57.0002, 57.0003, 95.0),
            lon = doubleArrayOf(0.0, 12.0, 12.0, 12.0, 12.0, 12.0),
            accuracy = doubleArrayOf(5.0, 0.0, 4.0, 80.0, 6.0, 5.0),
        )

        assertEquals(listOf(2, 4), GpsTrack.from(table)?.points?.map { it.row })
    }

    @Test
    fun aRepeatedFixTimeIsOneFix() {
        val table = gps(
            lat = doubleArrayOf(57.0, 57.0, 57.0001, 57.0001, 57.0002),
            lon = DoubleArray(5) { 12.0 },
            fix = doubleArrayOf(100.0, 100.0, 101.0, 101.0, 102.0),
        )

        assertEquals(listOf(0, 2, 4), GpsTrack.from(table)?.points?.map { it.row })
    }

    @Test
    fun aLogWithoutPositionsHasNoTrack() {
        assertNull(GpsTrack.from(LogTable(listOf(LogField("x", "X")), listOf(doubleArrayOf(1.0, 2.0)))))
        assertNull(GpsTrack.from(gps(doubleArrayOf(57.0, 0.0), doubleArrayOf(12.0, 0.0))))
    }

    @Test
    fun measuresTheTrackAndLaysItOnAFlatMapAroundItsCentre() {
        // 0.001 degrees of latitude is about 111 m; of longitude at 60° about half that.
        val track = GpsTrack.from(gps(doubleArrayOf(60.0, 60.001, 60.001), doubleArrayOf(10.0, 10.0, 10.002)))!!

        assertEquals(111.2 + 111.2, track.distance, 1.0)
        val map = track.map
        assertEquals(3, map.size)
        assertTrue(abs(map.maxOf { it.east } + map.minOf { it.east }) < 1e-6)
        assertTrue(abs(map.maxOf { it.north } + map.minOf { it.north }) < 1e-6)
        assertEquals(111.2, map.maxOf { it.north } - map.minOf { it.north }, 0.5)
    }

    @Test
    fun writesGpxWithTimesFromTheSamples() {
        val table = gps(doubleArrayOf(57.0, 57.0001), doubleArrayOf(12.0, 12.0001))
        val track = assertNotNull(GpsTrack.from(table))
        val out = StringBuilder()

        Gpx.write("Ride <1>", table, track, Instant.parse("2026-10-03T12:00:00Z"), out)

        val gpx = out.toString()
        assertTrue("<name>Ride &lt;1&gt;</name>" in gpx)
        assertTrue("<trkpt lat=\"57.0000000\" lon=\"12.0000000\"><time>2026-10-03T12:00:00Z</time></trkpt>" in gpx)
        assertTrue("<time>2026-10-03T12:00:01Z</time>" in gpx)
        val untimed = StringBuilder().also { Gpx.write("x", table, track, null, it) }
        assertTrue("<time>" !in untimed)
    }

    @Test
    fun namesGiveTheDateAndTheFirstRowSettlesTheDay() {
        val phone = LogNames.start("2026-10-03_23-59-58.csv")!!
        val express = LogNames.start("log_001_25-04-07_17-37-00.csv")!!

        assertEquals(LocalDateTime.of(2026, 10, 3, 23, 59, 58), phone.dateTime)
        assertEquals(true, express.utc)
        assertEquals(LocalDateTime.of(2025, 4, 7, 17, 37, 0), express.dateTime)
        assertNull(LogNames.start("notes.txt"))
        assertEquals("2026-10-03_08-05-09.csv", LogNames.phone(LocalDateTime.of(2026, 10, 3, 8, 5, 9)))

        // 23:59:58 local in Stockholm (UTC+2) is 21:59:58 UTC; the first row says 21:59:58.4.
        assertEquals(Instant.parse("2026-10-03T21:59:58.400Z"), LogNames.instant(phone, 21 * 3600 + 59 * 60 + 58.4, ZoneId.of("Europe/Stockholm")))
        // A name just before midnight UTC, first row just after: the next day.
        val late = LogNames.Start(LocalDateTime.of(2026, 10, 3, 23, 59, 59), utc = true)
        assertEquals(Instant.parse("2026-10-04T00:00:00.500Z"), LogNames.instant(late, 0.5, ZoneId.of("UTC")))
        assertEquals(Instant.parse("2026-10-03T23:59:59Z"), LogNames.instant(late, null, ZoneId.of("UTC")))
    }
}
