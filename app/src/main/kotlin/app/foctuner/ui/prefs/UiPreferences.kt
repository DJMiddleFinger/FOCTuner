package app.foctuner.ui.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** How the app chooses light or dark colors. SYSTEM follows the device setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * App appearance and motion preferences. Backed by SharedPreferences and exposed as Compose
 * state, so reading [themeMode] or [animations] in a composable recomposes it when they change,
 * and assigning them persists the new value.
 */
class UiPreferences private constructor(private val prefs: SharedPreferences) {
    private var themeModeState by mutableStateOf(readThemeMode())
    private var animationsState by mutableStateOf(prefs.getBoolean(KEY_ANIMATIONS, true))

    var themeMode: ThemeMode
        get() = themeModeState
        set(value) {
            themeModeState = value
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    var animations: Boolean
        get() = animationsState
        set(value) {
            animationsState = value
            prefs.edit().putBoolean(KEY_ANIMATIONS, value).apply()
        }

    private fun readThemeMode(): ThemeMode =
        prefs.getString(KEY_THEME_MODE, null)
            ?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
            ?: ThemeMode.SYSTEM

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ANIMATIONS = "animations"

        fun from(context: Context): UiPreferences =
            UiPreferences(context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE))
    }
}

/** Provided once at the top of the app so any screen can read or change preferences. */
val LocalUiPreferences = staticCompositionLocalOf<UiPreferences> { error("UiPreferences not provided") }

/** Resolves the current [ThemeMode] to a concrete dark/light choice for this frame. */
@Composable
fun UiPreferences.resolveDarkTheme(): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
