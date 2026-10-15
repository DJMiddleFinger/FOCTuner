package app.foctuner.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares the definitions with the layout in the firmware's own generated serializer
 * (the `.layout` files under `layout/7.01/`, produced by `tools/gen_config_layout.py`), which is independent of the XML.
 */
class FirmwareLayoutTest {
    private class Field(val name: String, val kind: String, val scale: Double?)

    private fun golden(file: String): List<Field> {
        val text = checkNotNull(javaClass.getResourceAsStream("/layout/7.01/$file")) { "missing $file" }
            .use { it.readBytes().decodeToString() }
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val parts = line.split(" ")
            Field(parts[0], parts[1], parts.getOrNull(2)?.toDouble())
        }
    }

    private fun width(kind: String) = when (kind) {
        "u8", "i8" -> 1
        "u16", "i16", "f16" -> 2
        else -> 4
    }

    private fun check(def: ConfigDefinition, file: String, expectedBytes: Int) {
        val fields = golden(file)
        assertEquals(fields.map { it.name }, def.serializeOrder)

        for ((field, p) in fields.zip(def.serialized)) {
            val ok = when (p.type) {
                ParamType.DOUBLE -> when (p.wire) {
                    WireType.DOUBLE16 -> field.kind == "f16" && field.scale == p.wireScale
                    WireType.DOUBLE32 -> field.kind == "f32" && field.scale == p.wireScale
                    else -> field.kind == "f32auto"
                }
                ParamType.INT -> field.kind == p.wire.name.lowercase().replace("uint", "u").replace("int", "i")
                ParamType.ENUM, ParamType.BOOL, ParamType.BITFIELD -> field.kind == "u8" || field.kind == "i8"
                else -> false
            }
            assertTrue(ok, "${p.name}: firmware has ${field.kind} ${field.scale}, definition has ${p.type} ${p.wire} ${p.wireScale}")
        }

        assertEquals(expectedBytes, fields.sumOf { width(it.kind) } + 4)
        assertEquals(expectedBytes, ConfigCodec.serialize(Config.defaults(def)).size)
    }

    @Test
    fun motorConfigMatchesTheFirmwareSerializer() {
        check(BundledDefinitions.load("7.01", ConfigKind.MCCONF), "mcconf.layout", MCCONF_BYTES)
    }

    @Test
    fun appConfigMatchesTheFirmwareSerializer() {
        check(BundledDefinitions.load("7.01", ConfigKind.APPCONF), "appconf.layout", APPCONF_BYTES)
    }

    private companion object {
        const val MCCONF_BYTES = 492
        const val APPCONF_BYTES = 290
    }
}
