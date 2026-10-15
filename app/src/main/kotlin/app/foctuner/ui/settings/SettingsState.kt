package app.foctuner.ui.settings

import androidx.compose.runtime.Immutable
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamGroup
import app.foctuner.config.ParamValue

enum class SettingsWork { Reading, Writing, LoadingDefaults }

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
    val work: SettingsWork? = null,
    val error: String? = null,
)

@Immutable
data class SettingsState(
    val motor: ConfigPaneState = ConfigPaneState(),
    val app: ConfigPaneState = ConfigPaneState(),
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
}
