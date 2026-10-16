package app.foctuner.ui.tools.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.theme.FocTheme

/**
 * Runs while held: [onPress] when the finger goes down, [onLift] when it comes up or the button goes away
 * (disabled by STOP, page left). Drawn with the accent while its command is the one running.
 */
@Composable
internal fun HoldButton(
    label: String,
    running: Boolean,
    enabled: Boolean,
    onPress: () -> Unit,
    onLift: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocTheme.colors
    val press by rememberUpdatedState(onPress)
    val lift by rememberUpdatedState(onLift)
    val shape = MaterialTheme.shapes.small
    val runningLabel = stringResource(R.string.control_running)
    Box(
        modifier
            .heightIn(min = HOLD_BUTTON_HEIGHT)
            .background(if (running) colors.surfaceHigh else Color.Transparent, shape)
            .border(1.dp, if (running) colors.accent else if (enabled) colors.outline else colors.hairline, shape)
            .semantics {
                role = Role.Button
                if (running) stateDescription = runningLabel
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        press()
                        try {
                            tryAwaitRelease()
                        } finally {
                            lift()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> colors.textTertiary
                running -> colors.accent
                else -> colors.text
            },
            style = FocTheme.type.label,
        )
    }
}

/**
 * Always enabled, also before the controls are armed: STOP is never the wrong thing to press. It fires when the
 * finger goes down, not when it lifts.
 */
@Composable
internal fun StopButton(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    val stop by rememberUpdatedState(onStop)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = HOLD_BUTTON_HEIGHT)
            .background(colors.fault, MaterialTheme.shapes.small)
            .semantics {
                role = Role.Button
                onClick {
                    stop()
                    true
                }
            }
            .pointerInput(Unit) { detectTapGestures(onPress = { stop() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.control_stop),
            color = colors.background,
            style = FocTheme.type.title.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
