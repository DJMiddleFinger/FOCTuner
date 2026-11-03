package app.foctuner.config

import java.util.concurrent.ConcurrentHashMap

enum class ConfigKind(internal val fileName: String) {
    MCCONF("parameters_mcconf.xml"),
    APPCONF("parameters_appconf.xml"),
}

/**
 * The definitions shipped as resources under `config/<series>/`, copied from VESC Tool's `res/config/` (every
 * series VESC Tool ships, 3.55 to 7.01). Neighbouring series often share a layout (6.05 and 6.06 share the app config).
 */
object BundledDefinitions {
    val versions: List<String> = listOf(
        "3.55", "3.56", "3.57", "3.58", "3.59", "3.60", "3.61", "3.62", "3.63", "3.64", "3.65", "3.66",
        "4.00", "4.01", "4.02", "5.00", "5.01", "5.02", "5.03", "6.00", "6.02", "6.05", "6.06", "7.00", "7.01",
    )

    /**
     * The series whose [kind] layout has [signature], newest first, or null if none does. For controllers that
     * report a version we don't bundle but use a known layout (test builds, forks).
     */
    fun versionWithSignature(kind: ConfigKind, signature: Long): String? =
        registered.entries.firstOrNull { (key, definition) -> key.second == kind && definition.signature == signature }?.key?.first
            ?: versions.asReversed().firstOrNull { load(it, kind).signature == signature }

    private val cache = ConcurrentHashMap<Pair<String, ConfigKind>, ConfigDefinition>()
    private val registered = ConcurrentHashMap<Pair<String, ConfigKind>, ConfigDefinition>()

    /**
     * Adds a definition from elsewhere (VESC Tool's repository, for firmware the app doesn't bundle) under [version],
     * so [load] and [versionWithSignature] find it. A bundled series can't be replaced.
     */
    fun register(version: String, kind: ConfigKind, definition: ConfigDefinition) {
        require(version !in versions) { "firmware $version is bundled" }
        registered[version to kind] = definition
    }

    /** Whether [version] has definitions of [kind], bundled or registered. */
    fun has(version: String, kind: ConfigKind): Boolean = version in versions || registered.containsKey(version to kind)

    fun load(version: String, kind: ConfigKind): ConfigDefinition {
        registered[version to kind]?.let { return it }
        require(version in versions) { "no bundled definitions for firmware $version" }
        return cache.computeIfAbsent(version to kind) { (v, k) ->
            val stream = checkNotNull(javaClass.getResourceAsStream("/config/$v/${k.fileName}")) {
                "resource config/$v/${k.fileName} is missing"
            }
            stream.use(ConfigXmlParser::parse)
        }
    }
}
