package app.foctuner.ui.tools.logs

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.logging.RideLogHub
import app.foctuner.logging.RideLogService
import app.foctuner.logging.RideLogState
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/**
 * Starts the ride log service, asking for the permissions it can use first: the phone's position (not in demo mode,
 * which has none) and notifications. The recording starts whatever the answers are; without location it has no GPS
 * track, and without notifications the service still runs.
 */
@Composable
fun rememberRideLogToggle(): () -> Unit {
    val context = LocalContext.current
    val state by RideLogHub.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { RideLogService.start(context) }
    return {
        if (state.recording) {
            RideLogService.stop(context)
        } else {
            val permissions = buildList {
                if (RideLogHub.source?.demo != true) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (permissions.isEmpty()) RideLogService.start(context) else launcher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun rideLogState(): State<RideLogState> = RideLogHub.state.collectAsStateWithLifecycle()

/** The ride log's one-line control for the top of RT Data: what it is doing and the button to start or stop it. */
@Composable
fun RideLogBar(modifier: Modifier = Modifier) {
    val state by rideLogState()
    val toggle = rememberRideLogToggle()
    val text = if (state.recording) stringResource(R.string.logging_bar_recording, clock(state.status.seconds)) else stringResource(R.string.logging_bar_idle)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .padding(start = Spacing.x2, end = Spacing.x1),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${stringResource(R.string.logging_bar_label)}  $text",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = if (state.recording) FocTheme.colors.accent else FocTheme.colors.textSecondary,
            style = FocTheme.type.supporting,
        )
        PlainButton(stringResource(if (state.recording) R.string.logging_stop_recording else R.string.logging_start), toggle)
    }
    Hairline()
}

internal fun clock(seconds: Double): String {
    val s = seconds.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
