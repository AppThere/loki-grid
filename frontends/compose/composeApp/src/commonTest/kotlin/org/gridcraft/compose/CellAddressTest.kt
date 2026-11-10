package org.gridcraft.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CellAddressTest {
    @Test fun columnNames() {
        assertEquals("A", columnName(0))
        assertEquals("Z", columnName(25))
        assertEquals("AA", columnName(26))
        assertEquals("AZ", columnName(51))
        assertEquals("BA", columnName(52))
    }

    @Test fun parse() {
        assertEquals(CellPos(1, 1), parseA1("B2"))
        assertEquals(CellPos(0, 26), parseA1("\$aa\$1"))
        assertNull(parseA1("A0"))
        assertNull(parseA1("1A"))
        assertNull(parseA1(""))
        assertNull(parseA1("A99999999999999999999"))
    }

    @Test fun roundTrip() {
        for (c in listOf(0, 25, 26, 701, 702)) assertEquals(CellPos(4, c), parseA1(a1(4, c)))
    }
}
