package app.foctuner.ui.settings

import app.foctuner.config.ParamDefinition
import app.foctuner.config.ParamType
import app.foctuner.config.ParamValue
import kotlin.test.Test
import kotlin.test.assertEquals

class ParamRangeTest {
    private fun track(
        min: Double,
        max: Double,
        step: Double,
        value: Double,
        stored: Double? = null,
        decimals: Int = 2,
        integer: Boolean = false,
        held: NumberTrack? = null,
    ) = NumberTracks.track(min, max, step, decimals, integer, value, stored, held)

    @Test
    fun wideLimitsGetAWindowAroundTheValue() {
        // l_current_max: 0..5000 A, 60 A.
        val current = track(0.0, 5000.0, 1.0, 60.0, 60.0)
        assertEquals(0.0, current.min)
        assertEquals(200.0, current.max)
        assertEquals(0.5, current.step)
        assertEquals(5000.0, current.limitMax)
        // l_current_min: −5000..0 A, −60 A.
        val braking = track(-5000.0, 0.0, 1.0, -60.0, -60.0)
        assertEquals(-200.0, braking.min)
        assertEquals(0.0, braking.max)
        // l_max_erpm: 0..1e6, 100 000.
        val erpm = track(0.0, 1e6, 100.0, 100_000.0)
        assertEquals(250_000.0, erpm.max)
        assertEquals(500.0, erpm.step)
    }

    @Test
    fun narrowLimitsAreDraggedInFull() {
        // l_temp_fet_start: 0..190 °C in whole degrees.
        val temperature = track(0.0, 190.0, 1.0, 85.0, decimals = 0, integer = true)
        assertEquals(0.0, temperature.min)
        assertEquals(190.0, temperature.max)
        assertEquals(1.0, temperature.step)
        // si_motor_poles: 2..254 in steps of 2.
        val poles = track(2.0, 254.0, 2.0, 14.0, decimals = 0, integer = true)
        assertEquals(2.0, poles.min)
        assertEquals(2.0, poles.step)
        assertEquals(16.0, poles.nudge(14.0, up = true))
    }

    @Test
    fun aWindowOverHalfTheLimitsBecomesTheFullRange() {
        // foc_observer_offset: −5..5, −1 would get −2.5..2.5, half of the limits.
        val offset = track(-5.0, 5.0, 0.01, -1.0)
        assertEquals(-5.0, offset.min)
        assertEquals(5.0, offset.max)
    }

    @Test
    fun theWindowGrowsWhenTheValueReachesItsEnd() {
        assertEquals(500.0, track(0.0, 5000.0, 1.0, 200.0, 60.0).max)
        // The stored value keeps it wide while the edit goes down.
        assertEquals(200.0, track(0.0, 5000.0, 1.0, 10.0, 60.0).max)
    }

    @Test
    fun aHeldTrackKeepsItsScaleUntilReleased() {
        // Motor Current Max at 60 A: 0..200 A. Dragging or nudging to 120 A would otherwise give 0..500 A at once.
        val start = track(0.0, 5000.0, 1.0, 60.0, 60.0)
        assertEquals(start, track(0.0, 5000.0, 1.0, 120.0, 60.0, held = start))
        assertEquals(start, track(0.0, 5000.0, 1.0, 200.0, 60.0, held = start))
        // Released at the end: the next track is 2.5 times as wide.
        assertEquals(500.0, track(0.0, 5000.0, 1.0, 200.0, 60.0).max)
        // A held nudge past the end gets a wider track straight away.
        assertEquals(1000.0, track(0.0, 5000.0, 1.0, 200.5, 60.0, held = start).max)
    }

    @Test
    fun zeroValuesGetAWindowFromTheStep() {
        // foc_fw_current_max: 0 A, off.
        val fieldWeakening = track(0.0, 5000.0, 1.0, 0.0, 0.0)
        assertEquals(20.0, fieldWeakening.max)
        assertEquals(0.05, fieldWeakening.step)
    }

    @Test
    fun aLowerLimitAboveZeroStartsTheWindow() {
        // m_ntc_motor_beta: 100..100 000 K, 3380 K.
        val beta = track(100.0, 100_000.0, 1.0, 3380.0, decimals = 1)
        assertEquals(100.0, beta.min)
        assertEquals(10_100.0, beta.max)
    }

    @Test
    fun valuesSnapToTheGridAndTheEndsAreExact() {
        val current = track(0.0, 5000.0, 1.0, 60.0, 60.0)
        assertEquals(60.5, current.at(0.3013f))
        assertEquals(0.0, current.at(0f))
        assertEquals(200.0, current.at(1f))
        assertEquals(60.5, current.nudge(60.26, up = true))
        assertEquals(60.0, current.nudge(60.26, up = false))
        assertEquals(61.0, current.nudge(60.5, up = true))
        // Nudges may leave the window, not the limits.
        assertEquals(200.5, current.nudge(200.0, up = true))
        assertEquals(0.0, current.nudge(0.0, up = false))
        assertEquals(0.3, NumberTrack(0.0, 1.0, 0.1, 0.0, 1.0).snap(0.1 * 3))
    }

    @Test
    fun niceCeilRoundsUpToRoundNumbers() {
        assertEquals(200.0, NumberTracks.niceCeil(150.0))
        assertEquals(10_000.0, NumberTracks.niceCeil(8450.0))
        assertEquals(2.5, NumberTracks.niceCeil(2.5))
        assertEquals(0.05, NumberTracks.niceCeil(0.03075), 1e-12)
    }

    @Test
    fun percentagesMoveInWholePercent() {
        // l_erpm_start: 0..1 edited as a percentage.
        val erpmStart = ParamDefinition(
            "l_erpm_start", ParamType.DOUBLE, ParamValue.DoubleValue(0.8),
            minDouble = 0.0, maxDouble = 1.0, stepDouble = 0.01, editAsPercentage = true,
        )
        val track = erpmStart.track(ParamValue.DoubleValue(0.8), null)
        assertEquals(0.0, track.min)
        assertEquals(100.0, track.max)
        assertEquals(1.0, track.step)
        assertEquals(ParamValue.DoubleValue(0.81), erpmStart.fromChart(0.8137, track))
    }

    @Test
    fun chartDragsOnIntegersRound() {
        val start = ParamDefinition("l_temp_fet_start", ParamType.INT, ParamValue.IntValue(85), minInt = 0, maxInt = 190)
        val track = start.track(ParamValue.IntValue(85), null)
        assertEquals(ParamValue.IntValue(85), start.fromChart(85.4, track))
        assertEquals(ParamValue.IntValue(190), start.fromChart(400.0, track))
    }
}
