package app.foctuner.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigWriteCheckTest {
    private val mcconf = BundledDefinitions.load("7.01", ConfigKind.MCCONF)
    private val appconf = BundledDefinitions.load("7.01", ConfigKind.APPCONF)
    private val defaults = Config.defaults(mcconf)

    @Test
    fun listsWhatTheControllerChangedWithVescToolsTolerance() {
        val written = defaults.with("l_current_max", 200.0).with("foc_motor_r", 0.0123).with("motor_type", 2L)
        val readBack = written.with("l_current_max", 120.0).with("foc_motor_r", 0.012300001).with("l_min_vin", written.double("l_min_vin") + 0.001)
        assertEquals(listOf("l_current_max"), written.notAppliedIn(readBack))
        assertEquals(listOf("motor_type"), written.notAppliedIn(written.with("motor_type", 1L)))
    }

    @Test
    fun ignoresOffsetsUnlessTheFirmwareTakesWrittenOnes() {
        val written = defaults.with("foc_offsets_current__1", 2048.0)
        val keeps = written.with("foc_offsets_current__1", 2010.0).with("foc_offsets_cal_mode", 0L)
        assertEquals(emptyList(), written.with("foc_offsets_cal_mode", 0L).notAppliedIn(keeps))
        val takes = written.with("foc_offsets_cal_mode", 2L)
        assertEquals(listOf("foc_offsets_current__1"), takes.notAppliedIn(takes.with("foc_offsets_current__1", 2010.0)))
    }

    @Test
    fun warnsLikeVescToolOnWrite() {
        assertEquals(emptyList(), defaults.with("l_current_max", 60.0).with("l_abs_current_max", 150.0).writeWarnings())
        assertEquals(listOf(ConfigWarning.ABS_CURRENT_CLOSE), defaults.with("l_current_max", 120.0).with("l_abs_current_max", 150.0).writeWarnings())
        assertEquals(listOf(ConfigWarning.ABS_CURRENT_CLOSE), defaults.with("l_current_min", -120.0).with("l_abs_current_max", 150.0).writeWarnings())
        assertEquals(
            listOf(ConfigWarning.SLOW_ABS_CURRENT, ConfigWarning.OVERMODULATION),
            defaults.with("l_slow_abs_current", true).with("foc_overmod_factor", 1.2).writeWarnings(),
        )
        assertEquals(emptyList(), Config.defaults(appconf).writeWarnings())
    }

    @Test
    fun backupsKeepEveryParameterPerController() {
        val dir = Files.createTempDirectory("backups").toFile()
        try {
            val store = ConfigBackupStore(dir)
            val mc = defaults.with("motor_description", "Hub motor").with("l_current_max", 33.0)
            val app = Config.defaults(appconf).with("controller_id", 3L)
            val manual = store.save("abc123", mapOf(ConfigKind.MCCONF to mc, ConfigKind.APPCONF to app), "7.01", "60_MK6", name = "Before trip", created = 1_000)
            repeat(4) { store.save("ABC123", mapOf(ConfigKind.MCCONF to mc), "7.01", "60_MK6", automatic = true, created = 2_000L + it) }
            store.save("OTHER", mapOf(ConfigKind.APPCONF to app), "6.06", "75_300", created = 1_500)

            assertEquals(6, store.list().size)
            assertEquals(2_003, store.list().first().created)
            val loaded = assertNotNull(store.list().single { it.id == manual.id })
            assertEquals("ABC123", loaded.uuid)
            assertEquals("Before trip", loaded.name)
            assertEquals(setOf(ConfigKind.MCCONF, ConfigKind.APPCONF), loaded.kinds)
            val back = assertNotNull(store.load(loaded, ConfigKind.MCCONF, defaults)).config
            assertEquals("Hub motor", back.text("motor_description"))
            assertEquals(33.0, back.double("l_current_max"))
            assertNull(store.load(store.list().first { it.uuid == "OTHER" }, ConfigKind.MCCONF, defaults))

            store.pruneAutomatic("abc123", keep = 2)
            assertEquals(listOf(2_003L, 2_002L), store.list().filter { it.automatic }.map { it.created })
            store.delete(loaded)
            assertTrue(store.list().none { !it.automatic && it.uuid == "ABC123" })
            store.deleteAll()
            assertEquals(emptyList(), store.list())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theCacheKeepsTheLastConfigPerController() {
        val dir = Files.createTempDirectory("cache").toFile()
        try {
            val cache = ConfigCache(dir)
            assertNull(cache.load("u1", ConfigKind.MCCONF, defaults))
            cache.save("u1", ConfigKind.MCCONF, defaults.with("motor_brand", "Old"))
            cache.save("u1", ConfigKind.MCCONF, defaults.with("motor_brand", "New"))
            assertEquals("New", assertNotNull(cache.load("U1", ConfigKind.MCCONF, defaults)).text("motor_brand"))
            assertNull(cache.load("u1", ConfigKind.APPCONF, Config.defaults(appconf)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
