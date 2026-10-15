package app.foctuner.ui.theme

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 400, heightDp = 860)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 400, heightDp = 860)
annotation class ThemePreviews

/** Long names and the largest common font scale, to catch truncation and overlap. */
@Preview(name = "Large font", uiMode = Configuration.UI_MODE_NIGHT_YES, fontScale = 1.6f, widthDp = 360, heightDp = 860)
annotation class StressPreview

@Preview(name = "Tablet", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 1000, heightDp = 700)
annotation class TabletPreview
