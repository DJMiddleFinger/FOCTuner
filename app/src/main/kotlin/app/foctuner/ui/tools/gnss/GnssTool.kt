package app.foctuner.ui.tools.gnss

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.fixed
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ReadingRow
import app.foctuner.vesc.GnssFix
import app.foctuner.vesc.VescException
import app.foctuner.vesc.gnss
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

@Immutable
data class GnssState(val fix: GnssFix? = null, val failed: Boolean = false)

/** The GNSS position the controller reads (COMM_GET_GNSS), as VESC Tool polls it for its log map. */
class GnssModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(GnssState())
    val state: StateFlow<GnssState> = _state.asStateFlow()

    suspend fun refresh() {
        try {
            val fix = context.controller.gnss()
            _state.update { it.copy(fix = fix, failed = false) }
        } catch (e: VescException) {
            _state.update { it.copy(failed = true) }
        }
    }
}

@Composable
fun GnssTool(context: ToolContext) {
    val model = context.model { GnssModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) {
        while (true) {
            model.refresh()
            delay(POLL_MS)
        }
    }
    val f = state.fix
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.failed) Notice(stringResource(R.string.gnss_no_reply), tone = NoticeTone.Warning)
        if (f != null && f.ageSeconds > STALE_S) {
            Notice(stringResource(R.string.gnss_stale, f.ageSeconds.fixed(0)), body = stringResource(R.string.gnss_stale_body), tone = NoticeTone.Warning)
        }
        SectionHeader(stringResource(R.string.gnss_section_position))
        ReadingRow(stringResource(R.string.gnss_latitude), f?.latitude?.fixed(7), "°")
        ReadingRow(stringResource(R.string.gnss_longitude), f?.longitude?.fixed(7), "°")
        ReadingRow(stringResource(R.string.gnss_height), f?.height?.fixed(1), "m")
        ReadingRow(stringResource(R.string.gnss_speed), f?.let { (it.speed * 3.6f).fixed(1) }, "km/h")
        ReadingRow(stringResource(R.string.gnss_hdop), f?.hdop?.fixed(2))
        SectionHeader(stringResource(R.string.gnss_section_time))
        ReadingRow(stringResource(R.string.gnss_date), f?.let { String.format(Locale.ROOT, "%04d-%02d-%02d", it.year, it.month, it.day) })
        ReadingRow(stringResource(R.string.gnss_time), f?.let { utc(it.msToday) }, "UTC")
        ReadingRow(stringResource(R.string.gnss_age), f?.ageSeconds?.fixed(1), "s")
    }
}

private fun utc(ms: Int): String {
    val s = ms / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d.%01d", s / 3600, s / 60 % 60, s % 60, ms % 1000 / 100)
}

private const val POLL_MS = 500L
private const val STALE_S = 5f
