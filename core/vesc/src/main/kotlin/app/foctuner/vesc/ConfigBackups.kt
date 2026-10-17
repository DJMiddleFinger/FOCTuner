package app.foctuner.vesc

import app.foctuner.config.Config
import app.foctuner.config.ConfigBackup
import app.foctuner.config.ConfigBackupStore
import app.foctuner.config.ConfigDefinition
import app.foctuner.config.ConfigKind

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
): ConfigBackup {
    val configs = ConfigKind.entries.associateWith { kind -> readMatchingConfig(kind, firmware.version) { base(kind, it) } }
    return store.save(firmware.uuid, configs, firmware.version, firmware.hardwareName, name, automatic)
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
    val restored = store.load(backup, kind, current)?.config ?: current
    try {
        writeConfigChecked(kind, restored)
    } catch (e: VescTimeoutException) {
        writeConfigChecked(kind, restored)
    }
}
