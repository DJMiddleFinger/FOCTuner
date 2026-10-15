package app.foctuner.config

data class ParamChange(val name: String, val from: ParamValue, val to: ParamValue)

/**
 * The parameters whose value differs between this config and [other], in definition order. Serialized parameters
 * count as equal when they encode to the same wire bytes, so `0.30001` and `0.3` on a scale-10 double16 are the same.
 * VESC Tool compares with a fixed epsilon instead.
 */
fun Config.diff(other: Config): List<ParamChange> {
    require(definition.signature == other.definition.signature && definition.params.keys == other.definition.params.keys) {
        "configs belong to different definitions"
    }
    return definition.params.values.mapNotNull { p ->
        val from = this[p.name]
        val to = other[p.name]
        if (equivalent(p, from, to)) null else ParamChange(p.name, from, to)
    }
}

private fun Config.equivalent(p: ParamDefinition, a: ParamValue, b: ParamValue): Boolean {
    if (a == b) return true
    if (!definition.isSerialized(p.name)) return false
    val x = WireFormat.encode(p, a) ?: return false
    val y = WireFormat.encode(p, b) ?: return false
    return x.contentEquals(y)
}
