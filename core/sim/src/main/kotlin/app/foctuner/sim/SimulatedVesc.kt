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
import app.foctuner.protocol.CommPacketId.COMM_FORWARD_CAN
import app.foctuner.protocol.CommPacketId.COMM_FW_VERSION
import app.foctuner.protocol.CommPacketId.COMM_GET_APPCONF
import app.foctuner.protocol.CommPacketId.COMM_GET_APPCONF_DEFAULT
import app.foctuner.protocol.CommPacketId.COMM_GET_MCCONF
import app.foctuner.protocol.CommPacketId.COMM_GET_MCCONF_DEFAULT
import app.foctuner.protocol.CommPacketId.COMM_GET_STATS
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES
import app.foctuner.protocol.CommPacketId.COMM_PING_CAN
import app.foctuner.protocol.CommPacketId.COMM_PRINT
import app.foctuner.protocol.CommPacketId.COMM_RESET_STATS
import app.foctuner.protocol.CommPacketId.COMM_SET_APPCONF
import app.foctuner.protocol.CommPacketId.COMM_SET_MCCONF
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT
import app.foctuner.protocol.CommPacketId.COMM_SET_CURRENT_BRAKE
import app.foctuner.protocol.CommPacketId.COMM_SET_DUTY
import app.foctuner.protocol.CommPacketId.COMM_SET_RPM
import app.foctuner.protocol.CommPacketId.COMM_TERMINAL_CMD
import app.foctuner.protocol.PacketCodec
import app.foctuner.protocol.PacketDecoder
import app.foctuner.transport.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * An in-memory VESC, for tests and demo mode: a [Transport] that answers like firmware 7.01 on a
 * dual-motor board. The attached motor has CAN id [controllerId]; with [dualMotor] a second one answers to
 * `controllerId + 1` through COMM_FORWARD_CAN and shows up in COMM_PING_CAN, as on real dual-motor hardware.
 * Forwarding to any other id goes nowhere, so the request times out.
 *
 * Supports COMM_FW_VERSION, COMM_GET_VALUES, the four motor commands, COMM_ALIVE, COMM_TERMINAL_CMD,
 * COMM_PING_CAN, COMM_FORWARD_CAN, COMM_GET_STATS / COMM_RESET_STATS and reading and writing the motor and app config (7.01 layout, starting
 * from the defaults); other commands are ignored like unknown ones in the firmware.
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
    override val received: Flow<ByteArray> = outbound.receiveAsFlow()

    private val lock = Any()
    private val decoder = PacketDecoder()
    private val started = timeSource.markNow()

    /** Time since the simulated controller booted. */
    internal val uptime: Duration get() = started.elapsedNow()
    private val battery = Battery()
    private val first = MotorModel()
    private val second = MotorModel()
    private var lastStep = Duration.ZERO
    private var lastCommand = Duration.ZERO
    private var timedOut = false
    private var closed = false

    /** Each motor's stored configs in wire form, created on first use (parsing the definitions is slow). */
    private val configs = HashMap<Pair<MotorModel, ConfigKind>, ByteArray>()

    override suspend fun write(bytes: ByteArray) {
        synchronized(lock) {
            if (closed) throw IOException("Simulated link is closed")
            for (packet in decoder.feed(bytes)) handle(packet, first)
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

    internal fun motorFor(id: Int): MotorModel? = when {
        id == controllerId -> first
        dualMotor && id == controllerId + 1 -> second
        else -> null
    }

    private fun handle(packet: ByteArray, motor: MotorModel) {
        val id = CommPacketId.fromId(packet[0].toInt() and 0xFF) ?: return
        val r = ByteReader(packet, 1)
        advance()
        when (id) {
            COMM_FW_VERSION -> reply(firmwareVersion(motor))
            COMM_GET_VALUES -> reply(values(motor))
            COMM_SET_DUTY -> command(motor, Drive.Duty(r.i32() / 1e5f))
            COMM_SET_CURRENT -> command(motor, currentDrive(r.i32() / 1e3f, brake = false))
            COMM_SET_CURRENT_BRAKE -> command(motor, currentDrive(r.i32() / 1e3f, brake = true))
            COMM_SET_RPM -> command(motor, Drive.Speed(r.i32().toFloat()))
            COMM_ALIVE -> resetTimeout()
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
            COMM_RESET_STATS -> {
                motor.stats.reset()
                if (packet.size > 1 && packet[1].toInt() != 0) reply(byteArrayOf(packet[0]))
            }
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

    private fun command(motor: MotorModel, drive: Drive) {
        motor.drive = drive
        resetTimeout()
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
        return ByteWriter().u8(COMM_FW_VERSION.id).u8(FIRMWARE_MAJOR).u8(FIRMWARE_MINOR)
            .cString(hardwareName)
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
            .f32(0f, 1e6f) // PID position
            .u8(id)
            .f16(motor.tempFet, 10f).f16(motor.tempFet, 10f).f16(motor.tempFet, 10f)
            .f32(0f, 1000f).f32(motor.duty * voltage * VQ_PER_DUTY_VOLT, 1000f)
            .u8(if (timedOut) 1 else 0)
            .toByteArray()
    }

    private fun terminal(command: String, motor: MotorModel) {
        val context = TerminalContext(
            firmwareVersion = "$FIRMWARE_MAJOR.$FIRMWARE_MINOR",
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
