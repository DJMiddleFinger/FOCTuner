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
}
