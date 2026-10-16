package app.foctuner.ui.tools.control

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.NO_VALUE
import app.foctuner.ui.components.Readout
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.vesc.PositionDisplay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** VESC Tool's "Rotor Position" tab: pick what the controller streams and watch it on a dial. */
@Composable
internal fun RotorSection(state: ControlState, onMode: (PositionDisplay) -> Unit) {
    SectionHeader(stringResource(R.string.control_section_rotor))
    ChoiceRow(PositionDisplay.entries, state.rotorMode, { stringResource(it.label()) }, onMode)
    Hairline()
    val angle = state.rotorAngle
    val description = angle?.let { stringResource(R.string.control_rotor_angle, it.fixed(1)) } ?: NO_VALUE
    Column(
        Modifier
            .fillMaxWidth()
            .padding(Spacing.x2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        RotorDial(angle, Modifier
            .size(160.dp)
            .semantics { contentDescription = description })
        Readout(angle?.fixed(1) ?: NO_VALUE, if (angle == null) "" else "°")
        Text(stringResource(state.rotorMode.hint()), color = FocTheme.colors.textTertiary, style = FocTheme.type.caption)
    }
}

/** Zero at the top, clockwise; ticks every 30 degrees. */
@Composable
private fun RotorDial(angle: Float?, modifier: Modifier) {
    val colors = FocTheme.colors
    Canvas(modifier) {
        val radius = size.minDimension / 2 - 2.dp.toPx()
        val c = center
        drawCircle(colors.outline, radius, c, style = Stroke(1.dp.toPx()))
        for (tick in 0 until 360 step 30) {
            val a = (tick - 90) * PI / 180
            val inner = radius - if (tick % 90 == 0) 12.dp.toPx() else 6.dp.toPx()
            drawLine(
                colors.textTertiary,
                Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat()),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (angle != null) {
            val a = (angle - 90) * PI / 180
            val tip = radius - 4.dp.toPx()
            drawLine(
                colors.accent,
                c,
                Offset(c.x + tip * cos(a).toFloat(), c.y + tip * sin(a).toFloat()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(colors.accent, 3.dp.toPx(), c)
        }
    }
}

private fun PositionDisplay.label(): Int = when (this) {
    PositionDisplay.NONE -> R.string.control_rotor_off
    PositionDisplay.INDUCTANCE -> R.string.control_rotor_inductance
    PositionDisplay.OBSERVER -> R.string.control_rotor_observer
    PositionDisplay.ENCODER -> R.string.control_rotor_encoder
    PositionDisplay.PID_POSITION -> R.string.control_rotor_pid
    PositionDisplay.PID_POSITION_ERROR -> R.string.control_rotor_pid_error
    PositionDisplay.ENCODER_OBSERVER_ERROR -> R.string.control_rotor_obs_enc
    PositionDisplay.HALL_OBSERVER_ERROR -> R.string.control_rotor_obs_hall
}

private fun PositionDisplay.hint(): Int = when (this) {
    PositionDisplay.NONE -> R.string.control_rotor_hint_off
    PositionDisplay.INDUCTANCE -> R.string.control_rotor_hint_inductance
    PositionDisplay.OBSERVER, PositionDisplay.ENCODER_OBSERVER_ERROR, PositionDisplay.HALL_OBSERVER_ERROR -> R.string.control_rotor_hint_foc
    else -> R.string.control_rotor_hint_any
}
