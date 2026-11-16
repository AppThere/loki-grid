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

class AxisMapTest {
    // Columns 10..14 are 100, 50, 0 (hidden), 80 and 100 wide; the rest 64.
    private val axis = AxisMap(10, listOf(100f, 50f, 0f, 80f, 100f), 64f, 1000)

    @Test fun sizesInsideTheWindowAreTheEnginesAndOutsideAreTheDefault() {
        assertEquals(100f, axis.size(10)); assertEquals(0f, axis.size(12)); assertEquals(64f, axis.size(9)); assertEquals(64f, axis.size(500))
        assertEquals(64f, AxisMap(0, listOf(Float.NaN), 64f, 9).size(0), "a size that is not a number is the default")
    }

    @Test fun anIndexStartsAfterTheOnesBeforeIt() {
        assertEquals(0f, axis.start(10, 0f, 10)); assertEquals(100f, axis.start(10, 0f, 11)); assertEquals(150f, axis.start(10, 0f, 12))
        assertEquals(150f, axis.start(10, 0f, 13), "a hidden column takes no room")
        assertEquals(-30f, axis.start(10, 30f, 10), "the view starts 30 px into the first")
        assertEquals(-64f, axis.start(10, 0f, 9)); assertEquals(-128f, axis.start(10, 0f, 8))
        assertEquals(330f + 64f * 4, axis.start(10, 0f, 19), "beyond the window it is the default size")
    }

    @Test fun aPositionFindsItsIndexAndSkipsAHiddenOne() {
        assertEquals(10, axis.at(10, 0f, 0f)); assertEquals(10, axis.at(10, 0f, 99f)); assertEquals(11, axis.at(10, 0f, 100f))
        assertEquals(13, axis.at(10, 0f, 150f), "12 is hidden, 13 starts at 150")
        assertEquals(10, axis.at(10, 30f, 60f), "70 px into the first column")
        assertEquals(9, axis.at(10, 0f, -10f)); assertEquals(0, axis.at(10, 0f, -100000f))
        assertEquals(1000, axis.at(10, 0f, 1e9f), "past the end is the last")
    }

    @Test fun scrollingMovesAcrossColumnsBothWaysAndStopsAtTheEdges() {
        assertEquals(11 to 20f, axis.scrolled(10, 0f, 120f))
        assertEquals(10 to 90f, axis.scrolled(11, 20f, -30f))
        assertEquals(0 to 0f, axis.scrolled(2, 0f, -100000f))
        assertEquals(13 to 0f, axis.scrolled(11, 0f, 50f), "past a hidden column")
        val far = axis.scrolled(10, 0f, 64f * 400)
        assertEquals(true, far.first in 400..420, far.toString())
    }

    @Test fun revealingScrollsOnlyWhenTheIndexIsOutOfView() {
        assertEquals(10 to 0f, axis.revealed(10, 0f, 10, 300f), "already in view")
        val (from, off) = axis.revealed(10, 0f, 20, 300f)
        val end = axis.start(from, off, 20) + axis.size(20)
        assertEquals(300f, end, 0.01f)                    // its right edge is the view's
        assertEquals(5 to 0f, axis.revealed(10, 0f, 5, 300f), "before the first: it becomes the first")
    }

    @Test fun aColumnBeingDraggedIsItsNewSize() {
        val wide = axis.with(11, 200f)
        assertEquals(200f, wide.size(11)); assertEquals(50f, axis.size(11), "the original is not changed")
        assertEquals(axis, axis.with(500, 3f).let { axis }, "outside the window nothing changes")
    }

    @Test fun theLayoutIsRead() {
        val e = kotlinx.serialization.json.Json.parseToJsonElement("""{"cols":[64,120],"rows":[20,40],"defaultWidth":64,"defaultHeight":20,"freeze":{"rows":1,"cols":0},"merges":["B2:C3"],
            "cells":[{"r":1,"c":1,"bold":true,"fill":"#FFFF00","align":"right","size":11},{"r":"x"}]}""")
        val l = parseLayout(e, 5, 7)
        assertEquals(listOf(64f, 120f), l.colSizes); assertEquals(1, l.freezeRows); assertEquals(listOf(CellRect(1, 1, 2, 2)), l.merges)
        assertEquals(CellLook(bold = true, fill = "#FFFF00", align = "right", size = 11f), l.look(1, 1))
        assertEquals(null, l.look(0, 0))
        assertEquals(SheetLayout.Empty.defaultWidth, parseLayout(null, 0, 0).defaultWidth)
    }
}

class HexColorTest {
    @Test fun aHexColourIsReadAndAnythingElseIsNot() {
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFFFF00), hexColor("#FFFF00"))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF0A0B0C), hexColor("0A0B0C"))
        for (bad in listOf(null, "", "#FFF", "#GGGGGG", "#FFFF00AA", "yellow")) assertNull(hexColor(bad), bad.toString())
    }
}
