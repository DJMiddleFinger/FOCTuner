package app.foctuner.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** Top of a page opened from a list: a back arrow, where the page lives ([breadcrumb]) and its [title]. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, breadcrumb: String? = null) {
    val colors = FocTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.row + Spacing.x1)
            .padding(start = Spacing.half, end = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, Modifier.size(Spacing.touchTarget)) {
            Icon(painterResource(R.drawable.ic_back), stringResource(R.string.page_back), tint = colors.text, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(start = Spacing.half)) {
            if (breadcrumb != null) {
                Text(breadcrumb, color = colors.textTertiary, style = FocTheme.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                title,
                Modifier.semantics { heading() },
                color = colors.text,
                style = FocTheme.type.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
