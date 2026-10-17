package app.foctuner.vesc

import app.foctuner.config.Config
import app.foctuner.config.ConfigCodec
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ConfigXmlParser
import app.foctuner.config.QtCompression
import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_CUSTOM_CONFIG
import app.foctuner.protocol.CommPacketId.COMM_GET_CUSTOM_CONFIG_DEFAULT
import app.foctuner.protocol.CommPacketId.COMM_GET_CUSTOM_CONFIG_XML
import app.foctuner.protocol.CommPacketId.COMM_SET_CUSTOM_CONFIG
import kotlin.time.Duration.Companion.milliseconds

/*
 * Custom configs: settings a controller defines itself rather than the firmware's motor and app config. The VESC
 * Express has its own, and packages such as Refloat register one from their native code. FirmwareInfo.customConfigCount
 * says how many there are. Each comes with a definition in VESC Tool's parameter XML (compressed, downloaded from the
 * controller), and its values are serialized by that definition exactly like the motor config.
 */

/** The page title VESC Tool shows: the long name of the definition's `hw_name` placeholder parameter. */
val ConfigDefinition.customConfigName: String?
    get() = params["hw_name"]?.longName?.takeIf { it.isNotBlank() }

/**
 * Downloads custom config [index]'s definition as the controller stores it (Qt-compressed XML), for caching and for
 * [parseCustomConfigDefinition]. [onProgress] gets bytes read and the total.
 */
suspend fun VescController.readCustomConfigDefinition(
    index: Int,
    onProgress: (read: Int, total: Int) -> Unit = { _, _ -> },
): ByteArray = readChunked(
    COMM_GET_CUSTOM_CONFIG_XML,
    request = { offset, length -> ByteWriter().u8(COMM_GET_CUSTOM_CONFIG_XML.id).i8(index).i32(length).i32(offset).toByteArray() },
    parse = { r, offset ->
        val replyIndex = r.i8()
        val total = r.i32()
        if (replyIndex == index && r.i32() == offset) total to r.restBytes() else null
    },
    onProgress = onProgress,
)

/**
 * @throws java.util.zip.ZipException if [data] isn't compressed the way the firmware stores definitions
 * @throws app.foctuner.config.ConfigDefinitionException if the XML isn't a valid definition
 */
fun parseCustomConfigDefinition(data: ByteArray): ConfigDefinition =
    QtCompression.uncompress(data).inputStream().use(ConfigXmlParser::parse)

/**
 * Reads custom config [index]. [base] supplies the definition and the values of parameters that aren't stored on
 * the controller.
 *
 * @throws app.foctuner.config.SignatureMismatchException if the controller's layout differs from the definition
 */
suspend fun VescController.readCustomConfig(index: Int, base: Config): Config = readCustomConfig(index, base, defaults = false)

/** The defaults of custom config [index] as the controller (or the package that registered it) defines them. */
suspend fun VescController.readDefaultCustomConfig(index: Int, base: Config): Config = readCustomConfig(index, base, defaults = true)

/**
 * Stores [config] as custom config [index]. The STM32 firmware acknowledges even when the package that owns the
 * config refuses the values (wrong layout), so read it back to see what was applied.
 */
suspend fun VescController.writeCustomConfig(index: Int, config: Config) {
    val payload = ByteWriter().u8(COMM_SET_CUSTOM_CONFIG.id).i8(index).bytes(ConfigCodec.serialize(config)).toByteArray()
    // The acknowledgement is the bare command id, although VESC Tool expects the index after it.
    session.request(forwarded(payload), COMM_SET_CUSTOM_CONFIG, session.options.configWriteTimeout) { }
}

private suspend fun VescController.readCustomConfig(index: Int, base: Config, defaults: Boolean): Config {
    val id = if (defaults) COMM_GET_CUSTOM_CONFIG_DEFAULT else COMM_GET_CUSTOM_CONFIG
    val request = ByteWriter().u8(id.id).i8(index).toByteArray()
    val payload = retrying(READ_TRIES) {
        exchange(request, id, READ_TIMEOUT) { r: ByteReader -> if (r.i8() == index) r.restBytes() else null }
    } ?: throw noReply(id, READ_TIMEOUT)
    return ConfigCodec.deserialize(base.definition, payload, base)
}

private const val READ_TRIES = 3
private val READ_TIMEOUT = 1500.milliseconds
