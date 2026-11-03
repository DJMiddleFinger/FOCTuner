package app.foctuner.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RangeEstimateTest {
    @Test
    fun arrivalFromHistory() {
        val wh = RangeEstimate.whPerKm(Lifetime(distance = 100_000.0, whUsed = 1_700.0, whRegen = 200.0))!!
        assertEquals(15.0, wh, 1e-9)
        // 500 Wh left at 50 % -> 1000 Wh pack; 10 km at 15 Wh/km uses 150 Wh -> 35 %.
        assertEquals(35.0, RangeEstimate.arrivalPercent(500.0, 0.5, 10.0, wh)!!, 1e-9)
        assertEquals(-25.0, RangeEstimate.arrivalPercent(500.0, 0.5, 50.0, wh)!!, 1e-9)
        assertNull(RangeEstimate.whPerKm(Lifetime(distance = 1_000.0, whUsed = 20.0)))
        assertNull(RangeEstimate.arrivalPercent(0.0, 0.0, 1.0, 15.0))
    }

    @Test
    fun hills() {
        // 100 kg up 100 m at 80 %: 100 * 9.81 * 125 / 3600 = 34.06 Wh; 30 % back coming down 100 m.
        assertEquals(34.06, RangeEstimate.hillWh(100.0, 100.0, 0.0), 0.01)
        assertEquals(34.06 - 8.18, RangeEstimate.hillWh(100.0, 100.0, 100.0), 0.01)
        // 1000 Wh pack at 50 %, 10 km at 15 Wh/km plus 50 Wh of climbing -> 30 %.
        assertEquals(30.0, RangeEstimate.arrivalPercent(500.0, 0.5, 10.0, 15.0, extraWh = 50.0)!!, 1e-9)
    }

    @Test
    fun climbIgnoresNoise() {
        val (up, down) = RangeEstimate.climbAndDescent(listOf(10.0, 11.0, 10.5, 15.0, 30.0, 29.0, 20.0, 21.0))
        assertEquals(20.0, up, 1e-9)
        assertEquals(10.0, down, 1e-9)
        assertEquals(0.0 to 0.0, RangeEstimate.climbAndDescent(emptyList()))
    }

    @Test
    fun samplesSpreadAlongTheLine() {
        // About 11.1 km due north in two legs.
        val line = listOf(0.0 to 0.0, 0.05 to 0.0, 0.1 to 0.0)
        val s = RangeEstimate.samples(line, maxPoints = 11)
        assertEquals(11, s.size)
        assertEquals(line.first(), s.first())
        assertEquals(line.last(), s.last())
        s.zipWithNext { a, b -> assertEquals(0.01, b.first - a.first, 1e-6) }
        // Short routes keep at least 30 m between points.
        assertEquals(5, RangeEstimate.samples(listOf(0.0 to 0.0, 0.001 to 0.0), maxPoints = 100).size)
    }
}
