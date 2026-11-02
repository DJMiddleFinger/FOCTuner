package app.foctuner

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.foctuner.ui.FocApp
import app.foctuner.ui.prefs.LocalUiPreferences
import app.foctuner.ui.prefs.UiPreferences
import app.foctuner.ui.prefs.resolveDarkTheme
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.LocalMotion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val prefs = remember { UiPreferences.from(applicationContext) }
            val dark = prefs.resolveDarkTheme()
            // Status and navigation bar icons follow the app's theme choice, not only the system's, so they stay
            // visible when Display › Appearance picks the opposite of the device setting.
            LaunchedEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            CompositionLocalProvider(LocalUiPreferences provides prefs, LocalMotion provides prefs.animations) {
                FocTheme(darkTheme = dark) { FocApp() }
            }
        }
    }
}
