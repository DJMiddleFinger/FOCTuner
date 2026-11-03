package app.foctuner.logs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogStatsTest {
    private fun table(time: DoubleArray, values: DoubleArray) = LogTable(
        listOf(LogField(LogKeys.TIME, "Time", "s", 3, timestamp = true), LogField("x", "X")),
        listOf(time, values),
    )

    @Test
    fun findsMinimumMaximumAndWhereTheyAre() {
        val s = table(doubleArrayOf(0.0, 1.0, 2.0, 3.0), doubleArrayOf(4.0, -2.0, 9.0, 5.0)).stats(1)!!

        assertEquals(-2.0, s.min)
        assertEquals(9.0, s.max)
        assertEquals(1, s.minRow)
        assertEquals(2, s.maxRow)
    }

    @Test
    fun theMeanFollowsTheLineBetweenSamplesSoASkippedStretchCountsForItsLength() {
        // 0 rising to 10 in the first second, then 10 for two more seconds: 25 / 3 where a plain average says 6.67.
        val s = table(doubleArrayOf(0.0, 1.0, 3.0), doubleArrayOf(0.0, 10.0, 10.0)).stats(1)!!

        assertEquals(25.0 / 3, s.mean, 1e-9)
    }

    @Test
    fun aSelectedStretchOnlyCountsItsOwnRowsAndAnEmptyOneHasNoStats() {
        val t = table(doubleArrayOf(0.0, 1.0, 2.0, 3.0), doubleArrayOf(100.0, 2.0, 4.0, 100.0))

        assertEquals(3.0, t.stats(1, 1..2)!!.mean, 1e-9)
        assertEquals(3, t.stats(1, 2..99)!!.maxRow)
        assertNull(t.stats(1, 5..9))
    }

    @Test
    fun decimatingKeepsEveryRowWhenThereIsRoomAndTheExtremesWhenThereIsNot() {
        val values = DoubleArray(10_000) { if (it == 4_321) 50.0 else if (it == 7_777) -50.0 else (it % 7).toDouble() }

        assertContentEquals(intArrayOf(2, 3, 4), decimate(values, 2, 4, 100))

        val rows = decimate(values, 0, 9_999, 100)
        assertTrue(rows.size <= 200)
        assertTrue(4_321 in rows && 7_777 in rows)
        assertTrue(rows.toList() == rows.sorted())
    }
}
