package app.foctuner.ui.tools.io

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.ui.tools.parts.Meter
import app.foctuner.ui.tools.parts.MeterRow
import kotlinx.coroutines.delay

@Composable
fun IoBoardTool(context: ToolContext) {
    val model = context.model { IoBoardModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) {
        while (true) {
            model.refresh()
            delay(POLL_MS)
        }
    }
    val colors = FocTheme.colors
    val v = state.values
    var idText by remember { mutableStateOf(state.boardId.toString()) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = idText,
            onValueChange = { text ->
                idText = text
                text.toIntOrNull()?.takeIf { it in 0..254 }?.let(model::setBoard)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.x2),
            label = { Text(stringResource(R.string.io_board_id)) },
            singleLine = true,
            textStyle = FocTheme.type.value,
            isError = idText.toIntOrNull()?.takeIf { it in 0..254 } == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (state.failed) Notice(stringResource(R.string.io_failed), tone = NoticeTone.Warning)
        if (state.missing) Notice(stringResource(R.string.io_no_reply, state.boardId), body = stringResource(R.string.io_no_reply_body))

        SectionHeader(stringResource(R.string.io_section_adc))
        val adc = (v?.adc1to4 ?: List(4) { null }) + (v?.adc5to8 ?: List(4) { null })
        adc.chunked(4).forEachIndexed { row, values ->
            MeterRow(*values.mapIndexed { i, a -> Meter(stringResource(R.string.io_adc, row * 4 + i + 1), a?.fixed(2), "V") }.toTypedArray())
            Hairline()
        }
        v?.adc1to4Age?.let { Age(it) }

        SectionHeader(stringResource(R.string.io_section_digital))
        val digital = v?.digital
        val onInputs = digital?.withIndex()?.filter { it.value }?.joinToString { it.index.toString() }
        val description = stringResource(R.string.io_digital_description, onInputs?.ifEmpty { null } ?: stringResource(R.string.io_none_on))
        DigitalGrid(
            digital,
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.x2)
                .aspectRatio(2f)
                .semantics { contentDescription = description },
        )
        v?.digitalAge?.let { Age(it) }

        SectionHeader(stringResource(R.string.io_section_outputs))
        Text(stringResource(R.string.io_channel), Modifier.padding(horizontal = Spacing.x2), color = colors.textSecondary, style = FocTheme.type.caption)
        ChoiceRow((0..7).toList(), state.digitalChannel, { it.toString() }, model::setDigitalChannel)
        Row(Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1), horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            OutlineButton(stringResource(R.string.io_digital_on), { model.setDigital(true) }, enabled = !state.missing)
            OutlineButton(stringResource(R.string.io_digital_off), { model.setDigital(false) }, enabled = !state.missing)
        }
        Hairline()
        Text(stringResource(R.string.io_pwm), Modifier.padding(start = Spacing.x2, end = Spacing.x2, top = Spacing.x2), color = colors.textSecondary, style = FocTheme.type.caption)
        ChoiceRow((0..3).toList(), state.pwmChannel, { it.toString() }, model::setPwmChannel)
        Row(Modifier.padding(horizontal = Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = state.pwmDuty,
                onValueChange = model::setPwmDuty,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = colors.text, activeTrackColor = colors.outline, inactiveTrackColor = colors.hairline),
            )
            Text((state.pwmDuty * 100f).fixed(0) + " %", Modifier.padding(start = Spacing.x2), color = colors.text, style = FocTheme.type.value)
        }
        OutlineButton(stringResource(R.string.io_pwm_apply), model::applyPwm, Modifier.padding(horizontal = Spacing.x2), enabled = !state.missing)
        Text(stringResource(R.string.io_outputs_hint), Modifier.padding(Spacing.x2), color = colors.textTertiary, style = FocTheme.type.caption)
    }
}

/** 64 inputs as an 8 by 8 grid, input 0 top left, row by row; inputs that are on are filled. */
@Composable
private fun DigitalGrid(inputs: List<Boolean>?, modifier: Modifier) {
    val colors = FocTheme.colors
    Canvas(modifier) {
        val cell = minOf(size.width / 16f, size.height / 8f)
        val gap = 3.dp.toPx()
        for (i in 0 until 64) {
            val topLeft = Offset((i % 8) * cell * 2f, (i / 8) * cell)
            val box = Size(cell * 2f - gap, cell - gap)
            if (inputs?.getOrNull(i) == true) {
                drawRoundRect(colors.accent, topLeft, box, CornerRadius(2.dp.toPx()))
            } else {
                drawRoundRect(colors.hairline, topLeft, box, CornerRadius(2.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
        }
    }
}

@Composable
private fun Age(seconds: Float) {
    Text(
        stringResource(R.string.io_age, seconds.fixed(2)),
        Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        color = FocTheme.colors.textTertiary,
        style = FocTheme.type.caption,
    )
}

private const val POLL_MS = 250L
