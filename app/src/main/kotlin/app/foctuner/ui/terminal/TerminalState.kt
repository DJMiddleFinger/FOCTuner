package app.foctuner.ui.terminal

import androidx.compose.runtime.Immutable

enum class LineKind { Command, Output, Error }

@Immutable
data class TerminalLine(val id: Long, val text: String, val kind: LineKind)

@Immutable
data class TerminalState(
    val lines: List<TerminalLine> = emptyList(),
    val input: String = "",
    /** Most recent first, without duplicates. */
    val history: List<String> = emptyList(),
    val quickCommands: List<String> = READ_ONLY_COMMANDS,
    val waiting: Boolean = false,
)

/** Firmware terminal commands that only report state; none of them moves the motor. */
val READ_ONLY_COMMANDS = listOf("faults", "hw_status", "foc_state", "can_devs", "uptime", "help")
