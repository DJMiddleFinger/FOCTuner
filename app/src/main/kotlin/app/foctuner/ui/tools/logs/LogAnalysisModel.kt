package app.foctuner.ui.tools.logs

import app.foctuner.logs.GpsTrack
import app.foctuner.logs.LogCsv
import app.foctuner.logs.LogFormatException
import app.foctuner.logs.LogKeys
import app.foctuner.logs.LogTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class TrackColor(val keys: List<String>) {
    Speed(listOf(LogKeys.SPEED_GNSS, LogKeys.SPEED_VESC)),
    Power(listOf(LogKeys.POWER)),
}

data class AnalysisState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val table: LogTable? = null,
    val track: GpsTrack? = null,
    /** Keys of the fields plotted, in the order they are shown. */
    val chosen: List<String> = emptyList(),
    /** The part of the log shown, in seconds from its start. */
    val from: Double = 0.0,
    val to: Double = 0.0,
    val cursor: Double = 0.0,
    val trackColor: TrackColor = TrackColor.Speed,
) {
    val rows: IntRange get() = table?.let { it.rowAt(from)..it.rowAt(to) } ?: IntRange.EMPTY
    val cursorRow: Int get() = table?.rowAt(cursor) ?: 0

    /** The column the track is coloured by, from the first key the log has. */
    fun trackColumn(): Int? = table?.let { t -> trackColor.keys.map(t::indexOf).firstOrNull { it >= 0 } }
}

/** One opened log: parsed off the main thread, with the fields, window and cursor the screen works with. */
class AnalysisModel(private val file: File, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    init {
        scope.launch {
            val table = try {
                withContext(Dispatchers.Default) { LogCsv.parse(file.readBytes()) }
            } catch (e: LogFormatException) {
                null
            } catch (e: IOException) {
                null
            }
            if (table == null || table.rowCount == 0) {
                _state.value = AnalysisState(loading = false, failed = true)
                return@launch
            }
            val track = withContext(Dispatchers.Default) { GpsTrack.from(table) }
            _state.value = AnalysisState(
                loading = false,
                table = table,
                track = track,
                chosen = defaultFields(table),
                to = table.durationSeconds,
                cursor = table.durationSeconds / 2,
            )
        }
    }

    fun toggle(key: String) = _state.update { it.copy(chosen = if (key in it.chosen) it.chosen - key else it.chosen + key) }

    fun setWindow(from: Double, to: Double) = _state.update { it.copy(from = from, to = to, cursor = it.cursor.coerceIn(from, to)) }

    fun setCursor(t: Double) = _state.update { it.copy(cursor = t.coerceIn(it.from, it.to)) }

    fun setTrackColor(color: TrackColor) = _state.update { it.copy(trackColor = color) }

    private fun defaultFields(table: LogTable): List<String> {
        val wanted = listOf(LogKeys.SPEED_VESC, LogKeys.POWER, LogKeys.VOLTAGE, LogKeys.SPEED_GNSS).filter { table.indexOf(it) >= 0 }
        return wanted.ifEmpty { table.fields.filter { !it.timestamp }.map { it.key } }.take(DEFAULT_FIELDS)
    }

    private companion object {
        const val DEFAULT_FIELDS = 3
    }
}
