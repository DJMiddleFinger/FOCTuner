package app.foctuner.vesc

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepliesTest {
    // Written in the same order and scales as COMM_FW_VERSION in the firmware's commands.c.
    @Test
    fun parsesFullFirmwareInfo() {
        val w = ByteWriter().u8(7).u8(1).cString("60_MK6")
            .bytes(ByteArray(12) { it.toByte() })
            .u8(1).u8(0).u8(0).u8(2).u8(1).u8(0).u8(1).u8(3)
            .cString("Refloat").u32(0xDEADBEEFL)
        val info = FirmwareInfo.parse(ByteReader(w.toByteArray()))
        assertEquals("7.01", info.version)
        assertEquals("60_MK6", info.hardwareName)
        assertEquals("000102030405060708090a0b", info.uuid)
        assertTrue(info.pairingDone)
        assertEquals(HardwareType.VESC, info.hardwareType)
        assertEquals(2, info.customConfigCount)
        assertTrue(info.hasPhaseFilters)
        assertEquals(3, info.nrfFlags)
        assertEquals("Refloat", info.firmwareName)
        assertEquals(0xDEADBEEFL, info.hardwareCrc)
    }

    @Test
    fun parsesShortFirmwareInfoFromOldFirmware() {
        val w = ByteWriter().u8(3).u8(40).cString("410")
        val info = FirmwareInfo.parse(ByteReader(w.toByteArray()))
        assertEquals("3.40", info.version)
        assertEquals("", info.uuid)
        assertEquals("", info.firmwareName)
    }

    @Test
    fun parsesRealtimeValues() {
        val w = ByteWriter()
            .f16(41.3f, 10f).f16(55.0f, 10f)
            .f32(-12.5f, 100f).f32(3.25f, 100f).f32(0.5f, 100f).f32(-12.4f, 100f)
            .f16(-0.452f, 1000f).f32(-15000f, 1f).f16(50.4f, 10f)
            .f32(1.2345f, 10000f).f32(0.1f, 10000f).f32(60.5f, 10000f).f32(4f, 10000f)
            .i32(-1234).i32(98765).u8(FaultCode.OVER_TEMP_FET.ordinal)
            .f32(0.5f, 1e6f).u8(37)
            .f16(40f, 10f).f16(41f, 10f).f16(42f, 10f)
            .f32(1.5f, 1000f).f32(-2.5f, 1000f).u8(0b10)
        val v = RealtimeValues.parse(ByteReader(w.toByteArray()))
        assertEquals(41.3f, v.tempMosfet)
        assertEquals(-12.5f, v.currentMotor)
        assertEquals(-0.452f, v.dutyCycle)
        assertEquals(-15000f, v.erpm)
        assertEquals(50.4f, v.voltageInput)
        assertEquals(1.2345f, v.ampHours)
        assertEquals(-1234, v.tachometer)
        assertEquals(FaultCode.OVER_TEMP_FET, v.fault)
        assertEquals(37, v.controllerId)
        assertEquals(42f, v.tempMosfet3)
        assertEquals(-2.5f, v.voltageQ)
        assertFalse(v.timedOut)
        assertTrue(v.killSwitchActive)
    }

    @Test
    fun unknownFaultCodeIsNull() {
        assertEquals(null, FaultCode.fromCode(200))
        assertEquals(FaultCode.ABS_OVERSPEED, FaultCode.fromCode(33))
    }
}
