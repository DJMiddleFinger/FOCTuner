package app.foctuner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import app.foctuner.ui.FocApp
import app.foctuner.ui.prefs.LocalUiPreferences
import app.foctuner.ui.prefs.UiPreferences
import app.foctuner.ui.prefs.resolveDarkTheme
import app.foctuner.ui.theme.FocTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val prefs = remember { UiPreferences.from(applicationContext) }
            CompositionLocalProvider(LocalUiPreferences provides prefs) {
                FocTheme(darkTheme = prefs.resolveDarkTheme()) { FocApp() }
            }
        }
    }
}
