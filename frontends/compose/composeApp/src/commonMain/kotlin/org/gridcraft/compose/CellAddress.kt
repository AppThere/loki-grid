package org.gridcraft.compose

/** Zero-based column index to its letters: 0 → "A", 26 → "AA". */
fun columnName(col: Int): String {
    require(col >= 0) { "negative column" }
    var n = col
    val sb = StringBuilder()
    do {
        sb.append('A' + n % 26)
        n = n / 26 - 1
    } while (n >= 0)
    return sb.reverse().toString()
}

/** A1-style reference of a zero-based (row, col). */
fun a1(row: Int, col: Int): String = columnName(col) + (row + 1)

data class CellPos(val row: Int, val col: Int)

/** Parses "B2" / "$B$2" into a zero-based position, or null if it isn't a plain cell reference. */
fun parseA1(text: String): CellPos? {
    val s = text.trim().replace("$", "").uppercase()
    val letters = s.takeWhile { it in 'A'..'Z' }
    val digits = s.drop(letters.length)
    if (letters.isEmpty() || letters.length > 3 || digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
    val row = digits.toIntOrNull() ?: return null
    if (row < 1) return null
    var col = 0
    for (ch in letters) col = col * 26 + (ch - 'A' + 1)
    return CellPos(row - 1, col - 1)
}
