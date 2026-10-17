package app.foctuner.config

import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * One saved set of a controller's configs. [automatic] backups are the ones taken before a write; the others were
 * asked for (VESC Tool's "Backup configs"), optionally with a [name].
 */
data class ConfigBackup(
    /** The controller's UUID, 24 upper-case hex digits as VESC Tool prints it. */
    val uuid: String,
    val id: String,
    val name: String,
    val automatic: Boolean,
    /** Milliseconds since the epoch. */
    val created: Long,
    val firmware: String,
    val hardware: String,
    val kinds: Set<ConfigKind>,
)

/**
 * Config backups on disk, kept per controller UUID like VESC Tool's: `<dir>/<uuid>/<id>/` with one VESC Tool XML file
 * per config (`mcconf.xml`, `appconf.xml`, loadable in VESC Tool too) and `backup.properties` for the rest. All
 * parameters are kept, so the motor description fields the controller doesn't store survive with them.
 */
class ConfigBackupStore(private val dir: File) {
    /** Newest first. */
    fun list(): List<ConfigBackup> =
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory }.flatMap { controller ->
            (controller.listFiles() ?: emptyArray()).mapNotNull { read(controller.name, it) }
        }.sortedByDescending { it.created }

    /** @throws IOException if the files can't be written */
    fun save(
        uuid: String,
        configs: Map<ConfigKind, Config>,
        firmware: String,
        hardware: String,
        name: String = "",
        automatic: Boolean = false,
        created: Long = System.currentTimeMillis(),
    ): ConfigBackup {
        var id = "$created"
        while (File(dir, "${uuid.uppercase()}/$id").exists()) id += "+"
        val backup = ConfigBackup(uuid.uppercase(), id, name, automatic, created, firmware, hardware, configs.keys)
        val folder = folder(backup)
        if (!folder.mkdirs()) throw IOException("Could not create $folder")
        for ((kind, config) in configs) File(folder, kind.fileName()).writeText(ConfigXml.write(config, kind.xmlRoot))
        val info = Properties().apply {
            setProperty("name", name)
            setProperty("automatic", automatic.toString())
            setProperty("created", created.toString())
            setProperty("firmware", firmware)
            setProperty("hardware", hardware)
        }
        File(folder, INFO).outputStream().use { info.store(it, null) }
        return backup
    }

    /**
     * [kind] of [backup] applied to [base] (normally the config just read from the controller), or null if the backup
     * has no such config.
     *
     * @throws ConfigXmlException if the file is damaged
     */
    fun load(backup: ConfigBackup, kind: ConfigKind, base: Config): XmlConfig? {
        val file = File(folder(backup), kind.fileName())
        if (!file.exists()) return null
        return file.inputStream().use { ConfigXml.read(it, kind.xmlRoot, base) }
    }

    fun delete(backup: ConfigBackup) {
        folder(backup).deleteRecursively()
        folder(backup).parentFile?.delete()
    }

    fun deleteAll() {
        dir.deleteRecursively()
    }

    /** Deletes all but the newest [keep] automatic backups of [uuid]. */
    fun pruneAutomatic(uuid: String, keep: Int) {
        list().filter { it.uuid == uuid.uppercase() && it.automatic }.drop(keep).forEach(::delete)
    }

    private fun folder(backup: ConfigBackup) = File(dir, "${backup.uuid}/${backup.id}")

    private fun read(uuid: String, folder: File): ConfigBackup? {
        val infoFile = File(folder, INFO)
        if (!infoFile.exists()) return null
        val info = Properties()
        try {
            infoFile.inputStream().use(info::load)
        } catch (e: IOException) {
            return null
        }
        return ConfigBackup(
            uuid = uuid,
            id = folder.name,
            name = info.getProperty("name", ""),
            automatic = info.getProperty("automatic") == "true",
            created = info.getProperty("created")?.toLongOrNull() ?: folder.lastModified(),
            firmware = info.getProperty("firmware", ""),
            hardware = info.getProperty("hardware", ""),
            kinds = ConfigKind.entries.filter { File(folder, it.fileName()).exists() }.toSet(),
        )
    }

    private companion object {
        const val INFO = "backup.properties"
    }
}

/**
 * The last config read from or written to each controller, as VESC Tool 7.01 caches it (`conf_cache/<uuid>/`), so
 * the settings can show values and keep the motor description fields before the controller has answered.
 */
class ConfigCache(private val dir: File) {
    /** @throws IOException if the file can't be written */
    fun save(uuid: String, kind: ConfigKind, config: Config) {
        val folder = File(dir, uuid.uppercase())
        folder.mkdirs()
        val target = File(folder, kind.fileName())
        val temp = File(folder, kind.fileName() + ".tmp")
        temp.writeText(ConfigXml.write(config, kind.xmlRoot))
        if (!temp.renameTo(target)) throw IOException("Could not replace $target")
    }

    /** Null if nothing is cached for [uuid] or the file can't be read. */
    fun load(uuid: String, kind: ConfigKind, base: Config): Config? {
        val file = File(dir, "${uuid.uppercase()}/${kind.fileName()}")
        if (!file.exists()) return null
        return try {
            file.inputStream().use { ConfigXml.read(it, kind.xmlRoot, base) }.config
        } catch (e: ConfigXmlException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}

private fun ConfigKind.fileName() = if (this == ConfigKind.MCCONF) "mcconf.xml" else "appconf.xml"
