package app.foctuner.config

/**
 * The settings sections VESC Tool shows for this definition (its `<Grouping>`), plus a final "Other" group with
 * every parameter that grouping leaves out, so each setting has a place. Parameters of undefined type are
 * placeholders in VESC Tool's XML with no value to edit, and are skipped.
 */
fun ConfigDefinition.sections(): List<ParamGroup> {
    val grouped = groups.flatMap { group -> group.subgroups.flatMap { it.paramNames } }.toHashSet()
    val rest = params.values.filter { it.name !in grouped && it.type != ParamType.UNDEFINED }
    if (rest.isEmpty()) return groups
    return groups + ParamGroup(OTHER, listOf(ParamSubgroup(OTHER, rest.map { GroupEntry.Param(it.name) })))
}

private const val OTHER = "Other"
