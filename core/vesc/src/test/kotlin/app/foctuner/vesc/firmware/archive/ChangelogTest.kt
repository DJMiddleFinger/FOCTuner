package app.foctuner.vesc.firmware.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangelogTest {
    private val sections = Changelog.parse(
        checkNotNull(ChangelogTest::class.java.getResourceAsStream("CHANGELOG.md")).use { it.readBytes().toString(Charsets.UTF_8) },
    )

    @Test
    fun readsTheRealChangelog() {
        assertEquals(listOf("7.01", "7.00", "6.06.6", "6.06", "6.05"), sections.map { it.version })
        assertNull(sections.first().date, "TBD before a release")
        assertEquals("2026-05-15", sections[1].date)
        assertTrue(sections[1].notes.startsWith("* Configurable HFI reset ERPM."))
        assertTrue("    * Performance improvements and bug fixes" in sections[1].notes, "nested bullets keep their indent")
    }

    @Test
    fun showsWhatChangedBetweenTheInstalledAndTheNewVersion() {
        assertEquals(listOf("7.01", "7.00", "6.06.6"), Changelog.between(sections, "6.06", "7.01").map { it.version })
        assertEquals(listOf("6.06"), Changelog.between(sections, "6.05", "6.06").map { it.version })
        assertTrue(Changelog.between(sections, "7.01", "6.06").isEmpty(), "a downgrade has nothing new")
        assertEquals(5, Changelog.between(sections, null, "7.01").size)
    }

    @Test
    fun comparesDottedVersionsPartByPart() {
        assertTrue(Changelog.compareVersions("6.06.6", "6.06") > 0)
        assertTrue(Changelog.compareVersions("7.00", "6.06.6") > 0)
        assertTrue(Changelog.compareVersions("3.9", "3.10") < 0)
        assertEquals(0, Changelog.compareVersions("7.01", "7.01.0"))
    }
}
