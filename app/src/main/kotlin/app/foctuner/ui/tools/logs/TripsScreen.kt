package app.foctuner.ui.tools.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.logging.TripHub
import app.foctuner.logs.Trip
import app.foctuner.prefs.Units
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.prefs.appPreferences
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.parts.ConfirmDialog
import app.foctuner.ui.tools.parts.ReadingRow
import app.foctuner.vesc.VescException
import app.foctuner.vesc.setOdometer
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import app.foctuner.ui.tools.ToolContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class OdometerAction { Write, Adopt }

/** RT Data's Trips tab: lifetime totals and trip history of the connected controller, which the app keeps across firmware updates. */
@Composable
fun TripsScreen(tools: ToolContext? = null) {
    val state by TripHub.state.collectAsStateWithLifecycle()
    val u = appPreferences().units
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<OdometerAction?>(null) }
    val life = state.ledger.lifetime
    fun distance(m: Double) = (m / 1000.0 * u.perKm).fixed(1)

    if (state.uuid == null) {
        Text(stringResource(R.string.trips_none), Modifier.padding(Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
        return
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        item { SectionHeader(stringResource(R.string.trips_section_lifetime)) }
        item {
            ReadingRow(stringResource(R.string.trips_distance), distance(life.distance), u.distance)
            ReadingRow(stringResource(R.string.trips_energy_used), life.whUsed.fixed(1), "Wh")
            ReadingRow(stringResource(R.string.trips_energy_regen), life.whRegen.fixed(1), "Wh")
            ReadingRow(stringResource(R.string.trips_riding_time), duration(life.movingMs), "")
            ReadingRow(stringResource(R.string.trips_count), state.ledger.trips.size.toString())
        }
        item { SectionHeader(stringResource(R.string.trips_section_odometer)) }
        item {
            ReadingRow(stringResource(R.string.trips_odometer_app), distance(life.distance), u.distance)
            ReadingRow(stringResource(R.string.trips_odometer_controller), state.controllerOdometer?.let { distance(it.toDouble()) }, u.distance)
            Text(stringResource(R.string.trips_odometer_note), Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.x1), horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                PlainButton(stringResource(R.string.trips_write), { confirm = OdometerAction.Write }, enabled = tools != null && state.controllerOdometer != null)
                PlainButton(stringResource(R.string.trips_adopt), { confirm = OdometerAction.Adopt }, enabled = state.controllerOdometer != null)
            }
        }
        state.current?.let { trip ->
            item { SectionHeader(stringResource(R.string.trips_section_current)) }
            item {
                ReadingRow(stringResource(R.string.trips_distance), distance(trip.distance), u.distance)
                ReadingRow(stringResource(R.string.trips_duration), duration(System.currentTimeMillis() - trip.startMs), "")
                ReadingRow(stringResource(R.string.trips_max_speed), (trip.maxSpeed * 3.6 * u.perKm).fixed(1), u.speed)
            }
        }
        item { SectionHeader(stringResource(R.string.trips_section_history)) }
        if (state.ledger.trips.isEmpty()) {
            item { Text(stringResource(R.string.trips_empty), Modifier.padding(horizontal = Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting) }
        }
        items(state.ledger.trips.asReversed(), key = { it.startMs }) { trip -> TripRow(trip, u) }
    }

    confirm?.let { action ->
        val meters = if (action == OdometerAction.Write) life.distance else state.controllerOdometer?.toDouble() ?: 0.0
        ConfirmDialog(
            title = stringResource(if (action == OdometerAction.Write) R.string.trips_write_title else R.string.trips_adopt_title),
            body = stringResource(if (action == OdometerAction.Write) R.string.trips_write_body else R.string.trips_adopt_body, distance(meters), u.distance),
            confirm = stringResource(if (action == OdometerAction.Write) R.string.trips_write else R.string.trips_adopt),
            dismiss = stringResource(R.string.logging_cancel),
            onConfirm = {
                confirm = null
                if (action == OdometerAction.Adopt) {
                    TripHub.adoptControllerOdometer()
                } else {
                    tools?.let { t ->
                        scope.launch {
                            try {
                                t.client.setOdometer(life.distance.toLong().coerceIn(0, 0xFFFFFFFFL))
                            } catch (e: VescException) {
                                // The next sample shows the controller's unchanged odometer.
                            }
                        }
                    }
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun TripRow(trip: Trip, u: Units) {
    val formatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    val title = formatter.format(Instant.ofEpochMilli(trip.startMs).atZone(ZoneId.systemDefault()))
    val line = stringResource(
        R.string.trips_line,
        (trip.distance / 1000.0 * u.perKm).fixed(2), u.distance, duration(trip.durationMs),
        trip.whUsed.fixed(1), trip.whRegen.fixed(1), (trip.maxSpeed * 3.6 * u.perKm).fixed(1), u.speed,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.twoLineRow)
            .padding(horizontal = Spacing.x2, vertical = Spacing.x1)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $line" },
    ) {
        Text(title, color = FocTheme.colors.text, style = FocTheme.type.body)
        Text(line, color = FocTheme.colors.textSecondary, style = FocTheme.type.code)
        Hairline(Modifier.padding(top = Spacing.x1))
    }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
