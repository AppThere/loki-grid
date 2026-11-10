package org.gridcraft.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private class FakeEngine : EngineClient {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var fail: String? = null

    override suspend fun call(method: String, params: JsonObject): JsonElement {
        calls += method to params
        fail?.let { throw EngineException(it) }
        return Json.parseToJsonElement(
            when (method) {
                "document.inspect" ->
                    """{"title":"Book1","dirty":false,"activeSheet":0,"activeCell":"B2",
                        "sheets":[{"name":"Sheet1","visibility":"Visible","usedRange":"A1:B2"},
                                  {"name":"Hid","visibility":"Hidden","usedRange":null}]}"""
                "sheet.read" -> """{"values":[["x",1.5],[null,{"error":"#DIV/0!"}]]}"""
                "cell.get" -> """{"input":"=1/0"}"""
                else -> "{}"
            },
        )
    }

    override fun close() {}
}

class GridViewModelTest {
    @Test fun refreshLoadsDocumentGridAndFormula() = runTest {
        val vm = GridViewModel(FakeEngine())
        vm.refresh()
        assertEquals("Book1", vm.document?.title)
        assertEquals(listOf(true, false), vm.document?.sheets?.map { it.visible })
        assertEquals(CellPos(1, 1), vm.selected)
        assertEquals("x", vm.cellText(0, 0))
        assertEquals("1.5", vm.cellText(0, 1))
        assertEquals("#DIV/0!", vm.cellText(1, 1))
        assertEquals("", vm.cellText(5, 5))
        assertEquals("=1/0", vm.formulaText)
        assertNull(vm.error)
    }

    @Test fun commitRunsCellSetThenRefreshes() = runTest {
        val e = FakeEngine()
        val vm = GridViewModel(e)
        vm.refresh()
        e.calls.clear()
        vm.commit("=SUM(A1:A3)")
        assertEquals("cell.set", e.calls.first().first)
        assertEquals("B2", e.calls.first().second["cell"]?.jsonPrimitive?.content)
        assertEquals("=SUM(A1:A3)", e.calls.first().second["input"]?.jsonPrimitive?.content)
        assertEquals("document.inspect", e.calls[1].first)
    }

    @Test fun engineErrorsSurfaceInsteadOfThrowing() = runTest {
        val e = FakeEngine().apply { fail = "boom" }
        val vm = GridViewModel(e)
        vm.refresh()
        assertEquals("boom", vm.error)
    }

    @Test fun selectOutsideViewIsIgnored() = runTest {
        val e = FakeEngine()
        val vm = GridViewModel(e)
        vm.select(-1, 0)
        vm.select(0, VIEW_COLS)
        assertEquals(0, e.calls.size)
    }

    @Test fun parsersTolerateGarbage() {
        assertEquals("Workbook", parseDocument(Json.parseToJsonElement("[]")).title)
        assertEquals(emptyList(), parseGrid(Json.parseToJsonElement("3")))
    }
}
