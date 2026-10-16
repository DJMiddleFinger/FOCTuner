package app.foctuner.ui.tools

import app.foctuner.vesc.FirmwareInfo
import app.foctuner.vesc.VescClient
import app.foctuner.vesc.VescController
import kotlinx.coroutines.CoroutineScope

/**
 * What a tool works with: [controller] is where commands go (the attached controller or the selected CAN one),
 * [client] the connection, and [scope] lives as long as the connection, so work started there survives
 * rotation and stops on disconnect. A new context is made for every connection and CAN selection; tools keep
 * their state in [model] so it lives exactly as long.
 */
class ToolContext(
    val controller: VescController,
    val client: VescClient,
    /** Firmware of [controller]. */
    val firmware: FirmwareInfo,
    val scope: CoroutineScope,
) {
    @PublishedApi
    internal val models = HashMap<Class<*>, Any>()

    /** The tool's state holder of type [T] for this context, created on first use. Main thread only. */
    inline fun <reified T : Any> model(create: () -> T): T = models.getOrPut(T::class.java, create) as T
}
