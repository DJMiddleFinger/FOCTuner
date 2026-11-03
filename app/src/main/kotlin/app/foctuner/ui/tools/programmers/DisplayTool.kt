package app.foctuner.ui.tools.programmers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.vesc.DisplayFont

private enum class Face(val typeface: Typeface) { Sans(Typeface.SANS_SERIF), Serif(Typeface.SERIF), Mono(Typeface.MONOSPACE) }

/**
 * VESC Tool's display tool, font part: renders a font into fixed-size glyphs for LispBM display drivers and saves
 * them in the `.bin` format they load (`DisplayFont`), 1 bit per pixel or 2 with antialiasing.
 */
@Composable
fun DisplayTool() {
    var face by rememberSaveable { mutableStateOf(Face.Sans) }
    var bold by rememberSaveable { mutableStateOf(false) }
    var antialias by rememberSaveable { mutableStateOf(false) }
    var digits by rememberSaveable { mutableStateOf(false) }
    var widthText by rememberSaveable { mutableStateOf("12") }
    var heightText by rememberSaveable { mutableStateOf("16") }
    var scaleText by rememberSaveable { mutableStateOf("1.0") }
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    val android = LocalContext.current
    val w = widthText.toIntOrNull()?.takeIf { it in 2..255 }
    val h = heightText.toIntOrNull()?.takeIf { it in 2..255 }
    val scale = scaleText.replace(',', '.').toFloatOrNull()?.takeIf { it in 0.2f..3f }
    val glyphs = remember(face, bold, antialias, digits, w, h, scale) {
        if (w == null || h == null || scale == null) null else renderGlyphs(face.typeface, bold, antialias, digits, w, h, scale)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && glyphs != null && w != null && h != null) {
            val bytes = DisplayFont.encode(w, h, glyphs.second, if (antialias) 2 else 1)
            saved = runCatching { android.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }; "${bytes.size}" }.getOrNull()
        }
    }
    val number = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    val field = Modifier.fillMaxWidth().padding(horizontal = Spacing.x2)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.display_intro),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = FocTheme.colors.textSecondary,
        )
        SectionHeader(stringResource(R.string.display_font))
        ChoiceRow(Face.entries, face, { stringResource(faceLabel(it)) }, { face = it })
        Check(stringResource(R.string.display_bold), bold) { bold = it }
        Check(stringResource(R.string.display_antialias), antialias) { antialias = it }
        Check(stringResource(R.string.display_digits), digits) { digits = it }
        OutlinedTextField(widthText, { widthText = it }, field, singleLine = true, isError = w == null, label = { Text(stringResource(R.string.display_width)) }, keyboardOptions = number)
        OutlinedTextField(heightText, { heightText = it }, field, singleLine = true, isError = h == null, label = { Text(stringResource(R.string.display_height)) }, keyboardOptions = number)
        OutlinedTextField(scaleText, { scaleText = it }, field, singleLine = true, isError = scale == null, label = { Text(stringResource(R.string.display_scale)) }, keyboardOptions = number)
        glyphs?.let { (sheet, _) ->
            SectionHeader(stringResource(R.string.display_preview))
            Image(
                sheet.asImageBitmap(),
                stringResource(R.string.display_preview),
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.x2)
                    .background(androidx.compose.ui.graphics.Color.Black),
                contentScale = ContentScale.FillWidth,
                filterQuality = FilterQuality.None,
            )
            OutlineButton(stringResource(R.string.display_save), { save.launch("font_${w}x${h}.bin") }, Modifier.padding(Spacing.x2))
        }
        saved?.let { Notice(stringResource(R.string.display_saved, it)) }
    }
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = FocTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null, colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.outline, checkmarkColor = colors.onAccent))
        Text(label, Modifier.padding(start = Spacing.x2), style = FocTheme.type.body, color = colors.text)
    }
}

private fun faceLabel(face: Face): Int = when (face) {
    Face.Sans -> R.string.display_sans
    Face.Serif -> R.string.display_serif
    Face.Mono -> R.string.display_mono
}

/**
 * Each character centred in a [w] × [h] cell, white on black, with the font sized so its ascent fills the cell
 * height times [scale] (as VESC Tool sizes it). Returns a preview sheet (16 glyphs per row) and the gray levels.
 */
private fun renderGlyphs(typeface: Typeface, bold: Boolean, antialias: Boolean, digits: Boolean, w: Int, h: Int, scale: Float): Pair<Bitmap, List<IntArray>> {
    val paint = Paint().apply {
        this.typeface = Typeface.create(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
        isAntiAlias = antialias
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 100f
    }
    paint.textSize = 100f * h / -paint.fontMetrics.ascent * scale
    val chars = DisplayFont.characters(digits)
    val perRow = 16
    val sheet = Bitmap.createBitmap(w * perRow, h * ((chars.size + perRow - 1) / perRow), Bitmap.Config.ARGB_8888)
    val sheetCanvas = Canvas(sheet)
    sheetCanvas.drawColor(Color.BLACK)
    val glyphs = chars.mapIndexed { i, c ->
        val cell = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cell)
        canvas.drawColor(Color.BLACK)
        val fm = paint.fontMetrics
        canvas.drawText(c.toString(), w / 2f, h / 2f - (fm.ascent + fm.descent) / 2, paint)
        val pixels = IntArray(w * h)
        cell.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { p ->
            val px = pixels[p]
            val level = (Color.red(px) * 11 + Color.green(px) * 16 + Color.blue(px) * 5) / 32
            if (antialias) level else if (level >= 128) 255 else 0
        }
        sheetCanvas.drawBitmap(cell, (i % perRow) * w.toFloat(), (i / perRow) * h.toFloat(), null)
        cell.recycle()
        gray
    }
    return sheet to glyphs
}
