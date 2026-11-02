package app.foctuner.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/**
 * Text tabs with an accent underline, as in VESC Tool's mobile app. [scrollable] strips keep their natural tab
 * widths and scroll the selected tab into view; fixed ones share the width evenly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabStrip(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
) {
    val colors = FocTheme.colors
    val tabs = @Composable {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = index == selected,
                onClick = { onSelect(index) },
                modifier = Modifier.height(Spacing.touchTarget),
                selectedContentColor = colors.text,
                unselectedContentColor = colors.textTertiary,
                text = { Text(label, style = FocTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
    if (scrollable) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selected,
            modifier = modifier,
            containerColor = colors.background,
            contentColor = colors.text,
            edgePadding = Spacing.x1,
            minTabWidth = 0.dp,
            indicator = {
                TabRowDefaults.PrimaryIndicator(
                    Modifier.tabIndicatorOffset(selected, matchContentSize = true),
                    color = colors.accent,
                    height = 2.dp,
                )
            },
            divider = { Hairline() },
            tabs = tabs,
        )
    } else {
        SecondaryTabRow(
            selectedTabIndex = selected,
            modifier = modifier,
            containerColor = colors.background,
            contentColor = colors.text,
            indicator = {
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(selected), height = 2.dp, color = colors.accent)
            },
            divider = { Hairline() },
            tabs = tabs,
        )
    }
}
