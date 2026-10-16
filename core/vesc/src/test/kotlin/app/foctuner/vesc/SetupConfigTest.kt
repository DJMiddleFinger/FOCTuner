package app.foctuner.vesc

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SetupConfigTest {
    private val motor = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.MCCONF))

    @Test
    fun gainsFollowVescToolsFormulas() {
        val gains = focGains(resistance = 0.03f, inductance = 28e-6f, linkage = 0.0344f, timeConstantUs = 1000f)
        assertEquals(0.028f, gains.kp, 1e-6f)
        assertEquals(30f, gains.ki, 1e-4f)
        assertEquals(0.845f, gains.observerGain, 1e-3f)

        val c = motor.withMotorParameters(ResistanceInductance(0.03f, 28e-6f, 4e-6f), 0.0344f, gains, motorTemp = 31f)
        assertEquals(845_000.0, c.double("foc_observer_gain"), 500.0)
        assertEquals(31.0, c.double("foc_temp_comp_base_temp"), 1e-6)
        assertEquals(motor.double("foc_temp_comp_base_temp"), motor.withMotorParameters(ResistanceInductance(0.03f, 28e-6f, 4e-6f), 0.0344f, gains, null).double("foc_temp_comp_base_temp"))
    }

    @Test
    fun batteryCutPerCellByTypeAndCaution() {
        val liIon = motor.with("si_battery_type", 0L).with("si_battery_cells", 12L)
        assertEquals(40.8, liIon.withBatteryCut(cautious = true).double("l_battery_cut_start"), 1e-6)
        assertEquals(30.0, liIon.withBatteryCut(cautious = false).double("l_battery_cut_end"), 1e-6)
        val lfp = motor.with("si_battery_type", 1L).with("si_battery_cells", 16L)
        assertEquals(46.4, lfp.withBatteryCut(cautious = true).double("l_battery_cut_start"), 1e-6)
        val lead = motor.with("si_battery_type", 2L)
        assertSame(lead, lead.withBatteryCut(cautious = true))
    }

    @Test
    fun bldcHallTableOnlyWhenTheHallsWereRead() {
        val read = BldcParams(58f, 512f, listOf(-1, 1, 3, 2, 5, 6, 4, -1), HallSensors.Detected)
        assertEquals(3L, motor.withBldcParams(read).int("hall_table__2"))
        val failed = read.copy(halls = HallSensors.Failed, hallTable = List(8) { -1 })
        assertEquals(motor.int("hall_table__2"), motor.withBldcParams(failed).int("hall_table__2"))
        assertEquals(512.0, motor.withBldcParams(failed).double("sl_bemf_coupling_k"), 1e-3)
    }

    @Test
    fun rangeCenterIsTheRestingPositionOrTheMidpoint() {
        val r = InputRange()
        listOf(1.5f, 1.0f, 2.0f, 1.52f).forEach(r::add)
        assertEquals(1.0f, r.min)
        assertEquals(2.0f, r.max)
        assertEquals(1.52f, r.center, "resting inside the middle half")
        r.add(1.9f)
        assertEquals(1.5f, r.center, 1e-6f, "outside it: midpoint")
        r.reset()
        r.add(0.7f)
        assertEquals(0.7f, r.min)
        assertEquals(0.7f, r.max)
    }
}
