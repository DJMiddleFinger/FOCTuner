package app.foctuner.ui.tools.setup

import androidx.compose.runtime.Immutable
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamValue
import app.foctuner.ui.components.fixed
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.FocDetectionResult
import app.foctuner.vesc.detectApplyAllFoc
import app.foctuner.vesc.updateConfig
import app.foctuner.vesc.withBatteryCut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.ceil

enum class WizardStep { Prepare, Usage, Motor, Battery, Setup, Detect, Result, Direction }

/** One controller's detected values, for the summary VESC Tool prints after detection. */
@Immutable
data class DetectedMotor(
    val canId: Long,
    val currentMax: Double,
    val resistance: Double,
    val inductance: Double,
    val ldLqDiff: Double,
    val linkage: Double,
    val tempComp: Boolean,
    val sensors: String,
)

@Immutable
data class FocWizardState(
    val step: WizardStep = WizardStep.Prepare,
    val targets: List<SetupTarget>? = null,
    /** The first target's motor config with the wizard's edits; written before detection. */
    val config: Config? = null,
    val defaultsRestored: Boolean = false,
    val usage: UsagePreset = UsagePreset.Generic,
    val overrideUsage: Boolean = false,
    val motor: MotorPreset = MotorPreset.MiniOutrunner,
    val overrideMotor: Boolean = false,
    val maxPowerLoss: String = MotorPreset.MiniOutrunner.maxPowerLoss.fixed(1),
    val openloopErpm: String = MotorPreset.MiniOutrunner.openloopErpm.fixed(0),
    val sensorlessErpm: String = MotorPreset.MiniOutrunner.sensorlessErpm.fixed(0),
    val batteryLimits: Boolean = false,
    val currentInMin: String = "0",
    val currentInMax: String = "0",
    val directDrive: Boolean = false,
    val motorPulley: String = "13",
    val wheelPulley: String = "36",
    val detectCan: Boolean = true,
    val result: FocDetectionResult? = null,
    val detected: List<DetectedMotor> = emptyList(),
) {
    val gearRatio: Double?
        get() {
            if (directDrive) return 1.0
            val motor = parseNumber(motorPulley)?.takeIf { it >= 1f } ?: return null
            val wheel = parseNumber(wheelPulley)?.takeIf { it >= 1f } ?: return null
            return (wheel / motor).toDouble()
        }

    val detectionInputsValid: Boolean
        get() = listOf(maxPowerLoss, openloopErpm, sensorlessErpm, currentInMin, currentInMax).all { parseNumber(it) != null } &&
            (parseNumber(maxPowerLoss) ?: 0f) > 0f && gearRatio != null
}

/**
 * VESC Tool's FOC setup wizard (mobile SetupWizardFoc, desktop DetectAllFocDialog) for the connected controller
 * and every motor controller on its CAN bus.
 */
class FocWizardModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(FocWizardState())
    val state: StateFlow<FocWizardState> = _state.asStateFlow()
    val runner = SetupRunner(context)

    private val primary get() = _state.value.targets?.firstOrNull()

    /** The controllers detection and the follow-up settings reach. */
    private val involved get() = _state.value.targets.orEmpty().let { if (_state.value.detectCan) it else it.take(1) }

    fun scan() = runner.start(spins = false) {
        val targets = context.scanTargets()
        _state.update { it.copy(targets = targets) }
        readPrimary()
    }

    /** Factory defaults (motor and app config) on every controller found, as VESC Tool offers before it starts. */
    fun restoreDefaults() = runner.start(spins = false) {
        val motorBase = context.baseConfig(ConfigKind.MCCONF)
        val appBase = context.baseConfig(ConfigKind.APPCONF)
        for (target in _state.value.targets.orEmpty()) {
            target.controller.writeConfig(ConfigKind.MCCONF, target.controller.readDefaultConfig(ConfigKind.MCCONF, motorBase))
            target.controller.writeConfig(ConfigKind.APPCONF, target.controller.readDefaultConfig(ConfigKind.APPCONF, appBase))
        }
        readPrimary()
        _state.update { it.copy(defaultsRestored = true) }
    }

    private suspend fun readPrimary() {
        val target = primary ?: return
        val config = target.controller.readConfig(ConfigKind.MCCONF, context.baseConfig(ConfigKind.MCCONF))
        _state.update { it.copy(config = applyUsage(applyMotor(config, it.motor), it.usage)) }
    }

    fun go(step: WizardStep) = _state.update { it.copy(step = step) }

    fun set(name: String, value: ParamValue) = _state.update { s -> s.copy(config = s.config?.with(name, value)) }

    fun edit(transform: (FocWizardState) -> FocWizardState) = _state.update(transform)

    fun selectUsage(usage: UsagePreset) = _state.update { s -> s.copy(usage = usage, config = s.config?.let { applyUsage(it, usage) }) }

    fun selectMotor(motor: MotorPreset) = _state.update { s ->
        s.copy(
            motor = motor,
            maxPowerLoss = motor.maxPowerLoss.fixed(1),
            openloopErpm = motor.openloopErpm.fixed(0),
            sensorlessErpm = motor.sensorlessErpm.fixed(0),
            config = s.config?.let { applyMotor(it, motor) },
        )
    }

    /** Leaving the motor page: guesses the cell count from the pack voltage like VESC Tool, then moves on. */
    fun confirmMotor() = runner.start(spins = false) {
        val volts = primary?.controller?.values()?.voltageInput
        if (volts != null && volts > 0f) {
            val cells = if (volts > 39f && volts < 51f) 12L else ceil(volts / 4.2f).toLong()
            set("si_battery_cells", ParamValue.IntValue(cells))
        }
        go(WizardStep.Battery)
    }

    /**
     * Writes the wizard's settings, lowers the battery cutoff out of the way, runs detection on the bus and then
     * sets the cutoff, start duty and start mode the way VESC Tool does after a successful run.
     */
    fun detect() {
        val s = _state.value
        val edited = s.config?.with("si_gear_ratio", s.gearRatio ?: return) ?: return
        val first = primary ?: return
        val loss = parseNumber(s.maxPowerLoss) ?: return
        runner.start(spins = true) {
            val base = context.baseConfig(ConfigKind.MCCONF)
            first.controller.writeConfig(ConfigKind.MCCONF, edited)
            for (target in involved) {
                target.controller.updateConfig(ConfigKind.MCCONF, base) { c ->
                    val cut = c.with("l_battery_cut_start", DETECTION_BATTERY_CUT).with("l_battery_cut_end", DETECTION_BATTERY_CUT)
                    if (target === first) cut else cut.copyFrom(edited, "m_motor_temp_sens_type", "m_ntc_motor_beta")
                }
            }
            val result = first.controller.detectApplyAllFoc(
                detectCan = s.detectCan,
                maxPowerLoss = loss,
                minCurrentIn = if (s.batteryLimits) parseNumber(s.currentInMin) ?: 0f else 0f,
                maxCurrentIn = if (s.batteryLimits) parseNumber(s.currentInMax) ?: 0f else 0f,
                openloopErpm = parseNumber(s.openloopErpm) ?: 0f,
                sensorlessErpm = parseNumber(s.sensorlessErpm) ?: 0f,
            )
            val detected = if (result.ok) afterDetection(edited, base) else emptyList()
            _state.update { it.copy(result = result, detected = detected, step = WizardStep.Result) }
        }
    }

    private suspend fun afterDetection(edited: Config, base: Config): List<DetectedMotor> {
        val s = _state.value
        val appBase = context.baseConfig(ConfigKind.APPCONF)
        return involved.map { target ->
            val stored = target.controller.updateConfig(ConfigKind.MCCONF, base) { c ->
                var x = c.copyFrom(edited, "si_battery_type", "si_battery_cells", "l_duty_start").withBatteryCut(s.usage.cautiousBatteryCut)
                // Temperature compensation tracks the motor at lower speed, so open loop can end earlier.
                if (x.bool("foc_temp_comp")) x = x.with("foc_openloop_rpm", x.double("foc_openloop_rpm") / 2)
                val hfiAvailable = x.definition.param("foc_sensor_mode").enumNames.size > HFI_START
                if (hfiAvailable && x.int("foc_sensor_mode") == 0L && s.usage.hfiStart && s.motor.hfiStart) x = x.with("foc_sensor_mode", HFI_START.toLong())
                x
            }
            val app = target.controller.readConfig(ConfigKind.APPCONF, appBase)
            DetectedMotor(
                canId = app.int("controller_id"),
                currentMax = stored.double("l_current_max"),
                resistance = stored.double("foc_motor_r"),
                inductance = stored.double("foc_motor_l"),
                ldLqDiff = stored.double("foc_motor_ld_lq_diff"),
                linkage = stored.double("foc_motor_flux_linkage"),
                tempComp = stored.bool("foc_temp_comp"),
                sensors = stored.definition.param("foc_sensor_mode").enumNames.getOrElse(stored.int("foc_sensor_mode").toInt()) { "?" },
            )
        }
    }

    private fun applyUsage(config: Config, usage: UsagePreset) = config
        .with("l_duty_start", usage.dutyStart)
        .with("m_fault_stop_time_ms", usage.faultStopMs)
        .with("bms.limit_mode", usage.bmsLimitMode)

    private fun applyMotor(config: Config, motor: MotorPreset) = config.with("si_motor_poles", motor.poles)

    private fun Config.copyFrom(other: Config, vararg names: String) = names.fold(this) { c, name -> c.with(name, other[name]) }

    private companion object {
        /** VESC Tool lowers the cutoff to 6 V during detection so a sagging pack doesn't stop it. */
        const val DETECTION_BATTERY_CUT = 6.0

        /** `foc_sensor_mode` FOC_SENSOR_MODE_HFI_START in the firmware (VESC Tool's 7.01 definitions label it "VSS"). */
        const val HFI_START = 4
    }
}
