package app.foctuner.ui.tools.control

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.foctuner.R
import app.foctuner.ui.components.NO_VALUE
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.Readout
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** VESC Tool's servo slider (app settings, General): moves the servo output, with a button to center it. */
@Composable
internal fun ServoSection(position: Float?, onPosition: (Float) -> Unit) {
    val colors = FocTheme.colors
    SectionHeader(stringResource(R.string.control_section_servo), trailing = { PlainButton(stringResource(R.string.control_servo_center), { onPosition(CENTER) }) })
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = position ?: CENTER,
            onValueChange = onPosition,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = colors.text,
                activeTrackColor = colors.outline,
                inactiveTrackColor = colors.hairline,
            ),
        )
        Readout(position?.fixed(2) ?: NO_VALUE, "", Modifier.padding(start = Spacing.x2), style = FocTheme.type.value)
    }
    Text(stringResource(R.string.control_servo_hint), Modifier.padding(horizontal = Spacing.x2), color = colors.textTertiary, style = FocTheme.type.caption)
}

private const val CENTER = 0.5f
