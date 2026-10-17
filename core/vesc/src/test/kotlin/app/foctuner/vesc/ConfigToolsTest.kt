package app.foctuner.vesc

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigBackupStore
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamValue
import app.foctuner.protocol.CommPacketId.COMM_SET_APPCONF_NO_STORE
import app.foctuner.protocol.CommPacketId.COMM_SET_MCCONF
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigToolsTest {
    private val motor = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.MCCONF))
    private val app = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.APPCONF))

    @Test
    fun configWritesAreSentTwiceAndBothAcknowledgementsAreTaken() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)
        client.writeConfig(ConfigKind.MCCONF, motor.with("l_current_max", 50.0))
        client.writeConfig(ConfigKind.MCCONF, motor.with("l_current_max", 51.0))
        client.can(1).writeConfig(ConfigKind.MCCONF, motor.with("l_current_max", 52.0))

        assertEquals(4, link.sent(COMM_SET_MCCONF.id).size)
        assertEquals(51.0, client.readConfig(ConfigKind.MCCONF, motor).double("l_current_max"), 1e-4)
        assertEquals(52.0, client.can(1).readConfig(ConfigKind.MCCONF, motor).double("l_current_max"), 1e-4)
    }

    @Test
    fun aCheckedWriteListsWhatTheFirmwareClamped() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val wanted = motor.with("l_current_max", 200.0).with("l_abs_current_max", 150.0).with("l_battery_cut_start", 40.0)
            .with("foc_offsets_current__0", 1234.0)
        val result = client.writeConfigChecked(ConfigKind.MCCONF, wanted)

        assertEquals(listOf("l_current_max"), result.notApplied, "offsets are kept by the firmware on purpose")
        assertEquals(120.0, result.applied.double("l_current_max"), 1e-4)
        assertEquals(40.0, result.applied.double("l_battery_cut_start"), 1e-4)
        assertEquals(motor.double("foc_offsets_current__0"), result.applied.double("foc_offsets_current__0"), 1e-3)
    }

    @Test
    fun appConfigCanBeAppliedWithoutStoringIt() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)
        client.applyAppConfig(app.with("timeout_msec", 900L))
        assertEquals(2, link.sent(COMM_SET_APPCONF_NO_STORE.id).size)
        assertEquals(900L, client.readConfig(ConfigKind.APPCONF, app).int("timeout_msec"))
    }

    @Test
    fun aProfileSetsSpeedLimitsFromTheWheelAndReachesTheOtherMotor() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val mc = client.readConfig(ConfigKind.MCCONF, motor)
        val profile = ConfigProfile("Eco", currentMinScale = 0.5, currentMaxScale = 0.6, speedMin = -2.0, speedMax = 7.0, wattMin = -800.0, wattMax = 900.0)
        client.applyProfile(profile, store = false)

        val after = client.readConfig(ConfigKind.MCCONF, motor)
        assertEquals(7.0 * mc.erpmPerMeterPerSecond(), after.double("l_max_erpm"), 1.0)
        assertEquals(0.6, after.double("l_current_max_scale"), 1e-4)
        assertTrue(profile.isInUse(after))
        assertTrue(!ConfigProfile("Other").isInUse(after))
        assertTrue(profile.isInUse(client.can(1).readConfig(ConfigKind.MCCONF, motor)), "forwarded to the CAN bus")
        assertEquals(after.double("l_max_erpm"), mc.withProfile(profile).double("l_max_erpm"), 1.0)
        assertEquals(profile.copy(name = "Read"), ConfigProfile.fromConfig(mc.withProfile(profile), "Read").let {
            it.copy(speedMin = Math.round(it.speedMin * 1e6) / 1e6, speedMax = Math.round(it.speedMax * 1e6) / 1e6)
        })
    }

    @Test
    fun backupsCoverEveryMotorControllerAndRestoreThem() = runTest {
        val dir = Files.createTempDirectory("backups").toFile()
        try {
            val store = ConfigBackupStore(dir)
            val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
            val targets = client.configTargets()
            assertEquals(listOf(null, 1), targets.map { it.canId })
            assertTrue(targets[0].uuid != targets[1].uuid)

            for (t in targets) {
                client.controller(t).backUpConfigs(store, t.firmware, name = "Stock") { kind, d ->
                    if (kind == ConfigKind.MCCONF) Config.defaults(d).with("motor_brand", "Hub ${t.canId}") else Config.defaults(d)
                }
            }
            client.can(1).writeConfig(ConfigKind.MCCONF, motor.with("l_current_max", 11.0))
            client.can(1).writeConfig(ConfigKind.APPCONF, app.with("controller_id", 1L).with("timeout_msec", 777L))

            val backup = store.list().single { it.uuid == targets[1].uuid }
            assertEquals("Stock", backup.name)
            assertEquals("Hub 1", store.load(backup, ConfigKind.MCCONF, motor)?.config?.text("motor_brand"))
            val results = client.can(1).restoreConfigs(store, backup, targets[1].firmware)

            assertEquals(setOf(ConfigKind.MCCONF, ConfigKind.APPCONF), results.keys)
            assertTrue(results.values.all { it.notApplied.isEmpty() })
            assertEquals(motor.double("l_current_max"), client.can(1).readConfig(ConfigKind.MCCONF, motor).double("l_current_max"), 1e-4)
            assertEquals(app.int("timeout_msec"), client.can(1).readConfig(ConfigKind.APPCONF, app).int("timeout_msec"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun multiSettingWritesTheChosenParametersToEachController() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val values = mapOf("l_current_max" to ParamValue.DoubleValue(70.0), "not_in_this_firmware" to ParamValue.DoubleValue(1.0))
        for (t in client.configTargets()) {
            val result = client.controller(t).writeMotorParams(t.firmware, values)
            assertEquals(listOf("not_in_this_firmware"), result.skipped)
            assertEquals(70.0, result.write.applied.double("l_current_max"), 1e-4)
        }
        assertEquals(70.0, client.can(1).readConfig(ConfigKind.MCCONF, motor).double("l_current_max"), 1e-4)
        assertTrue(MultiSettingGroup.entries.flatMap { it.params }.all { it in motor.definition.params })
    }

    @Test
    fun readsWithTheNewestDefinitionForUnknownFirmware() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        assertEquals(motor.definition.signature, client.readMatchingConfig(ConfigKind.MCCONF, "9.99").definition.signature)
        assertEquals(motor.definition.signature, client.readMatchingConfig(ConfigKind.MCCONF, "6.05").definition.signature, "found by signature")
    }

    @Test
    fun profilesSurviveTheirStoredForm() {
        val profiles = listOf(ConfigProfile("A b=c"), ConfigProfile("Fast", 0.8, 1.0, -3.0, 12.5, 0.01, 0.9, -ConfigProfile.NO_POWER_LIMIT, ConfigProfile.NO_POWER_LIMIT))
        assertEquals(profiles, profilesFrom(profiles.toProperties()))
        assertTrue(!profiles[1].limitsPower)
    }
}
