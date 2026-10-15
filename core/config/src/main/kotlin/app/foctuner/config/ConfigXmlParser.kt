package app.foctuner.config

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

/**
 * Reads VESC Tool's `parameters_mcconf.xml` / `parameters_appconf.xml`. Like VESC Tool it rejects elements it does
 * not know, so a format change shows up as an error instead of a silently wrong layout. SAX rather than StAX
 * because StAX is missing on Android.
 */
object ConfigXmlParser {
    private const val SEPARATOR_PREFIX = "::sep::"

    fun parse(input: InputStream): ConfigDefinition {
        val handler = Handler()
        try {
            newParser().parse(input, handler)
        } catch (e: SAXException) {
            throw ConfigDefinitionException(e.message ?: "invalid XML", e)
        }
        return handler.result()
    }

    fun parse(xml: String): ConfigDefinition = parse(xml.byteInputStream(Charsets.UTF_8))

    private fun newParser(): SAXParser {
        val factory = SAXParserFactory.newInstance()
        // Android's parser does not know the Xerces features; it never resolves external entities anyway.
        for (feature in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl",
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
        )) {
            runCatching { factory.setFeature(feature, feature.endsWith("disallow-doctype-decl")) }
        }
        return factory.newSAXParser()
    }

    private fun fail(message: String): Nothing = throw SAXException(message)

    private class Handler : DefaultHandler() {
        private val path = ArrayList<String>()
        private val text = StringBuilder()

        private val params = ArrayList<ParamDefinition>()
        private val order = ArrayList<String>()
        private val groups = ArrayList<ParamGroup>()
        private var seenParams = false
        private var seenOrder = false

        private var param: ParamBuilder? = null
        private var groupName: String? = null
        private val subgroups = ArrayList<ParamSubgroup>()
        private var subgroupName: String? = null
        private val entries = ArrayList<GroupEntry>()

        fun result(): ConfigDefinition {
            if (!seenParams || !seenOrder) throw ConfigDefinitionException("missing <Params> or <SerOrder>")
            return ConfigDefinition(params, order, groups)
        }

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
            path.add(qName)
            text.setLength(0)
            val section = path.getOrNull(1)
            when (path.size) {
                1 -> if (qName != "ConfigParams") fail("root element is <$qName>, expected <ConfigParams>")
                2 -> when (qName) {
                    "Params" -> seenParams = true
                    "SerOrder" -> seenOrder = true
                    "Grouping" -> Unit
                    else -> fail("unknown section <$qName>")
                }
                3 -> when (section) {
                    "Params" -> param = ParamBuilder(qName)
                    "SerOrder" -> if (qName != "ser") fail("unexpected <$qName> in <SerOrder>")
                    else -> {
                        if (qName != "group") fail("unexpected <$qName> in <Grouping>")
                        groupName = null
                        subgroups.clear()
                    }
                }
                4 -> if (section == "Grouping") {
                    when (qName) {
                        "groupName" -> Unit
                        "subgroup" -> {
                            subgroupName = null
                            entries.clear()
                        }
                        else -> fail("unexpected <$qName> in <group>")
                    }
                }
                5 -> if (qName != "subgroupName" && qName != "subgroupParams") fail("unexpected <$qName> in <subgroup>")
                6 -> if (qName != "param") fail("unexpected <$qName> in <subgroupParams>")
                else -> fail("<$qName> is nested too deeply")
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString()
            when (path.size) {
                3 -> when (path[1]) {
                    "Params" -> params.add(requireNotNull(param).build())
                    "SerOrder" -> order.add(value)
                    else -> groups.add(ParamGroup(groupName ?: "unknownGroup", subgroups.toList()))
                }
                4 -> when (path[1]) {
                    "Params" -> requireNotNull(param).set(qName, value)
                    else -> when (qName) {
                        "groupName" -> groupName = value
                        else -> subgroups.add(ParamSubgroup(subgroupName ?: "unknownSubgroup", entries.toList()))
                    }
                }
                5 -> if (qName == "subgroupName") subgroupName = value
                6 -> entries.add(
                    if (value.startsWith(SEPARATOR_PREFIX)) {
                        GroupEntry.Separator(value.removePrefix(SEPARATOR_PREFIX))
                    } else {
                        GroupEntry.Param(value)
                    },
                )
            }
            path.removeAt(path.lastIndex)
        }
    }

    private class ParamBuilder(private val name: String) {
        private var type = ParamType.UNDEFINED
        private var wire = WireType.UNDEFINED
        private var wireScale = 1.0
        private var longName = name
        private var description = ""
        private var cDefine = ""
        private var valDouble = 0.0
        private var valInt = 0L
        private var valString = ""
        private var minDouble = 0.0
        private var maxDouble = 99.0
        private var stepDouble = 1.0
        private var decimals = 2
        private var minInt = 0L
        private var maxInt = 99L
        private var stepInt = 1L
        private var maxLength = 0
        private val enumNames = ArrayList<String>()
        private var suffix = ""
        private var editorScale = 1.0
        private var editAsPercentage = false
        private var showDisplay = false
        private var transmittable = true

        fun set(field: String, text: String) {
            when (field) {
                "longName" -> longName = text
                "description" -> description = text
                "cDefine" -> cDefine = text
                "suffix" -> suffix = text
                "valString" -> valString = text
                "enumNames" -> enumNames.add(text)
                "type" -> type = ParamType.fromId(int(field, text)) ?: fail("$name: unknown type $text")
                "vTx" -> wire = WireType.fromId(int(field, text)) ?: fail("$name: unknown vTx $text")
                "vTxDoubleScale" -> wireScale = double(field, text)
                "valDouble" -> valDouble = double(field, text)
                "valInt" -> valInt = long(field, text)
                "minDouble" -> minDouble = double(field, text)
                "maxDouble" -> maxDouble = double(field, text)
                "stepDouble" -> stepDouble = double(field, text)
                "editorDecimalsDouble" -> decimals = int(field, text)
                "minInt" -> minInt = long(field, text)
                "maxInt" -> maxInt = long(field, text)
                "stepInt" -> stepInt = long(field, text)
                "maxLen" -> maxLength = int(field, text)
                "editorScale" -> editorScale = double(field, text)
                "editAsPercentage" -> editAsPercentage = long(field, text) != 0L
                "showDisplay" -> showDisplay = long(field, text) != 0L
                "transmittable" -> transmittable = long(field, text) != 0L
                else -> fail("$name: unknown field <$field>")
            }
        }

        fun build(): ParamDefinition = ParamDefinition(
            name = name,
            type = type,
            default = when (type) {
                ParamType.DOUBLE -> ParamValue.DoubleValue(valDouble)
                ParamType.STRING -> ParamValue.TextValue(valString)
                else -> ParamValue.IntValue(valInt)
            },
            wire = wire,
            wireScale = wireScale,
            longName = longName,
            description = description,
            cDefine = cDefine,
            minDouble = minDouble,
            maxDouble = maxDouble,
            stepDouble = stepDouble,
            decimals = decimals,
            minInt = minInt,
            maxInt = maxInt,
            stepInt = stepInt,
            maxLength = maxLength,
            enumNames = enumNames.toList(),
            suffix = suffix,
            editorScale = editorScale,
            editAsPercentage = editAsPercentage,
            showDisplay = showDisplay,
            transmittable = transmittable,
        )

        private fun long(field: String, text: String): Long =
            text.trim().toLongOrNull() ?: fail("$name: <$field> is not an integer: '$text'")

        private fun int(field: String, text: String): Int =
            text.trim().toIntOrNull() ?: fail("$name: <$field> is not an integer: '$text'")

        private fun double(field: String, text: String): Double =
            text.trim().toDoubleOrNull() ?: fail("$name: <$field> is not a number: '$text'")
    }
}
