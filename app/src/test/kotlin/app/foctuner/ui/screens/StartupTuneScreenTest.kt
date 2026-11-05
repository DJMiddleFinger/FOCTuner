package app.foctuner.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.sim.SimulatedVesc
import app.foctuner.ui.prefs.LocalUiPreferences
import app.foctuner.ui.prefs.UiPreferences
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.tools.Tool
import app.foctuner.ui.tools.ToolContent
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.setup.StartupTuneModel
import app.foctuner.vesc.VescClient
import app.foctuner.vesc.tune.RestoreMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.GraphicsMode

/** What the Startup tuning tool shows, against the simulator. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RobolectricConfig(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi")
class StartupTuneScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sim = SimulatedVesc()
    private val client = VescClient(sim, scope)
    private val context = ToolContext(client, client, runBlocking { client.firmwareInfo() }, scope, simulator = sim)
    private var open by mutableStateOf(true)

    @After
    fun tearDown() = scope.cancel()

    private fun show() = compose.setContent {
        CompositionLocalProvider(LocalUiPreferences provides UiPreferences.from(compose.activity.applicationContext)) {
            FocTheme(darkTheme = true) { if (open) ToolContent(Tool.StartupTuning, context) }
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(WAIT_MS) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun reopeningShowsSettingsChangedElsewhere() {
        show()
        waitForText("62.06")
        open = false
        compose.waitForIdle()
        runBlocking {
            val base = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.MCCONF))
            client.writeConfig(ConfigKind.MCCONF, client.readConfig(ConfigKind.MCCONF, base).with("foc_observer_gain", 31.03e6))
        }
        open = true
        waitForText("31.03")
    }

    @Test
    fun aStoppedRunSaysHowTheValuesCameBack() {
        val model = context.model { StartupTuneModel(context) }
        show()
        model.edit { it.copy(abortedRestore = RestoreMethod.PROBE) }
        waitForText("The starting values are back on the controller.")
        model.edit { it.copy(abortedRestore = RestoreMethod.FAILED) }
        waitForText("Reboot the controller to restore them")
    }

    private companion object {
        const val WAIT_MS = 20_000L
    }
}
