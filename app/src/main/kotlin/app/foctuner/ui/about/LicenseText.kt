package app.foctuner.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A plain-text licence from `assets/licenses/`, reflowed into paragraphs so it reads on a phone. */
@Composable
internal fun LicenseText(asset: String, modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    val paragraphs by produceState(emptyList<String>(), asset) {
        value = withContext(Dispatchers.IO) {
            assets.open("licenses/$asset").bufferedReader().use { it.readText() }
        }.let(::paragraphs)
    }
    LazyColumn(
        modifier
            .fillMaxSize()
            .background(FocTheme.colors.background),
    ) {
        items(paragraphs) { paragraph ->
            Text(
                paragraph,
                Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
                color = FocTheme.colors.textSecondary,
                style = FocTheme.type.supporting,
            )
        }
    }
}

/** Blank lines separate paragraphs; line breaks inside one are only the source's wrapping. */
internal fun paragraphs(text: String): List<String> =
    text.split(Regex("""\n\s*\n""")).map { block -> block.lines().joinToString(" ") { it.trim() }.trim() }.filter { it.isNotEmpty() }
