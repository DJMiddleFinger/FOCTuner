package app.foctuner.logs

data class ColumnStats(val min: Double, val max: Double, val mean: Double, val minRow: Int, val maxRow: Int)

/**
 * Minimum, maximum and mean of [column] over [rows]. The mean is the average of the line the plot draws between the
 * samples, so a logger that skipped a stretch doesn't pull it toward the stretch it did record; a single sample or
 * samples at one instant give the plain average. Null for an empty range.
 */
fun LogTable.stats(column: Int, rows: IntRange = 0 until rowCount): ColumnStats? {
    val values = columns[column]
    val range = rows.first.coerceAtLeast(0)..rows.last.coerceAtMost(rowCount - 1)
    if (range.isEmpty()) return null
    var min = values[range.first]
    var max = min
    var minRow = range.first
    var maxRow = range.first
    var area = 0.0
    var plain = 0.0
    val t = seconds
    for (i in range) {
        val v = values[i]
        if (v < min) { min = v; minRow = i }
        if (v > max) { max = v; maxRow = i }
        plain += v
        if (i < range.last) area += (v + values[i + 1]) / 2 * (t[i + 1] - t[i])
    }
    val span = t[range.last] - t[range.first]
    val mean = if (span > 0.0) area / span else plain / (range.last - range.first + 1)
    return ColumnStats(min, max, mean, minRow, maxRow)
}

/**
 * The rows to draw for [values] in [from]..[to] (inclusive) with room for about [buckets] horizontal pixels: every
 * row when there are few, otherwise the lowest and highest of each bucket in row order, so spikes survive.
 */
fun decimate(values: DoubleArray, from: Int, to: Int, buckets: Int): IntArray {
    val start = from.coerceAtLeast(0)
    val end = to.coerceAtMost(values.size - 1)
    if (end < start) return IntArray(0)
    val count = end - start + 1
    if (buckets <= 0 || count <= buckets * 2) return IntArray(count) { start + it }
    val out = ArrayList<Int>(buckets * 2)
    for (b in 0 until buckets) {
        val lo = start + (count.toLong() * b / buckets).toInt()
        val hi = start + (count.toLong() * (b + 1) / buckets).toInt() - 1
        if (hi < lo) continue
        var minRow = lo
        var maxRow = lo
        for (i in lo..hi) {
            if (values[i] < values[minRow]) minRow = i
            if (values[i] > values[maxRow]) maxRow = i
        }
        if (minRow == maxRow) out += minRow else { out += minOf(minRow, maxRow); out += maxOf(minRow, maxRow) }
    }
    return out.toIntArray()
}
