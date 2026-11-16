package app.loki.grid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loki.kit.EngineClient
import app.loki.kit.EngineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max

/** The values of a block of the sheet, with the cell its top-left corner is. */
private class Window(val firstRow: Int, val firstCol: Int, val values: List<List<String>>) {
    fun at(r: Int, c: Int): String = values.getOrNull(r - firstRow)?.getOrNull(c - firstCol).orEmpty()
}

private fun JsonElement?.text(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

/**
 * The sheet, drawn on one canvas: only the cells in view, anywhere in the 16,384 × 1,048,576 grid.
 * A mouse or pen selects by click and drag (Shift extends, Ctrl adds a block); a finger pans, taps
 * to select and selects by a long press then a drag. The wheel scrolls (Shift for sideways); the
 * headers select whole columns and rows. Arrows, Page Up/Down, Enter and Tab move, Delete clears,
 * Ctrl+C/X/V use the system clipboard, and typing or F2 edits in the bar. The engine owns the
 * selection (`selection.set`, `selection.move`), so the ribbon and keyboard shortcuts agree with it.
 */
@Composable
fun SheetGrid(client: EngineClient, revision: Int, onChanged: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    val density = LocalDensity.current
    val colW = with(density) { 96.dp.toPx() }
    val rowH = with(density) { 26.dp.toPx() }
    val hdrW = with(density) { 48.dp.toPx() }
    val hdrH = rowH
    val slop = LocalViewConfiguration.current.touchSlop
    val longPress = LocalViewConfiguration.current.longPressTimeoutMillis
    val clipboard = LocalClipboardManager.current
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val cellStyle = TextStyle(color = colors.onSurface, fontSize = 13.sp)
    val headStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp)

    var sel by remember { mutableStateOf(Selection.Start) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var window by remember { mutableStateOf(Window(0, 0, emptyList())) }
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val barFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }

    val viewW = (viewSize.width - hdrW).coerceAtLeast(1f)
    val viewH = (viewSize.height - hdrH).coerceAtLeast(1f)
    fun clampX(x: Float) = x.coerceIn(0f, ((MAX_COL + 1) * colW - viewW).coerceAtLeast(0f))
    fun clampY(y: Float) = y.coerceIn(0f, ((MAX_ROW + 1) * rowH - viewH).coerceAtLeast(0f))
    val firstCol = (scrollX / colW).toInt()
    val firstRow = (scrollY / rowH).toInt()
    val seenCols = (viewW / colW).toInt() + 2
    val seenRows = (viewH / rowH).toInt() + 2

    /** One engine command, one at a time, off the UI thread; a refusal becomes the note under the bar. */
    suspend fun send(command: String, params: JsonObject = JsonObject(emptyMap())): JsonElement? = gate.withLock {
        withContext(Dispatchers.Default) {
            try { client.execute(command, params).also { note = null } } catch (e: EngineException) { note = e.message; null }
        }
    }
    suspend fun read(method: String, params: JsonObject): JsonElement? = gate.withLock {
        withContext(Dispatchers.Default) { runCatching { client.call(method, params) }.getOrNull() }
    }
    fun reveal(r: Int, c: Int) {
        val x0 = c * colW; val x1 = x0 + colW
        if (x0 < scrollX) scrollX = clampX(x0) else if (x1 > scrollX + viewW) scrollX = clampX(x1 - viewW)
        val y0 = r * rowH; val y1 = y0 + rowH
        if (y0 < scrollY) scrollY = clampY(y0) else if (y1 > scrollY + viewH) scrollY = clampY(y1 - viewH)
    }
    /** The bar shows the formula, not the value, of the active cell. */
    suspend fun loadInput() {
        val ref = CellRect(sel.activeRow, sel.activeCol, sel.activeRow, sel.activeCol).ref()
        val e = read("cell.get", buildJsonObject { put("cell", ref) })
        input = e.text("input") ?: e.text("formula").orEmpty()
    }
    fun adopt(e: JsonElement?) {
        parseSelection(e.text("selection"), e.text("active"))?.let { sel = it; reveal(it.activeRow, it.activeCol) }
    }
    fun go(block: suspend () -> Unit) { scope.launch { block() } }

    fun select(rects: List<CellRect>, row: Int, col: Int, last: Boolean = true) {
        sel = Selection(row, col, rects)
        val active = CellRect(row, col, row, col).ref()
        go {
            send("selection.set", buildJsonObject { put("range", rects.joinToString(",") { it.ref() }); put("active", active) })
            if (last) { loadInput(); onChanged() }
        }
    }
    fun move(dr: Int, dc: Int, extend: Boolean = false, jump: Boolean = false, page: Boolean = false) = go {
        val e = send("selection.move", buildJsonObject { put("dr", dr); put("dc", dc); put("extend", extend); put("jump", jump); put("page", page) })
        adopt(e); loadInput(); onChanged()
    }
    fun next(forward: Boolean, byRow: Boolean) = go {
        val single = sel.rects.size == 1 && sel.main.single
        val e = if (single) {
            send("selection.move", buildJsonObject { put("dr", if (byRow) 0 else if (forward) 1 else -1); put("dc", if (!byRow) 0 else if (forward) 1 else -1) })
        } else send("selection.next", buildJsonObject { put("forward", forward); put("byRow", byRow) })
        adopt(e); loadInput(); onChanged()
    }
    fun beginEdit(text: String) { input = text; barFocus.requestFocus() }
    fun commit(thenMove: (Boolean) -> Unit, forward: Boolean = true) = go {
        val ref = CellRect(sel.activeRow, sel.activeCol, sel.activeRow, sel.activeCol).ref()
        val done = send("cell.set", buildJsonObject { put("cell", ref); put("input", input) })
        if (done != null) { thenMove(forward); gridFocus.requestFocus() }
        onChanged()
    }

    // The window of cells in view, again when the sheet changes or the view moves a cell.
    LaunchedEffect(revision, firstRow, firstCol, seenRows, seenCols, viewSize) {
        if (viewSize == IntSize.Zero) return@LaunchedEffect
        val e = read("sheet.read", buildJsonObject { put("range", windowRef(firstRow, firstCol, seenRows, seenCols)); put("formatted", true) })
        if (e != null) window = Window(firstRow, firstCol, parseSheetValues(e))
    }
    // The engine's selection, when something else (a ribbon command, a shortcut) moved it.
    LaunchedEffect(revision) {
        val e = read("document.inspect", JsonObject(emptyMap()))
        val parsed = parseSelection(e.text("selection"), e.text("activeCell"))
        if (parsed != null && parsed != sel) { sel = parsed; reveal(parsed.activeRow, parsed.activeCol) }
        loadInput()
    }

    fun cellAt(p: Offset): Pair<Int, Int> =
        ((p.y - hdrH + scrollY) / rowH).toInt().coerceIn(0, MAX_ROW) to ((p.x - hdrW + scrollX) / colW).toInt().coerceIn(0, MAX_COL)

    fun copyOrCut(cut: Boolean) = go {
        val e = send(if (cut) "edit.cut" else "edit.copy")
        e.text("text")?.let { clipboard.setText(AnnotatedString(it)) }
        if (cut) { loadInput(); onChanged() }
    }
    fun paste() = go {
        val text = clipboard.getText()?.text ?: return@go
        send("edit.paste", buildJsonObject { put("text", text) })
        loadInput(); onChanged()
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val shift = e.isShiftPressed
        val mod = e.isCtrlPressed || e.isMetaPressed
        when (e.key) {
            Key.DirectionDown -> move(1, 0, shift, mod)
            Key.DirectionUp -> move(-1, 0, shift, mod)
            Key.DirectionRight -> move(0, 1, shift, mod)
            Key.DirectionLeft -> move(0, -1, shift, mod)
            Key.PageDown -> move(1, 0, shift, page = true)
            Key.PageUp -> move(-1, 0, shift, page = true)
            Key.Enter, Key.NumPadEnter -> next(!shift, byRow = false)
            Key.Tab -> next(!shift, byRow = true)
            Key.Delete, Key.Backspace -> go { send("edit.clearContents"); loadInput(); onChanged() }
            Key.F2 -> beginEdit(input)
            Key.C -> if (mod) copyOrCut(false) else return typed(e, mod, ::beginEdit)
            Key.X -> if (mod) copyOrCut(true) else return typed(e, mod, ::beginEdit)
            Key.V -> if (mod) paste() else return typed(e, mod, ::beginEdit)
            else -> return typed(e, mod, ::beginEdit)
        }
        return true
    }

    Column(modifier.background(colors.surface)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(CellRect(sel.activeRow, sel.activeCol, sel.activeRow, sel.activeCol).ref(), Modifier.width(88.dp), maxLines = 1, style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                input, { input = it }, singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(barFocus).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.Escape -> { go { loadInput() }; gridFocus.requestFocus(); true }
                        e.key == Key.Tab -> { commit({ f -> next(f, byRow = true) }, !e.isShiftPressed); true }
                        else -> false
                    }
                },
                placeholder = { Text("Value or =formula") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit({ f -> next(f, byRow = false) }) }),
                supportingText = note?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            )
        }
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { viewSize = it }
                .focusRequester(gridFocus).focusable().onPreviewKeyEvent { onKey(it) }
                .pointerInput(colW, rowH, hdrW, hdrH, viewW, viewH) {
                    // The wheel scrolls; Shift turns it sideways.
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type != PointerEventType.Scroll) continue
                            val c = e.changes.firstOrNull() ?: continue
                            val sideways = e.keyboardModifiers.isShiftPressed
                            val dx = (if (sideways) c.scrollDelta.y else c.scrollDelta.x) * colW / 2
                            val dy = (if (sideways) 0f else c.scrollDelta.y) * rowH * 1.5f
                            scrollX = clampX(scrollX + dx); scrollY = clampY(scrollY + dy)
                            c.consume()
                        }
                    }
                }
                .pointerInput(colW, rowH, hdrW, hdrH, viewW, viewH) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        gridFocus.requestFocus()
                        val touch = down.type == PointerType.Touch
                        val start = down.position
                        val mods = currentEvent.keyboardModifiers
                        val inCorner = start.x < hdrW && start.y < hdrH
                        val onCols = start.y < hdrH && start.x >= hdrW
                        val onRows = start.x < hdrW && start.y >= hdrH
                        if (inCorner) { down.consume(); go { send("edit.selectAll"); val i = read("document.inspect", JsonObject(emptyMap())); parseSelection(i.text("selection"), i.text("activeCell"))?.let { sel = it }; onChanged() }; return@awaitEachGesture }
                        val anchor = cellAt(start)
                        var current = anchor
                        val extendFrom = if (mods.isShiftPressed) sel.activeRow to sel.activeCol else anchor
                        val others = if (mods.isCtrlPressed || mods.isMetaPressed) sel.rects else emptyList()
                        fun rectFor(to: Pair<Int, Int>): CellRect {
                            val r = CellRect.between(extendFrom.first, extendFrom.second, to.first, to.second)
                            return when {
                                onCols -> CellRect(0, r.c0, MAX_ROW, r.c1)
                                onRows -> CellRect(r.r0, 0, r.r1, MAX_COL)
                                else -> r
                            }
                        }
                        fun apply(to: Pair<Int, Int>, last: Boolean) {
                            val rect = rectFor(to)
                            val row = if (onCols) (scrollY / rowH).toInt() else if (mods.isShiftPressed) sel.activeRow else anchor.first
                            val col = if (onRows) 0 else if (mods.isShiftPressed) sel.activeCol else anchor.second
                            select(others + rect, row, col, last)
                        }
                        var selecting = !touch
                        var panned = false
                        var last = start
                        if (selecting) { apply(anchor, false); down.consume() }
                        while (true) {
                            val event = if (touch && !selecting && !panned) withTimeoutOrNull(longPress) { awaitPointerEvent() } else awaitPointerEvent()
                            if (event == null) { selecting = true; apply(anchor, false); continue }   // a long press
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                if (selecting) apply(current, true) else if (!panned) apply(anchor, true)
                                break
                            }
                            if (selecting) {
                                val cell = cellAt(ch.position)
                                if (cell != current) { current = cell; apply(cell, false) }
                            } else if (touch) {
                                if (panned || (ch.position - start).getDistance() > slop) {
                                    panned = true
                                    val d = ch.position - last
                                    scrollX = clampX(scrollX - d.x); scrollY = clampY(scrollY - d.y)
                                }
                            }
                            last = ch.position
                            ch.consume()
                        }
                    }
                },
        ) {
            val w = size.width; val h = size.height
            fun text(s: String, x: Float, y: Float, width: Float, style: TextStyle, right: Boolean = false) {
                if (s.isEmpty() || width < 4f) return
                val layout = measurer.measure(s, style, maxLines = 1, overflow = TextOverflow.Clip, constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1)))
                val ty = y + (rowH - layout.size.height) / 2
                val tx = if (right) x + width - layout.size.width else x
                drawText(layout, topLeft = Offset(tx, ty))
            }
            val offX = scrollX - firstCol * colW
            val offY = scrollY - firstRow * rowH
            clipRect(left = hdrW, top = hdrH) {
                for (r in 0..seenRows) for (c in 0..seenCols) {
                    val x = hdrW + c * colW - offX; val y = hdrH + r * rowH - offY
                    val row = firstRow + r; val col = firstCol + c
                    if (row > MAX_ROW || col > MAX_COL) continue
                    if (sel.covers(row, col)) drawRect(colors.primary.copy(alpha = 0.12f), Offset(x, y), Size(colW, rowH))
                    drawLine(colors.outlineVariant.copy(alpha = 0.6f), Offset(x, y + rowH), Offset(x + colW, y + rowH), 1f)
                    drawLine(colors.outlineVariant.copy(alpha = 0.6f), Offset(x + colW, y), Offset(x + colW, y + rowH), 1f)
                    val s = window.at(row, col)
                    if (s.isNotEmpty()) text(s, x + 6f, y, colW - 12f, cellStyle, right = s.toDoubleOrNull() != null)
                }
                // The selection's border, and the active cell's.
                for (r in sel.rects) {
                    val x0 = hdrW + r.c0 * colW - scrollX; val y0 = hdrH + r.r0 * rowH - scrollY
                    val x1 = hdrW + (r.c1 + 1) * colW - scrollX; val y1 = hdrH + (r.r1 + 1) * rowH - scrollY
                    drawRect(colors.primary, Offset(x0, y0), Size(x1 - x0, y1 - y0), style = Stroke(2f))
                }
                val ax = hdrW + sel.activeCol * colW - scrollX; val ay = hdrH + sel.activeRow * rowH - scrollY
                drawRect(colors.primary, Offset(ax, ay), Size(colW, rowH), style = Stroke(3f))
            }
            // Headers last, over the cells; those the selection touches are tinted.
            drawRect(colors.surfaceVariant, Offset.Zero, Size(w, hdrH))
            drawRect(colors.surfaceVariant, Offset.Zero, Size(hdrW, h))
            clipRect(left = hdrW, top = 0f, right = w, bottom = hdrH) {
                for (c in 0..seenCols) {
                    val col = firstCol + c
                    if (col > MAX_COL) break
                    val x = hdrW + c * colW - offX
                    if (sel.rects.any { col in it.c0..it.c1 }) drawRect(colors.primary.copy(alpha = 0.18f), Offset(x, 0f), Size(colW, hdrH))
                    drawLine(colors.outlineVariant, Offset(x + colW, 0f), Offset(x + colW, hdrH), 1f)
                    val name = colName(col)
                    val m = measurer.measure(name, headStyle, maxLines = 1)
                    drawText(m, topLeft = Offset(x + (colW - m.size.width) / 2, (hdrH - m.size.height) / 2))
                }
            }
            clipRect(left = 0f, top = hdrH, right = hdrW, bottom = h) {
                for (r in 0..seenRows) {
                    val row = firstRow + r
                    if (row > MAX_ROW) break
                    val y = hdrH + r * rowH - offY
                    if (sel.rects.any { row in it.r0..it.r1 }) drawRect(colors.primary.copy(alpha = 0.18f), Offset(0f, y), Size(hdrW, rowH))
                    drawLine(colors.outlineVariant, Offset(0f, y + rowH), Offset(hdrW, y + rowH), 1f)
                    val m = measurer.measure("${row + 1}", headStyle, maxLines = 1)
                    drawText(m, topLeft = Offset((hdrW - m.size.width) / 2, y + (rowH - m.size.height) / 2))
                }
            }
            drawLine(colors.outline, Offset(hdrW, 0f), Offset(hdrW, h), 1f)
            drawLine(colors.outline, Offset(0f, hdrH), Offset(w, hdrH), 1f)
        }
    }
}

/** Typing a character starts an edit that replaces the cell; a shortcut or a control key is left alone. */
private fun typed(e: KeyEvent, mod: Boolean, begin: (String) -> Unit): Boolean {
    val ch = e.utf16CodePoint
    if (mod || e.isAltPressed || ch < 32 || ch == 127 || ch > 0xFFFF) return false
    begin(ch.toChar().toString())
    return true
}
