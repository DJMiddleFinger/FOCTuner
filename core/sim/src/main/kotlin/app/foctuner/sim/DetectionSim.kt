package app.foctuner.sim

import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_DETECT_APPLY_ALL_FOC
import app.foctuner.protocol.CommPacketId.COMM_DETECT_ENCODER
import app.foctuner.protocol.CommPacketId.COMM_DETECT_HALL_FOC
import app.foctuner.protocol.CommPacketId.COMM_DETECT_MOTOR_FLUX_LINKAGE
import app.foctuner.protocol.CommPacketId.COMM_DETECT_MOTOR_FLUX_LINKAGE_OPENLOOP
import app.foctuner.protocol.CommPacketId.COMM_DETECT_MOTOR_PARAM
import app.foctuner.protocol.CommPacketId.COMM_DETECT_MOTOR_R_L
import app.foctuner.protocol.CommPacketId.COMM_GET_APPCONF
import app.foctuner.protocol.CommPacketId.COMM_GET_IMU_CALIBRATION
import app.foctuner.protocol.CommPacketId.COMM_GET_MCCONF
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Motor detection and IMU calibration as the firmware's blocking thread runs them: one job at a time for the
 * board (a command that arrives meanwhile is dropped), answered after roughly the time the real measurement takes.
 * The first motor has hall sensors and the second runs sensorless, so both outcomes show up. Results come from
 * fixed motor constants consistent with [MotorModel] (its speed per volt gives the flux linkage).
 */
internal class DetectionSim(private val vesc: SimulatedVesc, private val motors: List<MotorModel>) {
    private var busyUntil = Duration.ZERO

    private class Motor(val r: Float, val l: Float, val ldLqDiff: Float, val linkage: Float, val halls: Boolean)

    private fun motor(model: MotorModel) = if (model === motors.first()) FIRST else SECOND

    fun resistanceInductance(model: MotorModel) = run(RL_TIME) {
        val m = motor(model)
        ByteWriter().u8(COMM_DETECT_MOTOR_R_L.id).f32(m.r, 1e6f).f32(m.l * 1e6f, 1e3f).f32(m.ldLqDiff * 1e6f, 1e3f).toByteArray()
    }

    /** The legacy BLDC-based measurement, answered like the openloop one. */
    fun fluxLinkage(r: ByteReader, model: MotorModel) {
        val current = r.f32(1e3f)
        run(FLUX_LINKAGE_BASE + 3.seconds) {
            val linkage = if (current > MIN_CURRENT) motor(model).linkage else 0f
            ByteWriter().u8(COMM_DETECT_MOTOR_FLUX_LINKAGE.id).f32(linkage, 1e7f).toByteArray()
        }
    }

    /** At zero current nothing is driven, so it only works while the motor is already turning (passive measurement). */
    fun fluxLinkageOpenloop(r: ByteReader, model: MotorModel) {
        val current = r.f32(1e3f)
        val erpmPerSecond = r.f32(1e3f)
        val duty = r.f32(1e3f)
        val driven = current > MIN_CURRENT
        val ramp = when {
            !driven -> Duration.ZERO
            erpmPerSecond <= 0f -> MAX_RAMP
            else -> minOf(MAX_RAMP, (duty * NOMINAL_VOLTAGE * ERPM_PER_VOLT / erpmPerSecond).toDouble().seconds)
        }
        val config = vesc.config(model, ConfigKind.MCCONF)
        val reachable = driven && ramp < MAX_RAMP
        val turning = !driven && abs(model.erpm) > PASSIVE_MIN_ERPM
        run(FLUX_LINKAGE_BASE + ramp) {
            val w = ByteWriter().u8(COMM_DETECT_MOTOR_FLUX_LINKAGE_OPENLOOP.id)
                .f32(if (reachable || turning) motor(model).linkage else 0f, 1e7f)
            if (encoderConfigured(config)) {
                w.f32(ENCODER_OFFSET, 1e6f).f32(encoderRatio(config), 1e6f).u8(0)
            } else {
                w.f32(-1f, 1e6f).f32(-1f, 1e6f).u8(0)
            }
            w.toByteArray()
        }
    }

    /**
     * Without the sensor port in hall mode the firmware replies at once, but overwrites its own id byte with 255
     * (a bug in commands.c), so nothing recognisable arrives.
     */
    fun focHalls(r: ByteReader, model: MotorModel) {
        val current = r.f32(1e3f)
        if (!hallPort(vesc.config(model, ConfigKind.MCCONF))) {
            run(Duration.ZERO) { ByteArray(10) { if (it < 8) -1 else 0 } }
            return
        }
        run(HALL_TIME) {
            val ok = motor(model).halls && current > MIN_CURRENT
            val table = if (ok) FOC_HALL_TABLE else List(8) { NO_HALL }
            val w = ByteWriter().u8(COMM_DETECT_HALL_FOC.id)
            table.forEach { w.u8(it) }
            w.u8(if (ok) 0 else 1).toByteArray()
        }
    }

    fun encoder(r: ByteReader, model: MotorModel) {
        r.f32(1e3f)
        val config = vesc.config(model, ConfigKind.MCCONF)
        val w = ByteWriter().u8(COMM_DETECT_ENCODER.id)
        if (!encoderConfigured(config)) {
            run(Duration.ZERO) { w.f32(NO_ENCODER_OFFSET, 1e6f).f32(0f, 1e6f).u8(0).toByteArray() }
            return
        }
        run(ENCODER_TIME) { w.f32(ENCODER_OFFSET, 1e6f).f32(encoderRatio(config), 1e6f).u8(0).toByteArray() }
    }

    fun bldc(r: ByteReader, model: MotorModel) {
        val current = r.f32(1e3f)
        val config = vesc.config(model, ConfigKind.MCCONF)
        run(BLDC_TIME) {
            val w = ByteWriter().u8(COMM_DETECT_MOTOR_PARAM.id)
            if (current < BLDC_MIN_CURRENT) {
                w.f32(0f, 1e3f).f32(0f, 1e3f)
                repeat(8) { w.i8(-1) }
                return@run w.i8(-1).toByteArray()
            }
            w.f32(CYCLE_INT_LIMIT, 1e3f).f32(BEMF_COUPLING_K, 1e3f)
            val hallResult = when {
                encoderConfigured(config) -> -3
                motor(model).halls -> 0
                else -> -1
            }
            (if (hallResult == 0) BLDC_HALL_TABLE else List(8) { -1 }).forEach { w.i8(it) }
            w.i8(hallResult).toByteArray()
        }
    }

    /**
     * Detects both motors of the board (the simulated bus has no other controllers, so detect_can changes nothing),
     * stores the results like conf_general.c and, as the firmware does, sends the app config (when it changed) and
     * the motor config a second apart before the result.
     */
    fun applyAllFoc(r: ByteReader) {
        r.u8()
        val maxPowerLoss = r.f32(1e3f)
        val minCurrentIn = r.f32(1e3f)
        val maxCurrentIn = r.f32(1e3f)
        val openloopErpm = r.f32(1e3f)
        val sensorlessErpm = r.f32(1e3f)
        if (vesc.uptime < busyUntil) return
        val first = motors.first()
        if (maxPowerLoss <= 0f) {
            run(APPLY_ALL_FAIL_TIME) { result(-10) }
            return
        }
        busyUntil = vesc.uptime + APPLY_ALL_TIME + 2.seconds
        vesc.later(APPLY_ALL_TIME) {
            for (model in motors) {
                val defaults = Config.defaults(vesc.config(model, ConfigKind.MCCONF).definition)
                fun orDefault(value: Float, name: String) = if (abs(value) > 0.001f) value.toDouble() else defaults.double(name)
                val stored = vesc.config(model, ConfigKind.MCCONF)
                    .with("l_in_current_min", orDefault(minCurrentIn, "l_in_current_min"))
                    .with("l_in_current_max", orDefault(maxCurrentIn, "l_in_current_max"))
                    .with("foc_openloop_rpm", orDefault(openloopErpm, "foc_openloop_rpm"))
                    .with("foc_sl_erpm", orDefault(sensorlessErpm, "foc_sl_erpm"))
                vesc.replaceConfig(model, ConfigKind.MCCONF, detected(stored, motor(model), model.tempMotor, maxPowerLoss))
            }
            var appChanged = false
            for (model in motors) {
                val app = vesc.config(model, ConfigKind.APPCONF)
                if (app.int("can_status_msgs_r1") != CAN_STATUS_MSGS) {
                    vesc.replaceConfig(model, ConfigKind.APPCONF, app.with("can_status_msgs_r1", CAN_STATUS_MSGS))
                    appChanged = true
                }
            }
            val mcconf = byteArrayOf(COMM_GET_MCCONF.id.toByte()) + vesc.storedConfig(first, ConfigKind.MCCONF)
            if (appChanged) vesc.reply(byteArrayOf(COMM_GET_APPCONF.id.toByte()) + vesc.storedConfig(first, ConfigKind.APPCONF))
            vesc.later(1.seconds) {
                vesc.reply(mcconf)
                vesc.later(1.seconds) { vesc.reply(result(if (FIRST.halls) 1 else 0)) }
            }
        }
    }

    fun imuCalibration(r: ByteReader) {
        val yaw = r.f32(1e3f)
        run(IMU_TIME) {
            val w = ByteWriter().u8(COMM_GET_IMU_CALIBRATION.id).f32(IMU_ROLL, 1e6f).f32(IMU_PITCH, 1e6f).f32(yaw, 1e6f)
            repeat(3) { w.f32(0f, 1e6f) }
            GYRO_OFFSETS.forEach { w.f32(it, 1e6f) }
            w.toByteArray()
        }
    }

    /** What conf_general_detect_apply_all_foc stores for one motor. */
    private fun detected(config: Config, m: Motor, motorTemp: Float, maxPowerLoss: Float): Config {
        val iMax = minOf(sqrt(maxPowerLoss / m.r / 1.5f), HW_LIM_CURRENT)
        val bandwidth = 1f / DETECT_TIME_CONSTANT_S
        var c = config
            .with("l_current_max", iMax.toDouble())
            .with("l_current_min", -iMax.toDouble())
            .with("l_abs_current_max", minOf(iMax * 1.5f, HW_LIM_CURRENT_ABS).toDouble())
            .with("motor_type", MOTOR_TYPE_FOC)
            .with("foc_motor_r", m.r.toDouble())
            .with("foc_motor_l", m.l.toDouble())
            .with("foc_motor_ld_lq_diff", m.ldLqDiff.toDouble())
            .with("foc_motor_flux_linkage", m.linkage.toDouble())
            .with("foc_current_kp", (m.l * bandwidth).toDouble())
            .with("foc_current_ki", (m.r * bandwidth).toDouble())
            .with("foc_observer_gain", 1e-3 / (m.linkage * m.linkage) * 1e6)
            .with("foc_temp_comp_base_temp", motorTemp.toDouble())
            .with("foc_temp_comp", true)
        if (m.halls) {
            c = c.with("m_sensor_port_mode", SENSOR_PORT_HALL).with("foc_sensor_mode", FOC_SENSOR_HALL)
            FOC_HALL_TABLE.forEachIndexed { i, angle -> c = c.with("foc_hall_table__$i", angle.toLong()) }
        } else {
            c = c.with("foc_sensor_mode", FOC_SENSOR_SENSORLESS)
        }
        return c
    }

    private fun result(code: Int) = ByteWriter().u8(COMM_DETECT_APPLY_ALL_FOC.id).i16(code).toByteArray()

    /** Like the firmware's blocking thread: a command that arrives while one runs is dropped. */
    private fun run(duration: Duration, reply: () -> ByteArray) {
        if (vesc.uptime < busyUntil) return
        busyUntil = vesc.uptime + duration
        vesc.later(duration) { vesc.reply(reply()) }
    }

    private fun hallPort(config: Config) = config.int("m_sensor_port_mode") == SENSOR_PORT_HALL

    private fun encoderConfigured(config: Config) = !hallPort(config)

    private fun encoderRatio(config: Config) = config.int("si_motor_poles") / 2f

    private companion object {
        /** A 6374-class outrunner; λ = 0.577 V/duty·V over the sim's 160 ERPM/V in rad/s. */
        val FIRST = Motor(r = 0.030f, l = 28e-6f, ldLqDiff = 4.2e-6f, linkage = 0.0344f, halls = true)
        val SECOND = Motor(r = 0.031f, l = 29e-6f, ldLqDiff = 4.0e-6f, linkage = 0.0346f, halls = false)

        /** Hall states 1, 3, 2, 6, 4, 5 one sixth of a turn apart, in 1/200 turn. */
        val FOC_HALL_TABLE = listOf(255, 9, 76, 43, 143, 176, 109, 255)
        val BLDC_HALL_TABLE = listOf(-1, 1, 3, 2, 5, 6, 4, -1)
        val GYRO_OFFSETS = listOf(0.42f, -0.31f, 0.12f)

        val RL_TIME = 3.5.seconds
        val FLUX_LINKAGE_BASE = 4.7.seconds
        val MAX_RAMP = 15.seconds
        val HALL_TIME = 11.8.seconds
        val ENCODER_TIME = 35.seconds
        val BLDC_TIME = 9.seconds
        val APPLY_ALL_TIME = 28.seconds
        val APPLY_ALL_FAIL_TIME = 15.seconds
        val IMU_TIME = 4500.milliseconds

        const val MIN_CURRENT = 0.05f
        const val BLDC_MIN_CURRENT = 0.5f
        const val NOMINAL_VOLTAGE = 50f
        const val ERPM_PER_VOLT = 160f
        const val PASSIVE_MIN_ERPM = 500f
        const val NO_HALL = 255
        const val NO_ENCODER_OFFSET = 1001f
        const val ENCODER_OFFSET = 172.4f
        const val CYCLE_INT_LIMIT = 58.6f
        const val BEMF_COUPLING_K = 512.3f
        const val IMU_ROLL = 1.83f
        const val IMU_PITCH = -0.62f
        const val HW_LIM_CURRENT = 150f
        const val HW_LIM_CURRENT_ABS = 160f
        const val DETECT_TIME_CONSTANT_S = 1e-3f
        const val MOTOR_TYPE_FOC = 2L
        const val SENSOR_PORT_HALL = 0L
        const val FOC_SENSOR_SENSORLESS = 0L
        const val FOC_SENSOR_HALL = 2L
        const val CAN_STATUS_MSGS = 0b1111L
    }
}
