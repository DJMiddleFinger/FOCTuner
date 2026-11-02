package app.foctuner.ui.settings.curves

import androidx.annotation.StringRes
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** [Primary] is the value being set (accent), [Secondary] its counterpart (e.g. braking), [Reference] a thin guide. */
internal enum class LineTone { Primary, Secondary, Reference }

/** [Reduced]: the firmware is limiting (warning tint). [Cutoff]: off or a fault (fault tint). [Neutral]: a plain band. */
internal enum class ZoneTone { Reduced, Cutoff, Neutral }

/** [unit] is appended to the outermost tick label; [title] names the axis inside the plot when the unit alone is unclear. */
internal class PlotAxis(
    val min: Double,
    val max: Double,
    @param:StringRes val unit: Int?,
    val decimals: Int,
    @param:StringRes val title: Int? = null,
)

/** A curve given as a function of x, so it can be drawn at any width and blended into another one. */
internal class PlotLine(
    @param:StringRes val label: Int,
    val tone: LineTone,
    /** Where the curve has corners; sampled exactly so corners stay sharp. */
    val kinks: List<Double> = emptyList(),
    val alpha: Float = 1f,
    val y: (Double) -> Double,
)

internal class PlotZone(val from: Double, val to: Double, val tone: ZoneTone, @param:StringRes val label: Int, val alpha: Float = 1f)

/** A dashed vertical line at a setting's value, labeled at the top of the plot. */
internal class PlotMarker(val x: Double, @param:StringRes val label: Int, val alpha: Float = 1f)

internal class Plot(
    val x: PlotAxis,
    val y: PlotAxis,
    val lines: List<PlotLine>,
    val zones: List<PlotZone> = emptyList(),
    val markers: List<PlotMarker> = emptyList(),
    /** One sentence under the chart when the settings switch the feature off. */
    @param:StringRes val note: Int? = null,
)

private fun lerp(a: Double, b: Double, t: Float) = a + (b - a) * t

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * [a] when [t] is 0 and [b] when it is 1: axes slide, lines of the same tone bend into each other, zones and markers
 * move; whatever exists on only one side fades.
 */
internal fun blend(a: Plot, b: Plot, t: Float): Plot {
    if (t >= 1f) return b
    val lines = ArrayList<PlotLine>()
    for (i in 0 until maxOf(a.lines.size, b.lines.size)) {
        val from = a.lines.getOrNull(i)
        val to = b.lines.getOrNull(i)
        if (from != null && to != null && from.tone == to.tone) {
            lines += PlotLine(to.label, to.tone, from.kinks + to.kinks, lerp(from.alpha, to.alpha, t)) { x -> lerp(from.y(x), to.y(x), t) }
        } else {
            if (from != null) lines += PlotLine(from.label, from.tone, from.kinks, from.alpha * (1 - t), from.y)
            if (to != null) lines += PlotLine(to.label, to.tone, to.kinks, to.alpha * t, to.y)
        }
    }
    val zones = ArrayList<PlotZone>()
    for (i in 0 until maxOf(a.zones.size, b.zones.size)) {
        val from = a.zones.getOrNull(i)
        val to = b.zones.getOrNull(i)
        if (from != null && to != null && from.tone == to.tone) {
            zones += PlotZone(lerp(from.from, to.from, t), lerp(from.to, to.to, t), to.tone, to.label, lerp(from.alpha, to.alpha, t))
        } else {
            if (from != null) zones += PlotZone(from.from, from.to, from.tone, from.label, from.alpha * (1 - t))
            if (to != null) zones += PlotZone(to.from, to.to, to.tone, to.label, to.alpha * t)
        }
    }
    val markers = ArrayList<PlotMarker>()
    for (i in 0 until maxOf(a.markers.size, b.markers.size)) {
        val from = a.markers.getOrNull(i)
        val to = b.markers.getOrNull(i)
        if (from != null && to != null) {
            markers += PlotMarker(lerp(from.x, to.x, t), to.label, lerp(from.alpha, to.alpha, t))
        } else {
            if (from != null) markers += PlotMarker(from.x, from.label, from.alpha * (1 - t))
            if (to != null) markers += PlotMarker(to.x, to.label, to.alpha * t)
        }
    }
    return Plot(
        x = PlotAxis(lerp(a.x.min, b.x.min, t), lerp(a.x.max, b.x.max, t), b.x.unit, b.x.decimals, b.x.title),
        y = PlotAxis(lerp(a.y.min, b.y.min, t), lerp(a.y.max, b.y.max, t), b.y.unit, b.y.decimals, b.y.title),
        lines = lines,
        zones = zones,
        markers = markers,
        note = b.note,
    )
}

/** Round tick values (steps of 1, 2, 2.5 or 5 × 10ⁿ) inside [min]..[max], about [target] of them. */
internal fun niceTicks(min: Double, max: Double, target: Int): List<Double> {
    if (!(max > min) || !min.isFinite() || !max.isFinite()) return emptyList()
    val step = niceStep((max - min) / target)
    val first = ceil(min / step - 1e-9) * step
    val ticks = ArrayList<Double>()
    var v = first
    while (v <= max + step * 1e-9 && ticks.size < 50) {
        ticks += if (abs(v) < step * 1e-9) 0.0 else v
        v += step
    }
    return ticks
}

internal fun niceStep(rough: Double): Double {
    if (rough <= 0.0 || !rough.isFinite()) return 1.0
    val magnitude = 10.0.pow(floor(log10(rough)))
    val fraction = rough / magnitude
    val nice = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 2.5 -> 2.5
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}
