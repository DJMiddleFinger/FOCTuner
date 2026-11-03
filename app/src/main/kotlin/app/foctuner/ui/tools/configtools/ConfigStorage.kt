package app.foctuner.ui.tools.configtools

import android.content.Context
import app.foctuner.config.ConfigBackupStore
import app.foctuner.config.ConfigCache
import app.foctuner.vesc.ConfigProfile
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
