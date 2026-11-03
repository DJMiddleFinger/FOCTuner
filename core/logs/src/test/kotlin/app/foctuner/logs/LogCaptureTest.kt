package app.foctuner.logs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogCaptureTest {
    @Test
    fun cutsARowFromTheLatestValuesAtEachTick() {
        val capture = LogCapture()
        capture.accept(LogEvent.Start(2, 20.0, appendTime = false, appendGnss = false, appendGnssTime = false))
        capture.accept(LogEvent.Field(0, LogField("a", "Alpha", "A")))
        capture.accept(LogEvent.Samples(0, listOf(1.0, 2.0)))
        capture.tick(10.0)
        capture.accept(LogEvent.Samples(1, listOf(3.0)))
        capture.tick(10.05)
        capture.tick(10.1)

        val table = capture.table()

        assertEquals(listOf("a", "field_1"), table.fields.map { it.key })
        assertEquals("Alpha", table.fields[0].name)
        assertContentEquals(doubleArrayOf(1.0, 1.0, 1.0), table.columns[0])
        assertContentEquals(doubleArrayOf(2.0, 3.0, 3.0), table.columns[1])
        assertEquals(20.0, capture.rateHz)
    }

    @Test
    fun withTimeAppendedTheScriptsFieldsMoveOneColumnRight() {
        val capture = LogCapture()
        capture.accept(LogEvent.Start(1, 10.0, appendTime = true, appendGnss = false, appendGnssTime = false))
        capture.accept(LogEvent.Field(0, LogField("v", "Volts", "V")))
        capture.accept(LogEvent.Samples(0, listOf(48.5)))
        capture.tick(36_000.5)

        val table = capture.table()

        assertEquals(listOf(LogKeys.TIME, "v"), table.fields.map { it.key })
        assertTrue(table.fields[0].timestamp)
        assertContentEquals(doubleArrayOf(36_000.5), table.columns[0])
        assertContentEquals(doubleArrayOf(48.5), table.columns[1])
    }

    @Test
    fun noRowsOutsideARunAndAStartBeginsALogAnew() {
        val capture = LogCapture()
        capture.tick(1.0)
        assertEquals(0, capture.rowCount)

        capture.accept(LogEvent.Start(1, 5.0, appendTime = false, appendGnss = false, appendGnssTime = false))
        capture.tick(1.0)
        capture.accept(LogEvent.Stop)
        capture.tick(2.0)
        assertFalse(capture.running)
        assertEquals(1, capture.rowCount)

        capture.accept(LogEvent.Start(1, 5.0, appendTime = false, appendGnss = false, appendGnssTime = false))
        assertEquals(0, capture.rowCount)
        assertTrue(capture.running)
    }

    @Test
    fun valuesForFieldsThatDontExistAreIgnored() {
        val capture = LogCapture()
        capture.accept(LogEvent.Start(1, 5.0, appendTime = false, appendGnss = false, appendGnssTime = false))
        capture.accept(LogEvent.Samples(0, listOf(1.0, 2.0, 3.0)))
        capture.accept(LogEvent.Field(7, LogField("x", "X")))
        capture.tick(0.0)

        assertEquals(1, capture.table().fields.size)
    }
}
