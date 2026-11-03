package app.foctuner.ui.settings

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import app.foctuner.R
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.config.ConfigXml
import app.foctuner.config.ConfigXmlException
import app.foctuner.config.XmlConfig
import app.foctuner.config.validate
import app.foctuner.config.xmlRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Save to file and Load from file for one config, in VESC Tool's XML through the system file picker. */
internal class ConfigFiles(val save: () -> Unit, val load: () -> Unit)

/**
 * Saving writes the edited config, pending edits included, as VESC Tool would. Loading reads a VESC Tool file over the
 * stored config and makes the differences pending edits, so they are reviewed before anything is written.
 */
@Composable
internal fun rememberConfigFiles(kind: ConfigKind, pane: ConfigPaneState, actions: SettingsActions): ConfigFiles {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(pane)
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        val config = latest.edited
        if (uri == null || config == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(ConfigXml.write(config, kind.xmlRoot).toByteArray()) } != null
                } catch (e: IOException) {
                    false
                }
            }
            val notice = if (saved) PaneNotice(resources.getString(R.string.settings_file_saved)) else PaneNotice(resources.getString(R.string.settings_file_failed), warning = true)
            actions.notify(kind, notice)
        }
    }
    val loader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val stored = latest.stored
        if (uri == null || stored == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { ConfigXml.read(it, kind.xmlRoot, stored) }
                } catch (e: ConfigXmlException) {
                    null
                } catch (e: IOException) {
                    null
                }
            }
            if (read == null) {
                val name = resources.getString(if (kind == ConfigKind.MCCONF) R.string.nav_motor_cfg else R.string.nav_app_cfg)
                actions.notify(kind, PaneNotice(resources.getString(R.string.settings_file_not_config, name), warning = true))
            } else {
                actions.loadFile(kind, read.config, fileNotes(read, stored, resources))
            }
        }
    }
    val fileName = if (kind == ConfigKind.MCCONF) "mcconf.xml" else "appconf.xml"
    return remember(saver, loader) { ConfigFiles(save = { saver.launch(fileName) }, load = { loader.launch(arrayOf("text/xml", "application/xml", "*/*")) }) }
}

/** What a loaded file had that doesn't fit this firmware, for the notice under the page header. */
private fun fileNotes(read: XmlConfig, stored: Config, resources: Resources): List<String> {
    fun names(list: List<String>) = list.map { stored.definition.params[it]?.longName ?: it }.let { all ->
        if (all.size <= 5) all.joinToString() else all.take(5).joinToString() + " …"
    }
    val invalid = read.config.validate().map { it.param }.distinct()
    return listOfNotNull(
        read.configVersion?.takeIf { it != ConfigXml.CONFIG_VERSION }?.let { resources.getString(R.string.settings_file_version, it, ConfigXml.CONFIG_VERSION) },
        read.unknown.takeIf { it.isNotEmpty() }?.let { resources.getString(R.string.settings_file_unknown, it.joinToString()) },
        read.missing.takeIf { it.isNotEmpty() }?.let { resources.getString(R.string.settings_file_missing, names(it)) },
        invalid.takeIf { it.isNotEmpty() }?.let { resources.getString(R.string.settings_file_invalid, names(it)) },
    )
}
