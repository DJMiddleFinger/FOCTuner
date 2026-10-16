package app.foctuner.ui.tools.lisp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import app.foctuner.ui.theme.FocTheme

/**
 * Syntax colors. Kept to a few muted hues so the graphite palette still dominates; each is AA (4.5:1 or more)
 * on the page background. The accent is used only for the matching bracket, which follows the cursor.
 */
@Immutable
class CodeColors(
    val keyword: SpanStyle,
    val builtin: SpanStyle,
    val constant: SpanStyle,
    val text: SpanStyle,
    val comment: SpanStyle,
    val bracket: Color,
    val match: Color,
    val currentMatch: Color,
)

@Composable
fun rememberCodeColors(): CodeColors {
    val colors = FocTheme.colors
    return remember(colors) {
        val dark = colors.isDark
        CodeColors(
            keyword = SpanStyle(color = colors.text, fontWeight = FontWeight.Medium),
            builtin = SpanStyle(color = if (dark) Color(0xFF8FB8DE) else Color(0xFF22578A)),
            constant = SpanStyle(color = if (dark) Color(0xFFD0A8D8) else Color(0xFF7A3D86)),
            text = SpanStyle(color = if (dark) Color(0xFFCDBE8F) else Color(0xFF6A5A1E)),
            comment = SpanStyle(color = colors.textTertiary),
            bracket = colors.accent,
            match = colors.surfaceHigh,
            currentMatch = colors.accent,
        )
    }
}

/**
 * Colors [lines] (the field's current text, split and tokenized). One instance per text; the result is
 * kept because the field asks again on every cursor move.
 */
class LispHighlighting(private val lines: LispLines, private val colors: CodeColors) : VisualTransformation {
    private var last: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        last?.let { if (it.text.text == text.text) return it }
        val styled = if (text.text == lines.text) style() else text
        return TransformedText(styled, OffsetMapping.Identity).also { last = it }
    }

    private fun style(): AnnotatedString {
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>(lines.count * 4)
        for (line in 0 until lines.count) {
            val start = lines.starts[line]
            for (token in lines.tokens(line)) {
                val style = when (token.kind) {
                    TokenKind.Keyword -> colors.keyword
                    TokenKind.Builtin -> colors.builtin
                    TokenKind.Constant, TokenKind.Number -> colors.constant
                    TokenKind.Text -> colors.text
                    TokenKind.Comment -> colors.comment
                    TokenKind.Open, TokenKind.Close -> continue
                }
                spans += AnnotatedString.Range(style, start + token.start, start + token.end)
            }
        }
        return AnnotatedString(lines.text, spans)
    }

    override fun equals(other: Any?) = other is LispHighlighting && other.lines === lines && other.colors == colors

    override fun hashCode() = System.identityHashCode(lines) * 31 + colors.hashCode()
}
