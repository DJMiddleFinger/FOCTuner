package app.foctuner.ui.tools.route

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.BuildConfig
import app.foctuner.R
import app.foctuner.logging.DemoFixSource
import app.foctuner.logging.FixSource
import app.foctuner.logging.GpsFixSource
import app.foctuner.logging.TripStore
import app.foctuner.logs.RangeEstimate
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.Meter
import app.foctuner.ui.tools.parts.MeterRow
import app.foctuner.ui.tools.setup.parseNumber
import app.foctuner.vesc.firmware.archive.UrlConnectionFetcher
import app.foctuner.vesc.setupValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.io.IOException
import java.net.URLEncoder

@Immutable
data class Route(val points: List<GeoPoint>, val meters: Double, val seconds: Double, val steps: List<String>)

@Immutable
data class RouteState(
    val start: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val route: Route? = null,
    /** Wh/km from this controller's trip history, null until there is enough of it. */
    val historyWhPerKm: Double? = null,
    val batteryWh: Double? = null,
    val batteryLevel: Double? = null,
    val busy: Boolean = false,
    val failed: String? = null,
)

/**
 * Routes from the phone's position to a destination picked on the map or searched by name, with the battery left
 * on arrival from this vehicle's own consumption. Map tiles from OpenStreetMap, bike routes from the public OSRM
 * server of FOSSGIS (routing.openstreetmap.de), search from Nominatim: all need the internet.
 */
class RouteModel(private val context: ToolContext, private val trips: TripStore) {
    private val http = UrlConnectionFetcher(userAgent = "FOCTuner/${BuildConfig.VERSION_NAME} (Android; github.com/djmiddlefinger/foctuner)")
    private val _state = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = _state.asStateFlow()
    private var fixes: FixSource? = null

    fun start(android: Context) {
        if (fixes != null) return
        fixes = if (context.demo) DemoFixSource() else GpsFixSource(android)
        context.scope.launch {
            val history = context.firmware.uuid.takeIf { it.isNotEmpty() }?.let { uuid ->
                withContext(Dispatchers.IO) { RangeEstimate.whPerKm(trips.load(uuid).lifetime) }
            }
            _state.update { it.copy(historyWhPerKm = history) }
            while (true) {
                fixes?.latest()?.let { f -> _state.update { it.copy(start = GeoPoint(f.latitude, f.longitude)) } }
                runCatching { context.controller.setupValues() }.getOrNull()?.let { v ->
                    _state.update { it.copy(batteryWh = v.batteryWattHours.toDouble(), batteryLevel = v.batteryLevel.toDouble()) }
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
        routeTo(GeoPoint(hit.getString("lat").toDouble(), hit.getString("lon").toDouble()))
    }

    fun pick(point: GeoPoint) = work { routeTo(point) }

    private suspend fun routeTo(destination: GeoPoint) {
        _state.update { it.copy(destination = destination, route = null) }
        val start = _state.value.start ?: throw IOException("No GPS position yet. Wait for a fix outdoors, then pick again.")
        val url = "https://routing.openstreetmap.de/routed-bike/route/v1/driving/" +
            "${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}?overview=full&geometries=geojson&steps=true"
        _state.update { it.copy(route = parseOsrm(fetch(url))) }
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

/** OSRM's route reply: the first route's line, length, time and one readable line per step. */
internal fun parseOsrm(json: String): Route {
    val root = JSONObject(json)
    if (root.optString("code") != "Ok") throw IOException(root.optString("message", "No route found"))
    val route = root.getJSONArray("routes").getJSONObject(0)
    val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
    val points = List(coords.length()) { coords.getJSONArray(it).let { c -> GeoPoint(c.getDouble(1), c.getDouble(0)) } }
    val steps = ArrayList<String>()
    val legs = route.getJSONArray("legs")
    for (l in 0 until legs.length()) {
        val s = legs.getJSONObject(l).getJSONArray("steps")
        for (i in 0 until s.length()) {
            val step = s.getJSONObject(i)
            val m = step.getJSONObject("maneuver")
            val type = m.getString("type")
            val name = step.optString("name").ifBlank { "the path" }
            val turn = listOfNotNull(type.replaceFirstChar(Char::uppercase), m.optString("modifier").takeIf { it.isNotBlank() }).joinToString(" ")
            steps += when (type) {
                "depart" -> "Start on $name"
                "arrive" -> "Arrive"
                "new name", "continue" -> "Continue on $name"
                else -> "$turn onto $name"
            } + if (type != "arrive") " (${step.getDouble("distance").toInt()} m)" else ""
        }
    }
    return Route(points, route.getDouble("distance"), route.getDouble("duration"), steps)
}

@Composable
fun RouteTool(context: ToolContext) {
    val android = LocalContext.current
    val model = context.model { RouteModel(context, TripStore(android)) }
    val state by model.state.collectAsStateWithLifecycle()
    var allowed by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(android, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(allowed) { if (allowed || context.demo) model.start(android) else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    DisposableEffect(model) { onDispose { model.stop() } }
    var query by rememberSaveable { mutableStateOf("") }
    var whText by rememberSaveable { mutableStateOf("") }
    val colors = FocTheme.colors

    Column {
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
        RouteMap(state, model::pick, Modifier.fillMaxWidth().height(320.dp))
        if (!allowed && !context.demo) Notice(stringResource(R.string.route_no_permission), tone = NoticeTone.Warning)
        state.failed?.let { Notice(stringResource(R.string.route_failed), body = it, tone = NoticeTone.Warning) }

        val route = state.route
        val whPerKm = parseNumber(whText)?.toDouble()?.takeIf { it > 0 } ?: state.historyWhPerKm ?: DEFAULT_WH_PER_KM
        if (route != null) {
            val km = route.meters / 1000
            val arrival = state.batteryWh?.let { wh -> state.batteryLevel?.let { RangeEstimate.arrivalPercent(wh, it, km, whPerKm) } }
            MeterRow(
                Meter(stringResource(R.string.route_distance), km.fixed(1), "km"),
                Meter(stringResource(R.string.route_time), (route.seconds / 60).fixed(0), "min"),
                Meter(stringResource(R.string.route_arrival), arrival?.fixed(0), "%"),
            )
            when {
                arrival == null -> Notice(stringResource(R.string.route_no_battery))
                arrival < 0 -> Notice(stringResource(R.string.route_short), tone = NoticeTone.Fault)
                arrival < RESERVE -> Notice(stringResource(R.string.route_low), tone = NoticeTone.Warning)
            }
        }
        OutlinedTextField(
            whText, { whText = it }, Modifier.fillMaxWidth().padding(horizontal = Spacing.x2), singleLine = true,
            label = { Text(stringResource(R.string.route_consumption)) },
            placeholder = { Text(whPerKm.fixed(1)) },
            supportingText = { Text(stringResource(if (state.historyWhPerKm != null) R.string.route_from_history else R.string.route_default)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Hairline()
        LazyColumn {
            items(route?.steps.orEmpty()) { step ->
                Text(step, Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.half), style = FocTheme.type.body, color = colors.text)
            }
        }
    }
}

@Composable
private fun RouteMap(state: RouteState, onPick: (GeoPoint) -> Unit, modifier: Modifier) {
    val pick = androidx.compose.runtime.rememberUpdatedState(onPick)
    AndroidView(
        factory = { ctx ->
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName
                osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
                osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
            }
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
                overlays.add(CopyrightOverlay(ctx))
                overlays.add(
                    MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint) = false
                        override fun longPressHelper(p: GeoPoint): Boolean {
                            pick.value(p)
                            return true
                        }
                    }),
                )
            }
        },
        modifier = modifier,
        update = { map ->
            map.overlays.removeAll { it is Polyline || it is Marker }
            state.route?.let { r -> map.overlays.add(Polyline(map).apply { setPoints(r.points); outlinePaint.strokeWidth = 10f }) }
            state.start?.let { map.overlays.add(Marker(map).apply { position = it; title = "You" }) }
            state.destination?.let { map.overlays.add(Marker(map).apply { position = it; title = "Destination" }) }
            if (map.tag == null) state.start?.let { map.controller.setCenter(it); map.tag = true }
            map.invalidate()
        },
        onRelease = { it.onDetach() },
    )
}

/** Typical small electric vehicle until this one has ridden enough to know better; the field overrides it. */
private const val DEFAULT_WH_PER_KM = 15.0
private const val RESERVE = 10.0
