package app.foctuner.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigXmlTest {
    private val mcconf = BundledDefinitions.load("7.01", ConfigKind.MCCONF)
    private val appconf = BundledDefinitions.load("7.01", ConfigKind.APPCONF)
    private val defaults = Config.defaults(mcconf)

    private fun read(xml: String, base: Config = defaults, root: String = "MCConfiguration") =
        ConfigXml.read(xml.byteInputStream(), root, base)

    @Test
    fun doublesAreWrittenLikeQtStringNumber() {
        val cases = mapOf(
            0.0 to "0", -0.0 to "0", 60.0 to "60", -60.0 to "-60", 0.5 to "0.5", 0.1 to "0.1", 0.3f.toDouble() to "0.3",
            0.0001 to "0.0001", 0.00012345 to "0.00012345", 1e-5 to "1e-05", 1.5e-5 to "1.5e-05", 2.2e-10 to "2.2e-10",
            100000.0 to "100000", 123456.0 to "123456", 1234567.0 to "1.23457e+06", 1e6 to "1e+06", 3.14159265 to "3.14159",
            99999.96 to "100000", 99999.95 to "99999.9", 9999995.0 to "1e+07", 1.0e100 to "1e+100", 0.000999999 to "0.000999999",
            Double.NaN to "nan", Double.POSITIVE_INFINITY to "inf", Double.NEGATIVE_INFINITY to "-inf",
        )
        for ((value, text) in cases) assertEquals(text, qtNumber(value), "for $value")
    }

    @Test
    fun writesVescToolsLayout() {
        val xml = ConfigXml.write(defaults.with("motor_brand", "A&B <\"x\">").with("l_current_max", 80.5), "MCConfiguration")
        val lines = xml.lines()
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", lines[0])
        assertEquals("<MCConfiguration>", lines[1])
        assertEquals("    <ConfigVersion>4</ConfigVersion>", lines[2])
        assertEquals("    <pwm_mode>1</pwm_mode>", lines[3])
        assertTrue("    <l_current_max>80.5</l_current_max>" in lines)
        assertTrue("    <motor_brand>A&amp;B &lt;&quot;x&quot;&gt;</motor_brand>" in lines)
        assertTrue("    <motor_model></motor_model>" in lines)
        assertTrue(xml.endsWith("\n</MCConfiguration>\n"))
        assertEquals(mcconf.params.size + 5, lines.size, "a line per parameter plus declaration, root, version, end tag and final newline")
    }

    @Test
    fun everyParameterSurvivesARoundTrip() {
        val edited = defaults.with("motor_description", "<p>Line 1\nLine 2</p>\twith tab")
            .with("foc_motor_l", 1.234e-5).with("l_current_min", -42.25).with("motor_type", 1L).with("foc_hall_table__3", 200L)
        val back = read(ConfigXml.write(edited, "MCConfiguration"), Config.defaults(mcconf))
        assertEquals(emptyList(), edited.diff(back.config))
        assertEquals(4, back.configVersion)
        assertEquals(emptyList(), back.unknown)
        assertEquals(emptyList(), back.missing)

        val app = Config.defaults(appconf).with("controller_id", 7L).with("app_ppm_conf.hyst", 0.25)
        val appBack = ConfigXml.read(ConfigXml.write(app, "APPConfiguration").byteInputStream(), "APPConfiguration", Config.defaults(appconf))
        assertEquals(emptyList(), app.diff(appBack.config))
    }

    @Test
    fun readsLikeSetXml() {
        val base = defaults.with("l_current_max", 99.0)
        val result = read(
            """<?xml version="1.0"?>
            <MCConfiguration>
                <l_current_max> 75 </l_current_max>
                <l_current_min>junk</l_current_min>
                <motor_brand> spaced </motor_brand>
                <not_a_param>1</not_a_param>
                <l_in_current_max>2000</l_in_current_max>
            </MCConfiguration>""",
            base,
        )
        assertEquals(75.0, result.config.double("l_current_max"))
        assertEquals(0.0, result.config.double("l_current_min"), "unparseable text reads as 0")
        assertEquals(" spaced ", result.config.text("motor_brand"))
        assertEquals(2000.0, result.config.double("l_in_current_max"), "not clamped on load")
        assertEquals(listOf("not_a_param"), result.unknown)
        assertNull(result.configVersion)
        assertTrue("l_abs_current_max" in result.missing)
        assertEquals(base.double("l_abs_current_max"), result.config.double("l_abs_current_max"))
    }

    @Test
    fun unsignedIntegersAreWrittenAsCIntLikeVescTool() {
        val definition = ConfigDefinition(
            listOf(ParamDefinition("big", ParamType.INT, ParamValue.IntValue(0), wire = WireType.UINT32, maxInt = 4294967295L)),
            serializeOrder = listOf("big"),
        )
        val config = Config.defaults(definition).with("big", 4000000000L)
        val xml = ConfigXml.write(config, "X")
        assertTrue("<big>-294967296</big>" in xml)
        assertEquals(4000000000L, ConfigXml.read(xml.byteInputStream(), "X", Config.defaults(definition)).config.int("big"))
    }

    @Test
    fun refusesFilesWithoutTheRootOrWithADoctype() {
        assertFailsWith<ConfigXmlException> { read("<APPConfiguration><controller_id>3</controller_id></APPConfiguration>") }
        assertFailsWith<ConfigXmlException> { read("not xml") }
        assertFailsWith<ConfigXmlException> {
            read("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><MCConfiguration><motor_brand>&e;</motor_brand></MCConfiguration>")
        }
    }

    @Test
    fun findsTheRootInsideAnotherElementLikeVescTool() {
        val result = read("<wrapper><MCConfiguration><l_current_max>12</l_current_max></MCConfiguration></wrapper>")
        assertEquals(12.0, result.config.double("l_current_max"))
    }
}
