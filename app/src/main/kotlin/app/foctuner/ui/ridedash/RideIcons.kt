package app.foctuner.ui.ridedash

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import app.foctuner.R

/**
 * The Material icons the ride view uses, as vector drawables (`md_*`, Apache 2.0), under the names
 * `androidx.compose.material.icons` gives them, instead of the multi-megabyte icons-extended library.
 */
internal object Icons {
    object Filled {
        val Bolt: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_bolt)
        val BatteryAlert: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_battery_alert)
        val BluetoothDisabled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_bluetooth_disabled)
        val Home: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_home)
        val Memory: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_memory)
        val Thermostat: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_thermostat)
        val Add: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_add)
        val Delete: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_delete)
        val Edit: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_edit)
        val ContentCopy: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_content_copy)
        val FlipToBack: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_flip_to_back)
        val FlipToFront: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_flip_to_front)
        val Fullscreen: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_fullscreen)
        val FullscreenExit: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_fullscreen_exit)
        val GridOn: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_grid_on)
        val RestartAlt: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_restart_alt)
    }

    object AutoMirrored {
        object Filled {
            val Undo: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_undo)
            val KeyboardArrowLeft: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_keyboard_arrow_left)
            val KeyboardArrowRight: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.md_keyboard_arrow_right)
        }
    }
}
