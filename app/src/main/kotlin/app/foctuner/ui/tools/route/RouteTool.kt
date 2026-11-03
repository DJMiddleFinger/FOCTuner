package app.foctuner.ui.tools.route

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.BuildConfig
import app.foctuner.R
import app.foctuner.logging.DemoFixSource
import app.foctuner.logging.FixSource
import app.foctuner.logging.GpsFixSource
import app.foctuner.logging.TripStore
import app.foctuner.logs.RangeEstimate
import app.foctuner.prefs.AppPreferences
import app.foctuner.prefs.PreferencesStore
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.Meter
import app.foctuner.ui.tools.parts.MeterRow
import app.foctuner.ui.tools.ride.tripWhPerKm
import app.foctuner.ui.tools.setup.SwitchRow
import app.foctuner.ui.tools.setup.parseNumber
import app.foctuner.vesc.firmware.archive.UrlConnectionFetcher
import app.foctuner.vesc.setupValues
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

@Immutable
data class Place(val latitude: Double, val longitude: Double)

/** [climb] and [descent] in metres, null when the height lookup failed (the estimate then ignores hills). */
@Immutable
data class Route(val line: List<Place>, val meters: Double, val seconds: Double, val climb: Double? = null, val descent: Double? = null)

@Immutable
data class RouteState(
    val start: Place? = null,
    val destination: Place? = null,
    /** What the search found, so a wrong match is easy to spot. */
    val destinationName: String? = null,
    val route: Route? = null,
    /** Wh/km from this controller's trip history, null until there is enough of it. */
    val historyWhPerKm: Double? = null,
    /** RT Data's trip average since the controllers powered on, null until [MIN_RIDE_KM] is ridden. */
    val rideWhPerKm: Double? = null,
    val rideKm: Double = 0.0,
    val batteryWh: Double? = null,
    val batteryLevel: Double? = null,
    val busy: Boolean = false,
    val failed: String? = null,
)

/**
 * Routes from the phone's position to a searched destination and estimates the battery left on arrival from this
 * vehicle's own consumption and the route's climbs; navigation itself is handed to Google Maps. Search from
 * Nominatim, bike routes from the public OSRM server of FOSSGIS (routing.openstreetmap.de), heights from
 * Open-Meteo's elevation API: all need the internet.
 */
class RouteModel(private val context: ToolContext, private val trips: TripStore, private val prefs: PreferencesStore) {
    private val http = UrlConnectionFetcher(userAgent = "FOCTuner/${BuildConfig.VERSION_NAME} (Android; github.com/djmiddlefinger/foctuner)")
    private val _state = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = _state.asStateFlow()
    val preferences: Flow<AppPreferences> = prefs.preferences
    private var fixes: FixSource? = null

    fun editPreferences(change: (AppPreferences) -> AppPreferences) {
        context.scope.launch { prefs.update(change) }
    }

    fun start(android: Context) {
        if (fixes != null) return
        fixes = if (context.demo) DemoFixSource() else GpsFixSource(android)
        context.scope.launch {
            val history = context.firmware.uuid.takeIf { it.isNotEmpty() }?.let { uuid ->
                withContext(Dispatchers.IO) { RangeEstimate.whPerKm(trips.load(uuid).lifetime) }
            }
            _state.update { it.copy(historyWhPerKm = history) }
            while (true) {
                fixes?.latest()?.let { f -> _state.update { it.copy(start = Place(f.latitude, f.longitude)) } }
                runCatching { context.controller.setupValues() }.getOrNull()?.let { v ->
                    _state.update {
                        it.copy(
                            batteryWh = v.batteryWattHours.toDouble(),
                            batteryLevel = v.batteryLevel.toDouble(),
                            rideWhPerKm = v.tripWhPerKm(MIN_RIDE_KM)?.toDouble()?.takeIf { wh -> wh > 0 },
                            rideKm = v.distanceAbs / 1000.0,
                        )
                    }
                }
                delay(2_000)
            }
        }
    }

    fun stop() {
        fixes?.close()
        fixes = null
    }

    fun search(query: String) = work {
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(query.trim(), "UTF-8")
        val hits = JSONArray(fetch(url))
        if (hits.length() == 0) throw IOException("Nothing found for \"$query\"")
        val hit = hits.getJSONObject(0)
        val destination = Place(hit.getString("lat").toDouble(), hit.getString("lon").toDouble())
        _state.update { it.copy(destination = destination, destinationName = hit.optString("display_name").ifBlank { null }, route = null) }
        val start = _state.value.start ?: throw IOException("No GPS position yet. Wait for a fix outdoors, then search again.")
        val route = parseOsrm(
            fetch(
                "https://routing.openstreetmap.de/routed-bike/route/v1/driving/" +
                    "${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}?overview=full&geometries=geojson",
            ),
        )
        _state.update { it.copy(route = route) }
        val hills = try {
            heights(route.line)?.let(RangeEstimate::climbAndDescent)
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        }
        if (hills != null) _state.update { it.copy(route = route.copy(climb = hills.first, descent = hills.second)) }
    }

    /** Ground heights at up to 100 points along [line], one Open-Meteo request. */
    private suspend fun heights(line: List<Place>): List<Double>? {
        val points = RangeEstimate.samples(line.map { it.latitude to it.longitude })
        if (points.size < 2) return null
        val url = "https://api.open-meteo.com/v1/elevation?latitude=" + points.joinToString(",") { "%.5f".format(java.util.Locale.ROOT, it.first) } +
            "&longitude=" + points.joinToString(",") { "%.5f".format(java.util.Locale.ROOT, it.second) }
        val heights = JSONObject(fetch(url)).getJSONArray("elevation")
        return List(heights.length()) { heights.getDouble(it) }
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) { http.get(url, maxBytes = 4 * 1024 * 1024).decodeToString() }

    private fun work(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, failed = null) }
        context.scope.launch {
            try {
                block()
            } catch (e: IOException) {
                _state.update { it.copy(failed = e.message ?: e.javaClass.simpleName) }
            } catch (e: JSONException) {
                _state.update { it.copy(failed = "Unexpected answer from the server") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

/** OSRM's route reply: the first route's line, length and time. */
internal fun parseOsrm(json: String): Route {
    val root = JSONObject(json)
    if (root.optString("code") != "Ok") throw IOException(root.optString("message", "No route found"))
    val route = root.getJSONArray("routes").getJSONObject(0)
    val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
    val line = List(coords.length()) { coords.getJSONArray(it).let { c -> Place(c.getDouble(1), c.getDouble(0)) } }
    return Route(line, route.getDouble("distance"), route.getDouble("duration"))
}

/** Where the Wh/km comes from, in the order they are tried. */
enum class Consumption(@param:StringRes val source: Int) {
    Entered(R.string.route_source_entered),
    Ride(R.string.route_source_ride),
    History(R.string.route_source_history),
    Default(R.string.route_source_default),
}

/** The Wh/km to plan with and where it came from: the user's own figure when on, else measured, else typical. */
internal fun consumption(prefs: AppPreferences, state: RouteState): Pair<Double, Consumption> {
    val entered = prefs.routeWhPerKm?.toDouble()?.takeIf { prefs.routeOverride && it > 0 }
    return when {
        entered != null -> entered to Consumption.Entered
        state.rideWhPerKm != null -> state.rideWhPerKm to Consumption.Ride
        state.historyWhPerKm != null -> state.historyWhPerKm to Consumption.History
        else -> DEFAULT_WH_PER_KM to Consumption.Default
    }
}

/** Opens Google Maps (the app when installed, the website otherwise) with bike directions to [destination]. */
internal fun googleMapsUrl(destination: Place): String =
    "https://www.google.com/maps/dir/?api=1&travelmode=bicycling&destination=${destination.latitude},${destination.longitude}"

@Composable
fun RouteTool(context: ToolContext) {
    val android = LocalContext.current
    val model = context.model { RouteModel(context, TripStore(android), PreferencesStore(android)) }
    val state by model.state.collectAsStateWithLifecycle()
    val prefs by model.preferences.collectAsStateWithLifecycle(AppPreferences())
    var allowed by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(android, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(allowed) { if (allowed || context.demo) model.start(android) else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    DisposableEffect(model) { onDispose { model.stop() } }
    var query by rememberSaveable { mutableStateOf("") }
    var whText by rememberSaveable { mutableStateOf<String?>(null) }
    var massText by rememberSaveable { mutableStateOf<String?>(null) }
    val uri = LocalUriHandler.current
    val colors = FocTheme.colors

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query, { query = it }, Modifier.weight(1f), singleLine = true,
                label = { Text(stringResource(R.string.route_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) model.search(query) }),
            )
            PlainButton(stringResource(R.string.route_go), { model.search(query) }, enabled = query.isNotBlank() && !state.busy)
        }
        Text(stringResource(R.string.route_hint), Modifier.padding(horizontal = Spacing.x2), style = FocTheme.type.caption, color = colors.textTertiary)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.outline)
        if (!allowed && !context.demo) Notice(stringResource(R.string.route_no_permission), tone = NoticeTone.Warning)
        state.failed?.let { Notice(stringResource(R.string.route_failed), body = it, tone = NoticeTone.Warning) }
        state.destinationName?.let {
            Text(it, Modifier.padding(Spacing.x2), style = FocTheme.type.supporting, color = colors.textSecondary)
        }

        val route = state.route
        val (whPerKm, source) = consumption(prefs, state)
        val massKg = prefs.routeMassKg?.toDouble()?.takeIf { prefs.routeOverride && it > 0 } ?: DEFAULT_MASS_KG
        if (route != null) {
            val km = route.meters / 1000
            val hillWh = if (route.climb != null && route.descent != null) RangeEstimate.hillWh(massKg, route.climb, route.descent) else 0.0
            val arrival = state.batteryWh?.let { wh -> state.batteryLevel?.let { RangeEstimate.arrivalPercent(wh, it, km, whPerKm, hillWh) } }
            MeterRow(
                Meter(stringResource(R.string.route_distance), km.fixed(1), "km"),
                Meter(stringResource(R.string.route_time), (route.seconds / 60).fixed(0), "min"),
                Meter(stringResource(R.string.route_climb), route.climb?.fixed(0), "m"),
            )
            MeterRow(
                Meter(stringResource(R.string.route_energy), (km * whPerKm + hillWh).fixed(0), "Wh"),
                Meter(stringResource(R.string.route_arrival), arrival?.fixed(0), "%"),
            )
            when {
                arrival == null -> Notice(stringResource(R.string.route_no_battery))
                arrival < 0 -> Notice(stringResource(R.string.route_short), tone = NoticeTone.Fault)
                arrival < RESERVE -> Notice(stringResource(R.string.route_low), tone = NoticeTone.Warning)
            }
            if (route.climb == null && !state.busy) Notice(stringResource(R.string.route_no_heights))
        }
        state.destination?.let { destination ->
            Row(Modifier.fillMaxWidth().padding(Spacing.x2)) {
                OutlineButton(stringResource(R.string.route_google_maps), { uri.openUri(googleMapsUrl(destination)) }, Modifier.fillMaxWidth())
            }
        }
        Text(
            stringResource(source.source, whPerKm.fixed(1), state.rideKm.fixed(1)) + " " + stringResource(R.string.route_weight_used, massKg.fixed(0)),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = colors.textSecondary,
        )
        SwitchRow(stringResource(R.string.route_override), prefs.routeOverride, { on -> model.editPreferences { it.copy(routeOverride = on) } })
        if (prefs.routeOverride) {
            OutlinedTextField(
                whText ?: prefs.routeWhPerKm?.toDouble()?.fixed(1).orEmpty(),
                { text ->
                    whText = text
                    model.editPreferences { it.copy(routeWhPerKm = parseNumber(text)?.toFloat()?.takeIf { v -> v > 0 }) }
                },
                Modifier.fillMaxWidth().padding(horizontal = Spacing.x2), singleLine = true,
                label = { Text(stringResource(R.string.route_consumption)) },
                supportingText = { Text(stringResource(R.string.route_consumption_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                massText ?: prefs.routeMassKg?.toDouble()?.fixed(0).orEmpty(),
                { text ->
                    massText = text
                    model.editPreferences { it.copy(routeMassKg = parseNumber(text)?.toFloat()?.takeIf { v -> v > 0 }) }
                },
                Modifier.fillMaxWidth().padding(horizontal = Spacing.x2), singleLine = true,
                label = { Text(stringResource(R.string.route_mass)) },
                supportingText = { Text(stringResource(R.string.route_mass_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
    }
}

/** Typical small electric vehicle until this one has ridden enough to know better; the field overrides it. */
private const val DEFAULT_WH_PER_KM = 15.0

/** Rider and vehicle, for the climbing energy; the field overrides it. */
private const val DEFAULT_MASS_KG = 100.0

/** Less than this since power-on and the trip average is mostly the start-up and the first push. */
private const val MIN_RIDE_KM = 1f
private const val RESERVE = 10.0
