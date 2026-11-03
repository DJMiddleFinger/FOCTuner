package app.foctuner.ui.settings

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamDefinition
import app.foctuner.config.sections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    private val motor = BundledDefinitions.load("7.01", ConfigKind.MCCONF)
    private val sections = motor.sections()
    private val text = { p: ParamDefinition -> p.description.replace(Regex("<[^>]*>"), " ") }

    private fun search(query: String) = searchSettings(ConfigKind.MCCONF, sections, motor, query, text)

    @Test
    fun findsByLongNameFirstAndOpensItsPage() {
        val hits = search("cutoff")
        assertEquals("l_battery_cut_start", hits.first().name)
        val hit = hits.first()
        val page = sections[hit.group].subgroups[hit.subgroup]
        assertTrue("l_battery_cut_start" in page.paramNames)
        assertEquals("${sections[hit.group].name} › ${page.name}", hit.place)
    }

    @Test
    fun everyWordMustMatchInAnyField() {
        assertEquals(listOf("l_current_max"), search("motor current max").map { it.name }.take(1))
        assertEquals(listOf("l_abs_current_max"), search("l_abs_current").map { it.name })
        assertTrue(search("cutoff zzz").isEmpty())
        assertTrue(search("   ").isEmpty())
    }

    @Test
    fun descriptionsAreSearchedAfterNames() {
        // "spikes" is only in descriptions, Absolute Maximum Current's among them.
        val hits = search("current spikes")
        assertTrue("l_abs_current_max" in hits.map { it.name })
        assertTrue(hits.none { "spikes" in it.longName.lowercase() })
        // Long-name matches come before description matches.
        assertEquals("l_abs_current_max", search("absolute current").first().name)
        // A setting placed on two pages is listed once.
        val all = search("current")
        assertEquals(all.map { it.name }.distinct(), all.map { it.name })
    }
}
