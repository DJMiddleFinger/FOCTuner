package app.foctuner.ui.tools.radio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.ConfigKind
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.setup.baseConfig
import app.foctuner.vesc.NrfPairingResult
import app.foctuner.vesc.bleModuleConfigurable
import app.foctuner.vesc.blePin
import app.foctuner.vesc.pairNrfRemote
import app.foctuner.vesc.setBleName
import app.foctuner.vesc.setBlePin
import app.foctuner.vesc.uartPortServed
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Immutable
data class RadioState(
    /** Seconds left in the pairing window while pairing, else null. */
    val pairingLeft: Float? = null,
    val pairingWindow: Int = 10,
    val pairing: NrfPairingResult? = null,
    val sent: Boolean = false,
    val failed: String? = null,
    /** appconf `app_to_use`, once read. */
    val appToUse: Int? = null,
)

/** The nRF module's Bluetooth name and PIN, and VESC Remote pairing (VESC Tool `BleSetupDialog`, `NrfPair`). */
class RadioModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()
    private var pairJob: Job? = null

    suspend fun readApp() {
        try {
            val app = context.controller.readConfig(ConfigKind.APPCONF, context.baseConfig(ConfigKind.APPCONF))
            _state.update { it.copy(appToUse = app.int("app_to_use").toInt()) }
        } catch (e: IOException) {
            // The check is advisory; the rest of the page works without it.
        }
    }

    fun setWindow(seconds: Int) = _state.update { it.copy(pairingWindow = seconds) }

    fun apply(name: String?, pin: String?) {
        context.scope.launch {
            try {
                if (name != null) context.controller.setBleName(name)
                if (pin != null) context.controller.setBlePin(pin)
                _state.update { it.copy(sent = true, failed = null) }
            } catch (e: IOException) {
                _state.update { it.copy(failed = e.message) }
            }
        }
    }

    fun pair() {
        if (pairJob?.isActive == true) return
        val window = _state.value.pairingWindow
        _state.update { it.copy(pairing = null, failed = null) }
        pairJob = context.scope.launch {
            val countdown = launch {
                val start = TimeSource.Monotonic.markNow()
                while (true) {
                    val left = window - start.elapsedNow().inWholeMilliseconds / 1000f
                    _state.update { it.copy(pairingLeft = left.coerceAtLeast(0f)) }
                    delay(100.milliseconds)
                }
            }
            val result = try {
                context.controller.pairNrfRemote(window.seconds)
            } catch (e: IOException) {
                _state.update { it.copy(failed = e.message) }
                null
            } finally {
                countdown.cancel()
            }
            _state.update { it.copy(pairingLeft = null, pairing = result) }
        }
    }
}

@Composable
fun RadioTool(context: ToolContext) {
    val model = context.model { RadioModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val pinValid = runCatching { blePin(pin) }.isSuccess
    LaunchedEffect(model) { model.readApp() }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        state.appToUse?.takeIf { !uartPortServed(it) }?.let { app ->
            Notice(
                stringResource(R.string.radio_uart_off),
                body = stringResource(if (app == APP_ADC) R.string.radio_uart_off_adc else R.string.radio_uart_off_body),
                tone = NoticeTone.Warning,
            )
        }
        SectionHeader(stringResource(R.string.radio_ble_section))
        if (!context.firmware.bleModuleConfigurable) {
            Notice(stringResource(R.string.radio_ble_unsupported), body = stringResource(R.string.radio_ble_unsupported_body))
        } else {
            Text(
                stringResource(R.string.radio_ble_intro),
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                style = FocTheme.type.supporting,
                color = FocTheme.colors.textSecondary,
            )
            OutlinedTextField(
                name, { name = it.take(25) },
                Modifier.fillMaxWidth().padding(horizontal = Spacing.x2),
                singleLine = true,
                label = { Text(stringResource(R.string.radio_ble_name)) },
                supportingText = { Text(stringResource(R.string.radio_ble_name_hint)) },
            )
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(6) },
                Modifier.fillMaxWidth().padding(horizontal = Spacing.x2),
                singleLine = true,
                label = { Text(stringResource(R.string.radio_ble_pin)) },
                supportingText = { Text(stringResource(R.string.radio_ble_pin_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = !pinValid,
            )
            OutlineButton(stringResource(R.string.radio_ble_apply), { confirm = true }, Modifier.padding(Spacing.x2), enabled = pinValid)
            if (state.sent) Notice(stringResource(R.string.radio_ble_sent), body = stringResource(R.string.radio_ble_sent_body))
        }

        SectionHeader(stringResource(R.string.radio_pair_section))
        Text(
            stringResource(R.string.radio_pair_intro),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = FocTheme.colors.textSecondary,
        )
        val pairing = state.pairingLeft != null
        ChoiceRow(
            choices = listOf(5, 10, 20, 30),
            selected = state.pairingWindow,
            label = { stringResource(R.string.radio_pair_window, it) },
            onSelect = model::setWindow,
            enabled = !pairing,
        )
        state.pairingLeft?.let { left ->
            LinearProgressIndicator(
                progress = { left / state.pairingWindow },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                color = FocTheme.colors.accent,
                trackColor = FocTheme.colors.outline,
            )
        }
        OutlineButton(
            stringResource(if (pairing) R.string.radio_pair_running else R.string.radio_pair_start),
            model::pair,
            Modifier.padding(Spacing.x2),
            enabled = !pairing,
        )
        when (state.pairing) {
            NrfPairingResult.Paired -> Notice(stringResource(R.string.radio_pair_ok), body = stringResource(R.string.radio_pair_ok_body))
            NrfPairingResult.TimedOut -> Notice(stringResource(R.string.radio_pair_timeout), body = stringResource(R.string.radio_pair_timeout_body), tone = NoticeTone.Warning)
            NrfPairingResult.NotSupported -> Notice(stringResource(R.string.radio_pair_unsupported), body = stringResource(R.string.radio_pair_unsupported_body), tone = NoticeTone.Warning)
            NrfPairingResult.NoAnswer -> Notice(stringResource(R.string.radio_pair_no_answer), body = stringResource(R.string.radio_pair_no_answer_body), tone = NoticeTone.Warning)
            null -> Unit
        }
        state.failed?.let { Notice(stringResource(R.string.radio_failed), body = it, tone = NoticeTone.Fault) }
    }
    if (confirm) {
        val changes = listOfNotNull(
            if (name.isNotBlank()) stringResource(R.string.radio_ble_confirm_name, name.trim())
            else stringResource(R.string.radio_ble_confirm_name_default),
            if (pin.isNotEmpty()) stringResource(R.string.radio_ble_confirm_pin, blePin(pin))
            else stringResource(R.string.radio_ble_confirm_pin_off),
        )
        ConfirmDialog(
            title = stringResource(R.string.radio_ble_confirm_title),
            body = changes.joinToString("\n") + "\n\n" + stringResource(R.string.radio_ble_confirm_body),
            confirm = stringResource(R.string.radio_ble_apply),
            dismiss = stringResource(R.string.radio_cancel),
            onConfirm = {
                confirm = false
                model.apply(name, pin)
            },
            onDismiss = { confirm = false },
        )
    }
}

private const val APP_ADC = 2
