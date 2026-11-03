package app.foctuner.ui.tools.analysis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.ConfigKind
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.setup.baseConfig
import app.foctuner.vesc.CanFrame
import app.foctuner.vesc.CanMode
import app.foctuner.vesc.canFrames
import app.foctuner.vesc.sendCanFrame
import app.foctuner.vesc.setCanMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.TimeSource

@Immutable
data class CanRow(val number: Long, val seconds: Double, val frame: CanFrame, val sent: Boolean)

@Immutable
data class CanAnalyzerState(
    /** The mode to go back to, set while the analyzer has the controller in Comm Bridge. */
    val restore: CanMode? = null,
    val rows: List<CanRow> = emptyList(),
    val paused: Boolean = false,
    val failed: String? = null,
)

/**
 * VESC Tool's CAN analyzer: the attached controller relays raw frames from its bus while its CAN mode is Comm Bridge.
 * The analyzer switches it there without storing it, and back when it closes; a restart also brings the stored mode
 * back. Comm Bridge stops VESC traffic through that controller, so CAN controllers aren't reachable meanwhile.
 */
class CanAnalyzerModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(CanAnalyzerState())
    val state: StateFlow<CanAnalyzerState> = _state.asStateFlow()
    private var listener: Job? = null
    private var next = 0L
    private val start = TimeSource.Monotonic.markNow()

    fun start() {
        if (_state.value.restore != null) return
        context.scope.launch {
            try {
                val app = context.client.readConfig(ConfigKind.APPCONF, context.baseConfig(ConfigKind.APPCONF))
                val mode = CanMode.fromValue(app.int("can_mode").toInt()) ?: CanMode.Vesc
                context.client.setCanMode(CanMode.CommBridge, store = false)
                _state.update { it.copy(restore = mode, failed = null) }
                listener = context.scope.launch {
                    context.client.canFrames.collect { frame -> if (!_state.value.paused) add(frame, sent = false) }
                }
            } catch (e: IOException) {
                _state.update { it.copy(failed = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun stop() {
        val restore = _state.value.restore ?: return
        listener?.cancel()
        _state.update { it.copy(restore = null) }
        context.scope.launch {
            withContext(NonCancellable) {
                runCatching { context.client.setCanMode(restore, store = false) }
            }
        }
    }

    fun send(frame: CanFrame) {
        context.scope.launch {
            try {
                context.client.sendCanFrame(frame)
                add(frame, sent = true)
            } catch (e: IOException) {
                _state.update { it.copy(failed = e.message) }
            }
        }
    }

    fun setPaused(paused: Boolean) = _state.update { it.copy(paused = paused) }
    fun clear() = _state.update { it.copy(rows = emptyList()) }

    private fun add(frame: CanFrame, sent: Boolean) {
        val row = CanRow(next++, start.elapsedNow().inWholeMicroseconds / 1e6, frame, sent)
        _state.update { it.copy(rows = (listOf(row) + it.rows).take(MAX_ROWS)) }
    }

    private companion object {
        const val MAX_ROWS = 4000
    }
}

@Composable
fun CanAnalyzerTool(context: ToolContext) {
    val model = context.model { CanAnalyzerModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    DisposableEffect(model) { onDispose { model.stop() } }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var idText by rememberSaveable { mutableStateOf("123") }
    var dataText by rememberSaveable { mutableStateOf("01 02 03") }
    var extended by rememberSaveable { mutableStateOf(false) }
    val frame = parseFrame(idText, dataText, extended)
    val colors = FocTheme.colors
    Column {
        if (state.restore == null) {
            Notice(stringResource(R.string.can_bridge_needed), body = stringResource(R.string.can_bridge_needed_body))
            OutlineButton(stringResource(R.string.can_start), { confirm = true }, Modifier.padding(Spacing.x2))
        } else {
            Row(Modifier.padding(horizontal = Spacing.x1), verticalAlignment = Alignment.CenterVertically) {
                PlainButton(stringResource(R.string.can_stop), model::stop)
                PlainButton(stringResource(if (state.paused) R.string.can_resume else R.string.can_pause), { model.setPaused(!state.paused) })
                PlainButton(stringResource(R.string.can_clear), model::clear)
            }
            Row(Modifier.padding(horizontal = Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(idText, { idText = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.can_id)) }, isError = frame == null)
                Row(
                    Modifier.toggleable(extended, role = Role.Checkbox) { extended = it }.padding(start = Spacing.x1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(extended, null, colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.outline, checkmarkColor = colors.onAccent))
                    Text(stringResource(R.string.can_extended), style = FocTheme.type.body, color = colors.text)
                }
            }
            OutlinedTextField(dataText, { dataText = it }, Modifier.fillMaxWidth().padding(horizontal = Spacing.x2), singleLine = true, label = { Text(stringResource(R.string.can_data)) }, isError = frame == null)
            OutlineButton(stringResource(R.string.can_send), { frame?.let(model::send) }, Modifier.padding(Spacing.x2), enabled = frame != null)
        }
        state.failed?.let { Notice(stringResource(R.string.can_failed), body = it, tone = NoticeTone.Fault) }
        Hairline()
        Text(
            pluralStringResource(R.plurals.can_count, state.rows.size, state.rows.size),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.half),
            style = FocTheme.type.caption,
            color = colors.textTertiary,
        )
        LazyColumn {
            items(state.rows, key = { it.number }) { row ->
                Text(
                    "%9.3f  %s %s  [%d]  %s".format(
                        row.seconds,
                        if (row.sent) "→" else "←",
                        if (row.frame.extended) "%08X".format(row.frame.id) else "     %03X".format(row.frame.id),
                        row.frame.data.size,
                        row.frame.data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                    ),
                    Modifier.padding(horizontal = Spacing.x2),
                    style = FocTheme.type.code,
                    color = if (row.sent) colors.accent else colors.text,
                    maxLines = 1,
                )
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.can_confirm_title),
            body = stringResource(R.string.can_confirm_body),
            confirm = stringResource(R.string.can_start),
            dismiss = stringResource(R.string.can_cancel),
            onConfirm = {
                confirm = false
                model.start()
            },
            onDismiss = { confirm = false },
        )
    }
}

/** A frame from a hex id and up to 8 hex bytes separated by spaces, or null if either doesn't parse. */
internal fun parseFrame(id: String, data: String, extended: Boolean): CanFrame? {
    val parsedId = id.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return null
    val bytes = data.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { it.removePrefix("0x").toIntOrNull(16)?.takeIf { b -> b in 0..255 } ?: return null }
    if (bytes.size > 8) return null
    return runCatching { CanFrame(parsedId, extended, ByteArray(bytes.size) { bytes[it].toByte() }) }.getOrNull()
}
