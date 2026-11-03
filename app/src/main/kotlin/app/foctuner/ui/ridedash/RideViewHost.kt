package app.foctuner.ui.ridedash

import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.parts.ChoiceRow

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

/** VESC Dash's ride-view settings: style, theme, launch animation, heat warnings, dual controller. */
@Composable
fun RideViewSettings(hub: RideHub, onOpen: () -> Unit) {
    val p by hub.ridePrefs.collectAsStateWithLifecycle()
    val field = Modifier.fillMaxWidth().padding(horizontal = Spacing.x2)
    Column {
        SectionHeader(stringResource(R.string.rideview_section))
        OutlineButton(stringResource(R.string.rideview_open), onOpen, Modifier.padding(Spacing.x2))
        ChoiceRow(RideStyle.entries, p.rideStyle, { it.label }, { s -> hub.updatePrefs { it.copy(rideStyle = s) } })
        ChoiceRow(RideTheme.entries, p.rideTheme, { it.label }, { t -> hub.updatePrefs { it.copy(rideTheme = t) } })
        Toggle(stringResource(R.string.rideview_launch), p.launchAnimation) { on -> hub.updatePrefs { it.copy(launchAnimation = on) } }
        Toggle(stringResource(R.string.rideview_stabilize), p.batteryStabilize) { on -> hub.updatePrefs { it.copy(batteryStabilize = on) } }
        Toggle(stringResource(R.string.rideview_dual), p.dualController) { on -> hub.updatePrefs { it.copy(dualController = on) } }
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

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.x2),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = FocTheme.type.body, color = FocTheme.colors.text)
        Switch(checked, onChange)
    }
}

@Composable
private fun Number(label: String, value: Double, modifier: Modifier, onSet: (Double) -> Unit) {
    OutlinedTextField(
        value = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString(),
        onValueChange = { text -> text.replace(',', '.').toDoubleOrNull()?.let(onSet) },
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
