package app.foctuner.ui.ridedash

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.foctuner.sim.SimulatedVesc
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.VescClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Each ride style, once its intro has settled, recomposes only when its data changes: the red heat
 * warnings' blink is read while drawing, so it redraws the canvas without recomposing. Counts frames
 * in which the recomposer recomposed anything, and checks the screen still blinks when it should.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-night-mdpi")
class RideRecompositionTest(private val style: RideStyle) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun noWarnings() {
        val run = watch(emptyList())
        assertQuiet(run)
        assertEquals("distinct frames", 1, run.distinctFrames)
    }

    @Test
    fun warningsBelowRedDoNotBlink() {
        val run = watch(listOf(RideWarning.NoLink, RideWarning.ControllerHot(75.0, 60.0), RideWarning.MotorHot(90.0, 80.0)))
        assertQuiet(run)
        assertEquals("distinct frames", 1, run.distinctFrames)
    }

    @Test
    fun warningPastRedBlinksWithoutRecomposing() {
        val run = watch(listOf(RideWarning.ControllerHot(CONTROLLER_RED_C + 5, 60.0)))
        assertQuiet(run)
        assertTrue("blink frames: ${run.distinctFrames}", run.distinctFrames >= 4)
    }

    private class Run(val recomposingFrames: Long, val frames: Int, val distinctFrames: Int)

    private fun assertQuiet(run: Run) {
        // Tiles' clock ticks once a second; anything per frame would be about [run.frames].
        assertTrue("$style recomposed in ${run.recomposingFrames} of ${run.frames} frames", run.recomposingFrames <= 2)
    }

    /**
     * Shows [style] with [warnings], lets the intro settle, then runs one blink period (900 ms) frame by
     * frame, counting frames that recomposed and the distinct images drawn every 100 ms.
     */
    private fun watch(warnings: List<RideWarning>): Run {
        val d = rideData(warnings)
        val c = RidePalette.Dark
        val hub = if (style == RideStyle.CUSTOM) rideHub() else null
        compose.mainClock.autoAdvance = false
        compose.setContent { Style(hub, d, c) }
        compose.mainClock.advanceTimeBy(2_000)

        val before = recomposerChanges()
        val frames = (PERIOD_MS / FRAME_MS).toInt()
        val images = HashSet<Int>()
        for (i in 0 until frames) {
            compose.mainClock.advanceTimeByFrame()
            if (i % (frames / 9) == 0) images += screenHash()
        }
        return Run(recomposerChanges() - before, frames, images.size)
    }

    @Composable
    private fun Style(hub: RideHub?, d: RideData, c: RidePalette) = when (style) {
        RideStyle.CLASSIC -> ClassicRide(d, c, {}, {})
        RideStyle.MINIMAL -> MinimalRide(d, c, {}, {})
        RideStyle.TILES -> TilesRide(d, c, {}, {})
        RideStyle.CUSTOM -> CustomRide(hub!!, d, c, {}, {})
    }

    private fun rideHub(): RideHub {
        val sim = SimulatedVesc()
        val client = VescClient(sim, scope)
        val context = ToolContext(client, client, runBlocking { client.firmwareInfo() }, scope, simulator = sim)
        return RideHub(context, compose.activity.applicationContext, imperial = false)
    }

    /** Frames in which any composition recomposed and applied changes. */
    private fun recomposerChanges(): Long = Recomposer.runningRecomposers.value.sumOf { it.changeCount }

    /** Hash of the screen above Tiles' clock line, which follows the wall clock. */
    private fun screenHash(): Int {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val rows = (bitmap.height * 0.88f).toInt()
        val pixels = IntArray(bitmap.width * rows)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, rows)
        return pixels.contentHashCode()
    }

    private fun rideData(warnings: List<RideWarning>) = RideData(
        speed = 32.0,
        speedUnit = "kmh",
        trip = 12.3,
        tripUnit = "km",
        motorTempC = 70.0,
        battery = 64.0,
        torque = 0.4f,
        modeNumber = 2,
        modeName = "Trail",
        modeColor = Color(0xFF29B6F6),
        modeStatus = ModeApplyStatus.Idle,
        rideTimeMs = 17 * 60_000L,
        warnings = warnings,
    )

    companion object {
        private const val PERIOD_MS = 900L
        private const val FRAME_MS = 16L

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun styles(): List<Array<Any>> = RideStyle.entries.map { arrayOf(it) }
    }
}

class WarningBlinkTest {
    @Test
    fun onlyHeatAtItsRedPointBlinks() {
        assertTrue(RideWarning.ControllerHot(CONTROLLER_RED_C, 60.0).blinks)
        assertTrue(RideWarning.MotorHot(MOTOR_RED_C + 1, 80.0).blinks)
        assertFalse(RideWarning.ControllerHot(CONTROLLER_RED_C - 0.5, 60.0).blinks)
        assertFalse(RideWarning.MotorHot(MOTOR_RED_C - 0.5, 80.0).blinks)
        assertFalse(RideWarning.NoLink.blinks)
        assertFalse(RideWarning.LowBattery(critical = true).blinks)
        assertFalse(RideWarning.Fault("ABS_OVER_CURRENT").blinks)
    }
}
