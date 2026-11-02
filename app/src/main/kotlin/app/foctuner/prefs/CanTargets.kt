package app.foctuner.prefs

/** The CAN controller last selected behind an attached one: its id then, and its UUID to check it is still the same. */
data class CanTarget(val canId: Int, val uuid: String)

/**
 * VESC Tool's `lastFwUuids`: attached controller UUID → the CAN controller selected behind it. Most recently
 * changed first, at most [MAX_ENTRIES].
 */
object CanTargets {
    const val MAX_ENTRIES = 32

    fun with(targets: Map<String, CanTarget>, localUuid: String, target: CanTarget?): Map<String, CanTarget> {
        val others = targets.filterKeys { it != localUuid }
        return if (target == null) others else (mapOf(localUuid to target) + others).entries.take(MAX_ENTRIES).associate { it.key to it.value }
    }

    /** One line per attached controller: local UUID, CAN id, CAN UUID. UUIDs are hex, so no escaping is needed. */
    fun encode(targets: Map<String, CanTarget>): String =
        targets.entries.joinToString("\n") { (local, target) -> "$local\t${target.canId}\t${target.uuid}" }

    fun decode(text: String): Map<String, CanTarget> = text.lineSequence().mapNotNull { line ->
        val fields = line.split('\t')
        val id = fields.getOrNull(1)?.toIntOrNull()
        if (fields.size != 3 || id == null || fields[0].isEmpty()) null else fields[0] to CanTarget(id, fields[2])
    }.toMap()
}
