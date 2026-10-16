package app.foctuner.ui.tools.imu

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.foctuner.ui.theme.FocTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * The board as a wireframe box seen from behind, right and above, rotated by roll, pitch and yaw (radians, the
 * IMU's x forward, y right, z down convention, applied yaw then pitch then roll). The nose is marked with the
 * accent and the level ground square stays fixed, so tilt reads against it.
 */
@Composable
fun AttitudeView(roll: Float, pitch: Float, yaw: Float, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    Canvas(modifier) {
        val scale = size.minDimension * 0.42f
        val project = { p: FloatArray -> toScreen(p, scale) }
        val ground = listOf(
            floatArrayOf(-1.2f, -1.2f, GROUND), floatArrayOf(1.2f, -1.2f, GROUND),
            floatArrayOf(1.2f, 1.2f, GROUND), floatArrayOf(-1.2f, 1.2f, GROUND),
        ).map(project)
        polygon(ground, colors.hairline)

        val r = rotation(roll, pitch, yaw)
        val corners = BOX.map { project(r.apply(it)) }
        for ((a, b) in EDGES) drawLine(colors.outline, corners[a], corners[b], strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(
            colors.accent,
            project(r.apply(floatArrayOf(0f, 0f, -HALF_HEIGHT))),
            project(r.apply(floatArrayOf(HALF_LENGTH + 0.25f, 0f, -HALF_HEIGHT))),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.toScreen(p: FloatArray, scale: Float): Offset {
    val ca = cos(AZIMUTH)
    val sa = sin(AZIMUTH)
    val x = p[0] * ca - p[1] * sa
    val y = p[0] * sa + p[1] * ca
    val screenY = p[2] * cos(ELEVATION) - x * sin(ELEVATION)
    return Offset(center.x + y * scale, center.y + screenY * scale)
}

private fun DrawScope.polygon(points: List<Offset>, color: Color) {
    val path = Path()
    points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    path.close()
    drawPath(path, color, style = Stroke(1.dp.toPx()))
}

/** Body to world rotation R = Rz(yaw) Ry(pitch) Rx(roll), row-major. */
private class Rotation(val m: FloatArray) {
    fun apply(v: FloatArray) = FloatArray(3) { row -> m[row * 3] * v[0] + m[row * 3 + 1] * v[1] + m[row * 3 + 2] * v[2] }
}

private fun rotation(roll: Float, pitch: Float, yaw: Float): Rotation {
    val cr = cos(roll)
    val sr = sin(roll)
    val cp = cos(pitch)
    val sp = sin(pitch)
    val cy = cos(yaw)
    val sy = sin(yaw)
    return Rotation(
        floatArrayOf(
            cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr,
            sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr,
            -sp, cp * sr, cp * cr,
        ),
    )
}

private const val HALF_LENGTH = 1f
private const val HALF_WIDTH = 0.3f
private const val HALF_HEIGHT = 0.06f
private const val GROUND = 0.5f
private const val AZIMUTH = -0.6f
private const val ELEVATION = 0.45f

private val BOX = listOf(
    floatArrayOf(-HALF_LENGTH, -HALF_WIDTH, -HALF_HEIGHT), floatArrayOf(HALF_LENGTH, -HALF_WIDTH, -HALF_HEIGHT),
    floatArrayOf(HALF_LENGTH, HALF_WIDTH, -HALF_HEIGHT), floatArrayOf(-HALF_LENGTH, HALF_WIDTH, -HALF_HEIGHT),
    floatArrayOf(-HALF_LENGTH, -HALF_WIDTH, HALF_HEIGHT), floatArrayOf(HALF_LENGTH, -HALF_WIDTH, HALF_HEIGHT),
    floatArrayOf(HALF_LENGTH, HALF_WIDTH, HALF_HEIGHT), floatArrayOf(-HALF_LENGTH, HALF_WIDTH, HALF_HEIGHT),
)

private val EDGES = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
