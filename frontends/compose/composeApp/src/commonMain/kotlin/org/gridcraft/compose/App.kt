package org.gridcraft.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val CellWidth = 96.dp
private val RowHeaderWidth = 48.dp
private val CellHeight = 24.dp

@Composable
fun App(vm: GridViewModel) {
    LaunchedEffect(vm) { vm.refresh() }
    MaterialTheme {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Toolbar(vm)
            FormulaBar(vm)
            Box(Modifier.weight(1f)) { Grid(vm) }
            SheetTabs(vm)
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Toolbar(vm: GridViewModel) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(vm.document?.title.orEmpty(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 16.dp))
        TextButton(onClick = { scope.launch { vm.undo() } }) { Text("Undo") }
        TextButton(onClick = { scope.launch { vm.redo() } }) { Text("Redo") }
    }
}

@Composable
private fun FormulaBar(vm: GridViewModel) {
    val scope = rememberCoroutineScope()
    var draft by remember(vm.selected, vm.formulaText) { mutableStateOf(vm.formulaText) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            a1(vm.selected.row, vm.selected.col),
            modifier = Modifier.width(72.dp).border(1.dp, MaterialTheme.colorScheme.outline).padding(4.dp),
        )
        Text("fx", modifier = Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.Bold)
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .padding(4.dp)
                .onPreviewKeyEvent { e ->
                    when {
                        e.type == KeyEventType.KeyDown && e.key == Key.Enter -> {
                            scope.launch { vm.commit(draft) }
                            true
                        }
                        e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                            draft = vm.formulaText
                            true
                        }
                        else -> false
                    }
                },
        )
    }
}

@Composable
private fun Grid(vm: GridViewModel) {
    val scope = rememberCoroutineScope()
    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
        Row {
            HeaderCell("", RowHeaderWidth)
            for (c in 0 until VIEW_COLS) HeaderCell(columnName(c), CellWidth, vm.selected.col == c)
        }
        LazyColumn(state = listState) {
            items((0 until VIEW_ROWS).toList()) { r ->
                Row {
                    HeaderCell("${r + 1}", RowHeaderWidth, vm.selected.row == r)
                    for (c in 0 until VIEW_COLS) {
                        val sel = vm.selected.row == r && vm.selected.col == c
                        Box(
                            Modifier
                                .width(CellWidth)
                                .height(CellHeight)
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .border(if (sel) 2.dp else 0.5.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                .clickable { scope.launch { vm.select(r, c) } }
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(vm.cellText(r, c), maxLines = 1, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp, active: Boolean = false) {
    Box(
        Modifier
            .width(width)
            .height(CellHeight)
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun SheetTabs(vm: GridViewModel) {
    val scope = rememberCoroutineScope()
    val doc = vm.document ?: return
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), horizontalArrangement = Arrangement.Start) {
        doc.sheets.forEachIndexed { i, s ->
            if (!s.visible) return@forEachIndexed
            TextButton(onClick = { scope.launch { vm.activateSheet(s.name) } }) {
                Text(s.name, fontWeight = if (i == doc.activeSheet) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
