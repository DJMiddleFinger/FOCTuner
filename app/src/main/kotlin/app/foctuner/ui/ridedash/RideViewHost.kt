package app.foctuner.ui.ridedash

import android.content.pm.ActivityInfo
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.motionSpec
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.ui.tools.setup.SwitchRow

/** The ride view full screen over the app, landscape while it shows; the layout editor on top when opened. */
@Composable
fun RideViewDialog(hub: RideHub, onClose: () -> Unit) {
    val editing by hub.layoutEditorOpen.collectAsStateWithLifecycle()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val activity = view.context.findActivity()
            val before = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hub.start()
            onDispose {
                hub.stop()
                activity?.requestedOrientation = before ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (editing) RideLayoutEditor(hub) else RideScreen(hub, onHome = onClose)
        }
    }
}

/** VESC Dash's ride-view settings: a live preview of the chosen look over its choices, then the ride options. */
@Composable
fun RideViewSettings(hub: RideHub, onOpen: () -> Unit) {
    val p by hub.ridePrefs.collectAsStateWithLifecycle()
    val field = Modifier.fillMaxWidth().padding(horizontal = Spacing.x2, vertical = Spacing.x1)
    Column {
        SectionHeader(stringResource(R.string.rideview_section))
        RidePreview(hub, onOpen, Modifier.padding(horizontal = Spacing.x2).fillMaxWidth())
        Choice(stringResource(R.string.rideview_style), RideStyle.entries, p.rideStyle, { it.label }) { s -> hub.updatePrefs { it.copy(rideStyle = s) } }
        Choice(stringResource(R.string.rideview_appearance), AppAppearance.entries, p.appearance, { it.label }) { a -> hub.updatePrefs { it.copy(appearance = a) } }
        // The E-moto display keeps its own dark palette, so the theme only applies to the classic look.
        if (p.appearance == AppAppearance.CLASSIC) {
            Choice(stringResource(R.string.rideview_theme), RideTheme.entries, p.rideTheme, { it.label }) { t -> hub.updatePrefs { it.copy(rideTheme = t) } }
        }
        OutlineButton(stringResource(R.string.rideview_open), onOpen, Modifier.padding(Spacing.x2))
        Hairline()
        SwitchRow(stringResource(R.string.rideview_launch), p.launchAnimation, { on -> hub.updatePrefs { it.copy(launchAnimation = on) } })
        SwitchRow(stringResource(R.string.rideview_stabilize), p.batteryStabilize, { on -> hub.updatePrefs { it.copy(batteryStabilize = on) } })
        SwitchRow(stringResource(R.string.rideview_dual), p.dualController, { on -> hub.updatePrefs { it.copy(dualController = on) } })
        Number(stringResource(R.string.rideview_heat_controller), p.heatWarnControllerC, field) { v -> hub.updatePrefs { it.copy(heatWarnControllerC = v) } }
        Number(stringResource(R.string.rideview_heat_motor), p.heatWarnMotorC, field) { v -> hub.updatePrefs { it.copy(heatWarnMotorC = v) } }
        if (p.dualController) {
            Number(stringResource(R.string.rideview_can_id), p.canSlaveId.toDouble(), field) { v -> hub.updatePrefs { it.copy(canSlaveId = v.toInt().coerceIn(0, 254)) } }
        }
        Text(
            stringResource(R.string.rideview_modes_hint),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = FocTheme.colors.textSecondary,
        )
    }
}

/**
 * The chosen style and theme drawn at this phone's landscape size, scaled to the width, with readings from the
 * middle of a ride. The real ride view draws the same composable; tapping the preview opens it.
 */
@Composable
private fun RidePreview(hub: RideHub, onOpen: () -> Unit, modifier: Modifier) {
    val vehicle by hub.vehicle.collectAsStateWithLifecycle()
    val window = LocalWindowInfo.current.containerSize
    if (window.width == 0 || window.height == 0) return
    val density = LocalDensity.current
    val long = with(density) { maxOf(window.width, window.height).toDp() }
    val short = with(density) { minOf(window.width, window.height).toDp() }
    val data = remember(vehicle.imperial) { previewData(vehicle) }
    val shape = RoundedCornerShape(8.dp)
    BoxWithConstraints(modifier.aspectRatio(long / short).clip(shape).border(1.dp, FocTheme.colors.hairline, shape)) {
        val scale = maxWidth / long
        Box(Modifier.requiredSize(long, short).graphicsLayer { scaleX = scale; scaleY = scale }) {
            Crossfade(vehicle.rideStyle to ridePalette(vehicle), animationSpec = motionSpec(), label = "ridePreview") { (style, c) ->
                RideStyleView(style, hub, data, c, onCycleMode = {}, onHome = {})
            }
        }
        // On top, so the preview's own mode pill and buttons can't be pressed.
        Box(Modifier.matchParentSize().clickable(onClickLabel = stringResource(R.string.rideview_open), onClick = onOpen))
    }
}

/** Under way at a steady clip in the third sample mode, so the stopped-only buttons stay hidden. */
private fun previewData(v: VehicleSettings): RideData {
    val k = if (v.imperial) KMH_TO_MPH else 1.0
    val mode = Defaults.modes[2]
    return RideData(
        speed = 32.0 * k, speedUnit = if (v.imperial) "mph" else "kmh",
        trip = 12.4 * k, tripUnit = Metric.TRIP.unit(v),
        motorTempC = 48.0, battery = 76.0, torque = 0.35f,
        modeNumber = 3, modeName = mode.name, modeColor = Color(mode.color), modeStatus = ModeApplyStatus.Idle,
        rideTimeMs = 23 * 60_000L, warnings = emptyList(),
    )
}

@Composable
private fun <T> Choice(title: String, choices: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Text(
        title,
        Modifier.padding(start = Spacing.x2, end = Spacing.x2, top = Spacing.x2),
        style = FocTheme.type.supporting,
        color = FocTheme.colors.textSecondary,
    )
    ChoiceRow(choices, selected, label, onSelect)
}

@Composable
private fun Number(label: String, value: Double, modifier: Modifier, onSet: (Double) -> Unit) {
    // Holds what was typed, so the field can be emptied or end in "." on the way to the next value.
    var text by remember { mutableStateOf(if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toDoubleOrNull()?.let(onSet)
        },
        modifier = modifier,
        singleLine = true,
        isError = parsed == null,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
