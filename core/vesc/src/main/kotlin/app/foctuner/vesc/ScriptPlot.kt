package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.CommPacketId.COMM_PLOT_ADD_GRAPH
import app.foctuner.protocol.CommPacketId.COMM_PLOT_DATA
import app.foctuner.protocol.CommPacketId.COMM_PLOT_INIT
import app.foctuner.protocol.CommPacketId.COMM_PLOT_SET_GRAPH
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * What a script sends to draw a plot in the tool (LispBM `plot-init`, `plot-add-graph`, `plot-set-graph` and
 * `plot-send-points`, which the firmware sends as COMM_PLOT_*). VESC Tool shows them as its Experiment Plot.
 */
sealed interface PlotEvent {
    /** Starts a new plot: earlier graphs are dropped. */
    data class Init(val xLabel: String, val yLabel: String) : PlotEvent

    /** Adds a graph; graphs are numbered from 0 in the order they are added. */
    data class AddGraph(val name: String) : PlotEvent

    /** Points that follow go to graph [index]. */
    data class SelectGraph(val index: Int) : PlotEvent

    data class Point(val x: Float, val y: Float) : PlotEvent
}

/** Plot commands as they arrive. Like prints they carry no sender, so with CAN they can come from any controller. */
val VescController.plotEvents: Flow<PlotEvent>
    get() = session.incoming.mapNotNull { packet ->
        val r = ByteReader(packet, 1)
        try {
            when (packet[0].toInt() and 0xFF) {
                COMM_PLOT_INIT.id -> PlotEvent.Init(r.cString(), r.cString())
                COMM_PLOT_ADD_GRAPH.id -> PlotEvent.AddGraph(r.cString())
                COMM_PLOT_SET_GRAPH.id -> PlotEvent.SelectGraph(r.i8())
                COMM_PLOT_DATA.id -> PlotEvent.Point(r.f32Auto(), r.f32Auto())
                else -> null
            }
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }
