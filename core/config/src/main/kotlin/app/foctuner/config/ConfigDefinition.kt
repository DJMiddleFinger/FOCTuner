package app.foctuner.config

class ConfigDefinitionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The parameters of one config (motor or app) for one firmware series: what [ConfigXmlParser] reads from
 * VESC Tool's `parameters_*.xml`. Everything that decides the wire layout is validated here, so a
 * definition that constructs can be serialized.
 */
class ConfigDefinition(
    paramList: List<ParamDefinition>,
    serializeOrder: List<String>,
    groups: List<ParamGroup> = emptyList(),
) {
    /** In XML order, which is the order VESC Tool lists them in. */
    val params: Map<String, ParamDefinition>
    val serializeOrder: List<String> = serializeOrder.toList()
    val groups: List<ParamGroup> = groups.toList()

    /** The serialized parameters in wire order. */
    val serialized: List<ParamDefinition>

    /**
     * CRC32C over the serialize order's names, types, wire types and enum names, as `ConfigParams::getSignature`
     * computes it. A device sends the same value in front of its config; a different value means a different layout.
     */
    val signature: Long

    init {
        val byName = LinkedHashMap<String, ParamDefinition>()
        for (p in paramList) {
            if (byName.put(p.name, p) != null) throw ConfigDefinitionException("duplicate parameter ${p.name}")
            if (!p.accepts(p.default)) throw ConfigDefinitionException("default of ${p.name} does not match its type")
        }
        params = byName

        val seen = HashSet<String>()
        serialized = this.serializeOrder.map { name ->
            val p = byName[name] ?: throw ConfigDefinitionException("serialize order names unknown parameter $name")
            if (!seen.add(name)) throw ConfigDefinitionException("serialize order repeats $name")
            if (!p.serializable) {
                throw ConfigDefinitionException("$name (type ${p.type.id}, vTx ${p.wire.id}) has no wire encoding")
            }
            p
        }

        for (group in this.groups) {
            for (subgroup in group.subgroups) {
                for (entry in subgroup.entries) {
                    if (entry is GroupEntry.Param && entry.name !in byName) {
                        throw ConfigDefinitionException("group ${group.name}/${subgroup.name} names unknown parameter ${entry.name}")
                    }
                }
            }
        }

        signature = Crc32c.compute(signatureText().toByteArray(Charsets.UTF_8))
    }

    private val serializedNames: Set<String> = this.serializeOrder.toHashSet()

    fun param(name: String): ParamDefinition =
        params[name] ?: throw IllegalArgumentException("unknown parameter $name")

    fun isSerialized(name: String): Boolean = name in serializedNames

    private fun signatureText(): String = buildString {
        for (p in serialized) {
            append(p.name).append(p.type.id).append(p.wire.id)
            p.enumNames.forEach { append(it) }
        }
    }
}
