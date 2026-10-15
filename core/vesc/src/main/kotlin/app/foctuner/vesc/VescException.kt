package app.foctuner.vesc

import java.io.IOException

/** Base class for failures talking to a controller. Transport errors surface as their own exceptions. */
open class VescException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The controller did not answer in time. Either the request or its reply was lost, the command was dropped
 * (the firmware discards a terminal or CAN-ping command while another one is still running), or a CAN
 * controller that was asked for does not exist.
 */
class VescTimeoutException(message: String) : VescException(message)

/** The link is closed or dropped; nothing more can be sent or received on this client. */
class VescLinkException(message: String, cause: Throwable? = null) : VescException(message, cause)
