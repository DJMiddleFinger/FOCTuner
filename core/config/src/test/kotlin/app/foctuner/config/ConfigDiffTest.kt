package app.foctuner.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigDiffTest {
    private val mcconf = BundledDefinitions.load("7.01", ConfigKind.MCCONF)
    private val appconf = BundledDefinitions.load("7.01", ConfigKind.APPCONF)
    private val defaults = Config.defaults(mcconf)

    @Test
    fun identicalConfigsHaveNoChanges() {
        assertEquals(emptyList(), defaults.diff(defaults))
        assertEquals(emptyList(), defaults.diff(Config.defaults(mcconf)))
    }

    @Test
    fun listsChangesInDefinitionOrderWithBothValues() {
        val edited = defaults.with("motor_brand", "Acme").with("l_current_max", 80.0).with("pwm_mode", 2L)

        assertEquals(
            listOf(
                ParamChange("pwm_mode", ParamValue.IntValue(1), ParamValue.IntValue(2)),
                ParamChange("l_current_max", ParamValue.DoubleValue(60.0), ParamValue.DoubleValue(80.0)),
                ParamChange("motor_brand", ParamValue.TextValue(""), ParamValue.TextValue("Acme")),
            ),
            defaults.diff(edited),
        )
        assertEquals(listOf("pwm_mode", "l_current_max", "motor_brand"), edited.diff(defaults).map { it.name })
    }

    @Test
    fun valuesThatEncodeToTheSameBytesAreNotAChange() {
        val a = defaults.with("l_min_vin", 8.3) // double16, scale 10: 83
        assertEquals(emptyList(), a.diff(defaults.with("l_min_vin", 8.30001)))
        assertEquals(listOf("l_min_vin"), a.diff(defaults.with("l_min_vin", 8.4)).map { it.name })

        val auto = defaults.with("l_current_max", 50.0)
        assertEquals(emptyList(), auto.diff(defaults.with("l_current_max", 50.0)))
        assertEquals(listOf("l_current_max"), auto.diff(defaults.with("l_current_max", 50.001)).map { it.name })
    }

    @Test
    fun unencodableValuesFallBackToPlainComparison() {
        val a = defaults.with("foc_hfi_gain", 50.0)
        assertEquals(emptyList(), a.diff(defaults.with("foc_hfi_gain", 50.0)))
        assertEquals(listOf("foc_hfi_gain"), a.diff(defaults.with("foc_hfi_gain", 50.0001)).map { it.name })
    }

    @Test
    fun configsOfDifferentDefinitionsCannotBeCompared() {
        assertFailsWith<IllegalArgumentException> { defaults.diff(Config.defaults(appconf)) }
    }
}
