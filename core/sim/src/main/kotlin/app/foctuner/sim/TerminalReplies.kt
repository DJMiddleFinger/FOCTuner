package app.foctuner.sim

import java.util.Locale
import kotlin.time.Duration

/*
 * Output of the firmware's terminal commands (terminal.c). Each string is one commands_printf call and
 * becomes one COMM_PRINT packet, trailing newline included, exactly as the firmware formats it.
 */

internal class TerminalContext(
    val firmwareVersion: String,
    val hardwareName: String,
    val uuid: ByteArray,
    val uptime: Duration,
    val odometerMeters: Int,
)

internal object TerminalReplies {
    /** The lines the firmware prints for [command], starting with the echo of the command. */
    fun run(command: String, context: TerminalContext): List<String> {
        val words = command.trim().split(' ').filter { it.isNotEmpty() }
        val echo = "-> $command \n"
        if (words.isEmpty()) return listOf(echo, "No command received\n")
        return listOf(echo) + when (val name = words[0].lowercase()) {
            "help" -> help
            "mem" -> mem
            "fault" -> listOf("FAULT_CODE_NONE\n")
            "faults" -> listOf("No faults registered since startup\n")
            "uptime" -> listOf("Uptime: %.2f s\n".format(Locale.ROOT, context.uptime.inWholeMilliseconds / 1000.0))
            "fw_info" -> fwInfo
            "hw_status" -> hwStatus(context)
            else -> listOf("Invalid command: $name\ntype help to list all available commands\n")
        }
    }

    private val help = listOf(
        "Valid commands are:",
        "help",
        "  Show this help",
        "mem",
        "  Show memory usage",
        "fault",
        "  Print the current fault code",
        "faults",
        "  Print all stored fault codes and the data associated with them",
        "uptime",
        "  Show how long the firmware has been running",
        "fw_info",
        "  Print detailed firmware info",
        "hw_status",
        "  Print some hardware status information",
        " ",
    )

    private val mem = listOf(
        "core free memory : 12288 bytes",
        "heap fragments   : 2",
        "heap free total  : 41200 bytes\n",
    )

    private val fwInfo = listOf(
        "Git Branch: release_7_01",
        "Git Hash  : simulated",
        "Compiler  : arm-none-eabi-gcc (simulated)",
        " ",
    )

    private fun hwStatus(context: TerminalContext) = listOf(
        "Firmware: ${context.firmwareVersion}",
        "Hardware: ${context.hardwareName}",
        "UUID: " + context.uuid.joinToString(" ") { "%02X".format(it) },
        "Permanent NRF found: No",
        "Phase Shunts: Yes",
        "Odometer : ${context.odometerMeters} m",
        "Runtime  : ${context.uptime.inWholeSeconds} s",
        "FOC Current Offsets: 2048.12 2047.65 2049.03",
        "FOC Voltage Offsets: 0.0012 -0.0008 0.0003",
        "USB not enabled on hardware.\n",
    )
}
