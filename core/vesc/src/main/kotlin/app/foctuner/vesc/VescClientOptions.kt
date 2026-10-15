package app.foctuner.vesc

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class VescClientOptions(
    /** How long to wait for a reply. VESC Tool uses 1 s (100 ticks of its 10 ms timer) for every request. */
    val requestTimeout: Duration = 1.seconds,

    /**
     * The firmware probes all 255 CAN ids at up to 10 ms each, so a bus with no devices takes about 2.5 s
     * to answer. VESC Tool waits 5 s.
     */
    val pingCanTimeout: Duration = 5.seconds,

    /**
     * A half-received packet is dropped after this much silence so it can't swallow the start of the next
     * one. It must exceed the longest gap inside one packet (BLE connection intervals reach 50 ms, and a
     * packet may span two events) and stay well under [requestTimeout] so a retry starts clean.
     */
    val frameIdleTimeout: Duration = 250.milliseconds,

    /**
     * Gap between COMM_ALIVE packets while a motor command is active. The firmware's default command
     * timeout is 1 s, so this leaves room for several lost keepalives.
     */
    val keepaliveInterval: Duration = 100.milliseconds,

    /**
     * Terminal output has no end marker (each line is a separate COMM_PRINT), so a command is considered
     * finished once nothing has been printed for this long. Raise it for commands that print slowly.
     */
    val terminalQuietPeriod: Duration = 250.milliseconds,

    /** Upper bound for releasing motors and closing the transport when the client shuts down. */
    val shutdownTimeout: Duration = 1.seconds,
)
