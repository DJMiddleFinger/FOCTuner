package app.foctuner.ui.tools.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.ConfigKind
import app.foctuner.ui.components.NO_VALUE
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.InputRange
import app.foctuner.vesc.VescController
import app.foctuner.vesc.VescTimeoutException
import app.foctuner.vesc.decodedAdc
import app.foctuner.vesc.decodedPpm
import app.foctuner.vesc.updateConfig
import app.foctuner.vesc.validPpm
import app.foctuner.vesc.withAdcMapping
import app.foctuner.vesc.withPpmMapping
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

enum class MappedInput(val apps: Set<Long>) { Ppm(setOf(1L, 4L)), Adc(setOf(2L, 5L)) }

/** A live reading and the span captured so far; for ADC, [value2]/[range2] are the second input. */
@Immutable
data class MappingState(
    val value: Float? = null,
    val raw: Float? = null,
    val value2: Float? = null,
    val raw2: Float? = null,
    val min: Float = 0f,
    val max: Float = 0f,
    val center: Float = 0f,
    val min2: Float = 0f,
    val max2: Float = 0f,
    val valid: Boolean = false,
    /** False when the app that reads this input isn't selected, so it reads nothing. */
    val appSelected: Boolean = true,
    val applied: Boolean = false,
)

/** VESC Tool's PPM and ADC mapping: move the throttle end to end, let it rest, then store start, end and center. */
class InputMappingModel(private val context: ToolContext, val input: MappedInput) {
    private val _state = MutableStateFlow(MappingState())
    val state: StateFlow<MappingState> = _state.asStateFlow()
    val runner = SetupRunner(context)
    private val range = InputRange()
    private val range2 = InputRange()

    /** Reads the input every 50 ms, as VESC Tool does, for as long as the caller keeps it running. */
    suspend fun poll(controller: VescController) {
        try {
            val app = controller.readConfig(ConfigKind.APPCONF, context.baseConfig(ConfigKind.APPCONF)).int("app_to_use")
            _state.update { it.copy(appSelected = app in input.apps) }
        } catch (e: IOException) {
            // Polling shows whether anything arrives.
        }
        while (true) {
            try {
                sample(controller)
            } catch (e: VescTimeoutException) {
                // The next poll tries again.
            }
            delay(POLL_PERIOD)
        }
    }

    private suspend fun sample(controller: VescController) {
        when (input) {
            MappedInput.Ppm -> {
                val ppm = controller.decodedPpm()
                // No pulse means no receiver signal; it would drag the minimum to zero.
                if (ppm.pulseMs > 0f) range.add(ppm.pulseMs)
                _state.update { it.copy(value = ppm.value, raw = ppm.pulseMs).withRanges() }
            }
            MappedInput.Adc -> {
                val adc = controller.decodedAdc()
                range.add(adc.voltage)
                range2.add(adc.voltage2)
                _state.update { it.copy(value = adc.value, raw = adc.voltage, value2 = adc.value2, raw2 = adc.voltage2).withRanges() }
            }
        }
    }

    private fun MappingState.withRanges() = copy(
        min = range.min, max = range.max, center = range.center, min2 = range2.min, max2 = range2.max,
        valid = if (input == MappedInput.Ppm) range.validPpm else range.span > 0f,
    )

    fun reset() {
        range.reset()
        range2.reset()
        _state.update { it.copy(applied = false).withRanges() }
    }

    /** Stores the captured span in [controller]'s app config. */
    suspend fun store(controller: VescController) {
        controller.updateConfig(ConfigKind.APPCONF, context.baseConfig(ConfigKind.APPCONF)) {
            if (input == MappedInput.Ppm) it.withPpmMapping(range) else it.withAdcMapping(range, range2)
        }
        _state.update { it.copy(applied = true) }
    }

    fun apply(controller: VescController) = runner.start(spins = false) { store(controller) }

    private companion object {
        val POLL_PERIOD = 50.milliseconds
    }
}

class PpmMappingModel(context: ToolContext) {
    val mapping = InputMappingModel(context, MappedInput.Ppm)
}

class AdcMappingModel(context: ToolContext) {
    val mapping = InputMappingModel(context, MappedInput.Adc)
}

@Composable
fun PpmMappingTool(context: ToolContext) {
    val model = context.model { PpmMappingModel(context) }.mapping
    Column(Modifier.verticalScroll(rememberScrollState())) { MappingPanel(model, context.controller) }
}

@Composable
fun AdcMappingTool(context: ToolContext) {
    val model = context.model { AdcMappingModel(context) }.mapping
    Column(Modifier.verticalScroll(rememberScrollState())) { MappingPanel(model, context.controller) }
}

/** Live input, captured span and Apply for [controller]; polls while shown. */
@Composable
fun MappingPanel(model: InputMappingModel, controller: VescController) {
    val state by model.state.collectAsStateWithLifecycle()
    val run by model.runner.state.collectAsStateWithLifecycle()
    LaunchedEffect(model, controller) { model.poll(controller) }
    val ppm = model.input == MappedInput.Ppm
    val unit = if (ppm) "ms" else "V"
    val decimals = if (ppm) 4 else 2

    if (!state.appSelected) {
        Notice(stringResource(if (ppm) R.string.setup_ppm_app_off else R.string.setup_adc_app_off), body = stringResource(R.string.setup_app_off_body), tone = NoticeTone.Warning)
    }
    Text(stringResource(R.string.setup_mapping_intro), Modifier.padding(Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
    SectionHeader(stringResource(if (ppm) R.string.setup_ppm_input else R.string.setup_adc_input))
    LiveBar(state.value, if (ppm) -1f else 0f)
    ValueRow(stringResource(R.string.setup_value), state.value?.fixed(2) ?: NO_VALUE)
    ValueRow(stringResource(R.string.setup_now), state.raw?.fixed(decimals) ?: NO_VALUE, unit)
    ValueRow(stringResource(R.string.setup_min), state.min.fixed(decimals), unit)
    ValueRow(stringResource(R.string.setup_max), state.max.fixed(decimals), unit)
    ValueRow(stringResource(R.string.setup_center), state.center.fixed(decimals), unit)
    if (!ppm) {
        SectionHeader(stringResource(R.string.setup_adc_input2))
        LiveBar(state.value2, 0f)
        ValueRow(stringResource(R.string.setup_now), state.raw2?.fixed(decimals) ?: NO_VALUE, unit)
        ValueRow(stringResource(R.string.setup_min), state.min2.fixed(decimals), unit)
        ValueRow(stringResource(R.string.setup_max), state.max2.fixed(decimals), unit)
    }
    if (state.applied) Notice(stringResource(R.string.setup_written_app))
    RunStatus(run, stringResource(R.string.setup_working), model.runner::stop)
    ActionRow {
        PlainButton(stringResource(R.string.setup_reset), model::reset)
        OutlineButton(stringResource(R.string.setup_apply_mapping), { model.apply(controller) }, enabled = state.valid && run !is RunState.Running)
    }
    if (!state.valid && ppm) Text(stringResource(R.string.setup_ppm_not_valid), Modifier.padding(horizontal = Spacing.x2), color = FocTheme.colors.textTertiary, style = FocTheme.type.caption)
}

@Composable
private fun LiveBar(value: Float?, from: Float) {
    LinearProgressIndicator(
        progress = { ((value ?: from) - from) / (1f - from) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        color = FocTheme.colors.accent,
        trackColor = FocTheme.colors.surfaceHigh,
        drawStopIndicator = {},
    )
}
