package app.loki.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SheetModelTest {
    @Test fun columnsAreNamedAndReadBack() {
        assertEquals("A", colName(0)); assertEquals("Z", colName(25)); assertEquals("AA", colName(26)); assertEquals("XFD", colName(MAX_COL))
        for (c in listOf(0, 25, 26, 701, 702, MAX_COL)) assertEquals(c, colIndex(colName(c)))
        assertNull(colIndex("XFE")); assertNull(colIndex("A1")); assertNull(colIndex(""))
    }

    @Test fun referencesBecomeBlocksAndBack() {
        assertEquals(CellRect(1, 1, 1, 1), parseRect("B2"))
        assertEquals(CellRect(1, 1, 4, 3), parseRect("D5:B2"))
        assertEquals(CellRect(0, 2, MAX_ROW, 4), parseRect("C:E"))
        assertEquals(CellRect(3, 0, 5, MAX_COL), parseRect("4:6"))
        assertEquals(CellRect(0, 0, 1, 1), parseRect("Sheet2!A1:B2"))
        assertEquals(CellRect(0, 0, 0, 0), parseRect("\$A\$1"))
        for (r in listOf("B2", "B2:D5", "C:E", "4:6")) assertEquals(r, parseRect(r)!!.ref())
    }

    @Test fun nonsenseIsRefusedNotThrown() {
        for (s in listOf("", "hello", "A1:B2:C3", "XFE1", "A0", "A1048577", "0:0", "1:9999999")) assertNull(parseRect(s), s)
    }

    @Test fun theEnginesSelectionTextIsRead() {
        val s = parseSelection("A1:B2,D4", "B2")!!
        assertEquals(1, s.activeRow); assertEquals(1, s.activeCol)
        assertEquals(2, s.rects.size)
        assertTrue(s.covers(3, 3)); assertTrue(!s.covers(2, 2))
        assertEquals(CellRect(0, 0, 1, 1), s.main)
        assertEquals(Selection(4, 0, listOf(CellRect(4, 0, 4, 0))), parseSelection("A5", null))
        assertNull(parseSelection("junk", null))
    }

    @Test fun aWindowIsClampedToTheSheet() {
        assertEquals("A1:Z40", windowRef(0, 0, 40, 26))
        assertEquals("XFC1048571:XFD1048576", windowRef(MAX_ROW - 5, MAX_COL - 1, 50, 50))
    }
}
