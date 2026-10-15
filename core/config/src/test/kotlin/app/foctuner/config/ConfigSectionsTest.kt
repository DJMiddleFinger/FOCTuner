package app.foctuner.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigSectionsTest {
    @Test
    fun everySettingHasASection() {
        for (kind in ConfigKind.entries) {
            val definition = BundledDefinitions.load("7.01", kind)
            val shown = definition.sections().flatMap { group -> group.subgroups.flatMap { it.paramNames } }.toSet()
            val missing = definition.params.values.filter { it.type != ParamType.UNDEFINED && it.name !in shown }
            assertEquals(emptyList(), missing.map { it.name }, "$kind")
        }
    }

    @Test
    fun keepsVescToolsSectionsAndPutsTheRestUnderOther() {
        val motor = BundledDefinitions.load("7.01", ConfigKind.MCCONF).sections()
        assertEquals(listOf("General", "BLDC", "DC", "FOC", "PID Controllers", "Additional Info", "Other"), motor.map { it.name })
        assertEquals(
            setOf("l_max_erpm_fbrake_cc", "sl_max_fullbreak_current_dir_change", "motor_description", "motor_quality_description"),
            motor.last().subgroups.single().paramNames.toSet(),
        )

        val app = BundledDefinitions.load("7.01", ConfigKind.APPCONF)
        assertEquals(app.groups, app.sections())
        assertEquals(ParamType.UNDEFINED, app.param("new_parameter").type)
    }
}
