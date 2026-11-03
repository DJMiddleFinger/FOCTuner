package app.foctuner.ui.tools.logs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.logging.StoredLog
import app.foctuner.logs.GpsTrack
import app.foctuner.logs.LogTable
import app.foctuner.logs.decimate
import app.foctuner.logs.stats
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.PageHeader
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.Readout
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.settings.curves.niceTicks
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.parts.ChoiceRow
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One log opened for reading: plots of the chosen fields over a shared time axis, a cursor, statistics and the GPS track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogAnalysisScreen(log: StoredLog, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val model = remember(log.file) { AnalysisModel(log.file, scope) }
    val state by model.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    val title = rememberLogTitle(log.name)
    val table = state.table

    Column(modifier) {
        PageHeader(title, onBack, breadcrumb = stringResource(R.string.tool_logs))
        when {
            state.loading -> Text(stringResource(R.string.logging_analysis_loading), Modifier.padding(Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
            table == null -> Notice(stringResource(R.string.logging_analysis_failed), tone = NoticeTone.Warning)
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    Text(
                        stringResource(R.string.logging_analysis_summary, clock(table.durationSeconds), table.rowCount),
                        Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                        color = FocTheme.colors.textSecondary,
                        style = FocTheme.type.supporting,
                    )
                    val windowLabel = stringResource(R.string.logging_analysis_window_slider)
                    Text(
                        stringResource(R.string.logging_analysis_window, clock(state.from), clock(state.to)),
                        Modifier.padding(horizontal = Spacing.x2),
                        color = FocTheme.colors.textTertiary,
                        style = FocTheme.type.supporting,
                    )
                    RangeSlider(
                        value = state.from.toFloat()..state.to.toFloat(),
                        onValueChange = { model.setWindow(it.start.toDouble(), it.endInclusive.toDouble()) },
                        valueRange = 0f..max(1f, table.durationSeconds.toFloat()),
                        modifier = Modifier
                            .padding(horizontal = Spacing.x2)
                            .semantics { contentDescription = windowLabel },
                    )
                }
                items(state.chosen, key = { it }) { key ->
                    val column = table.indexOf(key)
                    if (column >= 0) PlotRow(table, column, state.rows, state.cursorRow, state.from, state.to, model::setCursor)
                }
                item {
                    PlainButton(stringResource(R.string.logging_fields), { choosing = true }, Modifier.padding(horizontal = Spacing.x1))
                    SectionHeader(stringResource(R.string.logging_section_stats))
                }
                items(state.chosen, key = { "stats-$it" }) { key ->
                    val column = table.indexOf(key)
                    if (column >= 0) StatsRow(table, column, state.rows)
                }
                item { SectionHeader(stringResource(R.string.logging_section_track)) }
                item { TrackSection(table, state.track, state, model::setTrackColor) }
                item { ExportSection(log, table, state.track) }
            }
        }
    }

    if (choosing && table != null) {
        FieldsDialog(table, state.chosen, model::toggle) { choosing = false }
    }
}

@Composable
private fun PlotRow(table: LogTable, column: Int, rows: IntRange, cursorRow: Int, from: Double, to: Double, onCursor: (Double) -> Unit) {
    val field = table.fields[column]
    val values = table.columns[column]
    val stats = table.stats(column, rows)
    val cursorText = formatValue(table, column, cursorRow)
    val min = stats?.min?.fixed(field.precision).orEmpty()
    val max = stats?.max?.fixed(field.precision).orEmpty()
    val description = stringResource(R.string.logging_plot_description, field.name, min, max, field.unit, "$cursorText ${field.unit}".trim())
    val step = (to - from) / CURSOR_STEPS
    val later = stringResource(R.string.logging_cursor_later)
    val earlier = stringResource(R.string.logging_cursor_earlier)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.x2, vertical = Spacing.half)
                .heightIn(min = Spacing.touchTarget),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(field.name, Modifier.weight(1f), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
            Readout(cursorText, field.unit, style = FocTheme.type.value, color = FocTheme.colors.accent)
        }
        val measurer = rememberTextMeasurer()
        val colors = FocTheme.colors
        val labelStyle = FocTheme.type.code.copy(color = colors.textTertiary)
        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.x2)
                .height(PLOT_HEIGHT)
                .background(colors.surface)
                .semantics {
                    contentDescription = description
                    customActions = listOf(
                        CustomAccessibilityAction(later) { onCursor(table.seconds[cursorRow] + step); true },
                        CustomAccessibilityAction(earlier) { onCursor(table.seconds[cursorRow] - step); true },
                    )
                }
                .pointerInput(from, to) { detectTapGestures { onCursor(from + (to - from) * (it.x / size.width)) } }
                .pointerInput(from, to) { detectHorizontalDragGestures { change, _ -> onCursor(from + (to - from) * (change.position.x / size.width)) } },
        ) {
            drawPlot(table, values, rows, cursorRow, measurer, labelStyle, colors.accent, colors.hairline, colors.textSecondary, field.precision)
        }
        Hairline(Modifier.padding(top = Spacing.x1))
    }
}

private fun DrawScope.drawPlot(
    table: LogTable,
    values: DoubleArray,
    rows: IntRange,
    cursorRow: Int,
    measurer: TextMeasurer,
    labelStyle: androidx.compose.ui.text.TextStyle,
    line: Color,
    grid: Color,
    cursorColor: Color,
    precision: Int,
) {
    if (rows.isEmpty()) return
    val t = table.seconds
    var low = Double.MAX_VALUE
    var high = -Double.MAX_VALUE
    for (i in rows) {
        low = min(low, values[i])
        high = max(high, values[i])
    }
    if (high - low < MIN_SPAN) {
        val mid = (high + low) / 2
        low = mid - MIN_SPAN / 2
        high = mid + MIN_SPAN / 2
    }
    val pad = (high - low) * 0.08
    low -= pad
    high += pad
    val t0 = t[rows.first]
    val t1 = max(t[rows.last], t0 + 1e-6)
    fun x(time: Double) = ((time - t0) / (t1 - t0) * size.width).toFloat()
    fun y(v: Double) = (size.height - (v - low) / (high - low) * size.height).toFloat()
    for (tick in niceTicks(low, high, 3)) {
        if (tick < low || tick > high) continue
        drawLine(grid, Offset(0f, y(tick)), Offset(size.width, y(tick)), strokeWidth = 1f)
        drawText(measurer, tick.fixed(precision), Offset(4f, y(tick) - 28f), labelStyle)
    }
    val buckets = (size.width / 2).toInt().coerceAtLeast(1)
    val path = Path()
    var first = true
    for (i in decimate(values, rows.first, rows.last, buckets)) {
        val px = x(t[i])
        val py = y(values[i])
        if (first) path.moveTo(px, py) else path.lineTo(px, py)
        first = false
    }
    drawPath(path, line, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    if (cursorRow in rows) {
        val cx = x(t[cursorRow])
        drawLine(cursorColor, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f)
        drawCircle(cursorColor, 6f, Offset(cx, y(values[cursorRow])))
    }
}

@Composable
private fun StatsRow(table: LogTable, column: Int, rows: IntRange) {
    val field = table.fields[column]
    val s = table.stats(column, rows) ?: return
    val line = stringResource(R.string.logging_stats_line, s.min.fixed(field.precision), s.mean.fixed(field.precision), s.max.fixed(field.precision), field.unit)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.twoLineRow)
            .padding(horizontal = Spacing.x2, vertical = Spacing.x1)
            .semantics(mergeDescendants = true) { contentDescription = "${field.name}. $line" },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(field.name, color = FocTheme.colors.text, style = FocTheme.type.body)
        Text(line, color = FocTheme.colors.textSecondary, style = FocTheme.type.code)
        Hairline(Modifier.padding(top = Spacing.x1))
    }
}

@Composable
private fun TrackSection(table: LogTable, track: GpsTrack?, state: AnalysisState, onColor: (TrackColor) -> Unit) {
    val column = state.trackColumn()
    if (track == null || column == null) {
        Text(stringResource(R.string.logging_track_none), Modifier.padding(Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
        return
    }
    val field = table.fields[column]
    val shown = track.points.indices.filter { track.points[it].row in state.rows }
    val values = shown.map { table.columns[column][track.points[it].row] }
    val low = values.minOrNull() ?: 0.0
    val high = values.maxOrNull() ?: 0.0
    val distanceText = if (track.distance >= 1000) stringResource(R.string.logging_kilometres, (track.distance / 1000).fixed(2)) else stringResource(R.string.logging_metres, track.distance.fixed(0))
    Column {
        Text(stringResource(R.string.logging_track_summary, distanceText, track.points.size), Modifier.padding(horizontal = Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
        ChoiceRow(TrackColor.entries, state.trackColor, { stringResource(if (it == TrackColor.Speed) R.string.logging_track_color_speed else R.string.logging_track_color_power) }, onColor)
        val colors = FocTheme.colors
        val description = stringResource(R.string.logging_track_description, distanceText, field.name, low.fixed(field.precision), high.fixed(field.precision), field.unit)
        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.x2)
                .height(TRACK_HEIGHT)
                .background(colors.surface)
                .semantics { contentDescription = description },
        ) {
            drawTrack(track, shown, values, low, high, state.cursorRow, colors.accent, colors.warning, colors.text, colors.outline)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.x2, vertical = Spacing.half), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${low.fixed(field.precision)} ${field.unit}", color = FocTheme.colors.accent, style = FocTheme.type.code)
            Text("${high.fixed(field.precision)} ${field.unit}", color = FocTheme.colors.warning, style = FocTheme.type.code)
        }
    }
}

private fun DrawScope.drawTrack(
    track: GpsTrack,
    shown: List<Int>,
    values: List<Double>,
    low: Double,
    high: Double,
    cursorRow: Int,
    lowColor: Color,
    highColor: Color,
    endpoint: Color,
    outline: Color,
) {
    if (shown.size < 2) return
    val map = shown.map { track.map[it] }
    val minE = map.minOf { it.east }
    val maxE = map.maxOf { it.east }
    val minN = map.minOf { it.north }
    val maxN = map.maxOf { it.north }
    val margin = 24f
    val scale = min((size.width - 2 * margin) / max(maxE - minE, 1.0), (size.height - 2 * margin) / max(maxN - minN, 1.0)).toFloat()
    val offsetX = (size.width - (maxE - minE).toFloat() * scale) / 2
    val offsetY = (size.height - (maxN - minN).toFloat() * scale) / 2
    fun at(i: Int) = Offset(offsetX + ((map[i].east - minE) * scale).toFloat(), size.height - offsetY - ((map[i].north - minN) * scale).toFloat())
    val span = max(high - low, 1e-9)
    for (i in 1 until map.size) {
        val t = (((values[i] + values[i - 1]) / 2 - low) / span).toFloat().coerceIn(0f, 1f)
        drawLine(lerp(lowColor, highColor, t), at(i - 1), at(i), strokeWidth = 7f, cap = StrokeCap.Round)
    }
    drawCircle(endpoint, 10f, at(0))
    drawCircle(endpoint, 10f, at(map.size - 1), style = Stroke(width = 4f))
    val cursorIndex = shown.indexOfLast { track.points[it].row <= cursorRow }.takeIf { it >= 0 }
    if (cursorIndex != null) drawCircle(outline, 14f, at(cursorIndex), style = Stroke(width = 4f))
}

@Composable
private fun ExportSection(log: StoredLog, table: LogTable, track: GpsTrack?) {
    val context = LocalContext.current
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { LogExport.save(context, it, log.file.readBytes()) } }
    val saveGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri -> uri?.let { LogExport.save(context, it, LogExport.gpx(log, table, track).orEmpty().encodeToByteArray()) } }
    Column {
        SectionHeader(stringResource(R.string.logging_section_export))
        Text(stringResource(R.string.logging_export_note), Modifier.padding(horizontal = Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.x1), horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            PlainButton(stringResource(R.string.logging_export_share_csv), { LogExport.share(context, log.file, "text/csv") })
            PlainButton(stringResource(R.string.logging_export_save_csv), { saveCsv.launch(log.name) })
        }
        if (track != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.x1), horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                PlainButton(stringResource(R.string.logging_export_share_gpx), { LogExport.gpx(log, table, track)?.let { LogExport.shareText(context, log.name.substringBeforeLast('.') + ".gpx", it, "application/gpx+xml") } })
                PlainButton(stringResource(R.string.logging_export_save_gpx), { saveGpx.launch(log.name.substringBeforeLast('.') + ".gpx") })
            }
        }
    }
}

@Composable
private fun FieldsDialog(table: LogTable, chosen: List<String>, onToggle: (String) -> Unit, onDone: () -> Unit) {
    val colors = FocTheme.colors
    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { PlainButton(stringResource(R.string.logging_fields_done), onDone) },
        title = { Text(stringResource(R.string.logging_fields_title), style = FocTheme.type.title) },
        text = {
            LazyColumn {
                items(table.fields.filter { !it.timestamp }, key = { it.key }) { field ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchTarget)
                            .clickable(role = Role.Checkbox) { onToggle(field.key) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(field.key in chosen, null, Modifier.padding(end = Spacing.x1))
                        Text(if (field.unit.isEmpty()) field.name else "${field.name} (${field.unit})", color = colors.text, style = FocTheme.type.body)
                    }
                }
            }
        },
        shape = MaterialTheme.shapes.small,
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
    )
}

/** The value of [column] at [row] as the log's field says to show it. */
internal fun formatValue(table: LogTable, column: Int, row: Int): String {
    val field = table.fields[column]
    val v = table.columns[column][row]
    return if (field.timestamp) clock(v) else if (field.relativeToFirst) (v - table.columns[column][0]).fixed(field.precision) else v.fixed(field.precision)
}

private val PLOT_HEIGHT = 120.dp
private val TRACK_HEIGHT = 280.dp
private const val MIN_SPAN = 1e-6
private const val CURSOR_STEPS = 50.0
