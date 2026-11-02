package app.foctuner.ui.shell

import androidx.annotation.StringRes
import app.foctuner.R

/**
 * Connected screens. The tabs run along the top in VESC Tool's mobile order; the others are opened from the header
 * (like VESC Tool's drawer) and go back to the tab they were opened from.
 */
enum class Destination(@param:StringRes val label: Int, val tab: Boolean = true) {
    Start(R.string.nav_start),
    RtData(R.string.nav_rt_data),
    Bms(R.string.nav_bms),
    Terminal(R.string.nav_terminal),
    Lisp(R.string.nav_lisp),
    MotorCfg(R.string.nav_motor_cfg),
    AppCfg(R.string.nav_app_cfg),
    Controllers(R.string.nav_controllers, tab = false),
    Display(R.string.nav_display, tab = false),
    Preferences(R.string.prefs_title, tab = false),
    About(R.string.about_title, tab = false),
    ;

    companion object {
        val tabs = entries.filter { it.tab }
    }
}
