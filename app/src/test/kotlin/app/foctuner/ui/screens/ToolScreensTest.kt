package app.foctuner.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.foctuner.sim.SimulatedVesc
import app.foctuner.ui.prefs.LocalUiPreferences
import app.foctuner.ui.prefs.UiPreferences
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.tools.Tool
import app.foctuner.ui.tools.ToolContent
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.VescClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Opens every tool against the simulator the way the Tools tab does, lets it poll for a few seconds of real time,
 * and draws it to `build/screens/<tool>.png`. Fails if composing or running the tool throws.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi")
class ToolScreensTest(private val tool: Tool) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun opensAndRuns() {
        val sim = SimulatedVesc()
        val client = VescClient(sim, scope)
        val context = ToolContext(client, client, runBlocking { client.firmwareInfo() }, scope, simulator = sim)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalUiPreferences provides UiPreferences.from(compose.activity.applicationContext)) {
                FocTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize().background(FocTheme.colors.background)) { ToolContent(tool, context) }
                }
            }
        }
        repeat(STEPS) {
            Thread.sleep(STEP_MS)
            compose.mainClock.advanceTimeBy(STEP_MS)
        }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screens").apply { mkdirs() }.resolve("${tool.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        private const val STEPS = 15
        private const val STEP_MS = 200L

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun tools(): List<Array<Any>> = Tool.entries.map { arrayOf(it) }
    }
}
