package app.foctuner.ui.about

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.prefs.PreferencesRoute
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** App-level pages reachable without a connection. In the connected shell the Tools tab lists them. */
enum class AppPage(@param:StringRes val title: Int) {
    Preferences(R.string.prefs_title),
    About(R.string.about_title),
}

/** [page] full screen with a way back, for the connect screen. */
@Composable
fun AppPageScreen(page: AppPage, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(
        Modifier
            .fillMaxSize()
            .background(FocTheme.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touchTarget)
                    .padding(start = Spacing.x1, end = Spacing.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlainButton("‹ " + stringResource(R.string.about_back), onBack)
                Text(stringResource(page.title), Modifier.weight(1f), color = FocTheme.colors.textSecondary, style = FocTheme.type.label, maxLines = 1)
            }
            Hairline()
            when (page) {
                AppPage.Preferences -> PreferencesRoute()
                AppPage.About -> AboutRoute()
            }
        }
    }
}
