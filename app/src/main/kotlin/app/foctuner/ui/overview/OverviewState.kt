package app.foctuner.ui.overview

import androidx.compose.runtime.Immutable
import app.foctuner.vesc.RealtimeValues

@Immutable
data class OverviewState(
    val values: RealtimeValues,
    val voltageTrend: List<Float> = emptyList(),
    val currentTrend: List<Float> = emptyList(),
    val powerTrend: List<Float> = emptyList(),
    /** Samples in a full trend buffer; the sparklines scroll by one step per sample. */
    val trendCapacity: Int = 120,
    val trendSeconds: Int = 60,
    /** Known once wheel size, gearing and pole pairs are set up. */
    val speedKmh: Float? = null,
    /** Whole seconds since the last reply; 0 while values are fresh. */
    val staleSeconds: Int = 0,
) {
    val power: Float get() = values.voltageInput * values.currentInput
}
