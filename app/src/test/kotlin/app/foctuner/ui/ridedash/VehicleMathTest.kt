package app.foctuner.ui.ridedash

import app.foctuner.vesc.ConfigProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleMathTest {
    // Defaults: 60 A motor, 30 A battery, 10 A regen, 0.95 duty.
    private val v = VehicleSettings()
    private val mode = DriveMode(name = "T", color = 0, powerPct = 50, regenPct = 40)

    @Test
    fun dutyCapShortensTheCurve() {
        val full = VehicleMath.powerCurve(mode, v).last().kmh
        assertTrue(VehicleMath.powerCurve(mode.copy(maxDutyPct = 50), v).last().kmh < full)
    }

    @Test
    fun profilesBecomeModes() {
        val m = ConfigProfile("Eco", currentMinScale = 0.5, currentMaxScale = 0.4, speedMax = 25 / 3.6, wattMax = 1500.0).toDriveMode(1, v)
        assertEquals("Eco", m.id)
        assertEquals(40, m.powerPct)
        assertEquals(50, m.regenPct)
        assertEquals(25.0, m.topSpeedKmh!!, 1e-9)
        assertEquals(1.5, m.powerCapKw!!, 1e-9)
        assertEquals(Defaults.modeColors[1], m.color)
        // VESC Tool's "no limit" profile: speed beyond max ERPM and power past 1.4 MW map to none.
        val open = ConfigProfile("Max", speedMax = 1000.0, wattMax = 1_500_000.0).toDriveMode(0, v)
        assertNull(open.topSpeedKmh)
        assertNull(open.powerCapKw)
    }
}
