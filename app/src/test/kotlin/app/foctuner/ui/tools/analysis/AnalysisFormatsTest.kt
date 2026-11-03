package app.foctuner.ui.tools.analysis

import app.foctuner.vesc.AdcSample
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalysisFormatsTest {
    @Test
    fun parsesTypedCanFrames() {
        val f = parseFrame("0x123", "01 ff,10", extended = false)!!
        assertEquals(0x123L, f.id)
        assertContentEquals(byteArrayOf(1, -1, 16), f.data)
        assertEquals(0L, parseFrame("0", "", extended = true)!!.id)
        assertNull(parseFrame("800", "", extended = false))
        assertNull(parseFrame("12g", "", extended = false))
        assertNull(parseFrame("1", "100", extended = false))
        assertNull(parseFrame("1", "1 2 3 4 5 6 7 8 9", extended = false))
    }

    @Test
    fun samplesCsvHasTimeFromTheSwitchingFrequency() {
        val s = AdcSample(3, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 30_000f, 0, 1)
        val csv = samplesCsv(listOf(s), decimation = 10).lines()
        assertEquals(13, csv[0].split(';').size)
        assertEquals("1.0", csv[1].split(';')[1])
    }
}
