package app.foctuner.ui.tools.parts

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.theme.FocTheme

/** Asks before an action that changes the controller or what is connected to it, saying what will happen. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FocTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { PlainButton(confirm, onConfirm) },
        dismissButton = { PlainButton(dismiss, onDismiss) },
        title = { Text(title, style = FocTheme.type.title) },
        text = { Text(body, style = FocTheme.type.supporting) },
        shape = MaterialTheme.shapes.small,
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
    )
}
