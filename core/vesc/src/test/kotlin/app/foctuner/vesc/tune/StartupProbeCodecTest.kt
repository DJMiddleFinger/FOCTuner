package app.foctuner.vesc.tune

import app.foctuner.protocol.ByteReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StartupProbeCodecTest {
    private fun sample(index: Int) = ProbeSample(
        index = index,
        seconds = 0.004 * index,
        erpm = 1234.0 + index,
        erpmFast = -1200.0,
        iq = 12.34,
        id = -0.5,
        iqSet = 12.5,
        vd = -1.23,
        vq = 4.56,
        phaseObserver = 359.99,
        phaseMotor = 12.34,
        duty = -0.1234,
        lambdaEst = 0.03442,
        resistanceEst = 0.0301,
    )

    /** What the script's 16-bit fields keep of [s]: values times their scale, truncated toward zero. */
    private fun quantized(s: ProbeSample): ProbeSample {
        fun q(v: Double, scale: Double) = (v * scale).toInt() / scale
        return s.copy(
            seconds = q(s.seconds, 1000.0), erpm = q(s.erpm, 1.0), erpmFast = q(s.erpmFast, 1.0), iq = q(s.iq, 50.0), id = q(s.id, 50.0),
            iqSet = q(s.iqSet, 50.0), vd = q(s.vd, 100.0), vq = q(s.vq, 100.0), phaseObserver = q(s.phaseObserver, 100.0),
            phaseMotor = q(s.phaseMotor, 100.0), duty = q(s.duty, 10000.0), lambdaEst = q(s.lambdaEst, 100000.0),
            resistanceEst = q(s.resistanceEst, 10000.0),
        )
    }

    @Test
    fun dataPacketsHaveTheScriptsFixedSizeAndDecodeToTheSamples() {
        val samples = List(3) { sample(it + 16) }
        val packet = ProbeCodec.data(7, 16, samples)
        assertEquals(ProbeCodec.DATA_HEADER + ProbeCodec.SAMPLES_PER_PACKET * ProbeCodec.SAMPLE_SIZE, packet.size)
        val data = assertIs<ProbeMessage.Data>(ProbeCodec.parse(packet))
        assertEquals(7, data.trial)
        assertEquals(16, data.first)
        assertEquals(samples.map(::quantized), data.samples)
    }

    @Test
    fun valuesOutsideTheFieldsAreClampedLikeTheScriptDoes() {
        val s = sample(0).copy(erpm = 99_999.0, iq = -1000.0, phaseMotor = -3.0)
        val decoded = (ProbeCodec.parse(ProbeCodec.data(1, 0, listOf(s))) as ProbeMessage.Data).samples.single()
        assertEquals(32767.0, decoded.erpm)
        assertEquals(-32767 / 50.0, decoded.iq)
        assertEquals(0.0, decoded.phaseMotor)
    }

    @Test
    fun endHelloParamsAndTheRestRoundTrip() {
        val end = TrialEnd(65535, TrialOutcome.TIMED_OUT, 412, 1234, 4, 48.5, 31.25, 40.5, 0.0344, 0.0301)
        val decoded = assertIs<ProbeMessage.End>(ProbeCodec.parse(ProbeCodec.end(end))).end
        assertEquals(end.copy(voltage = 48.5, fetTemp = 31.25, motorTemp = 40.5, lambdaEst = 0.0344f.toDouble(), resistanceEst = 0.0301f.toDouble()), decoded)

        assertEquals(ProbeMessage.Hello(ProbeCodec.VERSION, 2), ProbeCodec.parse(ProbeCodec.helloReply(2)))
        assertEquals(ProbeMessage.Released(9), ProbeCodec.parse(ProbeCodec.released(9)))
        assertEquals(ProbeMessage.Error(ProbeCodec.BUSY), ProbeCodec.parse(ProbeCodec.error(ProbeCodec.BUSY)))
        assertEquals(ProbeMessage.Bye, ProbeCodec.parse(ProbeCodec.bye()))
        assertEquals(0.0123f.toDouble(), (ProbeCodec.parse(ProbeCodec.resistanceReply(0.0123)) as ProbeMessage.Resistance).ohm)
        assertNull((ProbeCodec.parse(ProbeCodec.resistanceReply(null)) as ProbeMessage.Resistance).ohm)

        val params = ProbeCodec.parse(ProbeCodec.paramsReply(listOf(ProbeParam.OBSERVER_GAIN to 6.206e7, ProbeParam.OBSERVER_TYPE to null)))
        val values = assertIs<ProbeMessage.Params>(params).values
        assertEquals(6.206e7, values.getValue(ProbeParam.OBSERVER_GAIN)!!, 10.0)
        assertNull(values.getValue(ProbeParam.OBSERVER_TYPE))
    }

    @Test
    fun setSendsTheUnitsConfSetUses() {
        val packet = ProbeCodec.set(mapOf(ProbeParam.OBSERVER_GAIN to 6.206e7, ProbeParam.MOTOR_RESISTANCE to 0.0118, ProbeParam.FLUX_LINKAGE to 0.004014))
        val r = ByteReader(packet, 4)
        assertEquals(3, r.u8())
        assertEquals(ProbeParam.OBSERVER_GAIN.ordinal, r.u8())
        assertEquals(62.06f, Float.fromBits(r.i32()), 1e-4f) // x1M
        assertEquals(ProbeParam.MOTOR_RESISTANCE.ordinal, r.u8())
        assertEquals(11.8f, Float.fromBits(r.i32()), 1e-5f) // mΩ
        assertEquals(ProbeParam.FLUX_LINKAGE.ordinal, r.u8())
        assertEquals(4.014f, Float.fromBits(r.i32()), 1e-5f) // mWb
        val back = ProbeCodec.parseParams(packet)
        assertEquals(6.206e7, back[0].second!!, 10.0)
        assertEquals(0.0118, back[1].second!!, 1e-8)
    }

    @Test
    fun trialCommandRoundTrips() {
        val spec = TrialSpec(42, -12.5, 4800.0, 2500.milliseconds, 4.milliseconds, 6.25, 96.0, 250.milliseconds, 80.0, 1000.0, 10.5)
        assertEquals(spec, ProbeCodec.parseTrial(ProbeCodec.trial(spec)))
        assertEquals(-1, spec.direction)
    }

    @Test
    fun foreignPacketsAreIgnored() {
        assertNull(ProbeCodec.parse(byteArrayOf(101, 1, 2, 3)))
        assertNull(ProbeCodec.parse(byteArrayOf(70, 84, 2, ProbeCodec.HELLO_REPLY.toByte(), 1, 1)), "another protocol version")
        assertNull(ProbeCodec.parse(ProbeCodec.hello(1)), "a command echoed back is not a reply")
        assertNull(ProbeCodec.parse(byteArrayOf(70, 84, 1, ProbeCodec.END.toByte(), 0)), "cut off")
        assertContentEquals(byteArrayOf(70, 84, 1, ProbeCodec.STOP.toByte()), ProbeCodec.stop())
        assertEquals(ProbeCodec.TRIAL, ProbeCodec.commandOf(ProbeCodec.trial(TrialSpec(1, 1.0, 1.0, 1.seconds, 4.milliseconds, 1.0, 1.0, 1.seconds, 1.0, 1.0, 1.0))))
    }
}
