package app.loki.grid

/** The largest sheet: 16,384 columns (A…XFD) and 1,048,576 rows. */
const val MAX_COL = 16383
const val MAX_ROW = 1048575

/** A block of cells, zero-based and inclusive. A whole column spans every row, a whole row every column. */
data class CellRect(val r0: Int, val c0: Int, val r1: Int, val c1: Int) {
    fun contains(r: Int, c: Int) = r in r0..r1 && c in c0..c1
    val wholeColumns get() = r0 == 0 && r1 == MAX_ROW
    val wholeRows get() = c0 == 0 && c1 == MAX_COL
    val single get() = r0 == r1 && c0 == c1

    /** The reference the engine reads: `B2`, `B2:D5`, `C:C` or `4:6`. */
    fun ref(): String = when {
        wholeColumns && wholeRows -> "A1:${colName(MAX_COL)}${MAX_ROW + 1}"
        wholeColumns -> "${colName(c0)}:${colName(c1)}"
        wholeRows -> "${r0 + 1}:${r1 + 1}"
        single -> "${colName(c0)}${r0 + 1}"
        else -> "${colName(c0)}${r0 + 1}:${colName(c1)}${r1 + 1}"
    }

    companion object {
        /** The block between two corners, in either order. */
        fun between(r0: Int, c0: Int, r1: Int, c1: Int) = CellRect(minOf(r0, r1), minOf(c0, c1), maxOf(r0, r1), maxOf(c0, c1))
    }
}

/** What is selected: the cell that takes typing, and the blocks around it. */
data class Selection(val activeRow: Int, val activeCol: Int, val rects: List<CellRect>) {
    fun covers(r: Int, c: Int) = rects.any { it.contains(r, c) }
    /** The block that holds the active cell, or the last one: where Shift+arrow grows from. */
    val main: CellRect get() = rects.lastOrNull { it.contains(activeRow, activeCol) } ?: rects.lastOrNull() ?: CellRect(activeRow, activeCol, activeRow, activeCol)

    companion object {
        val Start = Selection(0, 0, listOf(CellRect(0, 0, 0, 0)))
    }
}

/** `A`, `B`, … `Z`, `AA`: the name of a zero-based column. */
fun colName(c: Int): String {
    var n = c + 1
    val sb = StringBuilder()
    while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
    return sb.toString()
}

/** The zero-based column of `A`, `AA`, `XFD`; null for anything that is not letters. */
fun colIndex(name: String): Int? {
    if (name.isEmpty() || !name.all { it in 'A'..'Z' || it in 'a'..'z' }) return null
    var n = 0
    for (ch in name.uppercase()) n = n * 26 + (ch - 'A' + 1)
    return (n - 1).takeIf { it in 0..MAX_COL }
}

private val CELL = Regex("""\$?([A-Za-z]{1,3})\$?(\d{1,7})""")
private val COLS = Regex("""\$?([A-Za-z]{1,3}):\$?([A-Za-z]{1,3})""")
private val ROWS = Regex("""\$?(\d{1,7}):\$?(\d{1,7})""")

/** One reference as a block: `B2`, `B2:D5`, `C:E`, `4:6`, a sheet prefix ignored. Null if it is none of those. */
fun parseRect(text: String): CellRect? {
    val s = text.trim().substringAfterLast('!')
    COLS.matchEntire(s)?.let { m ->
        val a = colIndex(m.groupValues[1]) ?: return null
        val b = colIndex(m.groupValues[2]) ?: return null
        return CellRect(0, minOf(a, b), MAX_ROW, maxOf(a, b))
    }
    ROWS.matchEntire(s)?.let { m ->
        val a = m.groupValues[1].toInt() - 1
        val b = m.groupValues[2].toInt() - 1
        if (a < 0 || b < 0 || a > MAX_ROW || b > MAX_ROW) return null
        return CellRect(minOf(a, b), 0, maxOf(a, b), MAX_COL)
    }
    val parts = s.split(':')
    if (parts.size > 2) return null
    val corners = parts.map { p ->
        val m = CELL.matchEntire(p) ?: return null
        val c = colIndex(m.groupValues[1]) ?: return null
        val r = m.groupValues[2].toInt() - 1
        if (r !in 0..MAX_ROW) return null
        r to c
    }
    val a = corners[0]; val b = corners.getOrElse(1) { a }
    return CellRect.between(a.first, a.second, b.first, b.second)
}

/** The engine's `selection` text (`B2`, `A1:B2,D4`) and active cell (`B2`) as a [Selection]; null if unreadable. */
fun parseSelection(selection: String?, active: String?): Selection? {
    val rects = selection.orEmpty().split(',').mapNotNull { parseRect(it) }
    val cell = active?.let { parseRect(it) } ?: rects.firstOrNull() ?: return null
    return Selection(cell.r0, cell.c0, rects.ifEmpty { listOf(CellRect(cell.r0, cell.c0, cell.r0, cell.c0)) })
}

/** The reference for a window of the sheet, `A1:Z40`, clamped to the sheet. */
fun windowRef(firstRow: Int, firstCol: Int, rows: Int, cols: Int): String {
    val r0 = firstRow.coerceIn(0, MAX_ROW); val c0 = firstCol.coerceIn(0, MAX_COL)
    val r1 = (r0 + rows - 1).coerceIn(r0, MAX_ROW); val c1 = (c0 + cols - 1).coerceIn(c0, MAX_COL)
    return "${colName(c0)}${r0 + 1}:${colName(c1)}${r1 + 1}"
}

// ---------------------------------------------------------------- where the columns and rows are

/**
 * The sizes along one axis (columns or rows) as the engine described a window of them, and what
 * lies beyond it. A scroll position is the first index in view and how far into it the view starts
 * (a sheet with columns of every width has no fixed pixel for column 5,000). Sizes are in pixels.
 */
class AxisMap(val first: Int, val sizes: List<Float>, val default: Float, val last: Int) {
    fun size(i: Int): Float = (if (i >= first && i - first < sizes.size) sizes[i - first] else default).let { if (it.isFinite()) it.coerceAtLeast(0f) else default }

    /** This map with index [i] given [size], for a column being dragged wider before the engine is told. */
    fun with(i: Int, size: Float): AxisMap =
        if (i < first || i - first >= sizes.size) this else AxisMap(first, sizes.toMutableList().also { it[i - first] = size.coerceAtLeast(0f) }, default, last)

    /** Where index [i] starts, in pixels from the start of the view, when the view starts [offset] into [from]. */
    fun start(from: Int, offset: Float, i: Int): Float {
        var x = 0f - offset
        if (i >= from) {
            var k = from
            while (k < i && k - from < SPAN_CAP) { x += size(k); k++ }
            if (k < i) x += (i - k) * default
        } else {
            var k = from - 1
            while (k >= i && from - k <= SPAN_CAP) { x -= size(k); k-- }
            if (k >= i) x -= (k - i + 1) * default
        }
        return x
    }

    /** The index at [pos] pixels from the start of the view (a hidden, zero-size index is never the one). */
    fun at(from: Int, offset: Float, pos: Float): Int {
        var x = 0f - offset
        var k = from
        if (pos < x) {
            while (k > 0 && pos < x) { k--; x -= size(k) }
            return k.coerceIn(0, last)
        }
        var steps = 0
        while (k < last) {
            val w = size(k)
            if (pos < x + w) return k
            x += w; k++
            if (++steps > SPAN_CAP) return (k + ((pos - x) / default.coerceAtLeast(1f)).toInt()).coerceIn(0, last)
        }
        return last
    }

    /** The view's start after scrolling by [delta] pixels, as (index, offset), kept inside the sheet. */
    fun scrolled(from: Int, offset: Float, delta: Float): Pair<Int, Float> {
        var k = from.coerceIn(0, last)
        var o = offset + delta
        var steps = 0
        while (o >= size(k) && k < last && steps++ < SPAN_CAP) { o -= size(k); k++ }
        if (o >= size(k) && k < last) { val more = (o / default.coerceAtLeast(1f)).toInt(); k = (k + more).coerceAtMost(last); o = 0f }
        steps = 0
        while (o < 0f && k > 0 && steps++ < SPAN_CAP) { k--; o += size(k) }
        if (o < 0f) o = 0f
        if (k >= last) o = o.coerceAtMost(size(last).coerceAtLeast(0f))
        return k to o
    }

    /** The view's start after bringing index [i] into a view [view] pixels long: unchanged if it already shows. */
    fun revealed(from: Int, offset: Float, i: Int, view: Float): Pair<Int, Float> {
        val s = start(from, offset, i)
        val e = s + size(i)
        return when {
            i < from || s < 0f -> i to 0f
            e > view -> scrolled(from, offset, e - view)
            else -> from to offset
        }
    }

    companion object { const val SPAN_CAP = 2000 }
}

/** What a cell looks like, as `sheet.layout` lists it; colours are `#RRGGBB`. */
data class CellLook(
    val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false,
    val color: String? = null, val fill: String? = null, val align: String? = null, val wrap: Boolean = false, val size: Float? = null,
)

/** The window `sheet.layout` described: sizes in engine pixels, the merges that touch it, the freeze, and the looks. */
class SheetLayout(
    val firstRow: Int, val firstCol: Int, val colSizes: List<Float>, val rowSizes: List<Float>,
    val defaultWidth: Float, val defaultHeight: Float, val freezeRows: Int, val freezeCols: Int,
    val merges: List<CellRect>, val looks: Map<Long, CellLook>,
) {
    fun look(r: Int, c: Int): CellLook? = looks[key(r, c)]

    companion object {
        fun key(r: Int, c: Int): Long = r.toLong() * (MAX_COL + 1) + c
        val Empty = SheetLayout(0, 0, emptyList(), emptyList(), 64f, 20f, 0, 0, emptyList(), emptyMap())
    }
}

/** `sheet.layout`'s answer for the window that starts at [firstRow] and [firstCol]; the default layout if it cannot be read. */
fun parseLayout(e: kotlinx.serialization.json.JsonElement?, firstRow: Int, firstCol: Int): SheetLayout {
    val o = e as? kotlinx.serialization.json.JsonObject ?: return SheetLayout.Empty
    fun nums(k: String) = (o[k] as? kotlinx.serialization.json.JsonArray).orEmpty().map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: 0f }
    fun num(k: String, d: Float) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: d
    val freeze = o["freeze"] as? kotlinx.serialization.json.JsonObject
    fun fz(k: String) = (freeze?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
    val merges = (o["merges"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(::parseRect) }
    val looks = HashMap<Long, CellLook>()
    for (c in (o["cells"] as? kotlinx.serialization.json.JsonArray).orEmpty()) {
        val co = c as? kotlinx.serialization.json.JsonObject ?: continue
        fun s(k: String) = (co[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        fun b(k: String) = s(k) == "true"
        val r = s("r")?.toIntOrNull() ?: continue
        val col = s("c")?.toIntOrNull() ?: continue
        looks[SheetLayout.key(r, col)] = CellLook(b("bold"), b("italic"), b("underline"), b("strike"), s("color"), s("fill"), s("align"), b("wrap"), s("size")?.toFloatOrNull())
    }
    return SheetLayout(firstRow, firstCol, nums("cols"), nums("rows"), num("defaultWidth", 64f), num("defaultHeight", 20f), fz("rows"), fz("cols"), merges, looks)
}
