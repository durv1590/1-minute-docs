package com.oneminutedocs.app.feature.documents

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.errorMessage
import com.oneminutedocs.core.designsystem.CenteredBox
import com.oneminutedocs.core.designsystem.ConfirmDialog
import com.oneminutedocs.core.designsystem.ErrorBlock
import com.oneminutedocs.core.designsystem.InfoBanner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(onBack: () -> Unit, vm: ViewerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val doc by vm.doc.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val actions = rememberDocumentActions(vm.repository) { msg -> scope.launch { snackbar.showSnackbar(context.getString(msg)) } }
    var zoom by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableIntStateOf(0) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(deleted) { if (deleted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc?.displayName.orEmpty(), maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f) }) { Icon(Icons.Outlined.ZoomOut, stringResource(R.string.action_zoom_out)) }
                    IconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(5f) }) { Icon(Icons.Outlined.ZoomIn, stringResource(R.string.action_zoom_in)) }
                    IconButton(onClick = { rotation = (rotation + 90) % 360 }) { Icon(Icons.AutoMirrored.Outlined.RotateRight, stringResource(R.string.action_rotate_view)) }
                },
            )
        },
        bottomBar = {
            val d = doc
            if (d != null) BottomAppBar {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { actions.share(listOf(d)) }) { Icon(Icons.Outlined.Share, stringResource(R.string.action_share)) }
                    IconButton(onClick = { actions.export(d) }) { Icon(Icons.Outlined.FileDownload, stringResource(R.string.action_export)) }
                    IconButton(onClick = { actions.openWith(d) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.action_open_with)) }
                    IconButton(onClick = { showRename = true }) { Icon(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.action_rename)) }
                    IconButton(onClick = { showDetails = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.action_details)) }
                    IconButton(onClick = { showDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceVariant)) {
            when (val c = content) {
                ViewerContent.Loading -> CenteredBox { CircularProgressIndicator() }
                is ViewerContent.Error -> Box(Modifier.padding(16.dp)) {
                    ErrorBlock(stringResource(R.string.viewer_cannot_open), stringResource(errorMessage(c.error.code)))
                }
                is ViewerContent.Pdf -> PdfPages(c.pageCount, zoom, rotation, onZoom = { zoom = it }) { index, width -> vm.page(index, width) }
                is ViewerContent.Image -> Image(
                    bitmap = c.bitmap.asImageBitmap(),
                    contentDescription = doc?.displayName,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, rotationZ = rotation.toFloat()),
                )
                is ViewerContent.Text -> androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (c.isStructuredPreview) InfoBanner(stringResource(R.string.viewer_text_preview_note))
                    Text(c.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }

    doc?.let { d ->
        if (showRename) RenameDialog(d.displayName, onDismiss = { showRename = false }) { vm.rename(it); showRename = false }
        if (showDetails) DetailsDialog(d) { showDetails = false }
        if (showDelete) ConfirmDialog(
            title = stringResource(R.string.documents_delete_title),
            message = stringResource(R.string.documents_delete_body, 1),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { showDelete = false; vm.delete() },
            onDismiss = { showDelete = false },
            destructive = true,
        )
    }
}

/** Lazily rendered pages: only visible pages are rendered, at a width matching the screen and zoom level. */
@Composable
fun PdfPages(pageCount: Int, zoom: Float, rotation: Int, onZoom: (Float) -> Unit, render: suspend (Int, Int) -> android.graphics.Bitmap?) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, pan, _ ->
        onZoom((zoom * zoomChange).coerceIn(1f, 5f))
        offset += pan
    }
    val listState = rememberLazyListState()
    val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Render resolution follows zoom (capped) so text stays sharp without unbounded memory use.
        val widthPx = with(density) { maxWidth.toPx() }.toInt().let { (it * zoom.coerceAtMost(2.5f)).toInt() }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .transformable(transform)
                .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = if (zoom > 1f) offset.x else 0f, translationY = 0f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        ) {
            items(pageCount) { index ->
                val bitmap by produceState<android.graphics.Bitmap?>(null, index, widthPx) { value = render(index, widthPx) }
                val label = stringResource(R.string.viewer_page_of, index + 1, pageCount)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(bitmap?.let { it.width.toFloat() / it.height } ?: 0.707f)
                        .background(Color.White)
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    val b = bitmap
                    if (b == null) CircularProgressIndicator() else Image(b.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer(rotationZ = rotation.toFloat()))
                }
            }
        }
        Text(
            stringResource(R.string.viewer_page_of, current, pageCount),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
