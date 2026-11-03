package app.foctuner.logs

import java.util.Locale

class LogFormatException(message: String) : Exception(message)

/**
 * VESC Tool's log files: a header line of `key:name:unit:precision:relative:timestamp` cells and one line per
 * sample, all separated by `;`. A value is written with its field's precision. An empty cell means the value did
 * not change, as the VESC Express logger and VESC Tool's analysis treat it; the first sample's empty cells are 0.
 */
object LogCsv {
    fun parse(bytes: ByteArray): LogTable = parse(bytes.toString(Charsets.UTF_8))

    /** @throws LogFormatException if the first line is not a header in this format */
    fun parse(text: String): LogTable {
        val lines = text.lineSequence().map { it.trimEnd('\r') }.iterator()
        if (!lines.hasNext()) throw LogFormatException("The log is empty")
        val fields = lines.next().split(';').map(::field)
        val last = DoubleArray(fields.size)
        val rows = ArrayList<DoubleArray>()
        for (line in lines) {
            if (line.isEmpty()) continue
            val cells = line.split(';')
            if (cells.size != fields.size) continue
            for (i in cells.indices) cells[i].trim().toDoubleOrNull()?.let { last[i] = it }
            rows += last.copyOf()
        }
        return LogTable(fields, fields.indices.map { c -> DoubleArray(rows.size) { rows[it][c] } })
    }

    fun header(fields: List<LogField>): String = fields.joinToString(";") {
        "${it.key}:${it.name}:${it.unit}:${it.precision}:${if (it.relativeToFirst) 1 else 0}:${if (it.timestamp) 1 else 0}"
    }

    /** One sample; NaN is written as an empty cell. */
    fun row(fields: List<LogField>, values: DoubleArray): String {
        require(values.size == fields.size) { "${values.size} values for ${fields.size} fields" }
        return buildString {
            for (i in values.indices) {
                if (i > 0) append(';')
                if (!values[i].isNaN()) append(String.format(Locale.ROOT, "%.${fields[i].precision.coerceIn(0, MAX_PRECISION)}f", values[i]))
            }
        }
    }

    fun write(table: LogTable, out: Appendable) {
        out.append(header(table.fields)).append('\n')
        val row = DoubleArray(table.fields.size)
        for (r in 0 until table.rowCount) {
            for (c in row.indices) row[c] = table.columns[c][r]
            out.append(row(table.fields, row)).append('\n')
        }
    }

    private fun field(cell: String): LogField {
        val t = cell.split(':')
        if (t.size < 2) throw LogFormatException("Not a VESC Tool log: the first line has no key:name cells")
        return LogField(
            key = t[0],
            name = t[1],
            unit = t.getOrElse(2) { "" },
            precision = t.getOrNull(3)?.toDoubleOrNull()?.toInt() ?: 2,
            relativeToFirst = t.getOrNull(4)?.toDoubleOrNull()?.toInt() == 1,
            timestamp = t.getOrNull(5)?.toDoubleOrNull()?.toInt() == 1,
        )
    }

    private const val MAX_PRECISION = 12
}
