package app.foctuner.vesc

import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ScriptPlotTest {
    @Test
    fun receivesWhatAScriptPlots() = runTest {
        val client = VescClient(SimulatedVesc(testTimeSource), backgroundScope)
        val events = Channel<PlotEvent>(Channel.UNLIMITED)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { client.plotEvents.collect { events.send(it) } }

        val script = """
            (plot-init "Time" "Current")
            (plot-add-graph "Motor")
            (plot-add-graph "Input")
            (plot-set-graph 1)
            (plot-send-points 0.5 -12.25)
        """.trimIndent()
        client.streamLisp(script.encodeToByteArray(), LispStreamMode.ResetAndLoad)

        val received = withTimeout(5.seconds) { List(5) { events.receive() } }
        assertEquals(
            listOf(
                PlotEvent.Init("Time", "Current"),
                PlotEvent.AddGraph("Motor"),
                PlotEvent.AddGraph("Input"),
                PlotEvent.SelectGraph(1),
                PlotEvent.Point(0.5f, -12.25f),
            ),
            received,
        )
    }
}
