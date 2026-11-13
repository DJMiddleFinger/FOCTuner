package app.foctuner.ui.tools.parts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** One-of-several choice laid out like the navigation tabs: the selected label gets the accent bar under it. */
@Composable
fun <T> ChoiceRow(
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = FocTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.x1)
            .selectableGroup(),
    ) {
        choices.forEach { choice ->
            val isSelected = choice == selected
            Box(
                Modifier
                    .heightIn(min = Spacing.touchTarget)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.Tab) { onSelect(choice) }
                    .padding(horizontal = Spacing.x1)
                    // Drawn rather than laid out: inside the horizontal scroll a fillMaxWidth bar measures 0 wide.
                    .drawBehind {
                        if (isSelected) drawRect(colors.accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(choice),
                    color = when {
                        !enabled -> colors.textTertiary
                        isSelected -> colors.text
                        else -> colors.textSecondary
                    },
                    style = FocTheme.type.label.copy(fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal),
                    maxLines = 1,
                )
            }
        }
    }
}
