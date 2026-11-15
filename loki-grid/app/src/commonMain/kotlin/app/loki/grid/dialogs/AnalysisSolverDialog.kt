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
 * Analysis solver: translated from `dialogs/analysis.rs` (fn `solver`, line 444) by egui2compose.
 *
 * The controls and their order are the desktop dialog's. NOT translated: the starting values (the
 * desktop reads them from its own state), anything it draws by hand, and enabling rules. The
 * parameter names are guessed from the Rust fields each control was bound to: check them against
 * the command's params doc before trusting OK.
 *   command: data.solverReset
 *   params:  {} → {removed, masked}. Removes the active sheet's Solver model. A model read from workbook-level names stays for the other sheets and is masked on this one by an empty sheet-level solver_opt (masked: true).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisSolverDialog(onRun: (command: String, params: JsonObject) -> Unit, onDismiss: () -> Unit) {
    var f0 by remember { mutableStateOf("") }
    var f1 by remember { mutableStateOf("") }
    var f2 by remember { mutableStateOf(false) }
    var f3 by remember { mutableStateOf("") }
    SheetDialog(
        "Analysis solver", "", onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = {
                val params = buildJsonObject {
                if (f0.isNotBlank()) put("t", f0)
                if (f1.isNotBlank()) put("field1", f1)
                put("nonneg", f2)
                if (f3.isNotBlank()) put("field3", f3)
                }
                onRun("data.solverReset", params)
                onDismiss()
            }) { Text("OK") }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(f0, { f0 = it }, label = { Text("T") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Subject to the Constraints", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            }
            Row(Modifier.fillMaxWidth().clickable { f2 = !f2 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f2, { f2 = it })
                Text("Make Unconstrained Variables Non-Negative")
            }
            Text("Select a Solving Method", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            }
            // egui button "Add"
            // egui button "Reset All" runs data.solverReset
            // egui button "Options"
            // egui button "Replace"
            // egui button "Don't Replace"
            // egui button " Close "
        }
    }
}
