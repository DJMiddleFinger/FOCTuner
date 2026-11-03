package app.foctuner.ui.settings

import androidx.compose.runtime.Immutable
import app.foctuner.config.Config
import app.foctuner.config.ConfigBackup
import app.foctuner.config.ConfigKind
import app.foctuner.config.ConfigWarning
import app.foctuner.config.ParamChange
import app.foctuner.config.ParamGroup
import app.foctuner.config.ParamValue
import app.foctuner.config.diff

enum class SettingsWork { Reading, Writing, LoadingDefaults }

/** Something the last action left for the user to read: what a write didn't take, or what a loaded file lacked. */
@Immutable
data class PaneNotice(
    val title: String,
    val checks: List<String> = emptyList(),
    val warning: Boolean = false,
    /** Left by a write; it goes away with the next edit. */
    val afterWrite: Boolean = false,
)

/** One config (motor or app) of the selected controller: as stored on it, and with the user's pending edits. */
@Immutable
data class ConfigPaneState(
    /** VESC Tool's sections for this config, plus "Other" for the settings it doesn't place. */
    val sections: List<ParamGroup> = emptyList(),
    /** As last read from the controller; null until a read succeeds. */
    val stored: Config? = null,
    val edited: Config? = null,
    /** Settings whose edited value differs from the stored one. */
    val changed: Set<String> = emptySet(),
    /** What the edited values raise ([app.foctuner.config.writeWarnings]), shown on their rows and before a write. */
    val warnings: List<ConfigWarning> = emptyList(),
    val work: SettingsWork? = null,
    val error: String? = null,
    val notice: PaneNotice? = null,
    /** The automatic backup the last write took, which Undo restores. */
    val undo: UndoState? = null,
)

/** The backup a write took first, and the configs that write changed. */
@Immutable
data class UndoState(val backup: ConfigBackup, val kinds: Set<ConfigKind>)

/** A backup of the selected controller and what restoring it would change; [changes] is null while it loads. */
@Immutable
data class BackupEntry(val backup: ConfigBackup, val changes: Map<ConfigKind, List<ParamChange>>? = null, val damaged: Boolean = false)

/** A restore waiting for confirmation: each config as stored and as it would be written, and what that changes. */
@Immutable
data class RestoreReview(
    val backup: ConfigBackup,
    val configs: Map<ConfigKind, Pair<Config, Config>>,
    val undo: Boolean,
) {
    val changes: Map<ConfigKind, List<ParamChange>> = configs.mapValues { (_, c) -> c.first.diff(c.second) }
}

@Immutable
data class SettingsState(
    val motor: ConfigPaneState = ConfigPaneState(),
    val app: ConfigPaneState = ConfigPaneState(),
    /** The selected controller's backups, newest first; null until the History page asks for them. */
    val history: List<BackupEntry>? = null,
    val restore: RestoreReview? = null,
    /** Set while a restore is being prepared or written. */
    val restoring: Boolean = false,
) {
    fun pane(kind: ConfigKind) = if (kind == ConfigKind.MCCONF) motor else app
}

interface SettingsActions {
    /** Reads [kind] unless it has been read already. */
    fun open(kind: ConfigKind)
    fun read(kind: ConfigKind)
    fun loadDefaults(kind: ConfigKind)
    fun edit(kind: ConfigKind, name: String, value: ParamValue)
    fun revert(kind: ConfigKind)
    fun write(kind: ConfigKind)

    /** Makes [config] (read from a file) the pending edits of [kind], noting what the file had that we don't. */
    fun loadFile(kind: ConfigKind, config: Config, notes: List<String>)
    fun notify(kind: ConfigKind, notice: PaneNotice)
    fun dismissNotice(kind: ConfigKind)

    /** Asks to restore the backup the last write of [kind] took. */
    fun undo(kind: ConfigKind)
    fun loadHistory()

    /** Asks to restore [backup]'s configs; [confirmRestore] writes them. */
    fun restore(backup: ConfigBackup)
    fun confirmRestore()
    fun dismissRestore()
    fun deleteBackup(backup: ConfigBackup)
}
