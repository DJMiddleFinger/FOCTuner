package app.foctuner.logs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogTableTest {
    private fun table(vararg time: Double, values: DoubleArray = DoubleArray(time.size) { it.toDouble() }) = LogTable(
        listOf(LogField(LogKeys.TIME, "Time", "s", 3, timestamp = true), LogField(LogKeys.VOLTAGE, "V", "V")),
        listOf(time, values),
    )

    @Test
    fun timeCountsFromTheFirstSampleAndCarriesOnPastMidnight() {
        val t = table(86_398.0, 86_399.5, 0.5, 1.0)

        assertContentEquals(doubleArrayOf(0.0, 1.5, 2.5, 3.0), t.seconds)
        assertEquals(3.0, t.durationSeconds)
    }

    @Test
    fun withoutATimeColumnEachRowIsOneSecond() {
        val t = LogTable(listOf(LogField("x", "X")), listOf(doubleArrayOf(5.0, 6.0, 7.0)))

        assertContentEquals(doubleArrayOf(0.0, 1.0, 2.0), t.seconds)
        assertNull(t.column(LogKeys.TIME))
    }

    @Test
    fun findsTheRowClosestToATime() {
        val t = table(10.0, 10.1, 10.2, 11.0)

        assertEquals(0, t.rowAt(-3.0))
        assertEquals(1, t.rowAt(0.12))
        assertEquals(2, t.rowAt(0.16))
        assertEquals(3, t.rowAt(0.7))
        assertEquals(3, t.rowAt(99.0))
    }
}
