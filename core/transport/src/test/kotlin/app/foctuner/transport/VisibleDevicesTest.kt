package app.foctuner.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleDevicesTest {
    private fun ble(id: String, rssi: Int) = LinkDevice(LinkKind.BLE, id, "esc $id", rssi)

    private fun TestScope.visible(vararg timeline: Pair<Long, LinkDevice>) =
        flow {
            var at = 0L
            for ((time, device) in timeline) {
                delay(time - at)
                at = time
                emit(device)
            }
            delay(60_000)
        }.visibleDevices(ttlMillis = 6_000, now = { testScheduler.currentTime })

    @Test
    fun `repeated sightings update one entry in place`() = runTest {
        val lists = visible(0L to ble("A", -70), 100L to ble("B", -60), 200L to ble("A", -50))
            .take(4).toList()

        assertEquals(
            listOf(emptyList(), listOf(ble("A", -70)), listOf(ble("A", -70), ble("B", -60)), listOf(ble("A", -50), ble("B", -60))),
            lists,
        )
    }

    @Test
    fun `devices that stop advertising disappear and the others stay`() = runTest {
        val flow = visible(0L to ble("A", -70), 0L to ble("B", -60), 4_000L to ble("B", -61))

        val last = flow.first { it.size == 1 && it.single().id == "B" && it.single().rssi == -61 }
        assertEquals(listOf(ble("B", -61)), last)
        assertEquals(emptyList(), flow.first { it.isEmpty() && testScheduler.currentTime > 4_000 })
    }
}
