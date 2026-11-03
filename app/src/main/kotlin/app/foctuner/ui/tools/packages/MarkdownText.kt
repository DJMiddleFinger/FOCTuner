package app.foctuner.ui.tools.packages

import android.text.Html
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

/** One block of a package description, after the little Markdown the packages use is read. */
internal sealed interface MdBlock {
    data class Heading(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
}

/** Headings, bullets, code fences and paragraphs; inline marks (`**`, `` ` ``, links, images) become plain text. */
internal fun parseMarkdown(md: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(inline(paragraph.toString().trim()))
        paragraph.clear()
    }
    var code: StringBuilder? = null
    for (raw in md.lines()) {
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (code == null) {
                flush()
                code = StringBuilder()
            } else {
                blocks += MdBlock.Code(code.toString().trimEnd())
                code = null
            }
            continue
        }
        if (code != null) {
            code.append(raw).append('\n')
            continue
        }
        val t = line.trimStart()
        when {
            t.isEmpty() -> flush()
            t.startsWith("#") -> {
                flush()
                blocks += MdBlock.Heading(inline(t.trimStart('#').trim()))
            }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> {
                flush()
                blocks += MdBlock.Bullet(inline(t.substring(2).trim()))
            }
            Regex("""^\d+[.)] """).containsMatchIn(t) -> {
                flush()
                blocks += MdBlock.Bullet(inline(t.substringAfter(' ').trim()))
            }
            else -> paragraph.append(if (paragraph.isEmpty()) "" else " ").append(t)
        }
    }
    code?.let { blocks += MdBlock.Code(it.toString().trimEnd()) }
    flush()
    return blocks
}

private val IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
private val LINK = Regex("""\[([^\]]*)]\(([^)]*)\)""")

private fun inline(text: String): String = text
    .replace(IMAGE, "")
    .replace(LINK) { m -> if (m.groupValues[1].isBlank()) m.groupValues[2] else "${m.groupValues[1]} (${m.groupValues[2]})" }
    .replace("**", "").replace("__", "").replace("`", "")
    .replace(Regex("<[^>]+>"), "")
    .replace(Regex("\\s{2,}"), " ")
    .trim()

internal fun htmlToText(html: String): String = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()

/** The first paragraph of the description, for the store list. */
internal fun firstParagraph(md: String, html: String): String {
    val blocks = if (md.isNotBlank()) parseMarkdown(md) else listOf(MdBlock.Paragraph(htmlToText(html)))
    return blocks.filterIsInstance<MdBlock.Paragraph>().firstOrNull()?.text.orEmpty()
}

@Composable
internal fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    val blocks = remember(md) { parseMarkdown(md) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(block.text, style = FocTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.text)
                is MdBlock.Bullet -> Text("•  " + block.text, style = FocTheme.type.supporting, color = colors.text)
                is MdBlock.Paragraph -> Text(block.text, style = FocTheme.type.supporting, color = colors.text)
                is MdBlock.Code -> Text(block.text, style = FocTheme.type.code, color = colors.textSecondary)
            }
        }
    }
}
