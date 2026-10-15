package app.foctuner.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** Groups are separated by whitespace and named by this label; there are no boxes around them. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .padding(start = Spacing.x2, top = Spacing.x2, end = if (trailing == null) Spacing.x2 else Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(LocalConfiguration.current.locales[0]),
            Modifier
                .weight(1f)
                .semantics { heading() },
            color = FocTheme.colors.textSecondary,
            style = FocTheme.type.heading,
        )
        if (trailing != null) Box { trailing() }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = Dp.Hairline, color = FocTheme.colors.hairline)
}
