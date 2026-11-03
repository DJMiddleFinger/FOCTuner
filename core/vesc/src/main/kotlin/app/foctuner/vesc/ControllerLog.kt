package app.foctuner.vesc

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Writes a log on a VESC Express's card with values read from this app: names the [columns], starts a log of them at
 * [rateHz] and keeps sending their values until cancelled, then stops it. The Express adds the time of day when
 * [appendTime] is set and its own position when [appendGnss] is (its receiver must have a fix, or it waits for one
 * before it opens the file). The log is a file in [EXPRESS_LOG_FOLDER]; download it from there.
 *
 * The Express keeps writing rows until it is told to stop, which only this call does on its way out. If the link
 * dies first, connect again and call [logStop].
 *
 * @param onRow gets the number of samples sent so far
 */
suspend fun VescController.logToController(
    columns: List<RideColumn>,
    rateHz: Float,
    appendTime: Boolean = true,
    appendGnss: Boolean = false,
    onRow: (Int) -> Unit = {},
) {
    require(columns.isNotEmpty()) { "choose at least one value to log" }
    val sampler = RideSampler(this)
    // A log that is still open would take the new names and values into its old file.
    logStop()
    delay(STOP_SETTLE)
    columns.forEachIndexed { i, c -> logConfigField(i, c.field) }
    logStart(columns.size, rateHz, appendTime, appendGnss)
    try {
        val period = (1000f / rateHz).toLong().milliseconds
        val values = FloatArray(columns.size)
        var rows = 0
        while (true) {
            val tickStart = TimeSource.Monotonic.markNow()
            val sample = sampler.sample()
            if (sample != null) {
                val row = RideRow(sample, 0.0, null, 0.0)
                for (i in columns.indices) columns[i].read(row).takeUnless { it.isNaN() }?.let { values[i] = it.toFloat() }
                logSendF32(0, values)
                onRow(++rows)
            }
            delay(period - tickStart.elapsedNow())
        }
    } finally {
        withContext(NonCancellable) {
            try {
                logStop()
            } catch (e: IOException) {
                // The link is gone; the next connection can stop the log.
            }
        }
    }
}

private val STOP_SETTLE = 400.milliseconds
