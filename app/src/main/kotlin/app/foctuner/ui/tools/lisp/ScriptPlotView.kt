package app.foctuner.ui.tools.lisp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.vesc.PlotEvent

@Immutable
class PlotSeries(val name: String, val xs: FloatArray, val ys: FloatArray)

@Immutable
class ScriptPlot(val xLabel: String, val yLabel: String, val series: List<PlotSeries>)

/**
 * Collects [PlotEvent]s the way VESC Tool's experiment plot does: init starts over, points go to the selected
 * graph (created if it doesn't exist yet), and each graph keeps its last [history] points.
 */
class PlotBuffer(private val history: Int = 2000) {
    private class Graph(var name: String) {
        val xs = ArrayDeque<Float>()
        val ys = ArrayDeque<Float>()
    }

    private var xLabel = ""
    private var yLabel = ""
    private val graphs = ArrayList<Graph>()
    private var current = 0

    fun apply(event: PlotEvent) {
        when (event) {
            is PlotEvent.Init -> {
                xLabel = event.xLabel
                yLabel = event.yLabel
                graphs.clear()
                current = 0
            }
            is PlotEvent.AddGraph -> graphs += Graph(event.name)
            is PlotEvent.SelectGraph -> current = event.index.coerceAtLeast(0)
            is PlotEvent.Point -> {
                while (graphs.size <= current) graphs += Graph("")
                val graph = graphs[current]
                graph.xs.addLast(event.x)
                graph.ys.addLast(event.y)
                while (graph.xs.size > history) {
                    graph.xs.removeFirst()
                    graph.ys.removeFirst()
                }
            }
        }
    }

    fun clear() {
        graphs.clear()
        current = 0
    }

    fun snapshot() = ScriptPlot(xLabel, yLabel, graphs.map { PlotSeries(it.name, it.xs.toFloatArray(), it.ys.toFloatArray()) })
}

/** The graphs with their ranges and a legend; line styles tell graphs apart, the first one in the accent. */
@Composable
fun ScriptPlotChart(plot: ScriptPlot, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    val points = plot.series.filter { it.xs.isNotEmpty() }
    val xMin = points.minOfOrNull { it.xs.min() } ?: 0f
    val xMax = points.maxOfOrNull { it.xs.max() } ?: 1f
    val yMin = points.minOfOrNull { it.ys.min() } ?: 0f
    val yMax = points.maxOfOrNull { it.ys.max() } ?: 1f
    val styles = listOf(colors.accent to null, colors.text to null, colors.textSecondary to floatArrayOf(12f, 8f), colors.textTertiary to floatArrayOf(3f, 6f))
    Column(modifier.padding(horizontal = Spacing.x2)) {
        Text("${plot.yLabel}  ${yMax.fixed(2)}", color = colors.textTertiary, style = FocTheme.type.caption)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp),
        ) {
            drawRect(colors.hairline, style = Stroke(1f))
            val xSpan = (xMax - xMin).takeIf { it > 0f } ?: 1f
            val ySpan = (yMax - yMin).takeIf { it > 0f } ?: 1f
            if (yMin < 0f && yMax > 0f) {
                val zero = size.height * (yMax / ySpan)
                drawLine(colors.hairline, Offset(0f, zero), Offset(size.width, zero))
            }
            plot.series.forEachIndexed { index, series ->
                if (series.xs.size < 2) return@forEachIndexed
                val path = Path()
                for (i in series.xs.indices) {
                    val x = (series.xs[i] - xMin) / xSpan * size.width
                    val y = size.height - (series.ys[i] - yMin) / ySpan * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val (color, dash) = styles[index % styles.size]
                drawPath(path, color, style = Stroke(1.5.dp.toPx(), pathEffect = dash?.let { PathEffect.dashPathEffect(it) }))
            }
        }
        Row {
            Text(yMin.fixed(2), Modifier.weight(1f), color = colors.textTertiary, style = FocTheme.type.caption)
            Text("${plot.xLabel}  ${xMin.fixed(2)} … ${xMax.fixed(2)}", color = colors.textTertiary, style = FocTheme.type.caption)
        }
        plot.series.forEachIndexed { index, series ->
            val (color, dash) = styles[index % styles.size]
            Row(Modifier.padding(top = Spacing.half), verticalAlignment = Alignment.CenterVertically) {
                LegendLine(color, dash)
                Text(series.name.ifEmpty { "${index + 1}" }, Modifier.padding(start = Spacing.x1), color = colors.text, style = FocTheme.type.caption)
            }
        }
    }
}

@Composable
private fun LegendLine(color: Color, dash: FloatArray?) {
    Canvas(
        Modifier
            .width(24.dp)
            .height(8.dp),
    ) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx(), pathEffect = dash?.let { PathEffect.dashPathEffect(it) })
    }
}
