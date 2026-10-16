package app.foctuner.ui.tools.parts

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.NO_VALUE
import app.foctuner.ui.components.Readout
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** Label on the left, number and unit on the right, hairline below. A null [value] shows a dash. */
@Composable
fun ReadingRow(label: String, value: String?, unit: String = "", modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.row)
            .padding(horizontal = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = FocTheme.colors.text, style = FocTheme.type.body)
        Readout(value ?: NO_VALUE, if (value == null) "" else unit, style = FocTheme.type.value)
    }
    Hairline(Modifier.padding(start = Spacing.x2))
}
