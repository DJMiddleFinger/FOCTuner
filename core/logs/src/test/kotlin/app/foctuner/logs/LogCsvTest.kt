package app.foctuner.logs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class LogCsvTest {
    private val fields = listOf(
        LogField(LogKeys.TIME, "Time", "s", 3, timestamp = true),
        LogField(LogKeys.VOLTAGE, "Input Voltage", "V", 1),
        LogField(LogKeys.LATITUDE, "Latitude", "°", 7),
    )

    @Test
    fun readsWhatTheVescExpressLoggerWrites() {
        val text = "t_day:Time:s:3:0:1;v_in:Input Voltage:V:2:0:0\n36000.100;50.40\n36000.200;\n36000.300;50.35\r\n\n"

        val table = LogCsv.parse(text)

        assertEquals(listOf("t_day", "v_in"), table.fields.map { it.key })
        assertEquals("Input Voltage", table.fields[1].name)
        assertEquals("V", table.fields[1].unit)
        assertEquals(2, table.fields[1].precision)
        assertEquals(true, table.fields[0].timestamp)
        assertContentEquals(doubleArrayOf(50.4, 50.4, 50.35), table.columns[1])
    }

    @Test
    fun anEmptyFirstCellIsZeroAndALineWithTheWrongNumberOfCellsIsSkipped() {
        val table = LogCsv.parse("a:A:u:1:0:0;b:B:u:1:0:0\n;2.5\n1;2;3\n4;\n")

        assertContentEquals(doubleArrayOf(0.0, 4.0), table.columns[0])
        assertContentEquals(doubleArrayOf(2.5, 2.5), table.columns[1])
    }

    @Test
    fun aHeaderCellNeedsAtLeastKeyAndName() {
        assertFailsWith<LogFormatException> { LogCsv.parse("ms_today;input_voltage\n1;2\n") }
        assertFailsWith<LogFormatException> { LogCsv.parse("") }
    }

    @Test
    fun writesWithEachFieldsPrecisionAndLeavesNanEmpty() {
        val line = LogCsv.row(fields, doubleArrayOf(36000.12345, 50.449, Double.NaN))

        assertEquals("36000.123;50.4;", line)
        assertEquals("t_day:Time:s:3:0:1;v_in:Input Voltage:V:1:0:0;gnss_lat:Latitude:°:7:0:0", LogCsv.header(fields))
    }

    @Test
    fun aWrittenTableReadsBackWithinItsPrecision() {
        val table = LogTable(fields, listOf(doubleArrayOf(1.5, 2.5), doubleArrayOf(48.2, 48.1), doubleArrayOf(57.7149587, 57.7149601)))
        val out = StringBuilder()

        LogCsv.write(table, out)
        val back = LogCsv.parse(out.toString())

        assertEquals(table.fields, back.fields)
        assertContentEquals(table.columns[2], back.columns[2])
        assertEquals(2, back.rowCount)
        assertNotNull(back.column(LogKeys.VOLTAGE))
    }
}
