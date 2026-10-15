package app.foctuner.config

import java.util.concurrent.ConcurrentHashMap

enum class ConfigKind(internal val fileName: String) {
    MCCONF("parameters_mcconf.xml"),
    APPCONF("parameters_appconf.xml"),
}

/** The definitions shipped as resources under `config/<series>/`. */
object BundledDefinitions {
    val versions: List<String> = listOf("7.01")

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
