package app.foctuner.vesc.firmware

import app.foctuner.vesc.FirmwareInfo
import app.foctuner.vesc.HardwareType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootloadersTest {
    private fun pick(hardware: String, type: HardwareType = HardwareType.VESC, uuid: String = "53494d554c41544544565343") =
        Bootloaders.forController(FirmwareInfo(7, 1, hardware, uuid, hardwareType = type))

    @Test
    fun picksTheBootloaderNamingTheHardwareElseTheGenericOne() {
        val mk6 = pick("60_mk5").single()
        assertTrue("60_MK5" in mk6.hardware && !mk6.generic)
        assertEquals(HardwareCheck.Match, mk6.check("60_MK5"))
        assertEquals("40 & 47 & 48 & 410 & 411 & 412 & DAS_RS", pick("410").single().name)
        assertTrue(pick("FLIPSKY_75_450").single().generic)
        assertEquals("generic", pick("18s_light", HardwareType.VESC_BMS).single().name)
        assertEquals("stm32g431", pick("str-dcdc", HardwareType.CUSTOM_MODULE).single().name)
        assertTrue(pick("VESC Express T", HardwareType.CUSTOM_MODULE, uuid = "a0b1c2d3e4f5000000000000").isEmpty())
    }
}
