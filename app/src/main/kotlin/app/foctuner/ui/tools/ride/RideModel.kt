package app.foctuner.ui.tools.ride

import androidx.compose.runtime.Immutable
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.ImuData
import app.foctuner.vesc.SetupValues
import app.foctuner.vesc.VescException
import app.foctuner.vesc.imu
import app.foctuner.vesc.setOdometer
import app.foctuner.vesc.setupValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.tan

enum class DistanceUnit(val perKm: Float, val distance: String, val speed: String, val efficiency: String) {
    Metric(1f, "km", "km/h", "Wh/km"),
    Imperial(0.621371192f, "mi", "mph", "Wh/mi"),
}

@Immutable
data class RideState(
    val values: SetupValues? = null,
    /** Incline in percent from the IMU's pitch, as VESC Tool's dashboard shows it. */
    val incline: Float? = null,
    /** Input power divided by speed, low-passed (VESC Tool's instantaneous efficiency), Wh per km. */
    val whPerKmNow: Float = 0f,
    val unit: DistanceUnit = DistanceUnit.Metric,
    val failed: Boolean = false,
    val odometerSet: Boolean = false,
) {
    /** Energy used per km over the trip since boot (VESC Tool's average efficiency). */
    val whPerKmTrip: Float? get() = values?.let { v ->
        val km = v.distanceAbs / 1000f
        if (km < MIN_TRIP_KM) null else (v.wattHours - v.wattHoursCharged) / km
    }

    /** Range in km at the trip's average consumption. */
    val rangeKm: Float? get() = whPerKmTrip?.takeIf { it > 0f }?.let { (values?.batteryWattHours ?: 0f) / it }
}

/**
 * VESC Tool's mobile dashboard (RtDataSetup): vehicle speed, battery, consumption, range, trip and odometer from
 * COMM_GET_VALUES_SETUP, which sums every controller on the bus, plus incline from the IMU's pitch.
 */
class RideModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(RideState())
    val state: StateFlow<RideState> = _state.asStateFlow()

    fun setUnit(unit: DistanceUnit) = _state.update { it.copy(unit = unit) }

    fun setOdometer(km: Float) {
        context.scope.launch {
            try {
                context.controller.setOdometer((km * 1000f).toLong())
                _state.update { it.copy(odometerSet = true) }
            } catch (e: VescException) {
                _state.update { it.copy(failed = true) }
            }
        }
    }

    suspend fun refresh() {
        try {
            val values = context.controller.setupValues()
            val pitch = try {
                context.controller.imu(ImuData.PITCH).pitch
            } catch (e: VescException) {
                null
            }
            val kmh = abs(values.speed) * 3.6f
            val now = if (kmh < MIN_SPEED_KMH) 0f else (values.currentInput * values.voltageInput / kmh).coerceIn(-MAX_WH_KM, MAX_WH_KM)
            _state.update {
                it.copy(
                    values = values,
                    incline = pitch?.let { p -> tan(p) * 100f },
                    whPerKmNow = it.whPerKmNow + (now - it.whPerKmNow) * EFFICIENCY_ALPHA,
                    failed = false,
                )
            }
        } catch (e: VescException) {
            _state.update { it.copy(failed = true) }
        }
    }
}

private const val MIN_TRIP_KM = 0.01f
private const val MIN_SPEED_KMH = 1f
private const val MAX_WH_KM = 60f
private const val EFFICIENCY_ALPHA = 0.1f
