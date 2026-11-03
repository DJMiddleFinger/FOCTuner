package app.foctuner.ui.tools.configtools

import android.content.Context
import app.foctuner.config.Config
import app.foctuner.config.ConfigBackupStore
import app.foctuner.config.ConfigCache
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ConfigKind
import app.foctuner.vesc.ConfigProfile
import app.foctuner.vesc.RemoteDefinitions
import app.foctuner.vesc.firmware.archive.UrlConnectionFetcher
import app.foctuner.vesc.profilesFrom
import app.foctuner.vesc.toProperties
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * Where the config tools keep their files, one set per app: backups per controller UUID, the last config read from
 * each controller, and the profiles. Files are touched off the main thread.
 */
class ConfigStorage private constructor(dir: File) {
    val backups = ConfigBackupStore(File(dir, "config_backups"))
    val cache = ConfigCache(File(dir, "conf_cache"))
    private val profilesFile = File(dir, "profiles.properties")
    private val definitions = RemoteDefinitions(UrlConnectionFetcher(), File(dir, "definitions"))

    @Volatile
    private var definitionsRestored = false

    /**
     * The definition series to read [kind] of firmware [version] with, given the layout [signature] the controller
     * sent: a bundled one, one downloaded earlier, or VESC Tool's file for that firmware fetched now and checked.
     * Blocking file and network work: call it off the main thread.
     */
    suspend fun definitionFor(kind: ConfigKind, version: String, signature: Long): RemoteDefinitions.Result {
        if (!definitionsRestored) {
            definitions.restore()
            definitionsRestored = true
        }
        return try {
            definitions.fetch(version, kind, signature)
        } catch (e: IllegalArgumentException) {
            RemoteDefinitions.Result(RemoteDefinitions.Outcome.NotPublished)
        }
    }

    /** The settings controller [uuid] doesn't store (motor description and the like): its last read, else defaults. */
    fun base(uuid: String): (ConfigKind, ConfigDefinition) -> Config = { kind, definition ->
        val defaults = Config.defaults(definition)
        cache.load(uuid, kind, defaults) ?: defaults
    }

    fun profiles(): List<ConfigProfile> = try {
        if (!profilesFile.exists()) emptyList() else profilesFile.inputStream().use { input -> profilesFrom(Properties().apply { load(input) }) }
    } catch (e: IOException) {
        emptyList()
    }

    /** @throws IOException if the file can't be written */
    fun saveProfiles(profiles: List<ConfigProfile>) {
        val temp = File(profilesFile.path + ".tmp")
        temp.outputStream().use { profiles.toProperties().store(it, null) }
        if (!temp.renameTo(profilesFile)) throw IOException("Could not replace $profilesFile")
    }

    companion object {
        @Volatile
        private var instance: ConfigStorage? = null

        fun of(context: Context): ConfigStorage = instance ?: synchronized(this) {
            instance ?: ConfigStorage(context.applicationContext.filesDir).also { instance = it }
        }
    }
}
