package app.foctuner.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foctuner.R
import app.foctuner.config.ParamDefinition
import app.foctuner.config.ParamType
import app.foctuner.config.ParamValue
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.motionSpec
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Corner = RoundedCornerShape(4.dp)
private val ThumbRadius = 9.dp
private val TrackHeight = 32.dp

/**
 * One setting with the editor its type needs right on the row: a slider with nudges for numbers, segments or a
 * picker for options, chips for bitfields, a switch, a text field. Tapping the name (or a number's value) opens
 * [ParamDialog] for an exact value and VESC Tool's description. A pending edit is marked with a bar and the value
 * on the controller.
 */
@Composable
internal fun ParamRow(
    param: ParamDefinition,
    value: ParamValue,
    stored: ParamValue?,
    changed: Boolean,
    onOpen: () -> Unit,
    onSet: (ParamValue) -> Unit,
) {
    val colors = FocTheme.colors
    val none = stringResource(R.string.settings_none)
    val bar by animateColorAsState(if (changed) colors.warning else Color.Transparent, motionSpec(), label = "changed-bar")
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = Spacing.x2, end = Spacing.x1, bottom = Spacing.x1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.row)
                    .clickable(onClickLabel = stringResource(R.string.settings_details), role = Role.Button, onClick = onOpen)
                    .padding(top = Spacing.x1, end = Spacing.x1),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(param.longName, color = colors.text, style = FocTheme.type.body)
                if (changed && stored != null) {
                    Text(stringResource(R.string.settings_was, param.display(stored, none)), color = colors.warning, style = FocTheme.type.caption)
                }
                if (!param.transmittable) {
                    Text(stringResource(R.string.settings_not_on_controller), color = colors.textTertiary, style = FocTheme.type.caption)
                }
            }
            when (param.type) {
                ParamType.BOOL -> Switch(
                    checked = (value as? ParamValue.IntValue)?.value == 1L,
                    onCheckedChange = { onSet(ParamValue.IntValue(if (it) 1 else 0)) },
                    Modifier
                        .padding(horizontal = Spacing.x1)
                        .semantics { contentDescription = param.longName },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onAccent,
                        checkedTrackColor = colors.accent,
                        uncheckedThumbColor = colors.outline,
                        uncheckedTrackColor = colors.surface,
                        uncheckedBorderColor = colors.outline,
                    ),
                )
                ParamType.DOUBLE, ParamType.INT -> ValueBox(param.display(value, none), changed, onOpen)
                else -> Unit
            }
        }
        val option = (value as? ParamValue.IntValue)?.value ?: 0L
        when (param.type) {
            ParamType.DOUBLE, ParamType.INT -> NumberSlider(param, value, stored?.takeIf { changed }, onSet)
            ParamType.ENUM -> if (param.segmented) {
                Segments(param.enumNames, option.toInt()) { onSet(ParamValue.IntValue(it.toLong())) }
            } else {
                OptionPicker(param.longName, param.enumNames, option.toInt()) { onSet(ParamValue.IntValue(it.toLong())) }
            }
            ParamType.BITFIELD -> BitChips(param.bitNames(), option) { onSet(ParamValue.IntValue(it)) }
            ParamType.STRING -> TextEditor(param, (value as? ParamValue.TextValue)?.value.orEmpty(), onSet)
            else -> Unit
        }
    }
}

/** Few, short options fit side by side; anything longer gets a picker. */
private val ParamDefinition.segmented: Boolean
    get() = enumNames.size in 2..4 && enumNames.all { it.length <= 12 } && enumNames.sumOf { it.length } <= 30

@Composable
private fun ValueBox(text: String, changed: Boolean, onOpen: () -> Unit) {
    val colors = FocTheme.colors
    Box(
        Modifier
            .widthIn(max = 200.dp)
            .heightIn(min = Spacing.touchTarget)
            .clickable(onClickLabel = stringResource(R.string.settings_type_value), role = Role.Button, onClick = onOpen)
            .padding(vertical = Spacing.half, horizontal = Spacing.half),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            Modifier
                .border(1.dp, colors.outline, Corner)
                .padding(horizontal = Spacing.x1, vertical = Spacing.half),
            color = if (changed) colors.text else colors.textSecondary,
            style = FocTheme.type.value,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * −/+ nudges either side of a track to drag along. The track covers [NumberTrack]'s practical window, which stays put
 * while the finger is down; dragging moves the value by how far the finger moves, so it never jumps on touch.
 */
@Composable
private fun NumberSlider(param: ParamDefinition, value: ParamValue, stored: ParamValue?, onSet: (ParamValue) -> Unit) {
    var frozen by remember { mutableStateOf<NumberTrack?>(null) }
    val live = remember(param, value, stored) { param.track(value, stored) }
    val track = frozen ?: live
    val shown = param.shown(value)
    val set = { v: Double -> if (v != shown) onSet(param.fromShown(v)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Nudge(R.drawable.ic_minus, stringResource(R.string.settings_decrease, param.longName), shown > track.limitMin) {
            set(track.nudge(shown, up = false))
        }
        Track(
            track = track,
            shown = shown,
            stored = stored?.let(param::shown),
            name = param.longName,
            state = param.display(value, ""),
            label = param::trackLabel,
            onDrag = { frozen = it },
            onSet = set,
            modifier = Modifier.weight(1f),
        )
        Nudge(R.drawable.ic_plus, stringResource(R.string.settings_increase, param.longName), shown < track.limitMax) {
            set(track.nudge(shown, up = true))
        }
    }
}

/** A 48dp button that steps once on tap and keeps stepping while held, each step from the latest value. */
@Composable
private fun Nudge(@DrawableRes icon: Int, description: String, enabled: Boolean, onStep: () -> Unit) {
    val colors = FocTheme.colors
    val step by rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (pressed) colors.surfaceHigh else Color.Transparent, motionSpec(), label = "nudge")
    Box(
        Modifier
            .size(Spacing.touchTarget)
            .background(fill, Corner)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (!enabled) disabled()
                onClick { step(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown()
                        pressed = true
                        val repeat = launch {
                            step()
                            delay(400)
                            while (true) {
                                step()
                                delay(60)
                            }
                        }
                        waitForUpOrCancellation()
                        repeat.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if (enabled) colors.text else colors.textTertiary.copy(alpha = 0.5f))
    }
}

@Composable
private fun Track(
    track: NumberTrack,
    shown: Double,
    stored: Double?,
    name: String,
    state: String,
    label: (Double) -> String,
    onDrag: (NumberTrack?) -> Unit,
    onSet: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocTheme.colors
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val tickStyle = FocTheme.type.code.copy(fontSize = 11.sp, lineHeight = 14.sp, color = colors.textTertiary)
    val labelHeight = with(LocalDensity.current) { tickStyle.lineHeight.toDp() }
    val latestTrack by rememberUpdatedState(track)
    val latestShown by rememberUpdatedState(shown)
    val latestSet by rememberUpdatedState(onSet)
    val was = stored?.let { stringResource(R.string.settings_track_was, label(it)) }
    Canvas(
        modifier
            .height(TrackHeight + labelHeight)
            .semantics {
                contentDescription = name
                stateDescription = if (was != null) "$state, $was" else state
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), track.min.toFloat()..track.max.toFloat())
                setProgress { target ->
                    onSet(track.snap(target.toDouble()))
                    true
                }
            }
            .pointerInput(Unit) {
                var fraction = 0f
                val inset = ThumbRadius.toPx()
                detectHorizontalDragGestures(
                    onDragStart = {
                        onDrag(latestTrack)
                        fraction = latestTrack.fraction(latestShown)
                    },
                    onDragEnd = { onDrag(null) },
                    onDragCancel = { onDrag(null) },
                ) { change, dx ->
                    change.consume()
                    fraction = (fraction + dx / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val next = latestTrack.at(fraction)
                    if (next != latestShown) latestSet(next)
                }
            },
    ) {
        val inset = ThumbRadius.toPx()
        val center = TrackHeight.toPx() / 2
        val left = inset
        val right = size.width - inset
        fun x(v: Double) = left + track.fraction(v) * (right - left)
        val line = 2.dp.toPx()
        drawRoundRect(colors.outline, Offset(left, center - line / 2), Size(right - left, line), CornerRadius(line / 2))
        val origin = x(0.0.coerceIn(track.min, track.max))
        val thumb = x(shown)
        drawRect(colors.accent, Offset(minOf(origin, thumb), center - line), Size(kotlin.math.abs(thumb - origin), line * 2))
        if (stored != null && stored in track.min..track.max) {
            val tick = x(stored)
            drawLine(colors.textTertiary, Offset(tick, center - 8.dp.toPx()), Offset(tick, center + 8.dp.toPx()), 2.dp.toPx())
        }
        drawCircle(colors.background, ThumbRadius.toPx(), Offset(thumb, center))
        drawCircle(colors.accent, ThumbRadius.toPx() - 2.dp.toPx(), Offset(thumb, center))
        drawCircle(colors.outline, ThumbRadius.toPx(), Offset(thumb, center), style = Stroke(1.dp.toPx()))
        val low = measurer.measure(label(track.min), tickStyle)
        val high = measurer.measure(label(track.max), tickStyle)
        drawText(low, topLeft = Offset(0f, TrackHeight.toPx()))
        drawText(high, topLeft = Offset(size.width - high.size.width, TrackHeight.toPx()))
    }
}

/** Options side by side in one bordered strip; the selected one is filled and underlined in accent. */
@Composable
private fun Segments(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = FocTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = Spacing.x1)
            .border(1.dp, colors.outline, Corner)
            .selectableGroup(),
    ) {
        names.forEachIndexed { index, name ->
            val isSelected = index == selected
            val fill by animateColorAsState(if (isSelected) colors.surfaceHigh else Color.Transparent, motionSpec(), label = "segment")
            if (index > 0) Box(Modifier.width(1.dp).height(Spacing.touchTarget).background(colors.outline))
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.touchTarget)
                    .background(fill)
                    .drawBehind {
                        if (isSelected) drawRect(colors.accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
                    }
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(index) }
                    .padding(horizontal = Spacing.half, vertical = Spacing.x1),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name,
                    color = if (isSelected) colors.text else colors.textSecondary,
                    style = FocTheme.type.label,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The selected option in a bordered box; tapping it lists every option. */
@Composable
private fun OptionPicker(name: String, names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = FocTheme.colors
    var open by remember { mutableStateOf(false) }
    val current = names.getOrNull(selected) ?: selected.toString()
    Box(Modifier.padding(end = Spacing.x1)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .border(1.dp, colors.outline, Corner)
                .clickable(onClickLabel = stringResource(R.string.settings_choose_option), role = Role.DropdownList) { open = true }
                .semantics {
                    contentDescription = name
                    stateDescription = current
                }
                .padding(start = Spacing.x2, end = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, Modifier.weight(1f).padding(vertical = Spacing.x1), color = colors.text, style = FocTheme.type.body)
            Icon(painterResource(R.drawable.ic_expand), null, Modifier.size(20.dp), tint = colors.textSecondary)
        }
        DropdownMenu(open, { open = false }, Modifier.widthIn(min = 240.dp), containerColor = colors.surface) {
            names.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option, color = colors.text, style = FocTheme.type.body) },
                    onClick = {
                        open = false
                        onSelect(index)
                    },
                    modifier = Modifier.semantics { this.selected = index == selected },
                    leadingIcon = {
                        RadioButton(
                            selected = index == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.outline),
                        )
                    },
                )
            }
        }
    }
}

/** One chip per bit; a set bit is outlined in accent with a check. */
@Composable
private fun BitChips(names: List<String>, bits: Long, onSet: (Long) -> Unit) {
    val colors = FocTheme.colors
    FlowRow(
        Modifier.padding(end = Spacing.x1),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        names.forEachIndexed { bit, name ->
            val mask = 1L shl bit
            val on = bits and mask != 0L
            Box(
                Modifier
                    .heightIn(min = Spacing.touchTarget)
                    .toggleable(value = on, role = Role.Checkbox) { onSet(if (it) bits or mask else bits and mask.inv()) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier
                        .background(if (on) colors.surfaceHigh else Color.Transparent, Corner)
                        .border(1.dp, if (on) colors.accent else colors.outline, Corner)
                        .padding(horizontal = Spacing.x1, vertical = Spacing.half),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (on) {
                        Canvas(Modifier.size(14.dp)) {
                            val w = 1.75.dp.toPx()
                            drawLine(colors.accent, Offset(size.width * 0.15f, size.height * 0.55f), Offset(size.width * 0.4f, size.height * 0.8f), w)
                            drawLine(colors.accent, Offset(size.width * 0.4f, size.height * 0.8f), Offset(size.width * 0.88f, size.height * 0.2f), w)
                        }
                    }
                    Text(
                        name,
                        Modifier.padding(start = if (on) Spacing.half else 0.dp),
                        color = if (on) colors.text else colors.textSecondary,
                        style = FocTheme.type.label,
                    )
                }
            }
        }
    }
}

@Composable
private fun TextEditor(param: ParamDefinition, external: String, onSet: (ParamValue) -> Unit) {
    val colors = FocTheme.colors
    var text by remember { mutableStateOf(external) }
    LaunchedEffect(external) { if (external != text) text = external }
    OutlinedTextField(
        value = text,
        onValueChange = {
            val limited = if (param.maxLength > 0) it.take(param.maxLength) else it
            text = limited
            onSet(ParamValue.TextValue(limited))
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = Spacing.x1)
            .semantics { contentDescription = param.longName },
        textStyle = FocTheme.type.body.copy(color = colors.text),
        maxLines = 4,
        shape = Corner,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.outline,
            cursorColor = colors.accent,
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}
