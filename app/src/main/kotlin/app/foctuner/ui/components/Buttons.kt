package app.foctuner.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** The action a notice or empty state asks for. Text-colored: the accent is kept for live data. */
@Composable
fun OutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = FocTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.hairline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.text,
            disabledContentColor = colors.textTertiary,
        ),
        contentPadding = PaddingValues(horizontal = Spacing.x2),
    ) {
        Text(label, style = FocTheme.type.label)
    }
}

/** Secondary actions in headers and bars (Scan, Disconnect, Clear). */
@Composable
fun PlainButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.textButtonColors(
            contentColor = FocTheme.colors.text,
            disabledContentColor = FocTheme.colors.textTertiary,
        ),
        contentPadding = PaddingValues(horizontal = Spacing.x1),
    ) {
        Text(label, style = FocTheme.type.label)
    }
}
