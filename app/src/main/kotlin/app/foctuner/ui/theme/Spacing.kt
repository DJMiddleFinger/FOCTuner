package app.foctuner.ui.theme

import androidx.compose.ui.unit.dp

/** 8dp grid. [half] exists only for optical nudges inside a component, never between components. */
object Spacing {
    val half = 4.dp
    val x1 = 8.dp
    val x2 = 16.dp
    val x3 = 24.dp
    val x4 = 32.dp
    val x6 = 48.dp

    val touchTarget = 48.dp
    val row = 56.dp
    val twoLineRow = 72.dp

    /** Content wider than this becomes two panes. */
    val wideBreakpoint = 600.dp
}
