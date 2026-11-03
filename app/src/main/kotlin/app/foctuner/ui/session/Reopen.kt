package app.foctuner.ui.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * Runs [attempt] every [pause] until it returns, for at most [timeout]; null if it never did. An attempt fails
 * by throwing [IOException] (or whatever [retryOn] accepts); anything else, and cancellation, passes through.
 * A controller that just jumped to its bootloader answers nothing for most of a minute, USB takes seconds to
 * show up again, and Bluetooth needs a fresh scan, so one failed attempt says nothing.
 */
suspend fun <T : Any> reopen(
    timeout: Duration,
    pause: Duration,
    retryOn: (Throwable) -> Boolean = { it is IOException },
    attempt: suspend () -> T,
): T? = withTimeoutOrNull(timeout) {
    while (true) {
        coroutineContext.ensureActive()
        delay(pause)
        try {
            return@withTimeoutOrNull attempt()
        } catch (e: Throwable) {
            if (!retryOn(e)) throw e
        }
    }
    @Suppress("UNREACHABLE_CODE")
    null
}
