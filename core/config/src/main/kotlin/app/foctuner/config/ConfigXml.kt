package app.foctuner.config

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/** The root element VESC Tool gives a saved config of this kind (its files and its config cache). */
val ConfigKind.xmlRoot: String
    get() = when (this) {
        ConfigKind.MCCONF -> "MCConfiguration"
        ConfigKind.APPCONF -> "APPConfiguration"
    }

class ConfigXmlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A config read from XML. [configVersion] is the file's `ConfigVersion` (null if it has none); VESC Tool warns when
 * it differs from [ConfigXml.CONFIG_VERSION]. [unknown] are elements the definition doesn't have, [missing] are
 * parameters the file doesn't set, which keep their value from the base config.
 */
data class XmlConfig(
    val config: Config,
    val configVersion: Int?,
    val unknown: List<String>,
    val missing: List<String>,
)

/**
 * VESC Tool's config XML (`ConfigParams::getXML` / `setXML`), byte for byte: every parameter in definition order,
 * including the ones the controller doesn't store (motor brand, description, quality), doubles as Qt prints them.
 */
object ConfigXml {
    /** `VT_CONFIG_VERSION` of the VESC Tool releases whose definitions we bundle. */
    const val CONFIG_VERSION = 4

    fun write(config: Config, root: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<").append(root).append('>')
        element("ConfigVersion", CONFIG_VERSION.toString())
        for (p in config.definition.params.values) {
            val text = when (val value = config[p.name]) {
                is ParamValue.DoubleValue -> qtNumber(value.value)
                // VESC Tool keeps every integer in a C int, so uint32 values above 2^31 are written negative.
                is ParamValue.IntValue -> value.value.toInt().toString()
                is ParamValue.TextValue -> value.value
            }
            if (p.type != ParamType.UNDEFINED) element(p.name, text)
        }
        append("\n</").append(root).append(">\n")
    }

    /**
     * Reads the first element named [root] and applies its children to [base], as VESC Tool's `setXML` does: values
     * are taken as they are (not clamped), and text that isn't a number reads as 0.
     *
     * @throws ConfigXmlException if the XML is malformed or has no [root] element
     */
    fun read(input: InputStream, root: String, base: Config): XmlConfig {
        val handler = Handler(root, base.definition)
        val bytes = input.readBytes()
        // Android's parser doesn't know the feature that refuses a DOCTYPE, so it is refused here on every platform.
        if (DOCTYPE.containsMatchIn(String(bytes, Charsets.UTF_8))) throw ConfigXmlException("A DOCTYPE is not allowed in a config file")
        try {
            SAXParserFactory.newInstance().newSAXParser().parse(InputSource(bytes.inputStream()), handler)
        } catch (e: SAXException) {
            throw ConfigXmlException("Not a config file: ${e.message}", e)
        }
        if (!handler.found) throw ConfigXmlException("No <$root> element")
        val missing = base.definition.params.values
            .filter { it.type != ParamType.UNDEFINED && it.name !in handler.values }
            .map { it.name }
        return XmlConfig(base.withDecoded(handler.values), handler.version, handler.unknown, missing)
    }

    private val DOCTYPE = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)

    private fun StringBuilder.element(name: String, text: String) {
        append("\n    <").append(name).append('>')
        escape(text)
        append("</").append(name).append('>')
    }

    /** `QXmlStreamWriter::writeCharacters`: markup characters and quotes escaped, other control characters dropped. */
    private fun StringBuilder.escape(text: String) {
        for (c in text) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\t', '\n', '\r' -> append(c)
                else -> if (c.code in 0x20 until 0xFFFE) append(c)
            }
        }
    }

    private class Handler(private val root: String, private val definition: ConfigDefinition) : DefaultHandler() {
        var found = false
        var version: Int? = null
        val values = LinkedHashMap<String, ParamValue>()
        val unknown = mutableListOf<String>()

        private var depth = 0
        private var rootDepth = -1
        private var child: String? = null
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
            depth++
            when {
                rootDepth < 0 && !found && qName == root -> {
                    rootDepth = depth
                    found = true
                }
                rootDepth > 0 && depth == rootDepth + 1 -> {
                    child = qName
                    text.setLength(0)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (child != null && depth == rootDepth + 1) text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (rootDepth > 0 && depth == rootDepth + 1) child?.let { take(it, text.toString()) }
            if (depth == rootDepth + 1) child = null
            if (depth == rootDepth) rootDepth = -1
            depth--
        }

        private fun take(name: String, text: String) {
            if (name == "ConfigVersion") {
                version = text.trim().toIntOrNull() ?: 0
                return
            }
            val p = definition.params[name]
            if (p == null || p.type == ParamType.UNDEFINED) {
                unknown += name
                return
            }
            values[name] = when (p.type) {
                ParamType.DOUBLE -> ParamValue.DoubleValue(parseDouble(text))
                ParamType.STRING -> ParamValue.TextValue(text)
                else -> {
                    val v = text.trim().toIntOrNull()?.toLong() ?: 0L
                    ParamValue.IntValue(if (p.wire == WireType.UINT32) v and 0xFFFFFFFFL else v)
                }
            }
        }

        private fun parseDouble(text: String): Double = when (val t = text.trim().lowercase()) {
            "nan" -> Double.NaN
            "inf", "+inf" -> Double.POSITIVE_INFINITY
            "-inf" -> Double.NEGATIVE_INFINITY
            else -> t.toDoubleOrNull() ?: 0.0
        }
    }
}
