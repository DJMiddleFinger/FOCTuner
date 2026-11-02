package app.foctuner.ui.prefs

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.foctuner.prefs.AppPreferences
import app.foctuner.prefs.INTRO_VERSION
import app.foctuner.prefs.PreferencesStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The app preferences for the UI; null until they have been read from disk. Activity-scoped. */
class PreferencesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PreferencesStore(application)

    val state: StateFlow<AppPreferences?> = store.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun update(change: (AppPreferences) -> AppPreferences) {
        viewModelScope.launch { store.update(change) }
    }

    fun acceptIntro() = update { it.copy(introAccepted = INTRO_VERSION) }

    fun restoreDefaults() = update { AppPreferences(introAccepted = it.introAccepted) }
}

/** The current preferences, defaults until they are loaded. For tools that only read them. */
@Composable
fun appPreferences(): AppPreferences {
    val prefs by viewModel<PreferencesViewModel>().state.collectAsStateWithLifecycle()
    return prefs ?: AppPreferences()
}
