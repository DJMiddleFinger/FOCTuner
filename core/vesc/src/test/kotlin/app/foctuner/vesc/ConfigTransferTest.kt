package app.foctuner.vesc

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamDefinition
import app.foctuner.config.ParamType
import app.foctuner.config.ParamValue
import app.foctuner.config.SignatureMismatchException
import app.foctuner.config.WireType
import app.foctuner.config.diff
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigTransferTest {
    private val motor = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.MCCONF))
    private val app = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.APPCONF))

    @Test
    fun readsWritesAndReadsBack() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        assertEquals(emptyList(), motor.diff(client.readConfig(ConfigKind.MCCONF, motor)))

        val edited = motor.with("l_current_max", 42.0).with("motor_brand", "Flipsky")
        client.writeConfig(ConfigKind.MCCONF, edited)

        val back = client.readConfig(ConfigKind.MCCONF, motor)
        assertEquals(42.0, back.double("l_current_max"), 1e-4)
        assertEquals(motor.text("motor_brand"), back.text("motor_brand"), "not stored on the controller")
        assertEquals("Flipsky", client.readConfig(ConfigKind.MCCONF, edited).text("motor_brand"), "kept from the base")
        assertEquals(motor.double("l_current_max"), client.readDefaultConfig(ConfigKind.MCCONF, motor).double("l_current_max"), 1e-4)
    }

    @Test
    fun eachMotorOfADualBoardHasItsOwnConfig() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        assertEquals(0L, client.readConfig(ConfigKind.APPCONF, app).int("controller_id"))
        assertEquals(1L, client.can(1).readConfig(ConfigKind.APPCONF, app).int("controller_id"))

        client.can(1).writeConfig(ConfigKind.MCCONF, motor.with("l_current_max", 30.0))

        assertEquals(30.0, client.can(1).readConfig(ConfigKind.MCCONF, motor).double("l_current_max"), 1e-4)
        assertEquals(motor.double("l_current_max"), client.readConfig(ConfigKind.MCCONF, motor).double("l_current_max"), 1e-4)
    }

    @Test
    fun aConfigWithAnotherLayoutIsNeitherReadNorWritten() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val other = Config.defaults(
            ConfigDefinition(listOf(ParamDefinition("x", ParamType.INT, ParamValue.IntValue(0), WireType.UINT8)), listOf("x")),
        )
        assertFailsWith<SignatureMismatchException> { client.readConfig(ConfigKind.MCCONF, other) }

        val warning = async(start = CoroutineStart.UNDISPATCHED) { client.prints.first() }
        val started = currentTime
        assertFailsWith<VescTimeoutException> { client.writeConfig(ConfigKind.MCCONF, other) }
        assertEquals(5_000, currentTime - started)
        assertEquals("Warning: Could not set mcconf due to wrong signature", warning.await())
        assertEquals(emptyList(), motor.diff(client.readConfig(ConfigKind.MCCONF, motor)))
    }
}
