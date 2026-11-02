package app.foctuner.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** A box with a picture, a name and, [below] it, whatever says more: an entry of the Tools or Settings grid. */
@Composable
fun Tile(@DrawableRes icon: Int, title: String, onClick: () -> Unit, modifier: Modifier = Modifier, below: @Composable () -> Unit = {}) {
    val colors = FocTheme.colors
    val shape = MaterialTheme.shapes.extraLarge
    Column(
        modifier
            .clip(shape)
            .background(colors.surface)
            .border(Dp.Hairline, colors.hairline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Box(
            Modifier
                .size(Spacing.x6)
                .background(colors.surfaceHigh, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = colors.text, modifier = Modifier.size(28.dp))
        }
        Text(title, color = colors.text, style = FocTheme.type.bodyStrong)
        below()
    }
}

/** [items] as rows of tiles, two a row on phones and more on wider screens; a row takes its tallest tile's height. */
@Composable
fun <T> TileGrid(items: List<T>, tile: @Composable (item: T, modifier: Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(2, (maxWidth / 176.dp).toInt())
        Column {
            for (row in items.chunked(columns)) {
                Row(
                    Modifier
                        .height(IntrinsicSize.Min)
                        .padding(horizontal = Spacing.x2, vertical = Spacing.half),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
                ) {
                    for (item in row) tile(item, Modifier.weight(1f).fillMaxHeight())
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
