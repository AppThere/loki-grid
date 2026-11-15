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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Format cells format cells: translated from `dialogs/format_cells.rs` (fn `format_cells`, line 5) by egui2compose.
 *
 * The controls and their order are the desktop dialog's. NOT translated: the starting values (the
 * desktop reads them from its own state), anything it draws by hand, and enabling rules. The
 * parameter names are guessed from the Rust fields each control was bound to: check them against
 * the command's params doc before trusting OK.
 *   command: home.formatCells
 *   params:  {range?, style: {font?, fill?, borders?, align?: {reading_order?: Context|LeftToRight|RightToLeft|null, ...}, numFmt?, protection?}} (partial Style JSON merged in; null clears reading_order)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormatCellsFormatCellsDialog(onRun: (command: String, params: JsonObject) -> Unit, onDismiss: () -> Unit) {
    var f0 by remember { mutableStateOf("") }
    var f1 by remember { mutableStateOf("") }
    var f2 by remember { mutableStateOf("") }
    var f3 by remember { mutableStateOf(0.0f) }
    var f4 by remember { mutableStateOf(-90.0f) }
    var f5 by remember { mutableStateOf(false) }
    var f6 by remember { mutableStateOf(false) }
    var f7 by remember { mutableStateOf("") }
    var f8 by remember { mutableStateOf(6.0f) }
    var f9 by remember { mutableStateOf(false) }
    var f10 by remember { mutableStateOf(false) }
    var f11 by remember { mutableStateOf(false) }
    var f12 by remember { mutableStateOf("") }
    var f13 by remember { mutableStateOf("") }
    var f14 by remember { mutableStateOf(false) }
    var f15 by remember { mutableStateOf(false) }
    SheetDialog(
        "Format cells format cells", "", onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = {
                val params = buildJsonObject {
                if (f0.isNotBlank()) put("code", f0)
                if (f1.isNotBlank()) put("h", f1)
                if (f2.isNotBlank()) put("v", f2)
                put("indent", f3)
                put("rot", f4)
                put("wrap", f5)
                put("shrink", f6)
                if (f7.isNotBlank()) put("name", f7)
                put("size", f8)
                put("bold", f9)
                put("italic", f10)
                put("strike", f11)
                if (f12.isNotBlank()) put("underline", f12)
                if (f13.isNotBlank()) put("vert", f13)
                put("locked", f14)
                put("hidden", f15)
                }
                onRun("home.formatCells", params)
                onDismiss()
            }) { Text("OK") }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(f0, { f0 = it }, label = { Text("Type (custom format code)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Horizontal", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            }
            Text("Vertical", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            }
            Text("Indent" + ": " + (kotlin.math.round(f3 * 100f) / 100f), style = MaterialTheme.typography.labelLarge)
            Slider(f3, { f3 = it }, valueRange = minOf(0.0f, 15.0f)..maxOf(0.0f, 15.0f))
            Text("Orientation (degrees)" + ": " + (kotlin.math.round(f4 * 100f) / 100f), style = MaterialTheme.typography.labelLarge)
            Slider(f4, { f4 = it }, valueRange = minOf(-90.0f, 90.0f)..maxOf(-90.0f, 90.0f))
            Row(Modifier.fillMaxWidth().clickable { f5 = !f5 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f5, { f5 = it })
                Text("Wrap text")
            }
            Row(Modifier.fillMaxWidth().clickable { f6 = !f6 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f6, { f6 = it })
                Text("Shrink to fit")
            }
            Text("Font", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            }
            Text("Size" + ": " + (kotlin.math.round(f8 * 100f) / 100f), style = MaterialTheme.typography.labelLarge)
            Slider(f8, { f8 = it }, valueRange = minOf(6.0f, 72.0f)..maxOf(6.0f, 72.0f))
            Row(Modifier.fillMaxWidth().clickable { f9 = !f9 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f9, { f9 = it })
                Text("Bold")
            }
            Row(Modifier.fillMaxWidth().clickable { f10 = !f10 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f10, { f10 = it })
                Text("Italic")
            }
            Row(Modifier.fillMaxWidth().clickable { f11 = !f11 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f11, { f11 = it })
                Text("Strikethrough")
            }
            Text("Reading direction", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            }
            Text("Reading direction", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(f13 == "Baseline", { f13 = "Baseline" }, label = { Text("Normal") })
                FilterChip(f13 == "Superscript", { f13 = "Superscript" }, label = { Text("Superscript") })
                FilterChip(f13 == "Subscript", { f13 = "Subscript" }, label = { Text("Subscript") })
            }
            Row(Modifier.fillMaxWidth().clickable { f14 = !f14 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f14, { f14 = it })
                Text("Locked")
            }
            Row(Modifier.fillMaxWidth().clickable { f15 = !f15 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f15, { f15 = it })
                Text("Hidden")
            }
            // egui button "None"
            // egui button "Outline"
        }
    }
}
