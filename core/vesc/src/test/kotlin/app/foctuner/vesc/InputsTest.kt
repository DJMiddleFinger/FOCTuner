package app.foctuner.vesc

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class InputsTest {
    private val app = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.APPCONF))

    @Test
    fun ppmReadsWhileItsAppRunsAndMapsToTheStoredRange() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        assertEquals(DecodedPpm(0f, 0f), client.decodedPpm(), "UART app selected: no receiver input")

        client.updateConfig(ConfigKind.APPCONF, app) { it.with("app_to_use", 4L) }
        val pulse = InputRange()
        repeat(240) {
            pulse.add(client.decodedPpm().pulseMs)
            delay(50.milliseconds)
        }
        assertEquals(1.05f, pulse.min, 1e-3f)
        assertEquals(1.95f, pulse.max, 1e-3f)
        assertEquals(1.52f, pulse.center, 1e-3f, "the stick rests at center")
        assertTrue(pulse.validPpm)

        client.updateConfig(ConfigKind.APPCONF, app) { it.with("app_to_use", 4L).withPpmMapping(pulse) }
        val values = List(240) { client.decodedPpm().value.also { delay(50.milliseconds) } }
        assertEquals(-1f, values.min(), 1e-3f)
        assertEquals(1f, values.max(), 1e-3f)
    }

    @Test
    fun adcReadsBothInputsAndMapsToTheStoredRange() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        client.updateConfig(ConfigKind.APPCONF, app) { it.with("app_to_use", 5L) }
        val voltage = InputRange()
        val voltage2 = InputRange()
        repeat(240) {
            val adc = client.decodedAdc()
            voltage.add(adc.voltage)
            voltage2.add(adc.voltage2)
            delay(50.milliseconds)
        }
        assertEquals(0.86f, voltage.min, 1e-3f)
        assertEquals(3.12f, voltage.max, 1e-3f)
        assertEquals(0.84f, voltage2.min, 1e-3f)
        assertEquals(2.95f, voltage2.max, 1e-3f)

        client.updateConfig(ConfigKind.APPCONF, app) { it.with("app_to_use", 5L).withAdcMapping(voltage, voltage2) }
        delay(7.seconds - (testTimeSource.markNow().elapsedNow()))
        val values = List(240) { client.decodedAdc().value.also { delay(50.milliseconds) } }
        assertEquals(0f, values.min(), 1e-3f)
        assertEquals(1f, values.max(), 1e-3f)
    }

    @Test
    fun nunchukStaysWithinItsRange() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val values = List(100) { client.decodedNunchuk().also { delay(100.milliseconds) } }
        assertTrue(values.all { it in -1f..1f } && values.max() > 0.9f && values.min() < -0.9f)
    }
}
