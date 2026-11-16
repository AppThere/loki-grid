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
    val unit = density.density                  // a size the engine gives in pixels is drawn that many dp
    val hdrW = with(density) { 48.dp.toPx() }
    val hdrH = with(density) { 24.dp.toPx() }
    val grab = with(density) { 6.dp.toPx() }
    val slop = LocalViewConfiguration.current.touchSlop
    val longPress = LocalViewConfiguration.current.longPressTimeoutMillis
    val clipboard = LocalClipboardManager.current
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val cellStyle = TextStyle(color = colors.onSurface, fontSize = 13.sp)
    val headStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp)

    var sel by remember { mutableStateOf(Selection.Start) }
    var colFirst by remember { mutableStateOf(0) }
    var colOff by remember { mutableFloatStateOf(0f) }
    var rowFirst by remember { mutableStateOf(0) }
    var rowOff by remember { mutableFloatStateOf(0f) }
    var layout by remember { mutableStateOf(SheetLayout.Empty) }
    var resizing by remember { mutableStateOf<Resize?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var window by remember { mutableStateOf(Window(0, 0, emptyList())) }
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val barFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }

    val viewW = (viewSize.width - hdrW).coerceAtLeast(1f)
    val viewH = (viewSize.height - hdrH).coerceAtLeast(1f)
    val baseCols = AxisMap(layout.firstCol, layout.colSizes.map { it * unit }, layout.defaultWidth * unit, MAX_COL)
    val baseRows = AxisMap(layout.firstRow, layout.rowSizes.map { it * unit }, layout.defaultHeight * unit, MAX_ROW)
    val colMap = resizing?.takeIf { it.isCol }?.let { baseCols.with(it.index, it.size) } ?: baseCols
    val rowMap = resizing?.takeIf { !it.isCol }?.let { baseRows.with(it.index, it.size) } ?: baseRows
    // Enough columns and rows to fill the view even when they are narrow.
    val seenCols = ((viewW / (32f * unit)).toInt() + 2).coerceIn(2, 120)
    val seenRows = ((viewH / (14f * unit)).toInt() + 2).coerceIn(2, 200)

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
        colMap.revealed(colFirst, colOff, c, viewW).let { colFirst = it.first; colOff = it.second }
        rowMap.revealed(rowFirst, rowOff, r, viewH).let { rowFirst = it.first; rowOff = it.second }
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

    // The window of cells in view and how they look, again when the sheet changes or the view moves a cell.
    LaunchedEffect(revision, rowFirst, colFirst, seenRows, seenCols, viewSize) {
        if (viewSize == IntSize.Zero) return@LaunchedEffect
        val range = windowRef(rowFirst, colFirst, seenRows, seenCols)
        val values = read("sheet.read", buildJsonObject { put("range", range); put("formatted", true) })
        val look = read("sheet.layout", buildJsonObject { put("range", range) })
        if (values != null) window = Window(rowFirst, colFirst, parseSheetValues(values))
        if (look != null) layout = parseLayout(look, rowFirst, colFirst)
    }
    // The engine's selection, when something else (a ribbon command, a shortcut) moved it.
    LaunchedEffect(revision) {
        val e = read("document.inspect", JsonObject(emptyMap()))
        val parsed = parseSelection(e.text("selection"), e.text("activeCell"))
        if (parsed != null && parsed != sel) { sel = parsed; reveal(parsed.activeRow, parsed.activeCol) }
        loadInput()
    }

    fun cellAt(p: Offset): Pair<Int, Int> =
        rowMap.at(rowFirst, rowOff, p.y - hdrH) to colMap.at(colFirst, colOff, p.x - hdrW)

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
                .pointerInput(unit, hdrW, hdrH, viewW, viewH) {
                    // The wheel scrolls; Shift turns it sideways.
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type != PointerEventType.Scroll) continue
                            val c = e.changes.firstOrNull() ?: continue
                            val sideways = e.keyboardModifiers.isShiftPressed
                            val dx = (if (sideways) c.scrollDelta.y else c.scrollDelta.x) * 32f * unit
                            val dy = (if (sideways) 0f else c.scrollDelta.y) * 30f * unit
                            colMap.scrolled(colFirst, colOff, dx).let { colFirst = it.first; colOff = it.second }
                            rowMap.scrolled(rowFirst, rowOff, dy).let { rowFirst = it.first; rowOff = it.second }
                            c.consume()
                        }
                    }
                }
                .pointerInput(unit, hdrW, hdrH, viewW, viewH) {
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
                        // A header's border is a handle: drag it to make the column or row wider or taller.
                        val border = if (onCols) {
                            val c = colMap.at(colFirst, colOff, start.x - hdrW)
                            val right = hdrW + colMap.start(colFirst, colOff, c + 1)
                            val left = hdrW + colMap.start(colFirst, colOff, c)
                            if (kotlin.math.abs(start.x - right) <= grab) c else if (kotlin.math.abs(start.x - left) <= grab && c > 0) c - 1 else null
                        } else if (onRows) {
                            val r = rowMap.at(rowFirst, rowOff, start.y - hdrH)
                            val bottom = hdrH + rowMap.start(rowFirst, rowOff, r + 1)
                            val top = hdrH + rowMap.start(rowFirst, rowOff, r)
                            if (kotlin.math.abs(start.y - bottom) <= grab) r else if (kotlin.math.abs(start.y - top) <= grab && r > 0) r - 1 else null
                        } else null
                        if (border != null) {
                            val before = if (onCols) colMap.size(border) else rowMap.size(border)
                            down.consume()
                            var now = before
                            while (true) {
                                val event = awaitPointerEvent()
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                now = (before + (if (onCols) ch.position.x - start.x else ch.position.y - start.y)).coerceAtLeast(8f * unit)
                                resizing = Resize(onCols, border, now)
                                if (!ch.pressed) break
                                ch.consume()
                            }
                            val units = kotlin.math.round(now / unit).toInt()
                            go {
                                if (onCols) send("home.columnWidth", buildJsonObject { put("cols", "${colName(border)}:${colName(border)}"); put("width", units) })
                                else send("home.rowHeight", buildJsonObject { put("rows", "${border + 1}:${border + 1}"); put("height", units) })
                                onChanged()
                                resizing = null
                            }
                            return@awaitEachGesture
                        }
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
                            val row = if (onCols) rowFirst else if (mods.isShiftPressed) sel.activeRow else anchor.first
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
                                    colMap.scrolled(colFirst, colOff, -d.x).let { colFirst = it.first; colOff = it.second }
                                    rowMap.scrolled(rowFirst, rowOff, -d.y).let { rowFirst = it.first; rowOff = it.second }
                                }
                            }
                            last = ch.position
                            ch.consume()
                        }
                    }
                },
        ) {
            val w = size.width; val h = size.height
            // The left edge of each column in view and the top of each row, so the loops below add nothing up twice.
            val cx = FloatArray(seenCols + 1).also { a -> a[0] = hdrW - colOff; for (i in 1..seenCols) a[i] = a[i - 1] + colMap.size(colFirst + i - 1) }
            val ry = FloatArray(seenRows + 1).also { a -> a[0] = hdrH - rowOff; for (i in 1..seenRows) a[i] = a[i - 1] + rowMap.size(rowFirst + i - 1) }
            fun xOf(c: Int) = (hdrW + colMap.start(colFirst, colOff, c)).coerceIn(-4096f, w + 4096f)
            fun yOf(r: Int) = (hdrH + rowMap.start(rowFirst, rowOff, r)).coerceIn(-4096f, h + 4096f)
            fun text(s: String, x: Float, y: Float, width: Float, height: Float, style: TextStyle, align: String) {
                if (s.isEmpty() || width < 4f) return
                val layoutText = measurer.measure(s, style, maxLines = 1, overflow = TextOverflow.Clip, constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1)))
                val ty = y + (height - layoutText.size.height) / 2
                val tx = when (align) { "right" -> x + width - layoutText.size.width; "center" -> x + (width - layoutText.size.width) / 2; else -> x }
                drawText(layoutText, topLeft = Offset(tx, ty))
            }
            fun styleFor(look: CellLook?): TextStyle {
                if (look == null) return cellStyle
                val deco = listOfNotNull(if (look.underline) androidx.compose.ui.text.style.TextDecoration.Underline else null, if (look.strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                return cellStyle.copy(
                    color = hexColor(look.color) ?: cellStyle.color,
                    fontWeight = if (look.bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    fontStyle = if (look.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    textDecoration = if (deco.isEmpty()) null else androidx.compose.ui.text.style.TextDecoration.combine(deco),
                )
            }
            val inMerge = HashSet<Long>()
            for (m in layout.merges) for (r in m.r0..minOf(m.r1, m.r0 + 200)) for (c in m.c0..minOf(m.c1, m.c0 + 200)) if (r != m.r0 || c != m.c0) inMerge += SheetLayout.key(r, c)
            clipRect(left = hdrW, top = hdrH) {
                for (r in 0 until seenRows) for (c in 0 until seenCols) {
                    val row = rowFirst + r; val col = colFirst + c
                    if (row > MAX_ROW || col > MAX_COL) continue
                    val x = cx[c]; val y = ry[r]; val cw = cx[c + 1] - x; val ch = ry[r + 1] - y
                    if (cw <= 0f || ch <= 0f) continue
                    val look = layout.look(row, col)
                    hexColor(look?.fill)?.let { drawRect(it, Offset(x, y), Size(cw, ch)) }
                    if (sel.covers(row, col)) drawRect(colors.primary.copy(alpha = 0.12f), Offset(x, y), Size(cw, ch))
                    drawLine(colors.outlineVariant.copy(alpha = 0.6f), Offset(x, y + ch), Offset(x + cw, y + ch), 1f)
                    drawLine(colors.outlineVariant.copy(alpha = 0.6f), Offset(x + cw, y), Offset(x + cw, y + ch), 1f)
                    if (SheetLayout.key(row, col) in inMerge) continue
                    val s = window.at(row, col)
                    if (s.isNotEmpty()) text(s, x + 6f, y, cw - 12f, ch, styleFor(look), look?.align ?: if (s.toDoubleOrNull() != null) "right" else "left")
                }
                // A merged block is one cell: its corner's fill and text over the grid.
                for (m in layout.merges) {
                    val x0 = xOf(m.c0); val x1 = xOf(m.c1 + 1); val y0 = yOf(m.r0); val y1 = yOf(m.r1 + 1)
                    if (x1 < hdrW || y1 < hdrH || x0 > w || y0 > h) continue
                    val look = layout.look(m.r0, m.c0)
                    drawRect(hexColor(look?.fill) ?: colors.surface, Offset(x0, y0), Size(x1 - x0, y1 - y0))
                    if (sel.covers(m.r0, m.c0)) drawRect(colors.primary.copy(alpha = 0.12f), Offset(x0, y0), Size(x1 - x0, y1 - y0))
                    drawRect(colors.outlineVariant.copy(alpha = 0.6f), Offset(x0, y0), Size(x1 - x0, y1 - y0), style = Stroke(1f))
                    val s = window.at(m.r0, m.c0)
                    text(s, x0 + 6f, y0, x1 - x0 - 12f, y1 - y0, styleFor(look), look?.align ?: if (s.toDoubleOrNull() != null) "right" else "center")
                }
                // The selection's border, and the active cell's.
                for (r in sel.rects) {
                    val x0 = xOf(r.c0); val y0 = yOf(r.r0); val x1 = xOf(r.c1 + 1); val y1 = yOf(r.r1 + 1)
                    drawRect(colors.primary, Offset(x0, y0), Size(x1 - x0, y1 - y0), style = Stroke(2f))
                }
                val merge = layout.merges.firstOrNull { it.contains(sel.activeRow, sel.activeCol) }
                val ax0 = xOf(merge?.c0 ?: sel.activeCol); val ax1 = xOf((merge?.c1 ?: sel.activeCol) + 1)
                val ay0 = yOf(merge?.r0 ?: sel.activeRow); val ay1 = yOf((merge?.r1 ?: sel.activeRow) + 1)
                drawRect(colors.primary, Offset(ax0, ay0), Size(ax1 - ax0, ay1 - ay0), style = Stroke(3f))
            }
            // Headers last, over the cells; those the selection touches are tinted.
            drawRect(colors.surfaceVariant, Offset.Zero, Size(w, hdrH))
            drawRect(colors.surfaceVariant, Offset.Zero, Size(hdrW, h))
            clipRect(left = hdrW, top = 0f, right = w, bottom = hdrH) {
                for (c in 0 until seenCols) {
                    val col = colFirst + c
                    if (col > MAX_COL) break
                    val x = cx[c]; val cw = cx[c + 1] - x
                    if (cw <= 0f) continue
                    if (sel.rects.any { col in it.c0..it.c1 }) drawRect(colors.primary.copy(alpha = 0.18f), Offset(x, 0f), Size(cw, hdrH))
                    drawLine(colors.outlineVariant, Offset(x + cw, 0f), Offset(x + cw, hdrH), 1f)
                    val m = measurer.measure(colName(col), headStyle, maxLines = 1)
                    drawText(m, topLeft = Offset(x + (cw - m.size.width) / 2, (hdrH - m.size.height) / 2))
                }
            }
            clipRect(left = 0f, top = hdrH, right = hdrW, bottom = h) {
                for (r in 0 until seenRows) {
                    val row = rowFirst + r
                    if (row > MAX_ROW) break
                    val y = ry[r]; val rh = ry[r + 1] - y
                    if (rh <= 0f) continue
                    if (sel.rects.any { row in it.r0..it.r1 }) drawRect(colors.primary.copy(alpha = 0.18f), Offset(0f, y), Size(hdrW, rh))
                    drawLine(colors.outlineVariant, Offset(0f, y + rh), Offset(hdrW, y + rh), 1f)
                    val m = measurer.measure("${row + 1}", headStyle, maxLines = 1)
                    drawText(m, topLeft = Offset((hdrW - m.size.width) / 2, y + (rh - m.size.height) / 2))
                }
            }
            drawLine(colors.outline, Offset(hdrW, 0f), Offset(hdrW, h), 1f)
            drawLine(colors.outline, Offset(0f, hdrH), Offset(w, hdrH), 1f)
            // While a header's border is dragged, the line where it will be.
            resizing?.let { rz ->
                if (rz.isCol) { val x = xOf(rz.index + 1); drawLine(colors.primary, Offset(x, 0f), Offset(x, h), 2f) }
                else { val y = yOf(rz.index + 1); drawLine(colors.primary, Offset(0f, y), Offset(w, y), 2f) }
            }
        }
    }
}

/** A column or row whose border is being dragged: which, and how big it is now (pixels). */
private class Resize(val isCol: Boolean, val index: Int, val size: Float)

/** `#RRGGBB` as a colour; null for anything else. */
internal fun hexColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    if (h.length != 6) return null
    val v = h.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or v)
}

/** Typing a character starts an edit that replaces the cell; a shortcut or a control key is left alone. */
private fun typed(e: KeyEvent, mod: Boolean, begin: (String) -> Unit): Boolean {
    val ch = e.utf16CodePoint
    if (mod || e.isAltPressed || ch < 32 || ch == 127 || ch > 0xFFFF) return false
    begin(ch.toChar().toString())
    return true
}
