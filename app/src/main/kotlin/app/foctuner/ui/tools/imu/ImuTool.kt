package app.foctuner.ui.tools.imu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.components.Sparkline
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.Meter
import app.foctuner.ui.tools.parts.MeterRow
import app.foctuner.vesc.Axes
import app.foctuner.vesc.ImuData
import app.foctuner.vesc.VescException
import app.foctuner.vesc.imu
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class ImuState(
    val data: ImuData? = null,
    /** Recent pitch in degrees, oldest first. */
    val pitchTrend: List<Float> = emptyList(),
    val rollTrend: List<Float> = emptyList(),
    val useYaw: Boolean = false,
    val failed: Boolean = false,
)

/** VESC Tool's IMU page: live attitude and the raw sensors of the selected controller. */
class ImuModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(ImuState())
    val state: StateFlow<ImuState> = _state.asStateFlow()

    fun setUseYaw(on: Boolean) = _state.update { it.copy(useYaw = on) }

    suspend fun refresh() {
        try {
            val data = context.controller.imu()
            _state.update {
                it.copy(
                    data = data,
                    pitchTrend = (it.pitchTrend + data.pitch * DEG).takeLast(TREND),
                    rollTrend = (it.rollTrend + data.roll * DEG).takeLast(TREND),
                    failed = false,
                )
            }
        } catch (e: VescException) {
            _state.update { it.copy(failed = true) }
        }
    }
}

@Composable
fun ImuTool(context: ToolContext) {
    val model = context.model { ImuModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) {
        while (true) {
            model.refresh()
            delay(POLL_MS)
        }
    }
    ImuContent(state, model::setUseYaw)
}

@Composable
private fun ImuContent(state: ImuState, onUseYaw: (Boolean) -> Unit) {
    val colors = FocTheme.colors
    val d = state.data
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.failed) Notice(stringResource(R.string.imu_no_reply), tone = NoticeTone.Warning)
        val description = d?.let { stringResource(R.string.imu_attitude_description, (it.roll * DEG).fixed(0), (it.pitch * DEG).fixed(0)) }.orEmpty()
        AttitudeView(
            roll = d?.roll ?: 0f,
            pitch = d?.pitch ?: 0f,
            yaw = if (state.useYaw) d?.yaw ?: 0f else 0f,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .semantics { contentDescription = description },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.row)
                .toggleable(value = state.useYaw, role = Role.Switch, onValueChange = onUseYaw)
                .padding(horizontal = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.imu_use_yaw), Modifier.weight(1f), color = colors.text, style = FocTheme.type.body)
            Switch(
                checked = state.useYaw,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.outline,
                    uncheckedTrackColor = colors.surface,
                    uncheckedBorderColor = colors.outline,
                ),
            )
        }
        Hairline()

        SectionHeader(stringResource(R.string.imu_section_attitude))
        MeterRow(
            Meter(stringResource(R.string.imu_roll), d?.let { (it.roll * DEG).fixed(1) }, "°"),
            Meter(stringResource(R.string.imu_pitch), d?.let { (it.pitch * DEG).fixed(1) }, "°"),
            Meter(stringResource(R.string.imu_yaw), d?.let { (it.yaw * DEG).fixed(1) }, "°"),
        )
        Trend(stringResource(R.string.imu_roll), state.rollTrend)
        Trend(stringResource(R.string.imu_pitch), state.pitchTrend)
        Hairline()

        AxesSection(R.string.imu_section_accel, d?.accel, 3, "g")
        AxesSection(R.string.imu_section_gyro, d?.gyro, 1, "°/s")
        AxesSection(R.string.imu_section_mag, d?.magnetometer, 1, "µT")

        SectionHeader(stringResource(R.string.imu_section_quaternion))
        val q = d?.quaternion
        MeterRow(
            Meter("w", q?.w?.fixed(3)),
            Meter("x", q?.x?.fixed(3)),
            Meter("y", q?.y?.fixed(3)),
            Meter("z", q?.z?.fixed(3)),
        )
        Hairline()
    }
}

@Composable
private fun AxesSection(title: Int, axes: Axes?, decimals: Int, unit: String) {
    SectionHeader(stringResource(title))
    MeterRow(
        Meter("x", axes?.x?.fixed(decimals), unit),
        Meter("y", axes?.y?.fixed(decimals), unit),
        Meter("z", axes?.z?.fixed(decimals), unit),
    )
    Hairline()
}

@Composable
private fun Trend(label: String, samples: List<Float>) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x2, vertical = Spacing.half),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(0.25f), color = FocTheme.colors.textSecondary, style = FocTheme.type.caption)
        Sparkline(samples, TREND, minSpan = 4f, modifier = Modifier
            .weight(0.75f)
            .height(32.dp))
    }
}

private const val DEG = 57.29578f
private const val POLL_MS = 50L
private const val TREND = 200
