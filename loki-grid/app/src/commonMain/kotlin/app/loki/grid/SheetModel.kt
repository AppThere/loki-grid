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
