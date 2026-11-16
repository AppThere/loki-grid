package app.loki.grid.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.loki.kit.SheetDialog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Data sort: translated from `dialogs/data.rs` (fn `sort`, line 184) by egui2compose.
 *
 * The controls and their order are the desktop dialog's. NOT translated: the starting values (the
 * desktop reads them from its own state), anything it draws by hand, and enabling rules. The
 * parameter names are guessed from the Rust fields each control was bound to: check them against
 * the command's params doc before trusting OK.
 *   command: data.sort
 *   params:  {range?, header?: bool, keys: [{column: "B", order: asc|desc, by?: values|cellColor|fontColor, customList?: [..]}], orientation?: rows|columns, matchCase?}
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataSortDialog(onRun: (command: String, params: JsonObject) -> Unit, onDismiss: () -> Unit) {
    var f0 by remember { mutableStateOf(false) }
    var f1 by remember { mutableStateOf("") }
    var f2 by remember { mutableStateOf("") }
    var f3 by remember { mutableStateOf("") }
    SheetDialog(
        "Data sort", "", onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = {
                val params = buildJsonObject {
                put("header", f0)
                put("keys", buildJsonArray {
                    add(buildJsonObject {
                        if (f1.isNotBlank()) put("column", f1)
                        if (f2.isNotBlank()) put("by", f2)
                        if (f3.isNotBlank()) put("order", if (f3 == "true") "desc" else "asc")
                    })
                })
                }
                onRun("data.sort", params)
                onDismiss()
            }) { Text("OK") }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { f0 = !f0 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f0, { f0 = it })
                Text("My list has headers")
            }
            OutlinedTextField(f1, { f1 = it }, label = { Text("Sort by") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("By", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(f2 == "values", { f2 = "values" }, label = { Text("Cell Values") })
                FilterChip(f2 == "cellColor", { f2 = "cellColor" }, label = { Text("Cell Color") })
                FilterChip(f2 == "fontColor", { f2 = "fontColor" }, label = { Text("Font Color") })
            }
            Text("Desc", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(f3 == "false", { f3 = "false" }, label = { Text("A to Z") })
                FilterChip(f3 == "true", { f3 = "true" }, label = { Text("Z to A") })
            }
            // egui button "+ Add Level"
        }
    }
}
