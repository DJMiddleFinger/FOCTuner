package app.foctuner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

enum class NoticeTone { Neutral, Warning, Fault }

/**
 * An inline explanation of a state the user has to deal with: what happened, why, what to check, and at
 * most one action. Neutral notices sit on the page; warnings and faults get a tinted band so they hold
 * up at a glance.
 */
@Composable
fun Notice(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    tone: NoticeTone = NoticeTone.Neutral,
    label: String? = null,
    checks: List<String> = emptyList(),
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = FocTheme.colors
    val (bar, band) = when (tone) {
        NoticeTone.Neutral -> colors.outline to Color.Transparent
        NoticeTone.Warning -> colors.warning to colors.warningContainer
        NoticeTone.Fault -> colors.fault to colors.faultContainer
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(band)
            .semantics { if (tone != NoticeTone.Neutral) liveRegion = LiveRegionMode.Polite },
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(bar),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = Spacing.x2 - 3.dp, end = Spacing.x2, top = Spacing.x2, bottom = Spacing.x2),
            verticalArrangement = Arrangement.spacedBy(Spacing.half),
        ) {
            if (label != null) {
                Text(
                    label.uppercase(LocalConfiguration.current.locales[0]),
                    color = if (tone == NoticeTone.Neutral) colors.textSecondary else bar,
                    style = FocTheme.type.heading,
                )
            }
            Text(title, color = colors.text, style = FocTheme.type.bodyStrong)
            if (body != null) Text(body, color = colors.textSecondary, style = FocTheme.type.supporting)
            checks.forEach { check ->
                Row {
                    Text("–", Modifier.width(Spacing.x2), color = colors.textTertiary, style = FocTheme.type.supporting)
                    Text(check, color = colors.textSecondary, style = FocTheme.type.supporting)
                }
            }
            if (action != null) {
                Spacer(Modifier.height(Spacing.half))
                OutlineButton(action, onAction)
            }
        }
    }
}
