package app.foctuner.ui.tools.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class StartupTuneStateTest {
    private val typed = StartupTuneState(current = "12,5", target = "4800", timeLimit = "2.5")

    @Test
    fun typedFieldsBecomeTheTest() {
        val settings = assertNotNull(typed.settings)
        assertEquals(12.5, settings.current)
        assertEquals(4800.0, settings.targetErpm)
        assertEquals(2500.milliseconds, settings.timeLimit)
        assertEquals(3, settings.trialsPerCandidate)
        assertEquals(3, settings.verifyPairs)
        assertEquals(4, typed.copy(thorough = true).settings!!.verifyPairs)
    }

    @Test
    fun unusableFieldsBlockTheStart() {
        assertNull(typed.copy(current = "").settings)
        assertNull(typed.copy(current = "-3").settings)
        assertNull(typed.copy(target = "40000").settings, "the probe's samples hold ±32767 ERPM")
        assertNull(typed.copy(timeLimit = "0.2").settings)
        assertNull(typed.copy(timeLimit = "abc").settings)
    }
}
