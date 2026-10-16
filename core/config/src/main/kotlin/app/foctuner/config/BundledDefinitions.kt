package app.foctuner.config

import java.util.concurrent.ConcurrentHashMap

enum class ConfigKind(internal val fileName: String) {
    MCCONF("parameters_mcconf.xml"),
    APPCONF("parameters_appconf.xml"),
}

/**
 * The definitions shipped as resources under `config/<series>/`, copied from VESC Tool's `res/config/`.
 * 6.05 and 6.06 share the app config layout.
 */
object BundledDefinitions {
    val versions: List<String> = listOf("6.05", "6.06", "7.00", "7.01")

    /**
     * The series whose [kind] layout has [signature], newest first, or null if none does. For controllers that
     * report a version we don't bundle but use a known layout (test builds, forks).
     */
    fun versionWithSignature(kind: ConfigKind, signature: Long): String? =
        versions.asReversed().firstOrNull { load(it, kind).signature == signature }

    private val cache = ConcurrentHashMap<Pair<String, ConfigKind>, ConfigDefinition>()

    fun load(version: String, kind: ConfigKind): ConfigDefinition {
        require(version in versions) { "no bundled definitions for firmware $version" }
        return cache.computeIfAbsent(version to kind) { (v, k) ->
            val stream = checkNotNull(javaClass.getResourceAsStream("/config/$v/${k.fileName}")) {
                "resource config/$v/${k.fileName} is missing"
            }
            stream.use(ConfigXmlParser::parse)
        }
    }
}
