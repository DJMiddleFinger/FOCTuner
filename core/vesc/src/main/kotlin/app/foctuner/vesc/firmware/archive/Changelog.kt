package app.foctuner.vesc.firmware.archive

/** One version's notes from the firmware changelog. */
data class ChangelogSection(val version: String, val date: String?, val notes: String)

/**
 * The firmware changelog VESC Tool shows (`res/firmwares/CHANGELOG.md` in its repository): `### <version>`,
 * a `#### <date>` line (or `TBD` before a release), then bullets.
 */
object Changelog {
    fun parse(text: String): List<ChangelogSection> {
        val sections = ArrayList<ChangelogSection>()
        var version: String? = null
        var date: String? = null
        val notes = StringBuilder()

        fun finish() {
            version?.let { sections += ChangelogSection(it, date, notes.toString().trim('\n')) }
            notes.clear()
            date = null
        }

        for (line in text.lines()) {
            when {
                line.startsWith("### ") -> {
                    finish()
                    version = line.removePrefix("### ").trim()
                }
                line.startsWith("#### ") && date == null && notes.isEmpty() -> date = line.removePrefix("#### ").trim().takeUnless { it.equals("TBD", true) }
                version != null -> notes.append(line.replace("\t", "    ")).append('\n')
            }
        }
        finish()
        return sections
    }

    /** What changed after [installed] up to and including [target]; newest first. Empty if [target] isn't newer. */
    fun between(sections: List<ChangelogSection>, installed: String?, target: String): List<ChangelogSection> =
        sections.filter {
            compareVersions(it.version, target) <= 0 && (installed == null || compareVersions(it.version, installed) > 0)
        }.sortedWith { a, b -> compareVersions(b.version, a.version) }

    /** Dotted numbers compared part by part: 6.06.6 is newer than 6.06, 7.00 newer than 6.06.6. */
    fun compareVersions(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun parts(version: String): List<Int> = version.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
