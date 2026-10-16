package app.foctuner.ui.tools.lisp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** Asks before [onConfirm]; both buttons and dismissing close it through [onDismiss]. */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    LispDialog(
        title = title,
        onDismiss = onDismiss,
        confirmButton = {
            PlainButton(confirm, {
                onDismiss()
                onConfirm()
            })
        },
        dismissButton = { PlainButton(stringResource(R.string.lisp_cancel), onDismiss) },
    ) {
        Text(body, color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
    }
}

@Composable
fun MessageDialog(title: String, body: String, onDismiss: () -> Unit) {
    LispDialog(title, onDismiss, confirmButton = { PlainButton(stringResource(R.string.lisp_ok), onDismiss) }) {
        Text(body, color = FocTheme.colors.textSecondary, style = FocTheme.type.code)
    }
}

/** VESC Tool's editor help: where the LispBM documentation is, and the keyboard shortcuts. */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val colors = FocTheme.colors
    LispDialog(
        title = stringResource(R.string.lisp_help_title),
        onDismiss = onDismiss,
        confirmButton = { PlainButton(stringResource(R.string.lisp_ok), onDismiss) },
        dismissButton = { PlainButton(stringResource(R.string.lisp_help_docs), { uriHandler.openUri(DOCS_URL) }) },
    ) {
        Text(stringResource(R.string.lisp_help_body), color = colors.textSecondary, style = FocTheme.type.supporting)
        Text(stringResource(R.string.lisp_help_keys), Modifier.padding(top = Spacing.x1), color = colors.text, style = FocTheme.type.label)
        SHORTCUTS.forEach { (keys, label) ->
            Row {
                Text(keys, Modifier.width(112.dp), color = colors.text, style = FocTheme.type.code)
                Text(stringResource(label), color = colors.textSecondary, style = FocTheme.type.supporting)
            }
        }
    }
}

@Composable
private fun LispDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = FocTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = { Text(title, style = FocTheme.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) { content() }
        },
        shape = MaterialTheme.shapes.small,
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
        modifier = Modifier.padding(vertical = Spacing.x3),
    )
}

private const val DOCS_URL = "https://github.com/vedderb/bldc/blob/master/lispBM/README.md"

private val SHORTCUTS = listOf(
    "Ctrl+E" to R.string.lisp_key_upload,
    "Ctrl+W" to R.string.lisp_key_stream,
    "Ctrl+Q" to R.string.lisp_key_stop,
    "Ctrl+R" to R.string.lisp_key_evaluate,
    "Ctrl+S" to R.string.lisp_key_save,
    "Ctrl+F" to R.string.lisp_key_find,
    "Ctrl+Z / Y" to R.string.lisp_key_undo,
    "Ctrl+/" to R.string.lisp_key_comment,
    "Ctrl+Shift+D" to R.string.lisp_key_duplicate,
    "Ctrl+D" to R.string.lisp_key_clear,
    "Ctrl+ + / −" to R.string.lisp_key_text_size,
)
