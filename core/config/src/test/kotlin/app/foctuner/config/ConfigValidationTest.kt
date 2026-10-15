package app.foctuner.config

import app.foctuner.config.ValidationIssue.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigValidationTest {
    private val mcconf = BundledDefinitions.load("7.01", ConfigKind.MCCONF)
    private val appconf = BundledDefinitions.load("7.01", ConfigKind.APPCONF)

    private fun kindOf(name: String, value: ParamValue, def: ConfigDefinition = mcconf) = def.validate(name, value)?.kind

    @Test
    fun bundledDefaultsAreValid() {
        assertEquals(emptyList(), Config.defaults(mcconf).validate())
        assertEquals(emptyList(), Config.defaults(appconf).validate())
    }

    @Test
    fun numbersMustBeInsideTheirRange() {
        assertNull(kindOf("l_current_max", ParamValue.DoubleValue(5000.0)))
        assertEquals(Kind.OUT_OF_RANGE, kindOf("l_current_max", ParamValue.DoubleValue(5000.5)))
        assertEquals(Kind.OUT_OF_RANGE, kindOf("l_current_max", ParamValue.DoubleValue(-0.1)))
        assertEquals(Kind.OUT_OF_RANGE, kindOf("l_current_max", ParamValue.DoubleValue(Double.NaN)))
        assertNull(kindOf("hall_table__0", ParamValue.IntValue(-1)))
        assertEquals(Kind.OUT_OF_RANGE, kindOf("hall_table__0", ParamValue.IntValue(7)))
    }

    @Test
    fun optionsMustExist() {
        assertNull(kindOf("pwm_mode", ParamValue.IntValue(2)))
        assertEquals(Kind.INVALID_OPTION, kindOf("pwm_mode", ParamValue.IntValue(3)))
        assertEquals(Kind.INVALID_OPTION, kindOf("pwm_mode", ParamValue.IntValue(-1)))

        val bool = mcconf.params.values.first { it.type == ParamType.BOOL }.name
        assertNull(kindOf(bool, ParamValue.IntValue(1)))
        assertEquals(Kind.INVALID_OPTION, kindOf(bool, ParamValue.IntValue(2)))

        assertNull(kindOf("l_additional_faults", ParamValue.IntValue(255)))
        assertEquals(Kind.INVALID_OPTION, kindOf("l_additional_faults", ParamValue.IntValue(256)))
    }

    @Test
    fun valuesThatWouldWrapOnTheWireAreRejectedEvenInsideTheDeclaredRange() {
        // The XML allows 0..99 but the wire carries value * 1000 as an int16, so 32.768 is the limit.
        assertNull(kindOf("foc_hfi_gain", ParamValue.DoubleValue(32.0)))
        assertEquals(Kind.NOT_ENCODABLE, kindOf("foc_hfi_gain", ParamValue.DoubleValue(50.0)))
        // uint16 on the wire, XML maximum 100000.
        assertEquals(Kind.NOT_ENCODABLE, kindOf("app_adc_conf.update_rate_hz", ParamValue.IntValue(70000), appconf))
        assertNull(kindOf("app_adc_conf.update_rate_hz", ParamValue.IntValue(65535), appconf))
    }

    @Test
    fun stringsRespectMaxLength() {
        val def = ConfigXmlParser.parse(
            "<ConfigParams><Params><s><type>3</type><maxLen>3</maxLen></s><free><type>3</type></free></Params>" +
                "<SerOrder><ser>s</ser></SerOrder></ConfigParams>",
        )
        assertNull(kindOf("s", ParamValue.TextValue("abc"), def))
        assertEquals(Kind.TOO_LONG, kindOf("s", ParamValue.TextValue("abcd"), def))
        assertNull(kindOf("free", ParamValue.TextValue("a long text that has no limit"), def))
    }

    @Test
    fun parametersThatAreNeverSentSkipTheWireCheck() {
        val def = ConfigXmlParser.parse(
            "<ConfigParams><Params><local><type>2</type><vTx>1</vTx><minInt>0</minInt><maxInt>1000</maxInt></local></Params>" +
                "<SerOrder></SerOrder></ConfigParams>",
        )
        assertNull(kindOf("local", ParamValue.IntValue(1000), def))
    }

    @Test
    fun validateListsEveryIssue() {
        val config = Config.defaults(mcconf).with("l_current_max", 9000.0).with("pwm_mode", 9L).with("foc_hfi_gain", 50.0)
        assertEquals(
            listOf("pwm_mode" to Kind.INVALID_OPTION, "l_current_max" to Kind.OUT_OF_RANGE, "foc_hfi_gain" to Kind.NOT_ENCODABLE)
                .sortedBy { mcconf.params.keys.indexOf(it.first) },
            config.validate().map { it.param to it.kind },
        )
        assertTrue(config.validate().all { it.message.startsWith("${it.param}: ") })
    }

    @Test
    fun clampMovesValuesIntoRange() {
        fun clamp(name: String, value: ParamValue) = mcconf.param(name).clamp(value)

        assertEquals(ParamValue.DoubleValue(5000.0), clamp("l_current_max", ParamValue.DoubleValue(6000.0)))
        assertEquals(ParamValue.DoubleValue(0.0), clamp("l_current_max", ParamValue.DoubleValue(-3.0)))
        assertEquals(ParamValue.DoubleValue(60.0), clamp("l_current_max", ParamValue.DoubleValue(Double.NaN)))
        assertEquals(ParamValue.DoubleValue(12.5), clamp("l_current_max", ParamValue.DoubleValue(12.5)))
        assertEquals(ParamValue.IntValue(6), clamp("hall_table__0", ParamValue.IntValue(99)))
        assertEquals(ParamValue.IntValue(2), clamp("pwm_mode", ParamValue.IntValue(7)))
        assertEquals(ParamValue.IntValue(0), clamp("pwm_mode", ParamValue.IntValue(-7)))
        assertEquals(ParamValue.IntValue(255), clamp("l_additional_faults", ParamValue.IntValue(1000)))

        val limited = ConfigXmlParser.parse("<ConfigParams><Params><s><type>3</type><maxLen>3</maxLen></s></Params><SerOrder/></ConfigParams>")
        assertEquals(ParamValue.TextValue("abc"), limited.param("s").clamp(ParamValue.TextValue("abcdef")))
    }
}
