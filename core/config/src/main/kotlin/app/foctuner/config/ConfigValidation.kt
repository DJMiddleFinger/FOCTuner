package app.foctuner.config

data class ValidationIssue(val param: String, val kind: Kind, val message: String) {
    enum class Kind {
        /** A number outside the definition's min..max. */
        OUT_OF_RANGE,

        /** An enum index with no name, a bool other than 0/1, or bitfield bits that do not exist. */
        INVALID_OPTION,

        TOO_LONG,

        /** Within min..max but not representable in the wire type, so writing it would corrupt the config. */
        NOT_ENCODABLE,
    }
}

/** Checks one value against the definition's limits and, for serialized parameters, against the wire type. */
fun ConfigDefinition.validate(name: String, value: ParamValue): ValidationIssue? {
    val p = param(name)
    require(p.accepts(value)) { "$name is ${p.type}, not ${value::class.simpleName}" }
    p.limitIssue(value)?.let { return it }
    if (!isSerialized(name)) return null
    return WireFormat.problem(p, value)?.let { ValidationIssue(name, ValidationIssue.Kind.NOT_ENCODABLE, "$name: $it") }
}

fun Config.validate(): List<ValidationIssue> = definition.params.keys.mapNotNull { definition.validate(it, this[it]) }

/** Moves [value] to the nearest allowed one; NaN becomes the default. Does not look at the wire type. */
fun ParamDefinition.clamp(value: ParamValue): ParamValue {
    require(accepts(value)) { "$name is $type, not ${value::class.simpleName}" }
    return when (value) {
        is ParamValue.DoubleValue -> ParamValue.DoubleValue(
            if (value.value.isNaN()) (default as? ParamValue.DoubleValue)?.value ?: minDouble else value.value.coerceIn(minDouble, maxDouble),
        )
        is ParamValue.TextValue -> if (maxLength > 0) ParamValue.TextValue(value.value.take(maxLength)) else value
        is ParamValue.IntValue -> when (type) {
            ParamType.INT -> ParamValue.IntValue(value.value.coerceIn(minInt, maxInt))
            ParamType.UNDEFINED -> value
            else -> ParamValue.IntValue(value.value.coerceIn(0, maxOption()))
        }
    }
}

private fun ParamDefinition.maxOption(): Long = when (type) {
    ParamType.BOOL -> 1
    ParamType.ENUM -> maxOf(enumNames.size - 1, 0).toLong()
    else -> (1L shl (if (enumNames.isEmpty()) 8 else minOf(enumNames.size, 8))) - 1
}

private fun ParamDefinition.limitIssue(value: ParamValue): ValidationIssue? {
    fun issue(kind: ValidationIssue.Kind, message: String) = ValidationIssue(name, kind, "$name: $message")
    return when (value) {
        is ParamValue.DoubleValue ->
            if (value.value in minDouble..maxDouble) null
            else issue(ValidationIssue.Kind.OUT_OF_RANGE, "${value.value} is outside $minDouble..$maxDouble")
        is ParamValue.TextValue ->
            if (maxLength <= 0 || value.value.length <= maxLength) null
            else issue(ValidationIssue.Kind.TOO_LONG, "${value.value.length} characters, at most $maxLength")
        is ParamValue.IntValue -> when (type) {
            ParamType.INT ->
                if (value.value in minInt..maxInt) null
                else issue(ValidationIssue.Kind.OUT_OF_RANGE, "${value.value} is outside $minInt..$maxInt")
            ParamType.UNDEFINED -> null
            else ->
                if (value.value in 0..maxOption()) null
                else issue(ValidationIssue.Kind.INVALID_OPTION, "${value.value} is not a valid $type value (0..${maxOption()})")
        }
    }
}
