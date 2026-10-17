package app.foctuner.sim

import app.foctuner.config.Config
import app.foctuner.config.ConfigCodec
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ConfigXmlParser
import app.foctuner.config.PayloadException
import app.foctuner.config.QtCompression
import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId
import app.foctuner.protocol.CommPacketId.COMM_GET_CUSTOM_CONFIG_XML
import app.foctuner.protocol.CommPacketId.COMM_SET_CUSTOM_CONFIG

/**
 * One custom config as the firmware's conf_custom.c serves it for a package: a small definition (`sim/custom_config.xml`)
 * handed out compressed in pieces, and a stored config. Index 0 only, as on the STM32 firmware. Like it, a write is
 * acknowledged even when the payload has the wrong layout; the config is just left unchanged.
 */
internal class CustomConfigSim(private val vesc: SimulatedVesc) {
    val count = 1

    private val xml: ByteArray by lazy {
        checkNotNull(javaClass.getResourceAsStream("/sim/custom_config.xml")) { "resource sim/custom_config.xml is missing" }.use { it.readBytes() }
    }
    private val definition: ConfigDefinition by lazy { ConfigXmlParser.parse(xml.inputStream()) }
    private val compressed: ByteArray by lazy { QtCompression.compress(xml) }
    private var stored: ByteArray? = null

    fun definitionChunk(r: ByteReader) {
        val index = r.i8()
        val length = r.i32()
        val offset = r.i32()
        if (index != 0 || length < 0 || offset < 0 || length + offset > compressed.size || length > MAX_CHUNK) return
        vesc.reply(
            ByteWriter().u8(COMM_GET_CUSTOM_CONFIG_XML.id).i8(index).i32(compressed.size).i32(offset)
                .bytes(compressed, offset, length).toByteArray(),
        )
    }

    fun get(id: CommPacketId, r: ByteReader, defaults: Boolean) {
        val index = r.i8()
        if (index != 0) return
        val payload = if (defaults) defaultPayload() else stored ?: defaultPayload()
        vesc.reply(ByteWriter().u8(id.id).i8(index).bytes(payload).toByteArray())
    }

    fun set(r: ByteReader) {
        if (r.i8() != 0) return
        val payload = r.restBytes()
        try {
            ConfigCodec.deserialize(definition, payload)
            stored = payload
        } catch (e: PayloadException) {
            // The package's set function refuses it; the firmware acknowledges anyway.
        }
        vesc.reply(byteArrayOf(COMM_SET_CUSTOM_CONFIG.id.toByte()))
    }

    private fun defaultPayload(): ByteArray = ConfigCodec.serialize(Config.defaults(definition))

    private companion object {
        /** PACKET_MAX_PL_LEN - 10 */
        const val MAX_CHUNK = 502
    }
}
