package app.foctuner.vesc

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ConfigDefinitionException
import app.foctuner.config.ConfigKind
import app.foctuner.config.ConfigXmlParser
import app.foctuner.vesc.firmware.archive.ArchiveException
import app.foctuner.vesc.firmware.archive.HttpFetcher
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * Config definitions for firmware the app doesn't bundle, from VESC Tool's repository (`res/config/<version>/`), the
 * same files VESC Tool ships. A downloaded definition is only used if its layout signature is the one the controller
 * sent, so a file for the wrong firmware can't misread or miswrite a config. Accepted files are kept in [dir] and
 * registered with [BundledDefinitions] again on the next start ([restore]).
 */
class RemoteDefinitions(
    private val fetcher: HttpFetcher,
    private val dir: File,
    private val baseUrl: String = VESC_TOOL_CONFIG_URL,
) {
    enum class Outcome { Found, NotPublished, WrongLayout, Unreachable }

    class Result(val outcome: Outcome, val version: String? = null)

    /** Registers every definition kept from earlier downloads; damaged files are dropped. */
    fun restore() {
        val versions = dir.listFiles()?.filter { it.isDirectory } ?: return
        for (versionDir in versions) {
            for (kind in ConfigKind.entries) {
                val file = File(versionDir, fileName(kind))
                if (!file.isFile || BundledDefinitions.has(versionDir.name, kind)) continue
                try {
                    BundledDefinitions.register(versionDir.name, kind, file.inputStream().use(ConfigXmlParser::parse))
                } catch (e: ConfigDefinitionException) {
                    file.delete()
                } catch (e: IOException) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Fetches the [kind] definition VESC Tool has for firmware [version] and registers it if its signature is
     * [signature]. [Result.version] is what to pass to [BundledDefinitions.load] afterwards.
     */
    suspend fun fetch(version: String, kind: ConfigKind, signature: Long): Result {
        BundledDefinitions.versionWithSignature(kind, signature)?.let { return Result(Outcome.Found, it) }
        require(VERSION.matches(version)) { "Not a firmware version: $version" }
        val bytes = try {
            fetcher.get("$baseUrl/$version/${fileName(kind)}", maxBytes = MAX_BYTES)
        } catch (e: ArchiveException) {
            return Result(if (e.reason == ArchiveException.Reason.NotFound) Outcome.NotPublished else Outcome.Unreachable)
        } catch (e: IOException) {
            return Result(Outcome.Unreachable)
        }
        val definition: ConfigDefinition = try {
            ByteArrayInputStream(bytes).use(ConfigXmlParser::parse)
        } catch (e: ConfigDefinitionException) {
            return Result(Outcome.WrongLayout)
        }
        if (definition.signature != signature) return Result(Outcome.WrongLayout)
        val key = if (BundledDefinitions.has(version, kind)) "$version-${signature.toString(16)}" else version
        try {
            val target = File(File(dir, key), fileName(kind))
            target.parentFile.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) temp.delete()
        } catch (e: IOException) {
            // Used for this session only; fetched again next time.
        }
        BundledDefinitions.register(key, kind, definition)
        return Result(Outcome.Found, key)
    }

    private fun fileName(kind: ConfigKind) = if (kind == ConfigKind.MCCONF) "parameters_mcconf.xml" else "parameters_appconf.xml"

    companion object {
        const val VESC_TOOL_CONFIG_URL = "https://raw.githubusercontent.com/vedderb/vesc_tool/master/res/config"
        private val VERSION = Regex("""\d{1,2}\.\d{2}""")
        private const val MAX_BYTES = 4 * 1024 * 1024
    }
}
