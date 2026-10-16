package app.foctuner.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.foctuner.R

/** Top-level screens once connected. Setup and firmware join this list as they are built. */
enum class Destination(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    Overview(R.string.nav_overview, R.drawable.ic_nav_overview),
    Settings(R.string.nav_settings, R.drawable.ic_nav_settings),
    Tools(R.string.nav_tools, R.drawable.ic_nav_tools),
    Terminal(R.string.nav_terminal, R.drawable.ic_nav_terminal),
    Controllers(R.string.nav_controllers, R.drawable.ic_nav_controllers),
}
