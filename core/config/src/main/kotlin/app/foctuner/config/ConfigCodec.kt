package app.foctuner.config

import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.ByteWriter

/** The payload cannot be read as a config: too short, too long, or cut off in the middle of a parameter. */
open class PayloadException(message: String) : Exception(message)

/** The payload was made for a different layout than the definition. Neither read nor write it. */
class SignatureMismatchException(val expected: Long, val actual: Long) :
    PayloadException("config signature $actual does not match the definition's $expected")

/** The wire form of a config: `uint32 signature`, then every serialized parameter in definition order. */
object ConfigCodec {
    /** Throws [IllegalArgumentException] naming the parameter if a value does not fit its wire type. */
    fun serialize(config: Config): ByteArray {
        val w = ByteWriter(1024)
        w.u32(config.definition.signature)
        for (p in config.definition.serialized) {
            val value = config[p.name]
            val problem = WireFormat.problem(p, value)
            require(problem == null) { "${p.name}: $problem" }
            WireFormat.write(p, value, w)
        }
        return w.toByteArray()
    }

    fun signatureOf(payload: ByteArray): Long {
        if (payload.size < SIGNATURE_BYTES) throw PayloadException("payload is shorter than the 4-byte signature")
        return ByteReader(payload).u32()
    }

    /**
     * Parameters that are not serialized keep their value from [base].
     *
     * @throws SignatureMismatchException if the payload belongs to a different layout
     * @throws PayloadException if the payload is truncated or has bytes left over
     */
    fun deserialize(definition: ConfigDefinition, payload: ByteArray, base: Config = Config.defaults(definition)): Config {
        require(base.definition === definition) { "base config belongs to another definition" }
        val actual = signatureOf(payload)
        if (actual != definition.signature) throw SignatureMismatchException(definition.signature, actual)

        val r = ByteReader(payload, SIGNATURE_BYTES)
        val values = LinkedHashMap<String, ParamValue>()
        for (p in definition.serialized) values[p.name] = WireFormat.read(p, r)
        if (r.remaining != 0) throw PayloadException("${r.remaining} unexpected bytes after the last parameter")
        return base.withDecoded(values)
    }

    private const val SIGNATURE_BYTES = 4
}
