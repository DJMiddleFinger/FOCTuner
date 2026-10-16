package app.foctuner.ui.tools.parts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.foctuner.ui.components.NO_VALUE
import app.foctuner.ui.components.Readout
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** A row of panel-meter cells (label top left, reading bottom right) split by hairlines. */
@Composable
fun MeterRow(vararg cells: Meter, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) {
                VerticalDivider(
                    Modifier
                        .fillMaxHeight()
                        .padding(vertical = Spacing.x1),
                    thickness = Dp.Hairline,
                    color = FocTheme.colors.hairline,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                verticalArrangement = Arrangement.spacedBy(Spacing.half),
            ) {
                Text(cell.label, color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Readout(cell.value ?: NO_VALUE, if (cell.value == null) "" else cell.unit, Modifier.align(Alignment.End))
            }
        }
    }
}

/** One cell of a [MeterRow]; a null [value] shows a dash. */
data class Meter(val label: String, val value: String?, val unit: String = "")
