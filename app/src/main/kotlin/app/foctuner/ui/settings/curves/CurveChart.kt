package app.foctuner.ui.settings.curves

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foctuner.ui.components.fixed
import app.foctuner.ui.components.grouped
import app.foctuner.ui.theme.FocColors
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.LocalMotion
import app.foctuner.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

private const val SAMPLES = 96

/** How far from a handle a touch still picks it up: half a 48dp touch target. */
internal val HandleReach = 24.dp

/** A draggable point at ([x], [y]) in plot units that moves along [axis]. */
internal class HandlePoint(val x: Double, val y: Double, val axis: HandleAxis)

/** A handle drawn as a ring around an accent dot, larger while [active]. */
internal fun DrawScope.drawHandle(center: Offset, fill: Color, accent: Color, outline: Color, active: Boolean) {
    val radius = (if (active) 10 else 8).dp.toPx()
    drawCircle(fill, radius, center)
    drawCircle(accent, radius - 2.5.dp.toPx(), center)
    drawCircle(outline, radius, center, style = Stroke(1.dp.toPx()))
}

/** The plot's area and axes as last drawn, for turning touches into plot units. */
private class Frame {
    var area = Rect.Zero
    var xMin = 0.0
    var xMax = 1.0
    var yMin = 0.0
    var yMax = 1.0
    var handles: List<Offset> = emptyList()

    fun plotX(px: Float) = xMin + ((px - area.left) / max(area.width, 1f)).coerceIn(0f, 1f) * (xMax - xMin)

    fun plotY(py: Float) = yMin + ((area.bottom - py) / max(area.height, 1f)).coerceIn(0f, 1f) * (yMax - yMin)
}

private fun Plot.withAxes(x: PlotAxis, y: PlotAxis) = Plot(x, y, lines, zones, markers, note)

/** Height of the tick label row under the plot. */
internal val AxisLabelHeight = 20.dp

internal fun FocColors.line(tone: LineTone): Color = when (tone) {
    LineTone.Primary -> accent
    LineTone.Secondary -> textSecondary
    LineTone.Reference -> outline
}

internal fun FocColors.zoneFill(tone: ZoneTone): Color = when (tone) {
    ZoneTone.Reduced -> warningContainer
    ZoneTone.Cutoff -> faultContainer
    ZoneTone.Neutral -> surfaceHigh
}

internal fun FocColors.zoneEdge(tone: ZoneTone): Color = when (tone) {
    ZoneTone.Reduced -> warning
    ZoneTone.Cutoff -> fault
    ZoneTone.Neutral -> outline
}

/**
 * Draws [plot] on a hairline grid with its zones and markers, reference lines thin and solid, and [ghost] (the same
 * chart for the values on the controller) dashed behind it. When [plot] changes, the old curves bend into the new ones. Tapping or dragging
 * across the plot puts a cursor there and lists every line's value at that point.
 *
 * [handles] are drawn on top; dragging one reports its position along its axis to [onHandle]. The axes stay put
 * while a handle is held, so the point stays under the finger, and move to fit the new values once it's let go.
 */
@Composable
internal fun CurveChart(
    plot: Plot,
    ghost: Plot?,
    description: String,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 176.dp,
    fill: Color = FocTheme.colors.surface,
    handles: List<HandlePoint> = emptyList(),
    onHandle: ((index: Int, position: Double) -> Unit)? = null,
    onRelease: () -> Unit = {},
    actions: List<CustomAccessibilityAction> = emptyList(),
) {
    val colors = FocTheme.colors
    val scope = rememberCoroutineScope()
    val frame = remember { Frame() }
    var held by remember { mutableStateOf<Int?>(null) }
    var frozen by remember { mutableStateOf<Pair<PlotAxis, PlotAxis>?>(null) }
    val handleUpdate by rememberUpdatedState(onHandle)
    val release by rememberUpdatedState(onRelease)
    val handlePoints by rememberUpdatedState(handles)
    val motion = LocalMotion.current
    val resources = LocalResources.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val tickStyle = FocTheme.type.code.copy(fontSize = 11.sp, lineHeight = 14.sp, color = colors.textTertiary)
    val titleStyle = FocTheme.type.caption.copy(color = colors.textTertiary)
    val readoutStyle = FocTheme.type.code.copy(fontSize = 12.sp, lineHeight = 16.sp, color = colors.text)

    var from by remember { mutableStateOf(plot) }
    var to by remember { mutableStateOf(plot) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(plot) {
        if (plot === to) return@LaunchedEffect
        // Start from wherever an interrupted animation got to, so typing quickly never jumps.
        from = blend(from, to, progress.value)
        to = plot
        if (held != null) {
            // While a handle is held the curves follow the finger without easing.
            from = plot
            progress.snapTo(1f)
        } else if (motion) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(Motion.LONG, easing = Motion.emphasized))
        } else {
            progress.snapTo(1f)
        }
    }

    // Cursor position as a fraction of the plot width; it stays put while the axes move under it.
    var probe by remember { mutableStateOf<Float?>(null) }
    val bounds = remember { floatArrayOf(0f, 1f) }
    fun fraction(x: Float) = ((x - bounds[0]) / max(bounds[1] - bounds[0], 1f)).coerceIn(0f, 1f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(plotHeight + AxisLabelHeight)
            .semantics {
                contentDescription = description
                if (actions.isNotEmpty()) customActions = actions
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val f = fraction(offset.x)
                    probe = if (probe?.let { abs(it - f) < 0.06f } == true) null else f
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { probe = fraction(it.x) }) { change, _ ->
                    change.consume()
                    probe = fraction(change.position.x)
                }
            }
            // Last, so it sees a touch first and takes it when it lands on a handle.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val reach = HandleReach.toPx()
                    val hit = frame.handles.withIndex()
                        .filter { (_, at) -> hypot(at.x - down.position.x, at.y - down.position.y) <= reach }
                        .minByOrNull { (_, at) -> hypot(at.x - down.position.x, at.y - down.position.y) }
                        ?.index
                    if (hit == null || handleUpdate == null) return@awaitEachGesture
                    down.consume()
                    held = hit
                    probe = null
                    val axes = PlotAxis(frame.xMin, frame.xMax, to.x.unit, to.x.decimals, to.x.title) to
                        PlotAxis(frame.yMin, frame.yMax, to.y.unit, to.y.decimals, to.y.title)
                    frozen = axes
                    drag(down.id) { change ->
                        change.consume()
                        val axis = handlePoints.getOrNull(hit)?.axis ?: return@drag
                        val position = if (axis == HandleAxis.X) frame.plotX(change.position.x) else frame.plotY(change.position.y)
                        handleUpdate?.invoke(hit, position)
                    }
                    held = null
                    release()
                    // Let the axes move from where they were held to where the new values put them.
                    from = to.withAxes(axes.first, axes.second)
                    frozen = null
                    scope.launch {
                        if (motion) {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(Motion.LONG, easing = Motion.emphasized))
                        } else {
                            progress.snapTo(1f)
                        }
                    }
                }
            },
    ) {
        val blended = blend(from, to, progress.value)
        val p = frozen?.let { (x, y) -> blended.withAxes(x, y) } ?: blended
        val xSpan = (p.x.max - p.x.min).takeIf { it > 0 } ?: 1.0
        val ySpan = (p.y.max - p.y.min).takeIf { it > 0 } ?: 1.0
        val xUnit = p.x.unit?.let(resources::getString)
        val yUnit = p.y.unit?.let(resources::getString)
        val xTicks = niceTicks(p.x.min, p.x.max, 5)
        val yTicks = niceTicks(p.y.min, p.y.max, 4)
        val xStep = if (xTicks.size > 1) xTicks[1] - xTicks[0] else xSpan
        val yStep = if (yTicks.size > 1) yTicks[1] - yTicks[0] else ySpan

        val yLabels = yTicks.mapIndexed { i, v -> measurer.measure(tickText(v, yStep, if (i == yTicks.lastIndex) yUnit else null), tickStyle) }
        val gutter = (yLabels.maxOfOrNull { it.size.width } ?: 0) + 6.dp.toPx()
        val area = Rect(gutter, 0f, size.width, plotHeight.toPx())
        bounds[0] = area.left
        bounds[1] = area.right
        fun sx(x: Double) = area.left + ((x - p.x.min) / xSpan * area.width).toFloat()
        fun sy(y: Double) = area.bottom - ((y - p.y.min) / ySpan * area.height).toFloat()
        frame.area = area
        frame.xMin = p.x.min
        frame.xMax = p.x.max
        frame.yMin = p.y.min
        frame.yMax = p.y.max
        frame.handles = handles.map { Offset(sx(it.x).coerceIn(area.left, area.right), sy(it.y).coerceIn(area.top, area.bottom)) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))

        drawRect(fill, area.topLeft, area.size)
        clipRect(area.left, area.top, area.right, area.bottom) {
            for (zone in p.zones) {
                val left = sx(min(zone.from, zone.to)).coerceIn(area.left, area.right)
                val right = sx(max(zone.from, zone.to)).coerceIn(area.left, area.right)
                if (right - left < 0.5f) continue
                drawRect(colors.zoneFill(zone.tone), Offset(left, area.top), Size(right - left, area.height), alpha = zone.alpha)
                drawRect(colors.zoneEdge(zone.tone), Offset(left, area.top), Size(right - left, 2.dp.toPx()), alpha = zone.alpha * 0.8f)
            }
            for (v in xTicks) drawLine(colors.hairline, Offset(sx(v), area.top), Offset(sx(v), area.bottom), 1f)
            for (v in yTicks) drawLine(colors.hairline, Offset(area.left, sy(v)), Offset(area.right, sy(v)), 1f)
            if (p.y.min < 0 && p.y.max > 0) drawLine(colors.outline, Offset(area.left, sy(0.0)), Offset(area.right, sy(0.0)), 1.dp.toPx(), alpha = 0.6f)
            if (p.x.min < 0 && p.x.max > 0) drawLine(colors.outline, Offset(sx(0.0), area.top), Offset(sx(0.0), area.bottom), 1.dp.toPx(), alpha = 0.6f)
            for (marker in p.markers) {
                drawLine(colors.outline, Offset(sx(marker.x), area.top), Offset(sx(marker.x), area.bottom), 1.dp.toPx(), pathEffect = dash, alpha = marker.alpha)
            }
            ghost?.lines?.filter { it.tone != LineTone.Reference }?.forEach { line ->
                drawCurve(line, p, ::sx, ::sy, colors.textTertiary, 1.5.dp.toPx(), dash)
            }
            for (tone in listOf(LineTone.Reference, LineTone.Secondary, LineTone.Primary)) {
                for (line in p.lines) {
                    if (line.tone != tone) continue
                    val width = when (tone) {
                        LineTone.Primary -> 2.5.dp.toPx()
                        LineTone.Secondary -> 2.dp.toPx()
                        LineTone.Reference -> 1.dp.toPx()
                    }
                    drawCurve(line, p, ::sx, ::sy, colors.line(tone), width, dash = null)
                }
            }
            probe?.let { f ->
                val x = p.x.min + f * xSpan
                drawLine(colors.text, Offset(sx(x), area.top), Offset(sx(x), area.bottom), 1.dp.toPx(), alpha = 0.5f)
                for (line in p.lines) {
                    if (line.tone == LineTone.Reference || line.alpha < 0.5f) continue
                    val y = line.y(x)
                    if (!y.isFinite()) continue
                    drawCircle(fill, 5.dp.toPx(), Offset(sx(x), sy(y)))
                    drawCircle(colors.line(line.tone), 3.5.dp.toPx(), Offset(sx(x), sy(y)))
                }
            }
        }

        // Tick labels: y right-aligned in the gutter, x centered under their ticks; the outermost carry the unit.
        yTicks.forEachIndexed { i, v ->
            val layout = yLabels[i]
            val top = (sy(v) - layout.size.height / 2f).coerceIn(0f, area.bottom - layout.size.height)
            drawText(layout, topLeft = Offset(gutter - 6.dp.toPx() - layout.size.width, top))
        }
        var lastRight = -Float.MAX_VALUE
        xTicks.forEachIndexed { i, v ->
            val layout = measurer.measure(tickText(v, xStep, if (i == xTicks.lastIndex) xUnit else null), tickStyle)
            val left = (sx(v) - layout.size.width / 2f).coerceIn(area.left, size.width - layout.size.width)
            if (left > lastRight + 4.dp.toPx()) {
                drawText(layout, topLeft = Offset(left, area.bottom + 4.dp.toPx()))
                lastRight = left + layout.size.width
            }
        }

        // Axis names inside the plot, marker names along its top edge, each on a backing so lines don't cross them.
        val taken = ArrayList<Rect>()
        p.y.title?.let { title ->
            val layout = measurer.measure(resources.getString(title), titleStyle)
            taken += labelOnFill(layout, Offset(area.left + 6.dp.toPx(), area.top + 4.dp.toPx()), fill)
        }
        p.x.title?.let { title ->
            val layout = measurer.measure(resources.getString(title), titleStyle)
            labelOnFill(layout, Offset(area.right - layout.size.width - 6.dp.toPx(), area.bottom - layout.size.height - 4.dp.toPx()), fill)
        }
        for (marker in p.markers) {
            if (marker.alpha < 0.5f) continue
            val layout = measurer.measure(resources.getString(marker.label), titleStyle)
            val x = sx(marker.x)
            if (x < area.left || x > area.right) continue
            var left = x + 3.dp.toPx()
            if (left + layout.size.width > area.right) left = x - 3.dp.toPx() - layout.size.width
            var top = area.top + 4.dp.toPx()
            while (taken.any { it.overlaps(Rect(left, top, left + layout.size.width, top + layout.size.height)) } && top < area.bottom / 2) {
                top += layout.size.height + 2.dp.toPx()
            }
            taken += labelOnFill(layout, Offset(left, top), fill)
        }

        if (onHandle != null) {
            frame.handles.forEachIndexed { i, at -> drawHandle(at, fill, colors.accent, colors.outline, held == i) }
        }

        probe?.let { f -> drawReadout(p, p.x.min + f * xSpan, sx(p.x.min + f * xSpan), area, xUnit, yUnit, measurer, readoutStyle, colors, resources::getString) }
    }
}

private fun DrawScope.drawCurve(
    line: PlotLine,
    p: Plot,
    sx: (Double) -> Float,
    sy: (Double) -> Float,
    color: Color,
    width: Float,
    dash: PathEffect?,
) {
    if (line.alpha <= 0.01f) return
    val inside = line.kinks.filter { it > p.x.min && it < p.x.max }
    val xs = DoubleArray(SAMPLES + 1 + inside.size)
    for (i in 0..SAMPLES) xs[i] = p.x.min + (p.x.max - p.x.min) * i / SAMPLES
    inside.forEachIndexed { i, k -> xs[SAMPLES + 1 + i] = k }
    xs.sort()
    val path = Path()
    var started = false
    for (x in xs) {
        val y = line.y(x)
        if (!y.isFinite()) continue
        if (started) path.lineTo(sx(x), sy(y)) else path.moveTo(sx(x), sy(y))
        started = true
    }
    drawPath(path, color, alpha = line.alpha, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash))
}

/** Draws [layout] at [topLeft] on a patch of [fill]; returns the area it covers. */
private fun DrawScope.labelOnFill(layout: TextLayoutResult, topLeft: Offset, fill: Color): Rect {
    val pad = 2.dp.toPx()
    val rect = Rect(topLeft.x - pad, topLeft.y, topLeft.x + layout.size.width + pad, topLeft.y + layout.size.height)
    drawRect(fill, rect.topLeft, rect.size, alpha = 0.85f)
    drawText(layout, topLeft = topLeft)
    return rect
}

/** The cursor's readout: the x value, then each line's value with its color, in a box on the side away from the cursor. */
private fun DrawScope.drawReadout(
    p: Plot,
    x: Double,
    cursor: Float,
    area: Rect,
    xUnit: String?,
    yUnit: String?,
    measurer: TextMeasurer,
    style: TextStyle,
    colors: FocColors,
    string: (Int) -> String,
) {
    val rows = ArrayList<Pair<Color?, TextLayoutResult>>()
    rows += null to measurer.measure(valueText(x, p.x.decimals, xUnit), style)
    for (line in p.lines) {
        if (line.tone == LineTone.Reference || line.alpha < 0.5f) continue
        val y = line.y(x)
        if (!y.isFinite()) continue
        rows += colors.line(line.tone) to measurer.measure(string(line.label) + "  " + valueText(y, p.y.decimals, yUnit), style)
    }
    val swatch = 8.dp.toPx()
    val gap = 6.dp.toPx()
    val pad = 8.dp.toPx()
    val width = rows.maxOf { (color, layout) -> layout.size.width + if (color != null) swatch + gap else 0f } + 2 * pad
    val height = rows.sumOf { it.second.size.height } + 2 * pad * 0.75f
    val left = if (cursor > area.center.x) area.left + 6.dp.toPx() else area.right - width - 6.dp.toPx()
    val top = area.top + 6.dp.toPx()
    drawRoundRect(colors.background, Offset(left, top), Size(width, height), CornerRadius(4.dp.toPx()))
    drawRoundRect(colors.outline, Offset(left, top), Size(width, height), CornerRadius(4.dp.toPx()), alpha = 0.6f, style = Stroke(1.dp.toPx()))
    var y = top + pad * 0.75f
    for ((color, layout) in rows) {
        var textLeft = left + pad
        if (color != null) {
            drawRoundRect(color, Offset(textLeft, y + (layout.size.height - swatch) / 2), Size(swatch, swatch), CornerRadius(2.dp.toPx()))
            textLeft += swatch + gap
        }
        drawText(layout, topLeft = Offset(textLeft, y))
        y += layout.size.height
    }
}

private fun valueText(v: Double, decimals: Int, unit: String?): String {
    val number = if (decimals == 0 && abs(v) >= 10_000) v.toFloat().grouped() else v.fixed(decimals)
    return if (unit.isNullOrEmpty()) number else "$number $unit"
}

/** A tick value with as many decimals as [step] needs; thousands shorten to "k" once steps reach 1000. */
private fun tickText(v: Double, step: Double, unit: String?): String {
    val text = when {
        v == 0.0 -> "0"
        abs(step) >= 1000 -> (v / 1000).fixed(decimalsFor(step / 1000)) + "k"
        else -> v.fixed(decimalsFor(step))
    }
    return if (unit.isNullOrEmpty()) text else "$text $unit"
}

private fun decimalsFor(step: Double): Int {
    for (d in 0..3) {
        val scaled = abs(step) * 10.0.pow(d)
        if (abs(scaled - scaled.roundToLong()) < 1e-6) return d
    }
    return 3
}
