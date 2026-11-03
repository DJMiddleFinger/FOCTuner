package app.foctuner.ui.ridedash

import android.content.Context
import android.os.SystemClock
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.configtools.ConfigStorage
import app.foctuner.vesc.ConfigProfile
import app.foctuner.vesc.FaultCode
import app.foctuner.vesc.RealtimeValues
import app.foctuner.vesc.applyProfile
import app.foctuner.vesc.readMatchingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import kotlin.math.PI
import kotlin.math.abs

/** Whether the controller took the last mode change. */
sealed interface ModeApplyStatus {
    data object Idle : ModeApplyStatus
    data class Applying(val modeId: String) : ModeApplyStatus
    data class Applied(val modeId: String) : ModeApplyStatus
    data class Failed(val modeId: String) : ModeApplyStatus
}

/** The ride view's own choices, kept on the phone; everything else comes from the controller's motor config. */
@Serializable
data class RidePrefs(
    val appearance: AppAppearance = AppAppearance.CLASSIC,
    val rideStyle: RideStyle = RideStyle.CLASSIC,
    val rideTheme: RideTheme = RideTheme.DARK,
    val launchAnimation: Boolean = false,
    val heatWarnControllerC: Double = 60.0,
    val heatWarnMotorC: Double = 60.0,
    val batteryStabilize: Boolean = true,
    val dualController: Boolean = false,
    val canSlaveId: Int = 1,
)

/**
 * What VESC Dash's view model gave its ride screens, fed from this connection: telemetry from COMM_GET_VALUES (and
 * the second controller's over CAN when [RidePrefs.dualController]), the vehicle from the motor config's `si_*` and
 * limits, and the drive modes from the Profiles tool, switched with COMM_SET_MCCONF_TEMP_SETUP (not stored).
 */
class RideHub(private val context: ToolContext, android: Context, private val imperial: Boolean) {
    private val prefs = android.getSharedPreferences("ride_view", Context.MODE_PRIVATE)
    private val storage = ConfigStorage.of(android)

    private val _prefs = MutableStateFlow(loadPrefs())
    val ridePrefs: StateFlow<RidePrefs> = _prefs.asStateFlow()

    private val _vehicle = MutableStateFlow(VehicleSettings(imperial = imperial).with(_prefs.value))
    val vehicle: StateFlow<VehicleSettings> = _vehicle.asStateFlow()

    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()
    private val _history = MutableStateFlow<List<Telemetry>>(emptyList())
    val history: StateFlow<List<Telemetry>> = _history.asStateFlow()
    private val _stale = MutableStateFlow(false)
    val stale: StateFlow<Boolean> = _stale.asStateFlow()
    private val _rideTimeMs = MutableStateFlow(0L)
    val rideTimeMs: StateFlow<Long> = _rideTimeMs.asStateFlow()
    private val _accel = MutableStateFlow(AccelRun())
    val accel: StateFlow<AccelRun> = _accel.asStateFlow()

    private var profiles: List<ConfigProfile> = emptyList()
    private val _modes = MutableStateFlow<List<DriveMode>>(emptyList())
    val modes: StateFlow<List<DriveMode>> = _modes.asStateFlow()
    private val _activeModeId = MutableStateFlow<String?>(null)
    val activeModeId: StateFlow<String?> = _activeModeId.asStateFlow()
    private val _modeStatus = MutableStateFlow<ModeApplyStatus>(ModeApplyStatus.Idle)
    val modeStatus: StateFlow<ModeApplyStatus> = _modeStatus.asStateFlow()

    private val _rideLayout = MutableStateFlow(loadLayout())
    val rideLayout: StateFlow<RideLayout> = _rideLayout.asStateFlow()
    private val _layoutEditorOpen = MutableStateFlow(false)
    val layoutEditorOpen: StateFlow<Boolean> = _layoutEditorOpen.asStateFlow()

    private val estimator = BatteryEstimator()
    private var polling: Job? = null

    /** Reads the motor config and the profiles once, then polls until [stop]. */
    fun start() {
        if (polling?.isActive == true) return
        polling = context.scope.launch {
            try {
                val mcconf = context.controller.readMatchingConfig(ConfigKind.MCCONF, context.firmware.version)
                _vehicle.value = vehicleFrom(mcconf, imperial).with(_prefs.value)
                profiles = withContext(Dispatchers.IO) { storage.profiles() }
                _modes.value = profiles.mapIndexed { i, p -> p.toDriveMode(i, _vehicle.value) }
                _activeModeId.value = profiles.firstOrNull { it.isInUse(mcconf) }?.name
            } catch (e: IOException) {
                // The ride view still shows speed and battery from the defaults; modes stay empty.
            }
            var lastAnswer = SystemClock.elapsedRealtime()
            while (true) {
                val v = _vehicle.value
                try {
                    val local = context.controller.values()
                    val remote = if (v.dualController) runCatching { context.client.can(v.canSlaveId).values() }.getOrNull() else null
                    publish(telemetryOf(local, remote, SystemClock.elapsedRealtime()))
                    lastAnswer = SystemClock.elapsedRealtime()
                    _stale.value = false
                } catch (e: IOException) {
                    if (SystemClock.elapsedRealtime() - lastAnswer > STALE_MS) _stale.value = true
                }
                delay(1000L / v.pollHz.coerceIn(1, 30))
            }
        }
    }

    fun stop() {
        polling?.cancel()
        polling = null
    }

    fun selectMode(id: String) {
        val profile = profiles.firstOrNull { it.name == id } ?: return
        _activeModeId.value = id
        _modeStatus.value = ModeApplyStatus.Applying(id)
        context.scope.launch {
            _modeStatus.value = try {
                context.controller.applyProfile(profile, store = false)
                ModeApplyStatus.Applied(id)
            } catch (e: IOException) {
                ModeApplyStatus.Failed(id)
            }
        }
    }

    fun updatePrefs(transform: (RidePrefs) -> RidePrefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        _vehicle.update { it.with(next) }
        prefs.edit().putString(KEY_PREFS, rideJson.encodeToString(RidePrefs.serializer(), next)).apply()
    }

    fun openLayoutEditor() {
        _layoutEditorOpen.value = true
    }

    fun closeLayoutEditor() {
        _layoutEditorOpen.value = false
    }

    fun saveRideLayout(layout: RideLayout) {
        _rideLayout.value = layout
        prefs.edit().putString(KEY_LAYOUT, rideJson.encodeToString(RideLayout.serializer(), layout)).apply()
    }

    fun armAccel(w: DashWidget) {
        _accel.value = AccelRun.armed(w.id, w.accelTargets())
    }

    private fun publish(raw: Telemetry) {
        val v = _vehicle.value
        val pct = if (v.batteryStabilize) {
            estimator.update(raw.voltage, raw.batteryCurrent, raw.ahUsed - raw.ahCharged, raw.timeMs, v.cellsSeries, v.batteryCapacityAh)
        } else {
            Battery.percent(raw.voltage / v.cellsSeries.coerceAtLeast(1))
        }
        val t = raw.copy(batteryPct = pct)
        val prev = _telemetry.value
        if (prev != null && abs(VehicleMath.speedKmhForErpm(t.erpm, v)) > 1.5) {
            _rideTimeMs.value += (t.timeMs - prev.timeMs).coerceIn(0L, 2_000L)
        }
        _telemetry.value = t
        _accel.update { it.step(t.timeMs, Metric.SPEED.value(t, v) ?: 0.0) }
        _history.update { (it + t).takeLast(v.historySamples) }
    }

    private fun loadPrefs(): RidePrefs =
        prefs.getString(KEY_PREFS, null)?.let { runCatching { rideJson.decodeFromString(RidePrefs.serializer(), it) }.getOrNull() } ?: RidePrefs()

    private fun loadLayout(): RideLayout =
        prefs.getString(KEY_LAYOUT, null)?.let { runCatching { rideJson.decodeFromString(RideLayout.serializer(), it) }.getOrNull() } ?: Defaults.rideLayout

    private companion object {
        const val KEY_PREFS = "prefs"
        const val KEY_LAYOUT = "layout"
        const val STALE_MS = 2_000L
    }
}

internal val rideJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

internal fun VehicleSettings.with(p: RidePrefs) = copy(
    appearance = p.appearance,
    rideStyle = p.rideStyle,
    rideTheme = p.rideTheme,
    launchAnimation = p.launchAnimation,
    heatWarnControllerC = p.heatWarnControllerC,
    heatWarnMotorC = p.heatWarnMotorC,
    batteryStabilize = p.batteryStabilize,
    dualController = p.dualController,
    canSlaveId = p.canSlaveId,
)

/** The vehicle as the motor config describes it; KV from the flux linkage the way VESC Tool's motor comparison does. */
internal fun vehicleFrom(mcconf: Config, imperial: Boolean): VehicleSettings {
    fun d(name: String, default: Double) = if (name in mcconf.definition.params) mcconf.double(name) else default
    fun i(name: String, default: Int) = if (name in mcconf.definition.params) mcconf.int(name).toInt() else default
    val poles = i("si_motor_poles", 14)
    val flux = d("foc_motor_flux_linkage", 0.0)
    return VehicleSettings(
        configured = true,
        motorPoles = poles,
        motorKv = if (flux > 0) 60.0 / (flux * 1.5 * 2 * PI * (poles / 2.0)) else 30.0,
        gearRatio = d("si_gear_ratio", 1.0),
        wheelDiameterMm = d("si_wheel_diameter", 0.254) * 1000,
        cellsSeries = i("si_battery_cells", 13),
        batteryCapacityAh = d("si_battery_ah", 0.0),
        imperial = imperial,
        motorCurrentMax = d("l_current_max", 60.0),
        batteryCurrentMax = d("l_in_current_max", 30.0),
        batteryRegenMax = abs(d("l_in_current_min", -10.0)),
        maxErpm = d("l_max_erpm", 60_000.0),
        maxDuty = d("l_max_duty", 0.95),
    )
}

/** A profile as a ride-view mode: its limits as percentages, named and coloured by position. */
internal fun ConfigProfile.toDriveMode(index: Int, v: VehicleSettings) = DriveMode(
    id = name,
    name = name,
    color = Defaults.modeColors[index % Defaults.modeColors.size],
    powerPct = (currentMaxScale * 100).toInt().coerceIn(1, 100),
    regenPct = (currentMinScale * 100).toInt().coerceIn(1, 100),
    topSpeedKmh = (speedMax * 3.6).takeIf { VehicleMath.erpmForSpeedKmh(it, v) < v.maxErpm },
    // VESC Tool treats ±1.4 MW and beyond as no power limit.
    powerCapKw = (wattMax / 1000).takeIf { wattMax < 1_400_000 },
    maxDutyPct = (dutyMax * 100).toInt(),
)

/** One sample across one or two controllers: currents and energy add up, temperatures take the hotter one. */
internal fun telemetryOf(local: RealtimeValues, remote: RealtimeValues?, timeMs: Long): Telemetry {
    val all = listOfNotNull(local, remote)
    return Telemetry(
        timeMs = timeMs,
        voltage = local.voltageInput.toDouble(),
        batteryCurrent = all.sumOf { it.currentInput.toDouble() },
        motorCurrent = all.sumOf { it.currentMotor.toDouble() },
        duty = local.dutyCycle.toDouble(),
        erpm = local.erpm.toDouble(),
        tempFet = all.mapNotNull { sensorTemp(it.tempMosfet.toDouble()) }.maxOrNull(),
        tempMotor = all.mapNotNull { sensorTemp(it.tempMotor.toDouble()) }.maxOrNull(),
        ahUsed = all.sumOf { it.ampHours.toDouble() },
        ahCharged = all.sumOf { it.ampHoursCharged.toDouble() },
        whUsed = all.sumOf { it.wattHours.toDouble() },
        whCharged = all.sumOf { it.wattHoursCharged.toDouble() },
        tachoAbs = local.tachometerAbs.toLong(),
        fault = local.faultCode.takeIf { it != 0 } ?: remote?.faultCode ?: 0,
        controllers = all.size,
    )
}

/** VESC Tool's fault names without the `FAULT_CODE_` prefix. */
internal fun faultName(code: Int): String = FaultCode.fromCode(code)?.name?.replace('_', ' ') ?: "FAULT $code"
