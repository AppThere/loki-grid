package org.gridcraft.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Visible window of the sheet the frontend asks the engine for. */
const val VIEW_ROWS = 100
const val VIEW_COLS = 26

/**
 * Presentation state. The UI is thin: it reads engine state through the control protocol and acts
 * only by running engine commands (`selection.set`, `cell.set`, `sheet.activate`, …).
 */
class GridViewModel(private val engine: EngineClient) {
    var document by mutableStateOf<DocumentInfo?>(null); private set
    var cells by mutableStateOf<List<List<String>>>(emptyList()); private set
    var selected by mutableStateOf(CellPos(0, 0)); private set
    /** What the formula bar edits: the active cell's input text (formula or literal). */
    var formulaText by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
            error = null
        } catch (e: EngineException) {
            error = e.message
        }
    }

    suspend fun refresh() = guarded {
        val doc = parseDocument(engine.call("document.inspect"))
        document = doc
        selected = parseA1(doc.activeCell) ?: CellPos(0, 0)
        cells = parseGrid(
            engine.call(
                "sheet.read",
                buildJsonObject {
                    put("range", "A1:" + a1(VIEW_ROWS - 1, VIEW_COLS - 1))
                    put("formatted", true)
                },
            ),
        )
        loadFormula()
    }

    private suspend fun loadFormula() {
        val r = engine.call("cell.get", buildJsonObject { put("cell", a1(selected.row, selected.col)) })
        formulaText = ((r as? JsonObject)?.get("input") as? JsonPrimitive)?.content.orEmpty()
    }

    fun cellText(row: Int, col: Int): String = cells.getOrNull(row)?.getOrNull(col).orEmpty()

    suspend fun select(row: Int, col: Int) = guarded {
        if (row !in 0 until VIEW_ROWS || col !in 0 until VIEW_COLS) return@guarded
        engine.call("selection.set", buildJsonObject { put("range", a1(row, col)) })
        selected = CellPos(row, col)
        loadFormula()
    }

    /** Commits [text] into the selected cell (the formula bar's Enter), then re-reads the sheet. */
    suspend fun commit(text: String) {
        guarded {
            engine.call(
                "cell.set",
                buildJsonObject {
                    put("cell", a1(selected.row, selected.col))
                    put("input", text)
                },
            )
        }
        if (error == null) refresh()
    }

    suspend fun activateSheet(name: String) {
        guarded { engine.call("sheet.activate", buildJsonObject { put("sheet", name) }) }
        if (error == null) refresh()
    }

    suspend fun undo() = run("edit.undo")
    suspend fun redo() = run("edit.redo")

    private suspend fun run(command: String) {
        guarded {
            engine.call(
                "engine.execute",
                buildJsonObject {
                    put("command", command)
                    put("params", JsonObject(emptyMap()))
                },
            )
        }
        if (error == null) refresh()
    }
}
