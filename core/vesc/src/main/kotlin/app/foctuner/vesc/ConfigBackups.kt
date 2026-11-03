package app.foctuner.vesc

import app.foctuner.config.Config
import app.foctuner.config.ConfigBackup
import app.foctuner.config.ConfigBackupStore
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ConfigKind
import app.foctuner.config.diff
import app.foctuner.config.restoredFrom

/** A motor controller reachable through the connection: the attached one ([canId] null) or one on its CAN bus. */
data class ConfigTarget(val canId: Int?, val firmware: FirmwareInfo) {
    /** As VESC Tool keys its backups: 24 upper-case hex digits. */
    val uuid: String get() = firmware.uuid.uppercase()
}

/**
 * The attached controller and the motor controllers on its CAN bus, the set VESC Tool's backup, restore and multi
 * settings work on. BMSs and other modules have no motor or app config and are left out.
 */
suspend fun VescClient.configTargets(): List<ConfigTarget> {
    val local = firmwareInfo()
    val attached = listOf(ConfigTarget(null, local)).filter { local.hardwareType == HardwareType.VESC }
    return attached + canVescs().map { (id, info) -> ConfigTarget(id, info) }
}

fun VescClient.controller(target: ConfigTarget): VescController = target.canId?.let(::can) ?: this

/**
 * Reads both configs and stores them in [store] under the controller's UUID. [base] supplies the parameters the
 * controller doesn't keep (motor description and the like), normally from the config cache.
 */
suspend fun VescController.backUpConfigs(
    store: ConfigBackupStore,
    firmware: FirmwareInfo,
    name: String = "",
    automatic: Boolean = false,
    base: (ConfigKind, ConfigDefinition) -> Config = { _, d -> Config.defaults(d) },
): ConfigBackup = store.save(firmware.uuid, readConfigs(firmware.version, base), firmware.version, firmware.hardwareName, name, automatic)

private suspend fun VescController.readConfigs(version: String, base: (ConfigKind, ConfigDefinition) -> Config) =
    ConfigKind.entries.associateWith { kind -> readMatchingConfig(kind, version) { base(kind, it) } }

/** How many automatic backups are kept per controller. */
const val AUTOMATIC_BACKUPS = 10

/** One config as it was read from the controller, and what it should become. */
data class ConfigEdit(val stored: Config, val edited: Config)

/** A write whose previous state was saved first: [backup] holds both configs as they were just before. */
data class BackedUpWrite(val backup: ConfigBackup, val results: Map<ConfigKind, ConfigWriteResult>)

/**
 * Writes [edits] with an automatic backup first, as every settings write does: reads both configs fresh, stores them
 * as an automatic backup (keeping the newest [keep] of the controller), applies only the parameters each edit changed
 * over the fresh read, so a change made elsewhere since [ConfigEdit.stored] was read survives, and writes the result
 * checked, motor config first. [base] supplies the parameters the controller doesn't store for the kinds not edited.
 */
suspend fun VescController.writeWithBackup(
    edits: Map<ConfigKind, ConfigEdit>,
    store: ConfigBackupStore,
    firmware: FirmwareInfo,
    keep: Int = AUTOMATIC_BACKUPS,
    base: (ConfigKind, ConfigDefinition) -> Config = { _, d -> Config.defaults(d) },
): BackedUpWrite {
    val fresh = ConfigKind.entries.associateWith { kind ->
        edits[kind]?.let { readConfig(kind, it.stored) } ?: readMatchingConfig(kind, firmware.version) { base(kind, it) }
    }
    val backup = store.save(firmware.uuid, fresh, firmware.version, firmware.hardwareName, automatic = true)
    store.pruneAutomatic(firmware.uuid, keep)
    val results = ConfigKind.entries.filter { it in edits }.associateWith { kind ->
        val edit = edits.getValue(kind)
        val rebased = edit.stored.diff(edit.edited).fold(fresh.getValue(kind)) { config, change -> config.with(change.name, change.to) }
        writeConfigChecked(kind, rebased)
    }
    return BackedUpWrite(backup, results)
}

/**
 * Writes [backup] back to the controller: reads each config (which also picks the right layout), applies the backup's
 * values over it and writes it, motor config first, each checked by reading it back. A write that isn't acknowledged is
 * tried once more, as VESC Tool does because BLE drops a reply now and then.
 *
 * @throws app.foctuner.config.ConfigXmlException if a backup file is damaged
 */
suspend fun VescController.restoreConfigs(
    store: ConfigBackupStore,
    backup: ConfigBackup,
    firmware: FirmwareInfo,
): Map<ConfigKind, ConfigWriteResult> = ConfigKind.entries.filter { it in backup.kinds }.associateWith { kind ->
    val current = readMatchingConfig(kind, firmware.version)
    val restored = store.load(backup, kind, current)?.config?.let(current::restoredFrom) ?: current
    try {
        writeConfigChecked(kind, restored)
    } catch (e: VescTimeoutException) {
        writeConfigChecked(kind, restored)
    }
}
