package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Refloat package's own commands, sent as custom app data that starts with its package id 101 (vesc_pkg
 * `refloat/src/main.c`, `on_command_received`). Only the long-standing ones are used: INFO version 2, the legacy
 * realtime data (`COMMAND_GET_RTDATA`, the layout Float and older Refloat apps read), LOCK and HANDTEST.
 */
object Refloat {
    const val PACKAGE_ID = 101
    internal const val INFO = 0
    internal const val GET_RTDATA = 1
    internal const val LOCK = 12
    internal const val HANDTEST = 13

    data class Info(val name: String, val major: Int, val minor: Int, val patch: Int, val suffix: String) {
        val version: String get() = "$major.$minor.$patch" + if (suffix.isNotEmpty()) "-$suffix" else ""
    }

    /** `state_compat` values. */
    enum class State(val code: Int) {
        Startup(0), Running(1), RunningTiltback(2), RunningWheelslip(3), RunningUpsideDown(4), RunningFlywheel(5),
        FaultAnglePitch(6), FaultAngleRoll(7), FaultSwitchHalf(8), FaultSwitchFull(9), FaultStartup(11),
        FaultReverse(12), FaultQuickstop(13), Charging(14), Disabled(15);

        val running: Boolean get() = code in 1..5

        companion object {
            fun of(code: Int): State? = entries.firstOrNull { it.code == code }
        }
    }

    /** Footpad as `footpad_sensor_state_to_switch_compat` reports it. */
    enum class Footpad { None, Half, Both }

    data class Realtime(
        /** Current the balance loop asks for, A. */
        val balanceCurrent: Float,
        /** Pitch the balance loop uses, degrees. */
        val pitch: Float,
        val roll: Float,
        val state: State?,
        /** `sat_compat`: 0 centering, 1 reverse stop, 2 none, 3 duty, 4 high voltage, 5 low voltage, 6 temperature, 7 speed, 8 error. */
        val tiltback: Int,
        val footpad: Footpad,
        val handtest: Boolean,
        /** Why it beeps: 0 none, 1 low voltage, 2 high voltage, 3 FET temp, 4 motor temp, 5 current, 6 duty, ... */
        val beep: Int,
        val adcLeft: Float,
        val adcRight: Float,
        val setpoint: Float,
        val atrSetpoint: Float,
        val brakeTiltSetpoint: Float,
        val torqueTiltSetpoint: Float,
        val turnTiltSetpoint: Float,
        val remoteSetpoint: Float,
        /** Raw IMU pitch, degrees. */
        val imuPitch: Float,
        val filteredCurrent: Float,
    ) {
        companion object {
            /** [data] without the package id and command byte. */
            fun parse(data: ByteArray): Realtime {
                val r = ByteReader(data)
                val current = r.f32Auto()
                val pitch = r.f32Auto()
                val roll = r.f32Auto()
                val stateByte = r.u8()
                val switchByte = r.u8()
                return Realtime(
                    balanceCurrent = current,
                    pitch = pitch,
                    roll = roll,
                    state = State.of(stateByte and 0xF),
                    tiltback = stateByte shr 4,
                    footpad = when (switchByte and 0x7) {
                        2 -> Footpad.Both
                        1 -> Footpad.Half
                        else -> Footpad.None
                    },
                    handtest = switchByte and 0x8 != 0,
                    beep = switchByte shr 4,
                    adcLeft = r.f32Auto(),
                    adcRight = r.f32Auto(),
                    setpoint = r.f32Auto(),
                    atrSetpoint = r.f32Auto(),
                    brakeTiltSetpoint = r.f32Auto(),
                    torqueTiltSetpoint = r.f32Auto(),
                    turnTiltSetpoint = r.f32Auto(),
                    remoteSetpoint = r.f32Auto(),
                    imuPitch = r.f32Auto(),
                    filteredCurrent = r.f32Auto(),
                )
            }
        }
    }
}

/** Refloat's name and version, or null if no Refloat answers (not installed, or not started). */
suspend fun VescController.refloatInfo(timeout: Duration = 1.seconds): Refloat.Info? {
    val data = refloatRequest(byteArrayOf(Refloat.INFO.toByte(), 2, 0), Refloat.INFO, timeout) ?: return null
    return try {
        val r = ByteReader(data)
        if (r.u8() != 2) return null
        r.u8() // flags
        val name = String(r.bytes(20), Charsets.US_ASCII).trimEnd('\u0000')
        val major = r.u8()
        val minor = r.u8()
        val patch = r.u8()
        val suffix = String(r.bytes(20), Charsets.US_ASCII).trimEnd('\u0000')
        Refloat.Info(name, major, minor, patch, suffix)
    } catch (e: IndexOutOfBoundsException) {
        null
    }
}

/** One realtime sample, or null if Refloat didn't answer within [timeout]. */
suspend fun VescController.refloatRealtime(timeout: Duration = 1.seconds): Refloat.Realtime? {
    val data = refloatRequest(byteArrayOf(Refloat.GET_RTDATA.toByte()), Refloat.GET_RTDATA, timeout) ?: return null
    return try {
        Refloat.Realtime.parse(data)
    } catch (e: IndexOutOfBoundsException) {
        null
    }
}

/** Locks (disables) or unlocks the board; Refloat ignores it while riding and stores it in its config. */
suspend fun VescController.refloatLock(locked: Boolean) =
    sendCustomAppData(byteArrayOf(Refloat.PACKAGE_ID.toByte(), Refloat.LOCK.toByte(), if (locked) 1 else 0))

/** Hand test mode (only from Ready): weak balance with tilts and I-term off, to check direction and response by hand. */
suspend fun VescController.refloatHandtest(on: Boolean) =
    sendCustomAppData(byteArrayOf(Refloat.PACKAGE_ID.toByte(), Refloat.HANDTEST.toByte(), if (on) 1 else 0))

private suspend fun VescController.refloatRequest(payload: ByteArray, command: Int, timeout: Duration): ByteArray? = coroutineScope {
    val reply = async(start = CoroutineStart.UNDISPATCHED) {
        customAppData.first { it.size >= 2 && (it[0].toInt() and 0xFF) == Refloat.PACKAGE_ID && (it[1].toInt() and 0xFF) == command && it.size > 2 }
    }
    sendCustomAppData(byteArrayOf(Refloat.PACKAGE_ID.toByte()) + payload)
    val data = withTimeoutOrNull(timeout) { reply.await() }
    reply.cancel()
    data?.copyOfRange(2, data.size)
}
