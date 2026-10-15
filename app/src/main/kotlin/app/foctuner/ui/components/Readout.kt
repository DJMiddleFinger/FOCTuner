package app.foctuner.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** A number in Plex Mono with its unit set smaller and dimmer on the same baseline. */
@Composable
fun Readout(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    style: TextStyle = FocTheme.type.readout,
    unitStyle: TextStyle = FocTheme.type.unit,
    color: Color = FocTheme.colors.text,
) {
    Row(modifier) {
        Text(value, Modifier.alignByBaseline(), color = color, style = style, maxLines = 1, softWrap = false)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(Spacing.half))
            Text(
                unit,
                Modifier.alignByBaseline(),
                color = FocTheme.colors.textTertiary,
                style = unitStyle,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
