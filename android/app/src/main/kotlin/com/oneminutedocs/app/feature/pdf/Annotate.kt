package com.oneminutedocs.app.feature.pdf

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.InputsViewModel
import com.oneminutedocs.app.common.Mime
import com.oneminutedocs.app.common.OpState
import com.oneminutedocs.app.common.OperationStatus
import com.oneminutedocs.app.common.OutputSaver
import com.oneminutedocs.app.common.PrimaryAction
import com.oneminutedocs.app.common.ToolScaffold
import com.oneminutedocs.app.common.errorMessage
import com.oneminutedocs.app.common.progress
import com.oneminutedocs.app.common.rememberPickers
import com.oneminutedocs.app.common.toDocError
import com.oneminutedocs.app.navigation.Routes
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.core.designsystem.ErrorBlock
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.domain.Availability
import com.oneminutedocs.domain.DocError
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.FileNames
import com.oneminutedocs.domain.Outcome
import com.oneminutedocs.processing.image.BitmapLoader
import com.oneminutedocs.processing.pdf.Overlay
import com.oneminutedocs.processing.pdf.OverlayExporter
import com.oneminutedocs.processing.pdf.OverlayPainter
import com.oneminutedocs.processing.pdf.PdfPageRenderer
import com.oneminutedocs.processing.pdf.ShapeKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class AnnotTool { INK, HIGHLIGHT, UNDERLINE, RECTANGLE, ELLIPSE, LINE, TEXT, MOVE }

@HiltViewModel
class AnnotateViewModel @Inject constructor(
    savedState: SavedStateHandle,
    workspaces: WorkspaceManager,
    repository: DocumentRepository,
    private val exporter: OverlayExporter,
    private val saver: OutputSaver,
) : InputsViewModel(workspaces, repository) {
    override val allowed = setOf(DocumentFormat.PDF)
    val signatureMode = savedState.toRoute<Routes.Annotate>().signatureMode
    val pageCount = MutableStateFlow(0)
    val pageIndex = MutableStateFlow(0)
    val pageBitmap = MutableStateFlow<Bitmap?>(null)
    val overlays = MutableStateFlow<List<Overlay>>(emptyList())
    val selectedPicture = MutableStateFlow<Int?>(null)
    val loadError = MutableStateFlow<DocError?>(null)
    private val history = ArrayDeque<List<Overlay>>()
    private var renderer: PdfPageRenderer? = null
    private val pictureCache = HashMap<File, Bitmap?>()

    init {
        preload(savedState.toRoute<Routes.Annotate>().documentId)
    }

    override fun onInputsChanged() {
        viewModelScope.launch {
            renderer?.close(); renderer = null
            overlays.value = emptyList(); history.clear()
            val input = inputs.value.firstOrNull() ?: return@launch
            try {
                val r = PdfPageRenderer.open(input.file)
                renderer = r
                pageCount.value = r.pageCount
                showPage(0)
                loadError.value = null
            } catch (t: Throwable) {
                loadError.value = t.toDocError()
            }
        }
    }

    fun showPage(index: Int) {
        val r = renderer ?: return
        if (index !in 0 until r.pageCount) return
        pageIndex.value = index
        selectedPicture.value = null
        viewModelScope.launch { pageBitmap.value = runCatching { r.render(index, 1400) }.getOrNull() }
    }

    fun add(o: Overlay) {
        history.addLast(overlays.value)
        overlays.value = overlays.value + o
        if (o is Overlay.Picture) selectedPicture.value = overlays.value.lastIndex
    }

    fun replace(index: Int, o: Overlay, recordHistory: Boolean) {
        if (recordHistory) history.addLast(overlays.value)
        overlays.value = overlays.value.toMutableList().also { it[index] = o }
    }

    fun undo() { history.removeLastOrNull()?.let { overlays.value = it; selectedPicture.value = null } }

    fun picture(file: File): Bitmap? = pictureCache.getOrPut(file) { runCatching { BitmapLoader.decode(file, 1200) }.getOrNull() }

    /** Adds an image (or signature PNG) centred on the current page, keeping its aspect ratio. */
    fun addPicture(file: File, pageAspect: Float) {
        val bmp = picture(file) ?: return
        val w = 0.35f
        val h = (w * bmp.height / bmp.width.toFloat() * pageAspect).coerceAtMost(0.6f)
        add(Overlay.Picture(pageIndex.value, file, 0.5f - w / 2, 0.5f - h / 2, 0.5f + w / 2, 0.5f + h / 2))
    }

    fun importImage(uri: Uri, pageAspect: Float) {
        viewModelScope.launch {
            when (val r = repository.importToWorkspace(uri, workspace, setOf(DocumentFormat.JPEG, DocumentFormat.PNG, DocumentFormat.WEBP, DocumentFormat.HEIF))) {
                is Outcome.Success -> addPicture(r.value.file, pageAspect)
                is Outcome.Failure -> loadError.value = r.error
            }
        }
    }

    fun saveSignature(bitmap: Bitmap, pageAspect: Float) {
        viewModelScope.launch {
            val file = workspace.newFile("png")
            withContext(Dispatchers.IO) { BitmapLoader.savePng(bitmap, file) }
            addPicture(file, pageAspect)
        }
    }

    fun export() = runOperation(R.string.op_applying_annotations) {
        val input = inputs.value.single()
        val out = workspace.newFile("pdf")
        exporter.apply(input.file, overlays.value, out) { progress(it) }
        val doc = saver.save(out, FileNames.derived(input.displayName, if (signatureMode) "signed" else "annotated", DocumentFormat.PDF), DocumentFormat.PDF, if (signatureMode) "sign" else "annotate")
        OpState.Done(listOf(doc.id))
    }

    override fun onCleared() {
        renderer?.close()
        super.onCleared()
    }
}

private val palette = listOf(Color(0xFF1B3A6B), Color(0xFFD32F2F), Color(0xFF2E7D32), Color(0xFF000000), Color(0xFFFFEB3B), Color(0xFF7B1FA2))

@Composable
fun AnnotateScreen(onBack: () -> Unit, onDone: (OpState.Done) -> Unit, vm: AnnotateViewModel = hiltViewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val page by vm.pageBitmap.collectAsStateWithLifecycle()
    val index by vm.pageIndex.collectAsStateWithLifecycle()
    val count by vm.pageCount.collectAsStateWithLifecycle()
    val overlays by vm.overlays.collectAsStateWithLifecycle()
    val selected by vm.selectedPicture.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    var tool by remember { mutableStateOf(if (vm.signatureMode) AnnotTool.MOVE else AnnotTool.INK) }
    var color by remember { mutableStateOf(palette[0]) }
    var width by remember { mutableStateOf(0.004f) }
    var textAt by remember { mutableStateOf<Offset?>(null) }
    var showSignaturePad by remember { mutableStateOf(vm.signatureMode) }
    val pickers = rememberPickers(vm::addUris)
    val pageAspect = page?.let { it.width.toFloat() / it.height } ?: 0.707f
    val imagePicker = rememberPickers { uris -> uris.firstOrNull()?.let { vm.importImage(it, pageAspect) } }

    ToolScaffold(
        stringResource(if (vm.signatureMode) R.string.tool_sign else R.string.tool_annotate), onBack, Availability.OFFLINE,
        actions = { IconButton(onClick = vm::undo) { Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.action_undo)) } },
    ) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult, onDone)) return@ToolScaffold
        if (inputs.isEmpty()) {
            OutlinedButton(onClick = { pickers.pickOne(Mime.PDF) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_choose_pdf)) }
            return@ToolScaffold
        }
        loadError?.let { ErrorBlock(stringResource(R.string.error_title), stringResource(errorMessage(it.code))) }
        InfoBanner(stringResource(if (vm.signatureMode) R.string.sign_disclaimer else R.string.annotate_disclaimer))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { vm.showPage(index - 1) }, enabled = index > 0) { Icon(Icons.Outlined.ChevronLeft, stringResource(R.string.action_previous_page)) }
            Text(stringResource(R.string.viewer_page_of, index + 1, count))
            IconButton(onClick = { vm.showPage(index + 1) }, enabled = index < count - 1) { Icon(Icons.Outlined.ChevronRight, stringResource(R.string.action_next_page)) }
        }
        val pageOverlays = overlays.withIndex().filter { it.value.page == index }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val stroke = remember { mutableStateListOf<Offset>() }
        var dragStart by remember { mutableStateOf<Offset?>(null) }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(pageAspect)
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                .onSizeChanged { size = it }
                .pointerInput(tool, index, color, width) {
                    detectTapGestures { pos ->
                        if (tool == AnnotTool.TEXT && size.width > 0) textAt = Offset(pos.x / size.width, pos.y / size.height)
                        if (tool == AnnotTool.MOVE) {
                            val nx = pos.x / size.width; val ny = pos.y / size.height
                            val hit = pageOverlays.lastOrNull { (_, o) -> o is Overlay.Picture && nx in o.left..o.right && ny in o.top..o.bottom }
                            vm.selectedPicture.value = hit?.index
                        }
                    }
                }
                .pointerInput(tool, index, color, width, selected) {
                    detectDragGestures(
                        onDragStart = { pos -> stroke.clear(); stroke.add(pos); dragStart = pos },
                        onDragEnd = {
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            val start = dragStart
                            val end = stroke.lastOrNull()
                            if (start != null && end != null && w > 0 && h > 0) {
                                val l = minOf(start.x, end.x) / w; val r = maxOf(start.x, end.x) / w
                                val t = minOf(start.y, end.y) / h; val b = maxOf(start.y, end.y) / h
                                val argb = color.toArgb()
                                when (tool) {
                                    AnnotTool.INK -> if (stroke.size > 1) vm.add(Overlay.Ink(index, stroke.map { it.x / w to it.y / h }, argb, width))
                                    AnnotTool.HIGHLIGHT -> vm.add(Overlay.Highlight(index, l, t, r, b, if (color == palette[0]) Color(0xFFFFEB3B).toArgb() else argb))
                                    AnnotTool.UNDERLINE -> vm.add(Overlay.Shape(index, ShapeKind.UNDERLINE, l, t, r, b, argb, width))
                                    AnnotTool.RECTANGLE -> vm.add(Overlay.Shape(index, ShapeKind.RECTANGLE, l, t, r, b, argb, width))
                                    AnnotTool.ELLIPSE -> vm.add(Overlay.Shape(index, ShapeKind.ELLIPSE, l, t, r, b, argb, width))
                                    AnnotTool.LINE -> vm.add(Overlay.Shape(index, ShapeKind.LINE, start.x / w, start.y / h, end.x / w, end.y / h, argb, width))
                                    else -> Unit
                                }
                            }
                            stroke.clear(); dragStart = null
                        },
                    ) { change, drag ->
                        change.consume()
                        if (tool == AnnotTool.MOVE) {
                            val sel = selected ?: return@detectDragGestures
                            val o = vm.overlays.value.getOrNull(sel) as? Overlay.Picture ?: return@detectDragGestures
                            val dx = drag.x / size.width; val dy = drag.y / size.height
                            vm.replace(sel, o.copy(left = o.left + dx, right = o.right + dx, top = o.top + dy, bottom = o.bottom + dy), recordHistory = false)
                        } else {
                            stroke.add(change.position)
                        }
                    }
                },
        ) {
            page?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c ->
                    OverlayPainter.draw(c.nativeCanvas, pageOverlays.map { it.value }, this.size.width, this.size.height) { vm.picture(it) }
                }
                selected?.let { sel ->
                    (overlays.getOrNull(sel) as? Overlay.Picture)?.takeIf { it.page == index }?.let { p ->
                        drawRect(Color(0xFF1B3A6B), topLeft = Offset(p.left * this.size.width, p.top * this.size.height),
                            size = androidx.compose.ui.geometry.Size((p.right - p.left) * this.size.width, (p.bottom - p.top) * this.size.height), style = Stroke(2f))
                    }
                }
                if (stroke.size > 1 && tool == AnnotTool.INK) {
                    val path = Path().apply { moveTo(stroke[0].x, stroke[0].y); stroke.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, color, style = Stroke(width = width * this.size.width, cap = StrokeCap.Round))
                } else if (stroke.size > 1) {
                    val s = stroke.first(); val e = stroke.last()
                    drawRect(color.copy(alpha = 0.3f), topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)), size = androidx.compose.ui.geometry.Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)), style = Stroke(2f))
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AnnotTool.entries.forEach { t ->
                FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(stringResource(toolLabel(t))) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            palette.forEach { c ->
                val label = stringResource(R.string.annotate_color)
                Box(
                    Modifier.size(32.dp).background(c, CircleShape)
                        .border(if (c == color) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable(onClickLabel = label) { color = c },
                )
            }
        }
        Text(stringResource(R.string.annotate_thickness))
        Slider(value = width, onValueChange = { width = it }, valueRange = 0.001f..0.012f)
        val sel = selected
        val selPic = sel?.let { overlays.getOrNull(it) as? Overlay.Picture }
        if (selPic != null) {
            Text(stringResource(R.string.annotate_picture_size))
            Slider(
                value = selPic.right - selPic.left,
                onValueChange = { nw ->
                    val cx = (selPic.left + selPic.right) / 2; val cy = (selPic.top + selPic.bottom) / 2
                    val ratio = (selPic.bottom - selPic.top) / (selPic.right - selPic.left)
                    vm.replace(sel, selPic.copy(left = cx - nw / 2, right = cx + nw / 2, top = cy - nw * ratio / 2, bottom = cy + nw * ratio / 2), recordHistory = false)
                },
                valueRange = 0.05f..0.9f,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showSignaturePad = true }) { Text(stringResource(R.string.action_add_signature)) }
            OutlinedButton(onClick = { imagePicker.pickImages() }) { Text(stringResource(R.string.action_add_image)) }
        }
        PrimaryAction(stringResource(R.string.action_save_new_pdf), enabled = overlays.isNotEmpty()) { vm.export() }
    }

    textAt?.let { pos ->
        var text by remember(pos) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textAt = null },
            title = { Text(stringResource(R.string.annotate_add_text)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2) },
            confirmButton = {
                TextButton(onClick = {
                    textAt = null
                    // [pos] is already normalised to the page box.
                    vm.add(Overlay.Note(index, pos.x, pos.y, text, color.toArgb(), 0.025f))
                }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = { TextButton(onClick = { textAt = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showSignaturePad) {
        SignaturePadDialog(onDismiss = { showSignaturePad = false }) { bmp ->
            showSignaturePad = false
            vm.saveSignature(bmp, pageAspect)
            tool = AnnotTool.MOVE
        }
    }
}

private fun toolLabel(t: AnnotTool) = when (t) {
    AnnotTool.INK -> R.string.annot_ink
    AnnotTool.HIGHLIGHT -> R.string.annot_highlight
    AnnotTool.UNDERLINE -> R.string.annot_underline
    AnnotTool.RECTANGLE -> R.string.annot_rectangle
    AnnotTool.ELLIPSE -> R.string.annot_ellipse
    AnnotTool.LINE -> R.string.annot_line
    AnnotTool.TEXT -> R.string.annot_text
    AnnotTool.MOVE -> R.string.annot_move
}

/** Finger-drawn signature on a transparent canvas, cropped to the strokes. */
@Composable
fun SignaturePadDialog(onDismiss: () -> Unit, onSave: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_draw_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(200.dp).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outline)
                        .onSizeChanged { padSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current.clear(); current.add(it) },
                                onDragEnd = { strokes.add(current.toList()); current.clear() },
                            ) { change, _ -> change.consume(); current.add(change.position) }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        (strokes + listOf(current.toList())).forEach { s ->
                            if (s.size > 1) drawPath(Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }, Color.Black, style = Stroke(6f, cap = StrokeCap.Round))
                        }
                    }
                }
                Text(stringResource(R.string.sign_disclaimer), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { strokes.clear() }) { Text(stringResource(R.string.action_clear)) }
            }
        },
        confirmButton = {
            TextButton(enabled = strokes.isNotEmpty(), onClick = { renderSignature(strokes, padSize)?.let(onSave) }) { Text(stringResource(R.string.action_use_signature)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun renderSignature(strokes: List<List<Offset>>, size: IntSize): Bitmap? {
    val points = strokes.flatten()
    if (points.isEmpty() || size.width == 0) return null
    val pad = 12f
    val minX = (points.minOf { it.x } - pad).coerceAtLeast(0f); val maxX = (points.maxOf { it.x } + pad).coerceAtMost(size.width.toFloat())
    val minY = (points.minOf { it.y } - pad).coerceAtLeast(0f); val maxY = (points.maxOf { it.y } + pad).coerceAtMost(size.height.toFloat())
    val scale = 2f
    val bmp = Bitmap.createBitmap(((maxX - minX) * scale).toInt().coerceAtLeast(1), ((maxY - minY) * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 6f * scale; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND; color = android.graphics.Color.BLACK
    }
    strokes.filter { it.size > 1 }.forEach { s ->
        val path = android.graphics.Path().apply { moveTo((s[0].x - minX) * scale, (s[0].y - minY) * scale); s.drop(1).forEach { lineTo((it.x - minX) * scale, (it.y - minY) * scale) } }
        canvas.drawPath(path, paint)
    }
    return bmp
}
