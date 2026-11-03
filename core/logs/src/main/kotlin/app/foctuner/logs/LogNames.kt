package app.foctuner.logs

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

object LogNames {
    /** `2026-10-03_14-05-09.csv`: the phone's local time, as VESC Tool names the logs it writes. */
    fun phone(at: LocalDateTime): String = "%04d-%02d-%02d_%02d-%02d-%02d.csv".format(at.year, at.monthValue, at.dayOfMonth, at.hour, at.minute, at.second)

    /** A name from [phone] or the VESC Express logger's `log_001_25-04-07_17-37-00.csv` (UTC from the GNSS receiver). */
    fun start(name: String): Start? {
        PHONE.find(name)?.let { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            return Start(LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()), utc = false)
        }
        EXPRESS.find(name)?.let { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            return Start(LocalDateTime.of(2000 + y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()), utc = true)
        }
        return null
    }

    /**
     * When the first sample of a log was taken, from its file name and the time of day (UTC) in its first row. The
     * name gives the date; the row gives the exact time, which also settles a name in local time near midnight.
     */
    fun instant(start: Start, firstTimeOfDay: Double?, zone: ZoneId): Instant {
        val named = start.dateTime.atZone(if (start.utc) ZoneOffset.UTC else zone).toInstant()
        if (firstTimeOfDay == null) return named
        val day = named.atZone(ZoneOffset.UTC).toLocalDate()
        return (-1L..1L).map { day.plusDays(it).atStartOfDay(ZoneOffset.UTC).toInstant().plusMillis((firstTimeOfDay * 1000).toLong()) }
            .minBy { Duration.between(named, it).abs() }
    }

    data class Start(val dateTime: LocalDateTime, val utc: Boolean)

    private val PHONE = Regex("""(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})""")
    private val EXPRESS = Regex("""_(\d{2})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})""")
}
