package app.foctuner.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigRulesTest {
    private val mcconf = BundledDefinitions.load("7.01", ConfigKind.MCCONF)

    /** A 10S Li-ion board as the setup wizard leaves it: cutoff 3.4 / 3.1 V per cell. */
    private val board = Config.defaults(mcconf)
        .with("si_battery_cells", 10L).with("si_battery_type", 0L)
        .with("l_battery_cut_start", 34.0).with("l_battery_cut_end", 31.0)
        .with("l_current_max", 60.0).with("l_current_min", -60.0).with("l_abs_current_max", 150.0)
        .with("foc_motor_flux_linkage", 0.01).with("l_max_erpm", 20_000.0)

    @Test
    fun aConsistentConfigRaisesNothing() {
        assertEquals(emptyList(), board.writeWarnings())
        assertEquals(emptyList(), board.writeWarnings(HardwareLimits(120.0, 160.0, 57.0, 200_000.0, 0.95)))
        assertEquals(emptyList(), Config.defaults(BundledDefinitions.load("7.01", ConfigKind.APPCONF)).writeWarnings())
    }

    @Test
    fun batteryCutoffAgainstCellsAndChemistry() {
        assertEquals(30.0..42.0, board.batteryVoltage()?.let { it.start..it.endInclusive })
        assertEquals(listOf(ConfigWarning.BATTERY_CUTOFF_ORDER), board.with("l_battery_cut_start", 30.0).writeWarnings().take(1))
        assertEquals(listOf(ConfigWarning.BATTERY_CUTOFF_BELOW_EMPTY), board.with("l_battery_cut_end", 28.0).writeWarnings())
        assertEquals(listOf(ConfigWarning.BATTERY_CUTOFF_ABOVE_FULL), board.with("l_battery_cut_start", 43.0).writeWarnings())
        // The same voltages are fine for LiFePO4 at 2.6–3.6 V per cell on 11 cells.
        val lifepo4 = board.with("si_battery_type", 1L).with("si_battery_cells", 11L)
        assertEquals(emptyList(), lifepo4.writeWarnings())
        assertEquals(listOf(ConfigWarning.BATTERY_CUTOFF_ABOVE_FULL), lifepo4.with("si_battery_cells", 8L).writeWarnings().take(1))
        // Lead acid has no per-cell range to check against.
        assertNull(board.with("si_battery_type", 2L).batteryVoltage())
    }

    @Test
    fun fullBatteryAboveMaxInputVoltage() {
        // 14S Li-ion is 58.8 V when full.
        val fourteenS = board.with("si_battery_cells", 14L).with("l_battery_cut_start", 47.6).with("l_battery_cut_end", 43.4)
        assertEquals(emptyList(), fourteenS.with("l_max_vin", 60.0).writeWarnings())
        assertEquals(listOf(ConfigWarning.BATTERY_ABOVE_MAX_VIN), fourteenS.with("l_max_vin", 57.0).writeWarnings())
        assertEquals(listOf(ConfigWarning.BATTERY_CUTOFF_BELOW_MIN_VIN), board.with("l_min_vin", 32.0).writeWarnings())
    }

    @Test
    fun temperatureAndDutyOrder() {
        assertEquals(listOf(ConfigWarning.FET_TEMP_ORDER), board.with("l_temp_fet_start", 100L).with("l_temp_fet_end", 90L).writeWarnings())
        assertEquals(listOf(ConfigWarning.MOTOR_TEMP_ORDER), board.with("l_temp_motor_start", 120L).with("l_temp_motor_end", 120L).writeWarnings())
        assertEquals(listOf(ConfigWarning.DUTY_ORDER, ConfigWarning.ERPM_UNREACHABLE), board.with("l_max_duty", 0.004).writeWarnings())
    }

    @Test
    fun erpmLimitBeyondWhatTheVoltageReaches() {
        // 42 V × 0.95 / √3 / 0.01 Wb = 2304 rad/s, 22 000 ERPM.
        assertEquals(listOf(ConfigWarning.ERPM_UNREACHABLE), board.with("l_max_erpm", 40_000.0).writeWarnings())
        assertEquals(emptyList(), board.with("l_max_erpm", 21_000.0).writeWarnings())
        assertEquals(emptyList(), board.with("l_max_erpm", 40_000.0).with("foc_motor_flux_linkage", 0.0).writeWarnings())
        assertEquals(emptyList(), board.with("l_max_erpm", 40_000.0).with("motor_type", 0L).writeWarnings())
    }

    @Test
    fun limitsAboveTheHardware() {
        val hw = HardwareLimits(currentMax = 120.0, absCurrentMax = 160.0, inputVoltageMax = 57.0, erpmMax = 200_000.0, dutyMax = 0.95)
        assertEquals(listOf(ConfigWarning.CURRENT_ABOVE_HARDWARE), board.with("l_abs_current_max", 200.0).writeWarnings(hw))
        assertEquals(
            listOf(ConfigWarning.ABS_CURRENT_CLOSE, ConfigWarning.CURRENT_ABOVE_HARDWARE),
            board.with("l_current_min", -130.0).writeWarnings(hw),
        )
        assertEquals(listOf(ConfigWarning.VOLTAGE_ABOVE_HARDWARE), board.with("l_max_vin", 60.0).writeWarnings(hw))
        assertEquals(listOf(ConfigWarning.ERPM_ABOVE_HARDWARE), board.with("l_min_erpm", -250_000.0).writeWarnings(hw))
        assertEquals(listOf(ConfigWarning.DUTY_ABOVE_HARDWARE), board.with("l_max_duty", 0.97).writeWarnings(hw).filterNot { it == ConfigWarning.ERPM_UNREACHABLE })
        // Unknown hardware limits aren't checked.
        assertEquals(emptyList(), board.with("l_abs_current_max", 200.0).writeWarnings())
    }

    @Test
    fun rowsKnowTheirWarnings() {
        val rows = board.with("l_battery_cut_start", 30.0).writeWarnings().byParam()
        assertEquals(listOf(ConfigWarning.BATTERY_CUTOFF_ORDER), rows["l_battery_cut_end"])
        assertNull(rows["l_current_max"])
    }
}
