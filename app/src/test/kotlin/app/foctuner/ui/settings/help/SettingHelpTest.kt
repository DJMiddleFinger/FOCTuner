package app.foctuner.ui.settings.help

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.ConfigKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingHelpTest {
    @Test
    fun everyHelpedSettingExistsInTheNewestDefinitions() {
        val known = ConfigKind.entries.flatMap { BundledDefinitions.load("7.01", it).params.keys }.toSet()
        assertEquals(emptyList(), SettingHelp.names.filter { it !in known })
    }
}
