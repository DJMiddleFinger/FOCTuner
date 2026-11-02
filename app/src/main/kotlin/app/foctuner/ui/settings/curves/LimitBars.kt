package app.foctuner.ui.settings.curves

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Motion
import app.foctuner.ui.theme.motionSpec
import kotlin.math.abs
import kotlin.math.max

private val RowHeight = 36.dp

/** Motor and battery current ranges with their scales applied, and the absolute maximum. */
private class Limits(val motorMin: Double, val motorMax: Double, val batteryMin: Double, val batteryMax: Double, val absolute: Double)

private fun Config.limits() = Limits(
    motorMin = double("l_current_min") * double("l_current_min_scale"),
    motorMax = double("l_current_max") * double("l_current_max_scale"),
    batteryMin = double("l_in_current_min") * double("l_in_current_min_scale"),
    batteryMax = double("l_in_current_max") * double("l_in_current_max_scale"),
    absolute = abs(double("l_abs_current_max")),
)

/**
 * Two bars, motor and battery, from their braking/regen limit on the left of zero to their drive limit on the
 * right, against the absolute maximum current beyond which the controller faults. [ghost] (the values on the
 * controller) is outlined dashed when it differs.
 */
@Composable
internal fun LimitBars(config: Config, ghost: Config?, description: String, modifier: Modifier = Modifier, fill: Color = FocTheme.colors.surface) {
    val colors = FocTheme.colors
    val resources = LocalResources.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val tickStyle = FocTheme.type.code.copy(fontSize = 11.sp, lineHeight = 14.sp, color = colors.textTertiary)
    val rowStyle = FocTheme.type.caption.copy(color = colors.textSecondary)
    val motorLabel = stringResource(R.string.curve_line_motor)
    val batteryLabel = stringResource(R.string.curve_line_battery)
    val limits = config.limits()
    val stored = ghost?.limits()
    val spec = motionSpec<Float>(Motion.LONG)
    val motorMin by animateFloatAsState(limits.motorMin.toFloat(), spec, label = "motorMin")
    val motorMax by animateFloatAsState(limits.motorMax.toFloat(), spec, label = "motorMax")
    val batteryMin by animateFloatAsState(limits.batteryMin.toFloat(), spec, label = "batteryMin")
    val batteryMax by animateFloatAsState(limits.batteryMax.toFloat(), spec, label = "batteryMax")
    val absolute by animateFloatAsState(limits.absolute.toFloat(), spec, label = "absolute")

    Canvas(
        modifier
            .fillMaxWidth()
            .height(RowHeight * 2 + AxisLabelHeight)
            .semantics { contentDescription = description },
    ) {
        val rows = listOf(
            Triple(measurer.measure(motorLabel, rowStyle), motorMin, motorMax),
            Triple(measurer.measure(batteryLabel, rowStyle), batteryMin, batteryMax),
        )
        val gutter = rows.maxOf { it.first.size.width } + 12.dp.toPx()
        val area = Rect(gutter, 0f, size.width, RowHeight.toPx() * 2)
        val reach = maxOf(absolute, abs(motorMin), motorMax, abs(batteryMin), batteryMax, 1f) * 1.12f
        fun sx(a: Float) = area.left + (a + reach) / (2 * reach) * area.width
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))

        drawRect(fill, area.topLeft, area.size)
        // Beyond the absolute maximum the controller cuts out with a fault.
        drawRect(colors.faultContainer, area.topLeft, Size(sx(-absolute) - area.left, area.height))
        drawRect(colors.faultContainer, Offset(sx(absolute), area.top), Size(area.right - sx(absolute), area.height))
        val ticks = niceTicks(-reach.toDouble(), reach.toDouble(), 4)
        for (v in ticks) drawLine(colors.hairline, Offset(sx(v.toFloat()), area.top), Offset(sx(v.toFloat()), area.bottom), 1f)
        drawLine(colors.outline, Offset(sx(0f), area.top), Offset(sx(0f), area.bottom), 1.dp.toPx(), alpha = 0.6f)
        for (edge in listOf(-absolute, absolute)) {
            drawLine(colors.fault, Offset(sx(edge), area.top), Offset(sx(edge), area.bottom), 1.dp.toPx(), pathEffect = dash)
        }
        val absText = measurer.measure(absolute.toDouble().fixed(0), tickStyle.copy(color = colors.fault))
        drawText(absText, topLeft = Offset(sx(absolute) - absText.size.width - 3.dp.toPx(), area.bottom - absText.size.height - 2.dp.toPx()))

        val bar = 10.dp.toPx()
        val radius = CornerRadius(2.dp.toPx())
        rows.forEachIndexed { i, (label, low, high) ->
            val center = area.top + RowHeight.toPx() * (i + 0.5f)
            drawText(label, topLeft = Offset(0f, center - label.size.height / 2f))
            if (stored != null) {
                val (storedLow, storedHigh) = if (i == 0) stored.motorMin to stored.motorMax else stored.batteryMin to stored.batteryMax
                val left = sx(storedLow.toFloat())
                drawRoundRect(
                    colors.textTertiary,
                    Offset(left, center - bar),
                    Size(sx(storedHigh.toFloat()) - left, bar * 2),
                    radius,
                    style = Stroke(1.dp.toPx(), pathEffect = dash),
                )
            }
            drawRoundRect(colors.textSecondary, Offset(sx(low), center - bar / 2), Size(sx(0f) - sx(low), bar), radius)
            drawRoundRect(colors.accent, Offset(sx(0f), center - bar / 2), Size(sx(high) - sx(0f), bar), radius)
            val lowText = measurer.measure(low.toDouble().fixed(0), tickStyle)
            val highText = measurer.measure(high.toDouble().fixed(0), tickStyle)
            val textTop = center - bar / 2 - lowText.size.height - 1.dp.toPx()
            drawText(lowText, topLeft = Offset(max(area.left + 2.dp.toPx(), sx(low)), textTop))
            drawText(highText, topLeft = Offset((sx(high) - highText.size.width).coerceAtMost(area.right - highText.size.width - 2.dp.toPx()), textTop))
        }

        val unit = resources.getString(R.string.unit_amp)
        var lastRight = -Float.MAX_VALUE
        ticks.forEachIndexed { i, v ->
            val layout = measurer.measure(v.fixed(0) + if (i == ticks.lastIndex) " $unit" else "", tickStyle)
            val left = (sx(v.toFloat()) - layout.size.width / 2f).coerceIn(area.left, size.width - layout.size.width)
            if (left > lastRight + 4.dp.toPx()) {
                drawText(layout, topLeft = Offset(left, area.bottom + 4.dp.toPx()))
                lastRight = left + layout.size.width
            }
        }
    }
}
