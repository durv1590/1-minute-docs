@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.oneminutedocs.app.feature.scanner

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.toRoute
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.OpState
import com.oneminutedocs.app.common.OperationStatus
import com.oneminutedocs.app.common.OperationViewModel
import com.oneminutedocs.app.common.OutputSaver
import com.oneminutedocs.app.common.PrimaryAction
import com.oneminutedocs.app.common.ToolScaffold
import com.oneminutedocs.app.common.progress
import com.oneminutedocs.app.feature.pdf.PageOptions
import com.oneminutedocs.app.navigation.Routes
import com.oneminutedocs.app.navigation.ScanPurpose
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.domain.Availability
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.imaging.Point
import com.oneminutedocs.imaging.Quad
import com.oneminutedocs.processing.image.BitmapLoader
import com.oneminutedocs.processing.image.PageFilter
import com.oneminutedocs.processing.pdf.ImagePage
import com.oneminutedocs.processing.pdf.ImagesPdfOptions
import com.oneminutedocs.processing.pdf.ImagesToPdf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@HiltViewModel
class ScanReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    workspaces: WorkspaceManager,
    val store: ScanSessionStore,
    private val images: ImagesToPdf,
    private val saver: OutputSaver,
) : OperationViewModel(workspaces) {
    private val route = savedState.toRoute<Routes.ScanReview>()
    val sessionId = route.sessionId
    val purpose = route.purpose
    val pages = store.pages(sessionId)
    val options = MutableStateFlow(ImagesPdfOptions())
    val name = MutableStateFlow("scan")

    fun update(page: ScanPage) = store.update(sessionId, page)
    fun remove(id: String) = store.remove(sessionId, id)
    fun move(id: String, d: Int) = store.move(sessionId, id, d)

    suspend fun preview(page: ScanPage, original: Boolean): Bitmap? = runCatching {
        if (original) BitmapLoader.decode(page.original, 1200) else store.render(page, 1200)
    }.getOrNull()

    /** Scanned-pages PDF: the page images exactly as reviewed (no recognition). */
    fun exportPdf() = runOperation(R.string.op_creating_pdf) {
        if (pages.value.isEmpty()) throw DocException(ErrorCode.NOTHING_TO_DO)
        val files = store.exportJpegs(sessionId, workspace.dir) { i, n -> progress(i / n.toFloat() * 0.6f) }
        val out = workspace.newFile("pdf")
        images.write(files.map { ImagePage(it) }, options.value, out) { progress(0.6f + it * 0.35f) }
        val doc = saver.save(out, name.value.ifBlank { "scan" }, DocumentFormat.PDF, if (purpose == ScanPurpose.HANDWRITING) "handwriting_scanned_pdf" else "scan")
        OpState.Done(listOf(doc.id))
    }
}

@Composable
fun ScanReviewScreen(onBack: () -> Unit, onRetake: (String) -> Unit, onContinueHandwriting: () -> Unit, onDone: (OpState.Done) -> Unit, vm: ScanReviewViewModel = hiltViewModel()) {
    val pages by vm.pages.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var cropMode by rememberSaveable { mutableStateOf(false) }
    var showOriginal by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pages) { if (selectedId == null || pages.none { it.id == selectedId }) selectedId = pages.firstOrNull()?.id }
    val page = pages.firstOrNull { it.id == selectedId }

    ToolScaffold(stringResource(R.string.review_title), onBack, Availability.OFFLINE) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult, onDone)) return@ToolScaffold
        if (page == null) {
            Text(stringResource(R.string.review_empty))
            return@ToolScaffold
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                val thumb by produceState<Bitmap?>(null, p) { value = vm.preview(p, original = false) }
                Card(onClick = { selectedId = p.id }, border = if (p.id == selectedId) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                    Box(Modifier.width(72.dp).height(96.dp), contentAlignment = Alignment.Center) {
                        thumb?.let { Image(it.asImageBitmap(), stringResource(R.string.viewer_page_of, i + 1, pages.size), Modifier.fillMaxSize()) } ?: CircularProgressIndicator()
                    }
                }
            }
        }
        if (cropMode) {
            CornerEditor(page) { quad -> vm.update(page.copy(quad = quad)) }
            OutlinedButton(onClick = { cropMode = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_done)) }
        } else {
            val bitmap by produceState<Bitmap?>(null, page, showOriginal) { value = vm.preview(page, showOriginal) }
            Box(Modifier.fillMaxWidth().aspectRatio(bitmap?.let { it.width.toFloat() / it.height } ?: 0.75f), contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(if (showOriginal) R.string.review_original else R.string.review_enhanced), Modifier.fillMaxSize()) } ?: CircularProgressIndicator()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showOriginal, onClick = { showOriginal = false }, label = { Text(stringResource(R.string.review_enhanced)) })
                FilterChip(selected = showOriginal, onClick = { showOriginal = true }, label = { Text(stringResource(R.string.review_original)) })
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { vm.move(page.id, -1) }) { Icon(Icons.Outlined.ArrowBack, stringResource(R.string.action_move_up)) }
            IconButton(onClick = { cropMode = true }) { Icon(Icons.Outlined.Crop, stringResource(R.string.action_crop)) }
            IconButton(onClick = { vm.update(page.copy(rotation = (page.rotation + 90) % 360)) }) { Icon(Icons.Outlined.RotateRight, stringResource(R.string.action_rotate)) }
            IconButton(onClick = { vm.remove(page.id); onRetake(page.id) }) { Icon(Icons.Outlined.CameraAlt, stringResource(R.string.action_retake)) }
            IconButton(onClick = { vm.remove(page.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
            IconButton(onClick = { vm.move(page.id, 1) }) { Icon(Icons.Outlined.ArrowForward, stringResource(R.string.action_move_down)) }
        }
        Text(stringResource(R.string.label_filter), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageFilter.entries.forEach { f ->
                FilterChip(selected = page.filter == f, onClick = { vm.update(page.copy(filter = f)) }, label = { Text(stringResource(filterLabel(f))) })
            }
        }
        Text(stringResource(R.string.label_brightness))
        Slider(value = page.adjustments.brightness.toFloat(), onValueChange = { vm.update(page.copy(adjustments = page.adjustments.copy(brightness = it.toInt()))) }, valueRange = -60f..60f)
        Text(stringResource(R.string.label_contrast))
        Slider(value = page.adjustments.contrast, onValueChange = { vm.update(page.copy(adjustments = page.adjustments.copy(contrast = it))) }, valueRange = 0.6f..1.8f)
        InfoBanner(stringResource(R.string.review_original_kept))
        if (vm.purpose == ScanPurpose.HANDWRITING) {
            PrimaryAction(stringResource(R.string.action_continue)) { onContinueHandwriting() }
        } else {
            PageOptions(options.pageSize, options.orientation, options.margins,
                onSize = { vm.options.value = options.copy(pageSize = it) },
                onOrientation = { vm.options.value = options.copy(orientation = it) },
                onMargins = { vm.options.value = options.copy(margins = it) },
                allowFitImage = true,
            )
            PrimaryAction(stringResource(R.string.action_create_pdf), enabled = pages.isNotEmpty()) { vm.exportPdf() }
        }
    }
}

fun filterLabel(f: PageFilter) = when (f) {
    PageFilter.ORIGINAL -> R.string.filter_original
    PageFilter.ENHANCED -> R.string.filter_enhanced
    PageFilter.GRAYSCALE -> R.string.filter_grayscale
    PageFilter.BLACK_AND_WHITE -> R.string.filter_bw
    PageFilter.SHADOW_REMOVAL -> R.string.filter_shadow
}

/** Four draggable corners over the original photo; the default comes from automatic edge detection. */
@Composable
private fun CornerEditor(page: ScanPage, onChange: (Quad) -> Unit) {
    val bitmap by produceState<Bitmap?>(null, page.original) { value = runCatching { BitmapLoader.decode(page.original, 1200) }.getOrNull() }
    val b = bitmap ?: run { CircularProgressIndicator(); return }
    var quad by remember(page.id) { mutableStateOf(page.quad ?: Quad(Point(0.05, 0.05), Point(0.95, 0.05), Point(0.95, 0.95), Point(0.05, 0.95))) }
    var dragging by remember { mutableStateOf(-1) }
    Text(stringResource(R.string.review_crop_hint), style = MaterialTheme.typography.bodySmall)
    Box(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)) {
        Image(b.asImageBitmap(), stringResource(R.string.review_original), Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(page.id) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val pts = quad.points
                        dragging = pts.indices.minByOrNull { i -> Offset((pts[i].x * size.width).toFloat(), (pts[i].y * size.height).toFloat()).minus(pos).getDistance() } ?: -1
                    },
                    onDragEnd = { dragging = -1; onChange(quad) },
                ) { change, drag ->
                    change.consume()
                    if (dragging < 0) return@detectDragGestures
                    val pts = quad.points.toMutableList()
                    val p = pts[dragging]
                    pts[dragging] = Point((p.x + drag.x / size.width).coerceIn(0.0, 1.0), (p.y + drag.y / size.height).coerceIn(0.0, 1.0))
                    quad = Quad(pts[0], pts[1], pts[2], pts[3])
                }
            },
        ) {
            val pts = quad.points.map { Offset((it.x * size.width).toFloat(), (it.y * size.height).toFloat()) }
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            drawPath(path, Color(0x552F6FDF))
            drawPath(path, Color(0xFF1B3A6B), style = Stroke(4f))
            pts.forEach { drawCircle(Color(0xFFE08A00), radius = 22f, center = it) }
        }
    }
}
