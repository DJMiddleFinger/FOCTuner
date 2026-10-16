package app.foctuner.ui.tools.bms

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.fixed
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/**
 * One horizontal bar per cell over [low]..[high] volts, as VESC Tool's mobile BMS page draws them: balancing cells
 * in the warning color, the lowest and highest cell named after the value.
 */
@Composable
internal fun CellBars(cells: List<Float>, balancing: List<Boolean>, low: Float = 3.0f, high: Float = 4.2f) {
    val colors = FocTheme.colors
    val min = cells.minOrNull()
    val max = cells.maxOrNull()
    val minLabel = stringResource(R.string.bms_cell_min)
    val maxLabel = stringResource(R.string.bms_cell_max)
    val balancingLabel = stringResource(R.string.bms_cell_balancing)
    cells.forEachIndexed { i, v ->
        val isBalancing = balancing.getOrElse(i) { false }
        val note = listOfNotNull(
            minLabel.takeIf { v == min && min != max },
            maxLabel.takeIf { v == max && min != max },
            balancingLabel.takeIf { isBalancing },
        ).joinToString(" · ")
        val description = stringResource(R.string.bms_cell_description, i + 1, v.fixed(3), note)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.x2, vertical = 2.dp)
                .clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("C${i + 1}", Modifier.width(36.dp), color = colors.textSecondary, style = FocTheme.type.caption)
            val fill = if (isBalancing) colors.warning else colors.accent
            Canvas(
                Modifier
                    .weight(1f)
                    .height(14.dp),
            ) {
                drawRoundRect(colors.hairline, cornerRadius = CornerRadius(2.dp.toPx()))
                val fraction = ((v - low) / (high - low)).coerceIn(0.01f, 1f)
                drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = CornerRadius(2.dp.toPx()))
            }
            Text(v.fixed(3), Modifier.padding(start = Spacing.x1), color = colors.text, style = FocTheme.type.code)
            Text(note, Modifier.width(72.dp).padding(start = Spacing.x1), color = colors.textTertiary, style = FocTheme.type.caption, maxLines = 1)
        }
    }
}
