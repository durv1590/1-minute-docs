@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.oneminutedocs.app.feature.pdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.InputFileList
import com.oneminutedocs.app.common.InputsViewModel
import com.oneminutedocs.app.common.Mime
import com.oneminutedocs.app.common.OpState
import com.oneminutedocs.app.common.OperationStatus
import com.oneminutedocs.app.common.OutputSaver
import com.oneminutedocs.app.common.PrimaryAction
import com.oneminutedocs.app.common.ToolScaffold
import com.oneminutedocs.app.common.progress
import com.oneminutedocs.app.common.rememberPickers
import com.oneminutedocs.app.navigation.Routes
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.domain.Availability
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.FileNames
import com.oneminutedocs.domain.ImageScaleMode
import com.oneminutedocs.domain.Margins
import com.oneminutedocs.domain.Orientation
import com.oneminutedocs.domain.PageRangeParser
import com.oneminutedocs.domain.PageSize
import com.oneminutedocs.processing.pdf.ImagePage
import com.oneminutedocs.processing.pdf.ImageQuality
import com.oneminutedocs.processing.pdf.ImagesPdfOptions
import com.oneminutedocs.processing.pdf.ImagesToPdf
import com.oneminutedocs.processing.pdf.PdfEngine
import com.oneminutedocs.processing.pdf.exportPagesAsImages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------- Images → PDF

@HiltViewModel
class ImagesToPdfViewModel @Inject constructor(
    workspaces: WorkspaceManager,
    repository: DocumentRepository,
    private val writer: ImagesToPdf,
    private val saver: OutputSaver,
) : InputsViewModel(workspaces, repository) {
    override val allowed = setOf(DocumentFormat.JPEG, DocumentFormat.PNG, DocumentFormat.WEBP, DocumentFormat.HEIF)
    override val maxInputs = repository.limits.maxImagesPerPdf
    val rotations = MutableStateFlow<Map<String, Int>>(emptyMap())
    val options = MutableStateFlow(ImagesPdfOptions())
    val name = MutableStateFlow("images")

    fun rotate(index: Int) {
        val key = inputs.value[index].file.path
        rotations.value = rotations.value + (key to ((rotations.value[key] ?: 0) + 90) % 360)
    }

    fun create() = runOperation(R.string.op_creating_pdf) {
        val pages = inputs.value.map { ImagePage(it.file, rotation = rotations.value[it.file.path] ?: 0) }
        val out = workspace.newFile("pdf")
        writer.write(pages, options.value, out) { progress(it) }
        OpState.Done(listOf(saver.save(out, name.value.ifBlank { "images" }, DocumentFormat.PDF, "images_to_pdf").id))
    }
}

@Composable
fun ImagesToPdfScreen(onBack: () -> Unit, onDone: (OpState.Done) -> Unit, vm: ImagesToPdfViewModel = hiltViewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val rotations by vm.rotations.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val pickers = rememberPickers(vm::addUris)
    ToolScaffold(stringResource(R.string.tool_images_to_pdf), onBack, Availability.OFFLINE) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult, onDone)) return@ToolScaffold
        InputFileList(inputs) { i ->
            Row {
                val rot = rotations[inputs[i].file.path] ?: 0
                if (rot != 0) Text("$rot°", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { vm.rotate(i) }) { Icon(Icons.Outlined.RotateRight, stringResource(R.string.action_rotate)) }
                IconButton(onClick = { vm.move(i, -1) }, enabled = i > 0) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.action_move_up)) }
                IconButton(onClick = { vm.move(i, 1) }, enabled = i < inputs.lastIndex) { Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.action_move_down)) }
                IconButton(onClick = { vm.remove(i) }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_remove)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = pickers.pickImages) { Text(stringResource(R.string.action_add_photos)) }
            OutlinedButton(onClick = { pickers.pickMany(Mime.IMAGES) }) { Text(stringResource(R.string.action_add_files)) }
        }
        PageOptions(options.pageSize, options.orientation, options.margins,
            onSize = { vm.options.value = options.copy(pageSize = it) },
            onOrientation = { vm.options.value = options.copy(orientation = it) },
            onMargins = { vm.options.value = options.copy(margins = it) },
            allowFitImage = true,
        )
        Text(stringResource(R.string.label_image_scaling), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageScaleMode.entries.forEach { m ->
                FilterChip(selected = options.scaleMode == m, onClick = { vm.options.value = options.copy(scaleMode = m) }, label = {
                    Text(stringResource(when (m) { ImageScaleMode.FIT -> R.string.scale_fit; ImageScaleMode.FILL -> R.string.scale_fill; ImageScaleMode.ORIGINAL -> R.string.scale_original }))
                })
            }
        }
        Text(stringResource(R.string.label_quality), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageQuality.entries.forEach { q ->
                FilterChip(selected = options.quality == q, onClick = { vm.options.value = options.copy(quality = q) }, label = {
                    Text(stringResource(when (q) { ImageQuality.HIGH -> R.string.quality_high; ImageQuality.STANDARD -> R.string.quality_standard; ImageQuality.COMPACT -> R.string.quality_compact }))
                })
            }
        }
        OutlinedTextField(value = name, onValueChange = { vm.name.value = it }, label = { Text(stringResource(R.string.label_output_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PrimaryAction(stringResource(R.string.action_create_pdf), enabled = inputs.isNotEmpty()) { vm.create() }
    }
}

/** Shared page-setup chips used by Images→PDF and the document editor export. */
@Composable
fun PageOptions(
    size: PageSize,
    orientation: Orientation,
    margins: Margins,
    onSize: (PageSize) -> Unit,
    onOrientation: (Orientation) -> Unit,
    onMargins: (Margins) -> Unit,
    allowFitImage: Boolean = false,
) {
    Text(stringResource(R.string.label_page_size), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PageSize.entries.filter { allowFitImage || it != PageSize.FIT_IMAGE }.forEach { s ->
            FilterChip(selected = size == s, onClick = { onSize(s) }, label = { Text(if (s == PageSize.FIT_IMAGE) stringResource(R.string.page_fit_image) else s.name) })
        }
    }
    Text(stringResource(R.string.label_orientation), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Orientation.entries.filter { allowFitImage || it != Orientation.AUTO }.forEach { o ->
            FilterChip(selected = orientation == o, onClick = { onOrientation(o) }, label = {
                Text(stringResource(when (o) { Orientation.PORTRAIT -> R.string.orientation_portrait; Orientation.LANDSCAPE -> R.string.orientation_landscape; Orientation.AUTO -> R.string.orientation_auto }))
            })
        }
    }
    Text(stringResource(R.string.label_margins), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Margins.entries.forEach { m ->
            FilterChip(selected = margins == m, onClick = { onMargins(m) }, label = {
                Text(stringResource(when (m) { Margins.NONE -> R.string.margins_none; Margins.SMALL -> R.string.margins_small; Margins.NORMAL -> R.string.margins_normal; Margins.LARGE -> R.string.margins_large }))
            })
        }
    }
}

// ---------------------------------------------------------------- PDF → images

@HiltViewModel
class PdfToImagesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    workspaces: WorkspaceManager,
    repository: DocumentRepository,
    private val pdf: PdfEngine,
    private val saver: OutputSaver,
) : InputsViewModel(workspaces, repository) {
    override val allowed = setOf(DocumentFormat.PDF)
    val pageCount = MutableStateFlow<Int?>(null)

    init {
        preload(savedState.toRoute<Routes.PdfToImages>().documentId)
    }

    override fun onInputsChanged() {
        viewModelScope.launch { pageCount.value = inputs.value.firstOrNull()?.let { runCatching { pdf.inspect(it.file) }.getOrNull()?.pageCount } }
    }

    fun export(ranges: String, dpi: Int, jpeg: Boolean) = runOperation(R.string.op_exporting_images) {
        val input = inputs.value.single()
        val count = pageCount.value ?: throw DocException(ErrorCode.CORRUPT_FILE)
        val parsed = PageRangeParser.parse(ranges.ifBlank { "1-$count" }, count) as? PageRangeParser.Result.Valid ?: throw DocException(ErrorCode.INVALID_PAGE_RANGE)
        val format = if (jpeg) DocumentFormat.JPEG else DocumentFormat.PNG
        val base = FileNames.baseName(input.displayName)
        val files = exportPagesAsImages(input.file, parsed.pageIndices, dpi, jpeg, workspace.dir, { "page-$it.${format.extension}" }) { progress(it * 0.9f) }
        val ids = files.mapIndexed { i, f -> saver.save(f, "${base}_page_${parsed.pageIndices[i] + 1}", format, "pdf_to_images").id }
        OpState.Done(ids)
    }
}

@Composable
fun PdfToImagesScreen(onBack: () -> Unit, onDone: (OpState.Done) -> Unit, vm: PdfToImagesViewModel = hiltViewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val count by vm.pageCount.collectAsStateWithLifecycle()
    var ranges by rememberSaveable { mutableStateOf("") }
    var dpi by rememberSaveable { mutableStateOf(150) }
    var jpeg by rememberSaveable { mutableStateOf(true) }
    val pickers = rememberPickers(vm::addUris)
    ToolScaffold(stringResource(R.string.tool_pdf_to_images), onBack, Availability.OFFLINE) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult, onDone)) return@ToolScaffold
        InputFileList(inputs)
        OutlinedButton(onClick = { pickers.pickOne(Mime.PDF) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (inputs.isEmpty()) R.string.action_choose_pdf else R.string.action_change_file)) }
        count?.let { Text(stringResource(R.string.details_pages, it)) }
        OutlinedTextField(value = ranges, onValueChange = { ranges = it }, label = { Text(stringResource(R.string.label_page_ranges)) }, supportingText = { Text(stringResource(R.string.images_ranges_help)) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.label_format), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = jpeg, onClick = { jpeg = true }, label = { Text("JPEG") })
            FilterChip(selected = !jpeg, onClick = { jpeg = false }, label = { Text("PNG") })
        }
        Text(stringResource(R.string.label_resolution), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(72, 150, 300).forEach { d -> FilterChip(selected = dpi == d, onClick = { dpi = d }, label = { Text("$d dpi") }) }
        }
        if (dpi >= 300 || !jpeg) InfoBanner(stringResource(R.string.images_size_warning))
        PrimaryAction(stringResource(R.string.action_export_images), enabled = inputs.isNotEmpty() && count != null) { vm.export(ranges, dpi, jpeg) }
    }
}
