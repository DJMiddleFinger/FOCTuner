package app.foctuner.sim

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigCodec
import app.foctuner.config.ConfigKind
import app.foctuner.config.PayloadException
import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_ALIVE
import app.foctuner.protocol.CommPacketId.COMM_BMS_FORCE_BALANCE
import app.foctuner.protocol.CommPacketId.COMM_BMS_GET_VALUES
import app.foctuner.protocol.CommPacketId.COMM_BMS_RESET_COUNTERS
import app.foctuner.protocol.CommPacketId.COMM_BMS_SET_BALANCE_OVERRIDE
import app.foctuner.protocol.CommPacketId.COMM_BMS_SET_CHARGE_ALLOWED
import app.foctuner.protocol.CommPacketId.COMM_BMS_ZERO_CURRENT_OFFSET
import app.foctuner.protocol.CommPacketId.COMM_APP_DISABLE_OUTPUT
import app.foctuner.protocol.CommPacketId.COMM_DETECT_APPLY_ALL_FOC
import app.foctuner.protocol.CommPacketId.COMM_DETECT_ENCODER
import app.foctuner.protocol.CommPacketId.COMM_DETECT_HALL_FOC
import app.foctuner.protocol.CommPacketId.COMM_DETECT_MOTOR_FLUX_LINKAGE
import app.foctuner.protocol.CommPacketId.COMM_DETECT_MOTOR_FLUX_LINKAGE_OPENLOOP
import app.foctuner.protocol.CommPacketId.COMM_DETECT_MOTOR_PARAM
import app.foctuner.protocol.CommPacketId.COMM_DETECT_MOTOR_R_L
import app.foctuner.protocol.CommPacketId.COMM_ERASE_BOOTLOADER
import app.foctuner.protocol.CommPacketId.COMM_ERASE_BOOTLOADER_ALL_CAN
import app.foctuner.protocol.CommPacketId.COMM_ERASE_NEW_APP
import app.foctuner.protocol.CommPacketId.COMM_ERASE_NEW_APP_ALL_CAN
import app.foctuner.protocol.CommPacketId.COMM_FORWARD_CAN
import app.foctuner.protocol.CommPacketId.COMM_FW_INFO
import app.foctuner.protocol.CommPacketId.COMM_FW_VERSION
import app.foctuner.protocol.CommPacketId.COMM_GET_APPCONF
import app.foctuner.protocol.CommPacketId.COMM_GET_APPCONF_DEFAULT
import app.foctuner.protocol.CommPacketId.COMM_GET_DECODED_ADC
import app.foctuner.protocol.CommPacketId.COMM_GET_DECODED_CHUK
import app.foctuner.protocol.CommPacketId.COMM_GET_DECODED_PPM
import app.foctuner.protocol.CommPacketId.COMM_GET_IMU_CALIBRATION
import app.foctuner.protocol.CommPacketId.COMM_GET_MCCONF
import app.foctuner.protocol.CommPacketId.COMM_GET_MCCONF_DEFAULT
import app.foctuner.protocol.CommPacketId.COMM_GET_STATS
import app.foctuner.protocol.CommPacketId.COMM_GET_GNSS
import app.foctuner.protocol.CommPacketId.COMM_GET_IMU_DATA
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES_SELECTIVE
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES_SETUP
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES_SETUP_SELECTIVE
import app.foctuner.protocol.CommPacketId.COMM_JUMP_TO_BOOTLOADER
import app.foctuner.protocol.CommPacketId.COMM_JUMP_TO_BOOTLOADER_ALL_CAN
import app.foctuner.protocol.CommPacketId.COMM_MOTOR_ESTOP
import app.foctuner.protocol.CommPacketId.COMM_IO_BOARD_GET_ALL
import app.foctuner.protocol.CommPacketId.COMM_IO_BOARD_SET_DIGITAL
import app.foctuner.protocol.CommPacketId.COMM_IO_BOARD_SET_PWM
import app.foctuner.protocol.CommPacketId.COMM_PING_CAN
import app.foctuner.protocol.CommPacketId.COMM_PRINT
import app.foctuner.protocol.CommPacketId.COMM_PSW_GET_STATUS
import app.foctuner.protocol.CommPacketId.COMM_PSW_SWITCH
import app.foctuner.protocol.CommPacketId.COMM_RESET_STATS
import app.foctuner.protocol.CommPacketId.COMM_SET_APPCONF
import app.foctuner.protocol.CommPacketId.COMM_SET_MCCONF
import app.foctuner.protocol.CommPacketId.COMM_SET_ODOMETER
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT_BRAKE
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT_REL
import app.foctuner.protocol.CommPacketId.COMM_SET_DETECT
import app.foctuner.protocol.CommPacketId.COMM_SET_DUTY
import app.foctuner.protocol.CommPacketId.COMM_SET_HANDBRAKE
import app.foctuner.protocol.CommPacketId.COMM_SET_POS
import app.foctuner.protocol.CommPacketId.COMM_SET_RPM
import app.foctuner.protocol.CommPacketId.COMM_TERMINAL_CMD
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA_ALL_CAN
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA_ALL_CAN_LZO
import app.foctuner.protocol.CommPacketId.COMM_WRITE_NEW_APP_DATA_LZO
import app.foctuner.protocol.PacketCodec
import app.foctuner.protocol.PacketDecoder
import app.foctuner.transport.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * An in-memory VESC, for tests and demo mode: a [Transport] that answers like firmware 7.01 on a
 * dual-motor board. The attached motor has CAN id [controllerId]; with [dualMotor] a second one answers to
 * `controllerId + 1` through COMM_FORWARD_CAN and shows up in COMM_PING_CAN, as on real dual-motor hardware.
 * Forwarding to any other id goes nowhere, so the request times out.
 *
 * Supports COMM_FW_VERSION, COMM_GET_VALUES, the four motor commands, COMM_ALIVE, COMM_MOTOR_ESTOP, COMM_TERMINAL_CMD,
 * COMM_PING_CAN, COMM_FORWARD_CAN, COMM_GET_STATS / COMM_RESET_STATS and reading and writing the motor and app config (7.01 layout, starting
 * from the defaults); COMM_SET_POS, COMM_SET_HANDBRAKE and COMM_SET_CURRENT_REL; COMM_SET_DETECT with the
 * COMM_ROTOR_POSITION stream ([RotorPositionSim]); COMM_GET_VALUES_SELECTIVE; COMM_GET_VALUES_SETUP(_SELECTIVE)
 * and COMM_SET_ODOMETER ([SetupValuesSim]); COMM_GET_IMU_DATA ([ImuSim]); COMM_BMS_GET_VALUES and the BMS set
 * commands ([BmsSim]); COMM_GET_GNSS ([GnssSim]); COMM_PSW_GET_STATUS / COMM_PSW_SWITCH ([PowerSwitchSim]);
 * COMM_IO_BOARD_GET_ALL / SET_PWM / SET_DIGITAL ([IoBoardSim]); motor detection (COMM_DETECT_*, replying after
 * the time the firmware takes, see [DetectionSim]), COMM_GET_IMU_CALIBRATION and the decoded PPM / ADC /
 * nunchuk inputs ([DecodedInputsSim]); and firmware updates (erase, write, LZO, bootloader, jump, COMM_FW_INFO;
 * see [FirmwareSim]). Other commands are ignored like unknown ones in the firmware.
 *
 * Time comes from [timeSource] and is applied lazily when a request arrives, so the simulation costs nothing
 * while idle and runs on virtual time when given a test scope's time source. Like the firmware, motors
 * are released when no command or COMM_ALIVE arrived for [commandTimeout] (zero disables the timeout).
 *
 * A small [chunkSize] (e.g. 20, a default BLE MTU) splits both directions into pieces of that size.
 */
class SimulatedVesc(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    chunkSize: Int = Int.MAX_VALUE,
    private val hardwareName: String = "60_MK6",
    private val controllerId: Int = 0,
    private val dualMotor: Boolean = true,
    private val commandTimeout: Duration = 1.seconds,
) : Transport {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(controllerId in 0..253) { "controllerId must leave room for the second motor" }
    }

    override val maxWriteSize = chunkSize

    private val outbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    /** Replies, plus the ones [later] scheduled, delivered once their time has come (waiting on the collector's clock). */
    override val received: Flow<ByteArray> = flow {
        while (true) {
            // No timeout when nothing is scheduled: an infinite one would let a test clock jump to the end of time.
            val wait = synchronized(lock) { runDue() }
            val next = (if (wait == null) nextReply() else withTimeoutOrNull(wait) { nextReply() }) ?: continue
            emit(next.getOrNull() ?: break)
        }
    }

    /** The next reply, or null when [later] scheduled something that may be due sooner. */
    private suspend fun nextReply(): ChannelResult<ByteArray>? = select {
        outbound.onReceiveCatching { it }
        wakeUp.onReceive { null }
    }

    private val lock = Any()
    private val decoder = PacketDecoder()
    private val started = timeSource.markNow()

    /** Time since the simulated controller booted. */
    internal val uptime: Duration get() = started.elapsedNow()
    private val battery = Battery()
    private val first = MotorModel()
    private val second = MotorModel()
    private val rotor = RotorPositionSim(this)
    private val imu = ImuSim(first)
    private val setup = SetupValuesSim(battery, first)
    private val bms = BmsSim(battery, listOf(first, second))
    private val gnss = GnssSim(first)
    private val powerSwitch = PowerSwitchSim()
    private val ioBoard = IoBoardSim()
    private var lastStep = Duration.ZERO
    private var lastCommand = Duration.ZERO
    private var timedOut = false
    private var ignoreInputUntil = Duration.ZERO
    private var closed = false

    /** Each motor's stored configs in wire form, created on first use (parsing the definitions is slow). */
    private val configs = HashMap<Pair<MotorModel, ConfigKind>, ByteArray>()

    /** Actions [later] scheduled, with the uptime they are due at. */
    private val scheduled = mutableListOf<Pair<Duration, () -> Unit>>()
    private val detection = DetectionSim(this, if (dualMotor) listOf(first, second) else listOf(first))
    private val firmware = FirmwareSim(this, hardwareName, FIRMWARE_MAJOR, FIRMWARE_MINOR)

    override suspend fun write(bytes: ByteArray) {
        synchronized(lock) {
            if (closed) throw IOException("Simulated link is closed")
            for (packet in decoder.feed(bytes)) handle(packet, first)
            rotor.sendDue(uptime)
        }
    }

    override suspend fun close() {
        synchronized(lock) {
            closed = true
            outbound.close()
        }
    }

    /** Whether the motor of controller [id] is being driven right now (a command is in effect and has not timed out). */
    fun isDriven(id: Int): Boolean = synchronized(lock) {
        advance()
        motorFor(id)?.drive.let { it != null && it != Drive.Released }
    }

    private fun idOf(motor: MotorModel) = if (motor === second) controllerId + 1 else controllerId

    private val packVoltage: Float get() = battery.voltage(first.inputCurrent + second.inputCurrent)

    internal fun motorFor(id: Int): MotorModel? = when {
        id == controllerId -> first
        dualMotor && id == controllerId + 1 -> second
        else -> null
    }

    private fun handle(packet: ByteArray, motor: MotorModel) {
        val id = CommPacketId.fromId(packet[0].toInt() and 0xFF) ?: return
        val r = ByteReader(packet, 1)
        advance()
        if (!firmware.answering) return
        when (id) {
            COMM_FW_VERSION -> reply(firmwareVersion(motor))
            COMM_GET_VALUES -> reply(values(motor))
            COMM_SET_DUTY -> command(motor, Drive.Duty(r.i32() / 1e5f))
            COMM_SET_CURRENT -> command(motor, currentDrive(r.i32() / 1e3f, brake = false))
            COMM_SET_CURRENT_BRAKE -> command(motor, currentDrive(r.i32() / 1e3f, brake = true))
            COMM_SET_RPM -> command(motor, Drive.Speed(r.i32().toFloat()))
            COMM_SET_POS -> command(motor, Drive.Position(r.i32() / 1e6f))
            COMM_SET_HANDBRAKE -> command(motor, Drive.Handbrake(r.i32() / 1e3f))
            COMM_SET_CURRENT_REL -> command(motor, currentDrive(relativeCurrent(r.i32() / 1e5f), brake = false))
            COMM_SET_DETECT -> rotor.select(r.u8(), motor)
            COMM_GET_VALUES_SELECTIVE -> reply(selectiveValues(values(motor), r.u32()))
            COMM_GET_VALUES_SETUP -> reply(setup.reply(id, 0xFFFFFFFFL, motor, packVoltage, idOf(motor), uptime))
            COMM_GET_VALUES_SETUP_SELECTIVE -> reply(setup.reply(id, r.u32(), motor, packVoltage, idOf(motor), uptime))
            COMM_SET_ODOMETER -> setup.setOdometer(r.u32())
            COMM_GET_IMU_DATA -> reply(imu.reply(uptime, r.u16(), idOf(motor)))
            COMM_BMS_GET_VALUES -> reply(bms.values(uptime, packVoltage))
            COMM_BMS_SET_CHARGE_ALLOWED -> bms.chargeAllowed = r.u8() != 0
            COMM_BMS_SET_BALANCE_OVERRIDE -> bms.override(r.u8(), r.u8() != 0)
            COMM_BMS_RESET_COUNTERS -> bms.resetCounters(r.u8() != 0, r.u8() != 0)
            COMM_BMS_FORCE_BALANCE -> bms.forceBalance = r.u8() != 0
            COMM_BMS_ZERO_CURRENT_OFFSET -> bms.zeroCurrentOffset()
            COMM_GET_GNSS -> reply(gnss.reply(uptime, r.u16()))
            COMM_PSW_GET_STATUS -> powerSwitch.status(r.u8() != 0, r.i16(), packVoltage)?.let(::reply)
            COMM_PSW_SWITCH -> powerSwitch.switch(r.i16(), r.u8() != 0)
            COMM_IO_BOARD_GET_ALL -> ioBoard.all(r.i16(), uptime)?.let(::reply)
            COMM_IO_BOARD_SET_PWM -> ioBoard.setPwm(r.i16(), r.i16(), r.f32Auto())
            COMM_IO_BOARD_SET_DIGITAL -> ioBoard.setDigital(r.i16(), r.i16(), r.u8() != 0)
            COMM_ALIVE -> resetTimeout()
            COMM_MOTOR_ESTOP -> emergencyStop(r.u16())
            COMM_TERMINAL_CMD -> terminal(r.restBytes().toString(Charsets.ISO_8859_1), motor)
            COMM_PING_CAN -> reply(pingReply())
            COMM_FORWARD_CAN -> forward(packet)
            COMM_GET_MCCONF -> reply(byteArrayOf(packet[0]) + storedConfig(motor, ConfigKind.MCCONF))
            COMM_GET_APPCONF -> reply(byteArrayOf(packet[0]) + storedConfig(motor, ConfigKind.APPCONF))
            COMM_GET_MCCONF_DEFAULT -> reply(byteArrayOf(packet[0]) + defaultConfig(motor, ConfigKind.MCCONF))
            COMM_GET_APPCONF_DEFAULT -> reply(byteArrayOf(packet[0]) + defaultConfig(motor, ConfigKind.APPCONF))
            COMM_SET_MCCONF -> storeConfig(motor, ConfigKind.MCCONF, packet)
            COMM_SET_APPCONF -> storeConfig(motor, ConfigKind.APPCONF, packet)
            COMM_GET_STATS -> reply(motor.stats.reply())
            COMM_DETECT_MOTOR_R_L -> detection.resistanceInductance(motor)
            COMM_DETECT_MOTOR_FLUX_LINKAGE -> detection.fluxLinkage(r, motor)
            COMM_DETECT_MOTOR_FLUX_LINKAGE_OPENLOOP -> detection.fluxLinkageOpenloop(r, motor)
            COMM_DETECT_HALL_FOC -> detection.focHalls(r, motor)
            COMM_DETECT_ENCODER -> detection.encoder(r, motor)
            COMM_DETECT_MOTOR_PARAM -> detection.bldc(r, motor)
            COMM_DETECT_APPLY_ALL_FOC -> detection.applyAllFoc(r)
            COMM_GET_IMU_CALIBRATION -> detection.imuCalibration(r)
            COMM_GET_DECODED_PPM -> reply(DecodedInputsSim.ppm(config(motor, ConfigKind.APPCONF), uptime))
            COMM_GET_DECODED_ADC -> reply(DecodedInputsSim.adc(config(motor, ConfigKind.APPCONF), uptime))
            COMM_GET_DECODED_CHUK -> reply(DecodedInputsSim.nunchuk(uptime))
            COMM_APP_DISABLE_OUTPUT -> Unit
            COMM_RESET_STATS -> {
                motor.stats.reset()
                if (packet.size > 1 && packet[1].toInt() != 0) reply(byteArrayOf(packet[0]))
            }
            COMM_ERASE_NEW_APP, COMM_ERASE_NEW_APP_ALL_CAN -> firmware.eraseNewApp(r)
            COMM_WRITE_NEW_APP_DATA, COMM_WRITE_NEW_APP_DATA_ALL_CAN -> firmware.write(r, lzo = false)
            COMM_WRITE_NEW_APP_DATA_LZO, COMM_WRITE_NEW_APP_DATA_ALL_CAN_LZO -> firmware.write(r, lzo = true)
            COMM_ERASE_BOOTLOADER, COMM_ERASE_BOOTLOADER_ALL_CAN -> firmware.eraseBootloader()
            COMM_JUMP_TO_BOOTLOADER, COMM_JUMP_TO_BOOTLOADER_ALL_CAN -> firmware.jump()
            COMM_FW_INFO -> reply(firmware.info())
            else -> Unit
        }
    }

    /** Only the second motor of this board answers; the simulated CAN bus has no other devices. */
    private fun forward(packet: ByteArray) {
        if (packet.size < 3) return
        val target = motorFor(packet[1].toInt() and 0xFF) ?: return
        handle(packet.copyOfRange(2, packet.size), target)
    }

    internal fun storedConfig(motor: MotorModel, kind: ConfigKind): ByteArray =
        configs.getOrPut(motor to kind) { defaultConfig(motor, kind) }

    internal fun defaultConfig(motor: MotorModel, kind: ConfigKind): ByteArray {
        val defaults = Config.defaults(BundledDefinitions.load(CONFIG_VERSION, kind))
        val id = if (motor === second) controllerId + 1 else controllerId
        return ConfigCodec.serialize(if (kind == ConfigKind.APPCONF) defaults.with("controller_id", id.toLong()) else defaults)
    }

    internal fun config(motor: MotorModel, kind: ConfigKind): Config =
        ConfigCodec.deserialize(BundledDefinitions.load(CONFIG_VERSION, kind), storedConfig(motor, kind))

    /** Stores [config] the way the firmware does after a detection: without a reply. */
    internal fun replaceConfig(motor: MotorModel, kind: ConfigKind, config: Config) {
        configs[motor to kind] = ConfigCodec.serialize(config)
    }

    /** Runs [action] (under the simulator's lock) [delay] from now, for replies the firmware sends when a long job ends. */
    internal fun later(delay: Duration, action: () -> Unit) {
        scheduled += (uptime + delay) to action
        wakeUp.trySend(Unit)
    }

    /** Runs the scheduled actions that are due and returns how long until the next one, null if none is pending. */
    private fun runDue(): Duration? {
        if (scheduled.isEmpty()) return null
        advance()
        while (true) {
            val next = scheduled.minByOrNull { it.first } ?: return null
            val wait = next.first - uptime
            if (wait > Duration.ZERO) return wait
            scheduled.remove(next)
            next.second()
        }
    }

    /** Like the firmware, a config with another layout is not stored and gets a printed warning instead of a reply. */
    internal fun storeConfig(motor: MotorModel, kind: ConfigKind, packet: ByteArray) {
        val payload = packet.copyOfRange(1, packet.size)
        try {
            ConfigCodec.deserialize(BundledDefinitions.load(CONFIG_VERSION, kind), payload)
        } catch (e: PayloadException) {
            print("Warning: Could not set ${kind.name.lowercase()} due to wrong signature")
            return
        }
        configs[motor to kind] = payload
        reply(byteArrayOf(packet[0]))
    }

    private fun pingReply(): ByteArray {
        val w = ByteWriter().u8(COMM_PING_CAN.id)
        if (dualMotor) w.u8(controllerId + 1)
        return w.toByteArray()
    }

    /** Like the firmware, a current below cc_min_current (0.05 A by default) releases the motor. */
    private fun currentDrive(amps: Float, brake: Boolean): Drive = when {
        abs(amps) < MIN_CURRENT -> Drive.Released
        brake -> Drive.Brake(amps)
        else -> Drive.Current(amps)
    }

    /** Like the firmware, motor commands during an emergency stop are dropped but still reset the timeout. */
    private fun command(motor: MotorModel, drive: Drive) {
        if (lastStep >= ignoreInputUntil) motor.drive = drive
        resetTimeout()
    }

    /** COMM_MOTOR_ESTOP acts on the whole board: both motors are released and ignore commands for [ms]. */
    private fun emergencyStop(ms: Int) {
        first.drive = Drive.Released
        second.drive = Drive.Released
        ignoreInputUntil = lastStep + ms.milliseconds
    }

    private fun resetTimeout() {
        lastCommand = lastStep
        timedOut = false
    }

    /** Brings the model up to the current time, applying the command timeout at the moment it expired. */
    private fun advance() {
        val now = started.elapsedNow()
        val deadline = lastCommand + commandTimeout
        if (commandTimeout > Duration.ZERO && !timedOut && now > deadline) {
            stepMotors(deadline - lastStep)
            first.drive = Drive.Released
            second.drive = Drive.Released
            timedOut = true
            lastStep = deadline
        }
        stepMotors(now - lastStep)
        lastStep = now
    }

    private fun stepMotors(elapsed: Duration) {
        val seconds = minOf(elapsed.toDouble(DurationUnit.SECONDS), MAX_ADVANCE_SECONDS).toFloat()
        if (seconds <= 0f) return
        val steps = ceil(seconds / STEP_SECONDS).toInt()
        val dt = seconds / steps
        repeat(steps) {
            val voltage = battery.voltage(first.inputCurrent + second.inputCurrent)
            first.step(dt, voltage)
            second.step(dt, voltage)
            battery.draw((first.inputCurrent + second.inputCurrent) * dt / 3600f)
        }
    }

    private fun firmwareVersion(motor: MotorModel): ByteArray {
        val uuid = uuidFor(motor)
        return ByteWriter().u8(COMM_FW_VERSION.id).u8(firmware.major).u8(firmware.minor)
            .cString(firmware.hardwareName)
            .bytes(uuid)
            .u8(0) // pairing done
            .u8(0) // test version
            .u8(0) // HW_TYPE_VESC
            .u8(0) // custom config count
            .u8(1) // phase filters
            .u8(0) // QML hardware
            .u8(0) // QML app
            .u8(0) // NRF flags
            .cString("")
            .u32(HARDWARE_CRC)
            .toByteArray()
    }

    /** The second motor of a dual board reports the last UUID byte + 1 so config backups stay apart. */
    private fun uuidFor(motor: MotorModel): ByteArray =
        UUID.copyOf().also { if (motor === second) it[it.size - 1]++ }

    /** COMM_GET_VALUES with every field the firmware sends for the full mask, in its order and scales. */
    private fun values(motor: MotorModel): ByteArray {
        val id = if (motor === second) controllerId + 1 else controllerId
        val voltage = battery.voltage(first.inputCurrent + second.inputCurrent)
        return ByteWriter().u8(COMM_GET_VALUES.id)
            .f16(motor.tempFet, 10f).f16(motor.tempMotor, 10f)
            .f32(motor.current, 100f).f32(motor.inputCurrent, 100f)
            .f32(0f, 100f).f32(motor.current, 100f)
            .f16(motor.duty, 1000f).f32(motor.erpm, 1f).f16(voltage, 10f)
            .f32(motor.ampHours.toFloat(), 10000f).f32(motor.ampHoursCharged.toFloat(), 10000f)
            .f32(motor.wattHours.toFloat(), 10000f).f32(motor.wattHoursCharged.toFloat(), 10000f)
            .i32(motor.tachometer.toInt()).i32(motor.tachometerAbs.toInt())
            .u8(0) // fault code
            .f32(motor.angle, 1e6f) // PID position
            .u8(id)
            .f16(motor.tempFet, 10f).f16(motor.tempFet, 10f).f16(motor.tempFet, 10f)
            .f32(0f, 1000f).f32(motor.duty * voltage * VQ_PER_DUTY_VOLT, 1000f)
            .u8(if (timedOut) 1 else 0)
            .toByteArray()
    }

    private fun terminal(command: String, motor: MotorModel) {
        val context = TerminalContext(
            firmwareVersion = "${firmware.major}.${firmware.minor}",
            hardwareName = hardwareName,
            uuid = uuidFor(motor),
            uptime = started.elapsedNow(),
            odometerMeters = (motor.tachometerAbs / TACHOMETER_STEPS_PER_METER).toInt(),
        )
        TerminalReplies.run(command, context).forEach(::print)
    }

    internal fun print(line: String) {
        reply(ByteWriter().u8(COMM_PRINT.id).bytes(line.toByteArray(Charsets.ISO_8859_1)).toByteArray())
    }

    internal fun reply(payload: ByteArray) {
        val frame = PacketCodec.encode(payload)
        var offset = 0
        while (offset < frame.size) {
            val end = if (frame.size - offset <= maxWriteSize) frame.size else offset + maxWriteSize
            outbound.trySend(frame.copyOfRange(offset, end))
            offset = end
        }
    }

    private companion object {
        const val FIRMWARE_MAJOR = 7
        const val FIRMWARE_MINOR = 1
        const val CONFIG_VERSION = "7.01"
        const val HARDWARE_CRC = 0x5E7A11C2L
        val UUID = "SIMULATEDVSC".toByteArray(Charsets.US_ASCII)
        const val MIN_CURRENT = 0.05f
        const val STEP_SECONDS = 0.01f
        const val MAX_ADVANCE_SECONDS = 60.0
        const val VQ_PER_DUTY_VOLT = 0.577f
        const val TACHOMETER_STEPS_PER_METER = 40
    }
}
