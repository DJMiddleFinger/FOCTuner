package app.foctuner.prefs

import app.foctuner.transport.LinkDevice
import app.foctuner.transport.LinkKind

/** A link the app has connected through, kept so it can be reconnected without finding it in a scan. */
data class RecentDevice(
    val kind: LinkKind,
    val id: String,
    val name: String,
    /** Epoch millis. */
    val lastConnected: Long,
    val pinned: Boolean = false,
) {
    /** Same as [LinkDevice.key]. */
    val key: String get() = "$kind/$id"

    fun toLinkDevice() = LinkDevice(kind, id, name)
}

/**
 * The recent-devices list: pinned first, then most recent. Pinned entries stay; of the others only the newest
 * [MAX_UNPINNED] are kept. USB devices are not kept because their system path changes every time they are plugged in.
 */
object RecentDevices {
    const val MAX_UNPINNED = 5

    fun connected(list: List<RecentDevice>, device: LinkDevice, at: Long): List<RecentDevice> {
        if (device.kind == LinkKind.USB) return list
        val pinned = list.any { it.key == device.key && it.pinned }
        return ordered(list.filter { it.key != device.key } + RecentDevice(device.kind, device.id, device.name, at, pinned))
    }

    fun togglePin(list: List<RecentDevice>, key: String): List<RecentDevice> =
        ordered(list.map { if (it.key == key) it.copy(pinned = !it.pinned) else it })

    fun forget(list: List<RecentDevice>, key: String): List<RecentDevice> = list.filter { it.key != key }

    private fun ordered(list: List<RecentDevice>): List<RecentDevice> {
        val (pinned, others) = list.sortedByDescending { it.lastConnected }.partition { it.pinned }
        return pinned + others.take(MAX_UNPINNED)
    }

    /** One device per line, tab-separated: kind, id, last connected, pinned, name. */
    fun encode(list: List<RecentDevice>): String = list.joinToString("\n") {
        listOf(it.kind.name, escape(it.id), it.lastConnected.toString(), if (it.pinned) "1" else "0", escape(it.name)).joinToString("\t")
    }

    fun decode(text: String): List<RecentDevice> = text.lineSequence().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size != 5) return@mapNotNull null
        val kind = LinkKind.entries.firstOrNull { it.name == fields[0] } ?: return@mapNotNull null
        val at = fields[2].toLongOrNull() ?: return@mapNotNull null
        RecentDevice(kind, unescape(fields[1]), unescape(fields[4]), at, fields[3] == "1")
    }.toList()

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\' || i == s.length) {
                append(c)
                continue
            }
            when (val next = s[i++]) {
                't' -> append('\t')
                'n' -> append('\n')
                else -> append(next)
            }
        }
    }
}
