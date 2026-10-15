package app.foctuner.config

enum class ParamType(val id: Int) {
    UNDEFINED(0),
    DOUBLE(1),
    INT(2),
    STRING(3),
    ENUM(4),
    BOOL(5),
    BITFIELD(6),
    ;

    companion object {
        fun fromId(id: Int): ParamType? = entries.firstOrNull { it.id == id }
    }
}

/** The `vTx` encoding of a parameter on the wire. Enum, bool, bitfield and string parameters ignore it. */
enum class WireType(val id: Int) {
    UNDEFINED(0),
    UINT8(1),
    INT8(2),
    UINT16(3),
    INT16(4),
    UINT32(5),
    INT32(6),
    DOUBLE16(7),
    DOUBLE32(8),
    DOUBLE32_AUTO(9),
    ;

    companion object {
        fun fromId(id: Int): WireType? = entries.firstOrNull { it.id == id }
    }
}

/** Enum, bool and bitfield parameters are [IntValue]; strings are [TextValue]. */
sealed interface ParamValue {
    data class DoubleValue(val value: Double) : ParamValue
    data class IntValue(val value: Long) : ParamValue
    data class TextValue(val value: String) : ParamValue
}

data class ParamDefinition(
    val name: String,
    val type: ParamType,
    val default: ParamValue,
    val wire: WireType = WireType.UNDEFINED,
    /** Multiplier for [WireType.DOUBLE16] and [WireType.DOUBLE32]: the wire carries `round(value * wireScale)`. */
    val wireScale: Double = 1.0,
    val longName: String = name,
    /** Qt rich-text HTML, exactly as in the XML. */
    val description: String = "",
    val cDefine: String = "",
    val minDouble: Double = 0.0,
    val maxDouble: Double = 99.0,
    val stepDouble: Double = 1.0,
    val decimals: Int = 2,
    val minInt: Long = 0,
    val maxInt: Long = 99,
    val stepInt: Long = 1,
    val maxLength: Int = 0,
    val enumNames: List<String> = emptyList(),
    val suffix: String = "",
    val editorScale: Double = 1.0,
    val editAsPercentage: Boolean = false,
    val showDisplay: Boolean = false,
    val transmittable: Boolean = true,
) {
    fun accepts(value: ParamValue): Boolean = when (value) {
        is ParamValue.DoubleValue -> type == ParamType.DOUBLE
        is ParamValue.TextValue -> type == ParamType.STRING
        is ParamValue.IntValue -> type != ParamType.DOUBLE && type != ParamType.STRING
    }

    /** Whether the (type, wire) pair maps to a wire encoding; only such parameters may appear in the serialize order. */
    val serializable: Boolean
        get() = when (type) {
            ParamType.DOUBLE -> wire == WireType.DOUBLE16 || wire == WireType.DOUBLE32 || wire == WireType.DOUBLE32_AUTO
            ParamType.INT -> wire.id in WireType.UINT8.id..WireType.INT32.id
            ParamType.STRING, ParamType.ENUM, ParamType.BOOL, ParamType.BITFIELD -> true
            ParamType.UNDEFINED -> false
        }
}

sealed interface GroupEntry {
    data class Param(val name: String) : GroupEntry

    /** A heading inside a subgroup; written as `::sep::<label>` in the XML. */
    data class Separator(val label: String) : GroupEntry
}

data class ParamSubgroup(val name: String, val entries: List<GroupEntry>) {
    val paramNames: List<String> get() = entries.filterIsInstance<GroupEntry.Param>().map { it.name }
}

data class ParamGroup(val name: String, val subgroups: List<ParamSubgroup>)
