package app.foctuner.config

/** An immutable set of values for every parameter of a [ConfigDefinition]; edits return a new instance. */
class Config private constructor(
    val definition: ConfigDefinition,
    private val values: Map<String, ParamValue>,
) {
    operator fun get(name: String): ParamValue =
        values[name] ?: throw IllegalArgumentException("unknown parameter $name")

    fun double(name: String): Double = (get(name) as? ParamValue.DoubleValue)?.value ?: wrongKind(name, "a double")

    fun int(name: String): Long = (get(name) as? ParamValue.IntValue)?.value ?: wrongKind(name, "an integer")

    fun bool(name: String): Boolean = int(name) != 0L

    fun text(name: String): String = (get(name) as? ParamValue.TextValue)?.value ?: wrongKind(name, "a string")

    fun with(name: String, value: ParamValue): Config {
        val p = definition.param(name)
        require(p.accepts(value)) { "$name is ${p.type}, not ${value::class.simpleName}" }
        return Config(definition, values + (name to value))
    }

    fun with(name: String, value: Double) = with(name, ParamValue.DoubleValue(value))

    fun with(name: String, value: Long) = with(name, ParamValue.IntValue(value))

    fun with(name: String, value: Boolean) = with(name, ParamValue.IntValue(if (value) 1 else 0))

    fun with(name: String, value: String) = with(name, ParamValue.TextValue(value))

    internal fun withDecoded(updates: Map<String, ParamValue>) = Config(definition, values + updates)

    override fun equals(other: Any?) = other is Config && other.definition === definition && other.values == values

    override fun hashCode() = 31 * System.identityHashCode(definition) + values.hashCode()

    private fun wrongKind(name: String, expected: String): Nothing =
        throw IllegalArgumentException("$name is not $expected")

    companion object {
        fun defaults(definition: ConfigDefinition): Config =
            Config(definition, definition.params.mapValues { it.value.default })
    }
}
