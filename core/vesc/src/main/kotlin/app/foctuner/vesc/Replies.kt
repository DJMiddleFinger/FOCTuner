package app.foctuner.vesc

import app.foctuner.protocol.ByteReader

/*
 * Reply payloads, ported from comm/commands.c in the firmware. Parsers take a reader positioned just
 * after the command id byte. Older firmware sends shorter replies, so trailing fields are optional.
 */

data class FirmwareInfo(
    val major: Int,
    val minor: Int,
    val hardwareName: String,
    /** STM32 unique id as hex; the second motor of a dual-motor board reports the last byte + 1. */
    val uuid: String,
    val pairingDone: Boolean = false,
    /** Non-zero on beta/test builds. */
    val testVersion: Int = 0,
    val hardwareType: HardwareType = HardwareType.VESC,
    val customConfigCount: Int = 0,
    val hasPhaseFilters: Boolean = false,
    /** 0 none, 1 embedded, 2 fullscreen. */
    val qmlHardware: Int = 0,
    val qmlApp: Int = 0,
    val nrfFlags: Int = 0,
    val firmwareName: String = "",
    val hardwareCrc: Long = 0,
) {
    val version: String get() = "$major.${minor.toString().padStart(2, '0')}"

    companion object {
        fun parse(r: ByteReader): FirmwareInfo {
            val major = r.u8()
            val minor = r.u8()
            val hwName = r.cString()
            val uuid = if (r.remaining >= 12) r.bytes(12).toHex() else ""
            fun u8OrZero() = if (r.remaining > 0) r.u8() else 0
            return FirmwareInfo(
                major = major,
                minor = minor,
                hardwareName = hwName,
                uuid = uuid,
                pairingDone = u8OrZero() != 0,
                testVersion = u8OrZero(),
                hardwareType = HardwareType.entries.getOrElse(u8OrZero()) { HardwareType.VESC },
                customConfigCount = u8OrZero(),
                hasPhaseFilters = u8OrZero() != 0,
                qmlHardware = u8OrZero(),
                qmlApp = u8OrZero(),
                nrfFlags = u8OrZero(),
                firmwareName = if (r.remaining > 0) r.cString() else "",
                hardwareCrc = if (r.remaining >= 4) r.u32() else 0,
            )
        }
    }
}

enum class HardwareType { VESC, VESC_BMS, CUSTOM_MODULE }

/** COMM_GET_VALUES. Currents and voltages are averages since the previous request. */
data class RealtimeValues(
    val tempMosfet: Float,
    val tempMotor: Float,
    val currentMotor: Float,
    val currentInput: Float,
    val currentD: Float,
    val currentQ: Float,
    /** -1..1 */
    val dutyCycle: Float,
    /** Electrical RPM; divide by pole pairs for mechanical. */
    val erpm: Float,
    val voltageInput: Float,
    val ampHours: Float,
    val ampHoursCharged: Float,
    val wattHours: Float,
    val wattHoursCharged: Float,
    val tachometer: Int,
    val tachometerAbs: Int,
    val faultCode: Int,
    val pidPosition: Float = 0f,
    val controllerId: Int = 0,
    val tempMosfet1: Float = 0f,
    val tempMosfet2: Float = 0f,
    val tempMosfet3: Float = 0f,
    val voltageD: Float = 0f,
    val voltageQ: Float = 0f,
    /** No command received within the app timeout; the motor has been released. */
    val timedOut: Boolean = false,
    val killSwitchActive: Boolean = false,
) {
    val fault: FaultCode? get() = FaultCode.fromCode(faultCode)

    companion object {
        fun parse(r: ByteReader): RealtimeValues {
            val tempMosfet = r.f16(10f)
            val tempMotor = r.f16(10f)
            val currentMotor = r.f32(100f)
            val currentInput = r.f32(100f)
            val currentD = r.f32(100f)
            val currentQ = r.f32(100f)
            val duty = r.f16(1000f)
            val erpm = r.f32(1f)
            val voltage = r.f16(10f)
            val ah = r.f32(10000f)
            val ahCharged = r.f32(10000f)
            val wh = r.f32(10000f)
            val whCharged = r.f32(10000f)
            val tacho = r.i32()
            val tachoAbs = r.i32()
            val fault = r.u8()
            val pidPos = if (r.remaining >= 4) r.f32(1e6f) else 0f
            val controllerId = if (r.remaining >= 1) r.u8() else 0
            val hasMosTemps = r.remaining >= 6
            val t1 = if (hasMosTemps) r.f16(10f) else 0f
            val t2 = if (hasMosTemps) r.f16(10f) else 0f
            val t3 = if (hasMosTemps) r.f16(10f) else 0f
            val vd = if (r.remaining >= 4) r.f32(1000f) else 0f
            val vq = if (r.remaining >= 4) r.f32(1000f) else 0f
            val status = if (r.remaining >= 1) r.u8() else 0
            return RealtimeValues(
                tempMosfet, tempMotor, currentMotor, currentInput, currentD, currentQ, duty, erpm, voltage,
                ah, ahCharged, wh, whCharged, tacho, tachoAbs, fault, pidPos, controllerId, t1, t2, t3, vd, vq,
                timedOut = status and 1 != 0,
                killSwitchActive = status and 2 != 0,
            )
        }
    }
}

/** `mc_fault_code` in the firmware; the ordinal is the wire value. */
enum class FaultCode {
    NONE,
    OVER_VOLTAGE,
    UNDER_VOLTAGE,
    DRV,
    ABS_OVER_CURRENT,
    OVER_TEMP_FET,
    OVER_TEMP_MOTOR,
    GATE_DRIVER_OVER_VOLTAGE,
    GATE_DRIVER_UNDER_VOLTAGE,
    MCU_UNDER_VOLTAGE,
    BOOTING_FROM_WATCHDOG_RESET,
    ENCODER_SPI,
    ENCODER_SINCOS_BELOW_MIN_AMPLITUDE,
    ENCODER_SINCOS_ABOVE_MAX_AMPLITUDE,
    FLASH_CORRUPTION,
    HIGH_OFFSET_CURRENT_SENSOR_1,
    HIGH_OFFSET_CURRENT_SENSOR_2,
    HIGH_OFFSET_CURRENT_SENSOR_3,
    UNBALANCED_CURRENTS,
    BRK,
    RESOLVER_LOT,
    RESOLVER_DOS,
    RESOLVER_LOS,
    FLASH_CORRUPTION_APP_CFG,
    FLASH_CORRUPTION_MC_CFG,
    ENCODER_NO_MAGNET,
    ENCODER_MAGNET_TOO_STRONG,
    PHASE_FILTER,
    ENCODER_FAULT,
    LV_OUTPUT_FAULT,
    ENCODER_SLIP,
    OVERSPEED,
    UNDERSPEED,
    ABS_OVERSPEED,
    ;

    companion object {
        /** Null for codes added by firmware newer than this app. */
        fun fromCode(code: Int): FaultCode? = entries.getOrNull(code)
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
