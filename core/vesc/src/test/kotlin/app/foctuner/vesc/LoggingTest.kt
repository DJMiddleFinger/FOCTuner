package app.foctuner.vesc

import app.foctuner.logs.GeoFix
import app.foctuner.logs.LogCsv
import app.foctuner.logs.LogEvent
import app.foctuner.logs.LogField
import app.foctuner.logs.LogKeys
import app.foctuner.logs.GpsTrack
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_LOG_CONFIG_FIELD
import app.foctuner.protocol.CommPacketId.COMM_LOG_DATA_F32
import app.foctuner.protocol.CommPacketId.COMM_LOG_DATA_F64
import app.foctuner.protocol.CommPacketId.COMM_LOG_START
import app.foctuner.protocol.CommPacketId.COMM_LOG_STOP
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LoggingTest {
    private fun TestScope.client(chunkSize: Int = Int.MAX_VALUE) = VescClient(SimulatedVesc(testTimeSource, chunkSize = chunkSize), backgroundScope)

    private class MemorySink(initial: ByteArray = ByteArray(0)) : DownloadSink {
        var data = initial
        override val size get() = data.size.toLong()
        override fun head(length: Int) = data.copyOf(length)
        override fun append(bytes: ByteArray) { data += bytes }
        override fun clear() { data = ByteArray(0) }
    }

    @Test
    fun theLogCommandsCarryWhatTheExpressAndVescToolExpect() = runTest {
        val transport = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(transport, backgroundScope)

        client.logConfigField(3, LogField("v_in", "Input Voltage", "V", 2, relativeToFirst = true, timestamp = false))
        client.logStart(4, 20f, appendTime = true, appendGnss = false, appendGnssTime = true)
        client.logSendF32(1, floatArrayOf(1.5f, -2f))
        client.logSendF64(0, doubleArrayOf(57.7149587))
        client.logStop()

        val config = transport.sent(COMM_LOG_CONFIG_FIELD.id).single()
        assertContentEquals(byteArrayOf(0, 3) + "v_in\u0000Input Voltage\u0000V\u0000".encodeToByteArray() + byteArrayOf(2, 1, 0), config.copyOfRange(1, config.size))
        val start = transport.sent(COMM_LOG_START.id).single()
        assertEquals(1 + 2 + 4 + 3, start.size)
        assertEquals(listOf<Byte>(1, 0, 1), start.takeLast(3))
        assertEquals(listOf(LogEvent.Start(4, 20.0, appendTime = true, appendGnss = false, appendGnssTime = true)), listOf(parseLogEvent(start)))
        assertEquals(LogEvent.Samples(1, listOf(1.5, -2.0)), parseLogEvent(transport.sent(COMM_LOG_DATA_F32.id).single()))
        val wide = parseLogEvent(transport.sent(COMM_LOG_DATA_F64.id).single()) as LogEvent.Samples
        assertEquals(57.7149587, wide.values.single(), 1e-9)
        assertEquals(LogEvent.Stop, parseLogEvent(transport.sent(COMM_LOG_STOP.id).single()))
        assertEquals(LogEvent.Field(3, LogField("v_in", "Input Voltage", "V", 2, relativeToFirst = true)), parseLogEvent(config))
    }

    @Test
    fun theExpressStoresOnlyShortNames() = runTest {
        val client = client()

        assertFailsWith<IllegalArgumentException> { client.logConfigField(0, LogField("k".repeat(25), "n")) }
        assertFailsWith<IllegalArgumentException> { client.logConfigField(0, LogField("k", "n", "°C°C°C°")) }
        assertFailsWith<IllegalArgumentException> { client.logStart(121, 10f) }
    }

    @Test
    fun aLogStartedFromTheAppFillsAFileOnTheExpressAndStopsWithIt() = runTest {
        val client = client()
        val columns = RideColumns.controller.filter { it.field.key in setOf(LogKeys.SPEED_VESC, LogKeys.VOLTAGE, LogKeys.AH_USED) }
        var rows = 0

        val job = launch { client.logToController(columns, rateHz = 10f) { rows = it } }
        advanceTimeBy(3.seconds)
        job.cancelAndJoin()

        val names = client.listFiles("/log_can").map { it.name }
        assertEquals(4, names.size)
        val log = names.first { it.startsWith("log_003_") }
        assertTrue(log.endsWith(".csv"))
        val table = LogCsv.parse(client.readFile("/log_can/$log"))
        assertEquals(listOf(LogKeys.SPEED_VESC, LogKeys.VOLTAGE, LogKeys.AH_USED, LogKeys.TIME), table.fields.map { it.key })
        assertEquals("Input Voltage", table.fields[1].name)
        assertTrue(rows >= 20, "sent $rows samples in 3 s at 10 Hz")
        assertTrue(table.rowCount in 25..35, "wrote ${table.rowCount} rows in 3 s at 10 Hz")
        assertTrue(table.column(LogKeys.VOLTAGE)!!.drop(3).all { it in 30.0..55.0 })

        val sizeAfterStop = client.listFiles("/log_can").first { it.name == log }.size
        advanceTimeBy(5.seconds)
        assertEquals(sizeAfterStop, client.listFiles("/log_can").first { it.name == log }.size)
    }

    @Test
    fun aSecondStartWhileALogRunsChangesNothing() = runTest {
        val client = client()
        client.logConfigField(0, LogField("a", "A"))
        client.logStart(1, 10f)
        client.logSendF32(0, floatArrayOf(1f))
        advanceTimeBy(1.seconds)
        client.logConfigField(0, LogField("other", "Other"))
        client.logStart(1, 50f)
        advanceTimeBy(1.seconds)
        client.logStop()

        val logs = client.listFiles("/log_can").filter { it.name.startsWith("log_003_") }
        assertEquals(1, logs.size)
        val text = client.readFile("/log_can/${logs.single().name}").decodeToString()
        assertTrue(text.startsWith("a:A:"))
        assertTrue(text.lines().size in 18..24)
    }

    @Test
    fun aScriptsLogStreamBecomesALogWhenItStops() = runTest {
        val transport = ScriptedTransport()
        val client = VescClient(transport, backgroundScope)
        val log = async(start = CoroutineStart.UNDISPATCHED) { client.streamedLogs(minRows = 3) { currentTime / 1000.0 + 36_000 }.first() }

        transport.inject(ByteWriter().u8(COMM_LOG_START.id).i16(2).f32Auto(10f).u8(1).u8(0).u8(0).toByteArray())
        transport.inject(ByteWriter().u8(COMM_LOG_CONFIG_FIELD.id).i16(0).cString("val_a").cString("Value A").cString("").u8(2).u8(0).u8(0).toByteArray())
        transport.inject(ByteWriter().u8(COMM_LOG_CONFIG_FIELD.id).i16(1).cString("val_b").cString("Value B").cString("rad").u8(2).u8(0).u8(0).toByteArray())
        advanceTimeBy(5)
        transport.inject(ByteWriter().u8(COMM_LOG_DATA_F32.id).i16(0).f32Auto(0.5f).f32Auto(-0.5f).toByteArray())
        advanceTimeBy(500)
        transport.inject(ByteWriter().u8(COMM_LOG_STOP.id).toByteArray())

        val table = log.await()
        assertEquals(listOf(LogKeys.TIME, "val_a", "val_b"), table.fields.map { it.key })
        assertEquals("rad", table.fields[2].unit)
        assertTrue(table.rowCount in 4..6)
        assertEquals(0.5, table.columns[1].last())
        assertTrue(table.seconds.last() in 0.3..0.6)
    }

    @Test
    fun aDownloadCutOffByTheLinkContinuesWhereItStopped() = runTest {
        val client = client(chunkSize = 20)
        val path = "/log_can/log_002_26-10-15_18-47-05.csv"
        val whole = client.readFile(path)
        val sink = MemorySink()

        assertFailsWith<IllegalStateException> {
            client.downloadFile(path, sink) { done, _ -> if (done >= 5_000) throw IllegalStateException("link dropped") }
        }
        val partial = sink.size
        assertTrue(partial in 5_000..6_000)

        val seen = mutableListOf<Long>()
        val result = client.downloadFile(path, sink) { done, _ -> seen += done }

        assertEquals(partial, result.resumedAt)
        assertEquals(whole.size.toLong(), result.total)
        assertContentEquals(whole, sink.data)
        assertTrue(seen.first() == partial && seen.last() == whole.size.toLong())
    }

    @Test
    fun aPartialFileThatNoLongerMatchesStartsOver() = runTest {
        val client = client()
        val path = "/log_can/log_000_26-10-14_17-02-11.csv"
        val stale = MemorySink(client.readFile("/log_can/log_001_26-10-15_08-31-40.csv").copyOf(3_000))

        val result = client.downloadFile(path, stale)

        assertEquals(0L, result.resumedAt)
        assertContentEquals(client.readFile(path), stale.data)
    }

    @Test
    fun aFileThatGrewIsContinuedToItsNewEnd() = runTest {
        val client = client()
        client.logConfigField(0, LogField("a", "A"))
        client.logStart(1, 10f)
        client.logSendF32(0, floatArrayOf(1f))
        advanceTimeBy(2.seconds)
        val path = "/log_can/" + client.listFiles("/log_can").first { it.name.startsWith("log_003_") }.name
        val sink = MemorySink()
        client.downloadFile(path, sink)
        val first = sink.size
        advanceTimeBy(3.seconds)

        val result = client.downloadFile(path, sink)

        assertEquals(first, result.resumedAt)
        assertTrue(sink.size > first)
        assertContentEquals(client.readFile(path), sink.data)
    }

    @Test
    fun aMissingFileComesBackEmptyAndStoredRidesReadAsLogs() = runTest {
        val client = client()
        val sink = MemorySink(byteArrayOf(1, 2, 3))

        assertEquals(0L, client.downloadFile("/log_can/missing.csv", sink).total)
        assertEquals(0L, sink.size)

        client.downloadFile("/log_can/log_000_26-10-14_17-02-11.csv", sink)
        val table = LogCsv.parse(sink.data)
        assertEquals(900, table.rowCount)
        assertEquals(LogKeys.TIME, table.fields.last().key)
        val speed = table.column(LogKeys.SPEED_VESC)!!
        assertTrue(speed.max() in 15.0..40.0)
        assertTrue(speed.first() < 1.0 && speed.last() < 1.0)
        assertEquals(90.0, table.durationSeconds, 0.2)
        assertEquals(listOf("log_002_26-10-15_18-47-05.csv", "log_001_26-10-15_08-31-40.csv", "log_000_26-10-14_17-02-11.csv"), client.listLogFiles("/log_can").map { it.name })
    }

    @Test
    fun theRideRecorderWritesAVescToolLogWithThePhonesPosition() = runTest {
        val client = client()
        client.motor.setCurrent(15f, holdFor = 10.seconds)
        val recorder = RideRecorder(client) { 1_760_000_000_000L + currentTime }
        val out = StringWriter()
        val base = 1_760_000_000_000L
        val fix = { GeoFix(57.7 + (currentTime / 1000) * 1e-5, 11.97, 12.0, 4.5f, 5f, 8f, base + currentTime / 1000 * 1000) }

        val job = launch { recorder.record(out, rateHz = 5f, fix = fix) }
        advanceTimeBy(6.seconds)
        job.cancelAndJoin()

        val table = LogCsv.parse(out.toString())
        assertEquals(RideColumns.ride.map { it.field.key }, table.fields.map { it.key })
        assertEquals(table.rowCount, recorder.status.value.rows)
        assertTrue(table.rowCount in 20..35, "rows: ${table.rowCount}")
        assertTrue(table.seconds.last() in 4.5..6.0)
        val track = GpsTrack.from(table)
        assertTrue(track != null && track.points.size in 4..8, "fixes: ${track?.points?.size}")
        assertEquals(16.2, table.column(LogKeys.SPEED_GNSS)!!.max(), 1e-6)
        assertTrue(table.column(LogKeys.SPEED_VESC)!!.max() > 0.5)
        assertTrue(table.column(LogKeys.VOLTAGE)!!.all { it > 30.0 })
        assertTrue(table.column(LogKeys.TRIP_GNSS)!!.last() > 5.0)
        assertTrue(recorder.status.value.hasFix)
    }

    @Test
    fun theRideColumnsAreVescToolsOwn() {
        val keys = RideColumns.ride.map { it.field.key }

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(listOf("kmh_vesc", "kmh_gnss", "t_day", "t_day_pos", "t_trip", "trip_vesc", "trip_vesc_abs", "trip_gnss", "setup_curr_motor"), keys.take(9))
        assertTrue(keys.containsAll(listOf("gnss_lat", "gnss_lon", "gnss_alt", "gnss_h_acc", "gnss_v_acc", "cnt_wh", "pitch")))
        assertTrue(RideColumns.controller.all { it.field.key.length <= 24 && it.field.name.length <= 29 && it.field.unit.encodeToByteArray().size <= 9 })
    }
}
