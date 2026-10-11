package com.oneminutedocs.app.feature.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
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
import com.oneminutedocs.app.common.rememberPickers
import com.oneminutedocs.app.common.toDocError
import com.oneminutedocs.app.navigation.Routes
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.core.designsystem.ErrorBlock
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.app.common.errorMessage
import com.oneminutedocs.domain.Availability
import com.oneminutedocs.domain.DocError
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.FileNames
import com.oneminutedocs.processing.pdf.PageSpec
import com.oneminutedocs.processing.pdf.PdfEngine
import com.oneminutedocs.processing.pdf.PdfPageRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Working state of one page; edits are kept in memory (and undoable) until the user exports. */
data class PageItem(val sourceIndex: Int, val rotation: Int = 0, val deleted: Boolean = false, val selected: Boolean = false)

@HiltViewModel
class PageManagerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    workspaces: WorkspaceManager,
    repository: DocumentRepository,
    private val pdf: PdfEngine,
    private val saver: OutputSaver,
) : InputsViewModel(workspaces, repository) {
    override val allowed = setOf(DocumentFormat.PDF)
    val pages = MutableStateFlow<List<PageItem>>(emptyList())
    val loadError = MutableStateFlow<DocError?>(null)
    private val history = ArrayDeque<List<PageItem>>()
    private var renderer: PdfPageRenderer? = null
    private val thumbs = HashMap<Int, Bitmap>()

    init {
        preload(savedState.toRoute<Routes.PageManager>().documentId)
    }

    override fun onInputsChanged() {
        viewModelScope.launch {
            renderer?.close(); renderer = null; thumbs.clear(); history.clear()
            val input = inputs.value.firstOrNull() ?: return@launch
            try {
                val info = pdf.inspect(input.file)
                if (info.needsPassword) throw com.oneminutedocs.domain.DocException(com.oneminutedocs.domain.ErrorCode.ENCRYPTED)
                renderer = PdfPageRenderer.open(input.file)
                pages.value = (0 until info.pageCount).map { PageItem(it) }
                loadError.value = null
            } catch (t: Throwable) {
                loadError.value = t.toDocError()
            }
        }
    }

    suspend fun thumbnail(index: Int): Bitmap? {
        thumbs[index]?.let { return it }
        val r = renderer ?: return null
        // Thumbnails are small (~0.2 MB); keep at most ~60 references. Evicted bitmaps are left to the GC because a
        // visible Image may still be drawing them.
        if (thumbs.size > 60) thumbs.keys.take(20).toList().forEach { thumbs.remove(it) }
        return runCatching { r.render(index, 240) }.getOrNull()?.also { thumbs[index] = it }
    }

    private fun edit(transform: (List<PageItem>) -> List<PageItem>) {
        history.addLast(pages.value)
        if (history.size > 50) history.removeFirst()
        pages.value = transform(pages.value)
    }

    fun undo() { history.removeLastOrNull()?.let { pages.value = it } }
    fun rotate(pos: Int) = edit { l -> l.mapIndexed { i, p -> if (i == pos) p.copy(rotation = (p.rotation + 90) % 360) else p } }
    fun toggleDelete(pos: Int) = edit { l -> l.mapIndexed { i, p -> if (i == pos) p.copy(deleted = !p.deleted) else p } }
    fun toggleSelect(pos: Int) { pages.value = pages.value.mapIndexed { i, p -> if (i == pos) p.copy(selected = !p.selected) else p } }
    fun move(pos: Int, delta: Int) = edit { l ->
        val target = pos + delta
        if (target !in l.indices) l else l.toMutableList().apply { add(target, removeAt(pos)) }
    }

    val canUndo: Boolean get() = history.isNotEmpty()

    /** Exports the arrangement (or only selected pages when [selectedOnly]) as a new PDF. The original is kept. */
    fun export(selectedOnly: Boolean) = runOperation(R.string.op_saving_pages) {
        val input = inputs.value.single()
        val specs = pages.value.filter { !it.deleted && (!selectedOnly || it.selected) }.map { PageSpec(it.sourceIndex, it.rotation) }
        if (specs.isEmpty()) throw com.oneminutedocs.domain.DocException(com.oneminutedocs.domain.ErrorCode.NOTHING_TO_DO)
        val out = workspace.newFile("pdf")
        pdf.rebuild(input.file, specs, out)
        val doc = saver.save(out, FileNames.derived(input.displayName, if (selectedOnly) "extracted" else "edited", DocumentFormat.PDF), DocumentFormat.PDF, "pages")
        OpState.Done(listOf(doc.id))
    }

    override fun onCleared() {
        renderer?.close()
        thumbs.clear()
        super.onCleared()
    }
}

@Composable
fun PageManagerScreen(onBack: () -> Unit, onDone: (OpState.Done) -> Unit, vm: PageManagerViewModel = hiltViewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val pickers = rememberPickers(vm::addUris)
    ToolScaffold(
        stringResource(R.string.tool_pages), onBack, Availability.OFFLINE, scrollable = false,
        actions = { IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.action_undo)) } },
    ) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult, onDone)) return@ToolScaffold
        if (inputs.isEmpty()) {
            OutlinedButton(onClick = { pickers.pickOne(Mime.PDF) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_choose_pdf)) }
            return@ToolScaffold
        }
        loadError?.let { ErrorBlock(stringResource(R.string.error_title), stringResource(errorMessage(it.code))); return@ToolScaffold }
        InfoBanner(stringResource(R.string.pages_hint))
        val kept = pages.count { !it.deleted }
        val selected = pages.count { it.selected && !it.deleted }
        Text(stringResource(R.string.pages_summary, kept, pages.size))
        LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f, fill = true).heightIn(min = 200.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(pages, key = { _, p -> p.sourceIndex }) { pos, p ->
                val thumb by produceState<Bitmap?>(null, p.sourceIndex) { value = vm.thumbnail(p.sourceIndex) }
                Card(border = if (p.selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null, colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.72f), contentAlignment = Alignment.Center) {
                            val b = thumb
                            if (b == null) CircularProgressIndicator() else Image(
                                b.asImageBitmap(),
                                contentDescription = stringResource(R.string.viewer_page_of, p.sourceIndex + 1, pages.size),
                                modifier = Modifier.graphicsLayer(rotationZ = p.rotation.toFloat(), alpha = if (p.deleted) 0.25f else 1f),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = p.selected, onCheckedChange = { vm.toggleSelect(pos) })
                            Text("${p.sourceIndex + 1}", style = MaterialTheme.typography.labelLarge)
                        }
                        Row {
                            IconButton(onClick = { vm.move(pos, -1) }, enabled = pos > 0) { Icon(Icons.Outlined.ArrowBack, stringResource(R.string.action_move_up)) }
                            IconButton(onClick = { vm.rotate(pos) }) { Icon(Icons.Outlined.RotateRight, stringResource(R.string.action_rotate)) }
                            IconButton(onClick = { vm.toggleDelete(pos) }) {
                                Icon(if (p.deleted) Icons.Outlined.RestoreFromTrash else Icons.Outlined.Delete, stringResource(if (p.deleted) R.string.action_restore else R.string.action_delete))
                            }
                            IconButton(onClick = { vm.move(pos, 1) }, enabled = pos < pages.lastIndex) { Icon(Icons.Outlined.ArrowForward, stringResource(R.string.action_move_down)) }
                        }
                    }
                }
            }
        }
        if (selected > 0) OutlinedButton(onClick = { vm.export(selectedOnly = true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pages_extract_selected, selected)) }
        PrimaryAction(stringResource(R.string.action_save_new_pdf), enabled = kept > 0) { vm.export(selectedOnly = false) }
    }
}
