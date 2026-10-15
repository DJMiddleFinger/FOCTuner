package app.foctuner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.foctuner.ui.theme.FocTheme

/**
 * A trend line with no axes. New samples enter on the right at a fixed spacing of `width / capacity`, so
 * the line scrolls at a constant rate and a partly filled buffer starts mid-chart instead of stretching.
 *
 * The vertical range follows the data but never gets narrower than [minSpan], so sensor noise on a
 * steady value stays a flat line instead of being blown up to full height. When the range crosses zero
 * (regen current, regen power) a hairline marks zero.
 */
@Composable
fun Sparkline(
    samples: List<Float>,
    capacity: Int,
    minSpan: Float,
    modifier: Modifier = Modifier,
    color: Color = FocTheme.colors.accent,
    zeroColor: Color = FocTheme.colors.hairline,
) {
    Canvas(modifier) {
        if (samples.size < 2 || capacity < 2) return@Canvas
        val stroke = 1.5.dp.toPx()
        val dot = 2.5.dp.toPx()

        var lo = samples.min()
        var hi = samples.max()
        if (hi - lo < minSpan) {
            val mid = (hi + lo) / 2
            lo = mid - minSpan / 2
            hi = mid + minSpan / 2
        }
        val top = dot
        val plotHeight = size.height - 2 * dot
        fun y(v: Float) = top + plotHeight * (hi - v) / (hi - lo)

        val right = size.width - dot
        val step = right / (capacity - 1)
        val left = right - step * (samples.size - 1)

        if (lo < 0f && hi > 0f) {
            drawLine(zeroColor, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 1.dp.toPx())
        }
        val path = Path()
        samples.forEachIndexed { i, v ->
            if (i == 0) path.moveTo(left, y(v)) else path.lineTo(left + i * step, y(v))
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = dot, center = Offset(right, y(samples.last())))
    }
}
