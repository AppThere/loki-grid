package org.gridcraft.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

data class SheetInfo(val name: String, val usedRange: String?, val visible: Boolean)

data class DocumentInfo(
    val title: String,
    val dirty: Boolean,
    val sheets: List<SheetInfo>,
    val activeSheet: Int,
    val activeCell: String,
)

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/** Parses a `document.inspect` result. Missing fields fall back to defaults; never throws on shape. */
fun parseDocument(json: JsonElement): DocumentInfo {
    val o = json as? JsonObject ?: JsonObject(emptyMap())
    val sheets = (o["sheets"] as? JsonArray).orEmpty().mapNotNull { s ->
        val so = s as? JsonObject ?: return@mapNotNull null
        SheetInfo(
            name = so["name"].str() ?: return@mapNotNull null,
            usedRange = so["usedRange"].str(),
            visible = so["visibility"].str().let { it == null || it == "Visible" },
        )
    }
    return DocumentInfo(
        title = o["title"].str() ?: "Workbook",
        dirty = (o["dirty"] as? JsonPrimitive)?.booleanOrNull ?: false,
        sheets = sheets,
        activeSheet = (o["activeSheet"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
        activeCell = o["activeCell"].str() ?: "A1",
    )
}

/** Parses a `sheet.read {formatted:true}` result into rows of display text, anchored at [origin]. */
fun parseGrid(json: JsonElement): List<List<String>> {
    val rows = ((json as? JsonObject)?.get("values") as? JsonArray).orEmpty()
    return rows.map { row ->
        (row as? JsonArray).orEmpty().map { cell ->
            when (cell) {
                is JsonNull -> ""
                is JsonPrimitive -> cell.content
                is JsonObject -> cell["error"].str() ?: ""
                else -> ""
            }
        }
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonElement.arr(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
