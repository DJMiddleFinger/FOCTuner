package app.foctuner.vesc

import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefloatTest {
    @Test
    fun readsInfoAndRealtimeDataAndLocks() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val info = assertNotNull(client.refloatInfo())
        assertEquals("Refloat", info.name)
        assertEquals("1.1.0-demo", info.version)

        val rt = assertNotNull(client.refloatRealtime())
        assertEquals(Refloat.State.FaultSwitchFull, rt.state)
        assertEquals(Refloat.Footpad.None, rt.footpad)
        assertEquals(2, rt.tiltback)
        assertTrue(rt.pitch in 5f..7f)
        assertEquals(0.4f, rt.adcLeft, 1e-6f)
        assertFalse(rt.handtest)

        client.refloatHandtest(true)
        assertTrue(assertNotNull(client.refloatRealtime()).handtest)
        client.refloatLock(true)
        assertEquals(Refloat.State.Disabled, client.refloatRealtime()?.state)
    }

    @Test
    fun noRefloatMeansNull() = runTest {
        val transport = ScriptedTransport()
        val client = VescClient(transport, backgroundScope)
        assertNull(client.refloatRealtime())
        assertNull(client.refloatInfo())
    }
}
