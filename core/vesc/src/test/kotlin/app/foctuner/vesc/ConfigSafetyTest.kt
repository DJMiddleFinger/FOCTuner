package app.foctuner.vesc

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigBackupStore
import app.foctuner.config.ConfigKind
import app.foctuner.config.diff
import app.foctuner.config.restoredFrom
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigSafetyTest {
    private val motor = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.MCCONF))

    private fun withStore(test: suspend TestScope.(ConfigBackupStore) -> Unit) = runTest {
        val dir = Files.createTempDirectory("safety").toFile()
        try {
            test(ConfigBackupStore(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aWriteBacksUpFirstAndKeepsChangesMadeElsewhere() = withStore { store ->
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val firmware = client.firmwareInfo()
        val stored = client.readConfig(ConfigKind.MCCONF, motor)
        // Another app lowers the brake current after we read.
        client.writeConfig(ConfigKind.MCCONF, stored.with("l_current_min", -40.0))

        val done = client.writeWithBackup(mapOf(ConfigKind.MCCONF to ConfigEdit(stored, stored.with("l_current_max", 80.0))), store, firmware)

        val applied = done.results.getValue(ConfigKind.MCCONF).applied
        assertEquals(80.0, applied.double("l_current_max"), 1e-4)
        assertEquals(-40.0, applied.double("l_current_min"), 1e-4)
        assertEquals(listOf(done.backup), store.list())
        assertEquals(true, done.backup.automatic)
        assertEquals(setOf(ConfigKind.MCCONF, ConfigKind.APPCONF), done.backup.kinds)
        assertEquals(stored.double("l_current_max"), store.load(done.backup, ConfigKind.MCCONF, motor)?.config?.double("l_current_max"))
    }

    @Test
    fun undoRestoresTheBackupWithoutRoundingNoise() = withStore { store ->
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val firmware = client.firmwareInfo()
        val precise = client.readConfig(ConfigKind.MCCONF, motor).with("foc_motor_flux_linkage", 0.0123456789)
        client.writeConfig(ConfigKind.MCCONF, precise)
        val stored = client.readConfig(ConfigKind.MCCONF, motor)
        val done = client.writeWithBackup(mapOf(ConfigKind.MCCONF to ConfigEdit(stored, stored.with("l_current_max", 80.0))), store, firmware)
        val now = done.results.getValue(ConfigKind.MCCONF).applied

        // The backup's XML has six significant digits; only the setting the write changed comes back as a change.
        val undo = now.restoredFrom(store.load(done.backup, ConfigKind.MCCONF, now)?.config ?: now)
        assertEquals(listOf("l_current_max"), now.diff(undo).map { it.name })
        client.writeWithBackup(mapOf(ConfigKind.MCCONF to ConfigEdit(now, undo)), store, firmware)

        val after = client.readConfig(ConfigKind.MCCONF, motor)
        assertEquals(stored.double("l_current_max"), after.double("l_current_max"), 1e-4)
        assertEquals(stored.double("foc_motor_flux_linkage"), after.double("foc_motor_flux_linkage"))
        assertEquals(2, store.list().size)
    }

    @Test
    fun onlyTheNewestAutomaticBackupsAreKept() = withStore { store ->
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val firmware = client.firmwareInfo()
        client.backUpConfigs(store, firmware, name = "Mine")
        var stored = client.readConfig(ConfigKind.MCCONF, motor)
        repeat(4) { i ->
            val done = client.writeWithBackup(mapOf(ConfigKind.MCCONF to ConfigEdit(stored, stored.with("l_current_max", 50.0 + i))), store, firmware, keep = 2)
            stored = done.results.getValue(ConfigKind.MCCONF).applied
        }
        val backups = store.list()
        assertEquals(listOf(false, true, true), backups.map { it.automatic }.sorted())
        assertEquals("Mine", backups.single { !it.automatic }.name)
    }
}
