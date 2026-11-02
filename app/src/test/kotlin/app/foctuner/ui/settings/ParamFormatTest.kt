package app.foctuner.ui.settings

import app.foctuner.config.ParamDefinition
import app.foctuner.config.ParamType
import app.foctuner.config.ParamValue
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ParamFormatTest {
    private val saved = Locale.getDefault()

    @BeforeTest
    fun useEnglishNumbers() = Locale.setDefault(Locale.US)

    @AfterTest
    fun restoreLocale() = Locale.setDefault(saved)

    private fun double(value: Double, min: Double, max: Double, scale: Double = 1.0, percent: Boolean = false, suffix: String = "", decimals: Int = 2) =
        ParamDefinition(
            "p", ParamType.DOUBLE, ParamValue.DoubleValue(value),
            minDouble = min, maxDouble = max, editorScale = scale, editAsPercentage = percent, suffix = suffix, decimals = decimals,
        )

    @Test
    fun percentagesIgnoreTheEditorScale() {
        // l_temp_accel_dec: editorScale 100, edited as a percentage of 0..1.
        val accelDec = double(0.15, 0.0, 1.0, scale = 100.0, percent = true, suffix = " %", decimals = 0)
        assertEquals("15 %", accelDec.display(ParamValue.DoubleValue(0.15), ""))
        assertEquals("15", accelDec.editText(ParamValue.DoubleValue(0.15)))
        assertEquals(ParamValue.DoubleValue(0.15), accelDec.parse("15"))
    }

    @Test
    fun percentagesAreOfTheLargerLimitInWholePercent() {
        // throttle_exp: −5..5, so −0.5 is −10 %.
        val expo = double(0.0, -5.0, 5.0, percent = true)
        assertEquals("−10 %", expo.display(ParamValue.DoubleValue(-0.5), ""))
        assertEquals(ParamValue.DoubleValue(-0.5), expo.parse("-10"))
        assertEquals("−100 %" to "100 %", expo.range())
        // l_erpm_start keeps no decimals even though its editor would show two.
        assertEquals("80 %", double(0.8, 0.0, 1.0, percent = true).display(ParamValue.DoubleValue(0.8), ""))
    }

    @Test
    fun otherDoublesUseTheEditorScaleAndDecimals() {
        val maxDuty = double(0.95, 0.0, 1.0, scale = 100.0, suffix = " %", decimals = 1)
        assertEquals("95.0 %", maxDuty.display(ParamValue.DoubleValue(0.95), ""))
        assertEquals(ParamValue.DoubleValue(0.5), maxDuty.parse("50"))
    }
}
