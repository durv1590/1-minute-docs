@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.oneminutedocs.app.feature.pdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.AvailabilityPill
import com.oneminutedocs.app.common.CloudConsentDialog
import com.oneminutedocs.app.common.CloudGate
import com.oneminutedocs.app.common.InputFileList
import com.oneminutedocs.app.common.InputsViewModel
import com.oneminutedocs.app.common.Mime
import com.oneminutedocs.app.common.OpState
import com.oneminutedocs.app.common.OperationStatus
import com.oneminutedocs.app.common.OutputSaver
import com.oneminutedocs.app.common.PrimaryAction
import com.oneminutedocs.app.common.ToolScaffold
import com.oneminutedocs.app.common.errorMessage
import com.oneminutedocs.app.common.formatSize
import com.oneminutedocs.app.common.progress
import com.oneminutedocs.app.common.rememberPickers
import com.oneminutedocs.app.navigation.Routes
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.core.network.CloudJobRunner
import com.oneminutedocs.core.network.UploadFile
import com.oneminutedocs.core.designsystem.ErrorBlock
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.domain.Availability
import com.oneminutedocs.domain.CompressionPreset
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.FileNames
import com.oneminutedocs.domain.PageRangeParser
import com.oneminutedocs.domain.SizeReport
import com.oneminutedocs.processing.pdf.PdfEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import javax.inject.Inject

// ---------------------------------------------------------------- Merge

@HiltViewModel
class MergeViewModel @Inject constructor(
    workspaces: WorkspaceManager,
    repository: DocumentRepository,
    private val pdf: PdfEngine,
    private val saver: OutputSaver,
) : InputsViewModel(workspaces, repository) {
    override val allowed = setOf(DocumentFormat.PDF)
    override val maxInputs = repository.limits.maxMergeFiles
    val pageCounts = MutableStateFlow<Map<String, Int?>>(emptyMap())
    val outputName = MutableStateFlow("merged")

    override fun onInputsChanged() {
        viewModelScope.launch {
            val map = pageCounts.value.toMutableMap()
            for (input in inputs.value) {
                val key = input.file.path
                if (key !in map) map[key] = runCatching { pdf.inspect(input.file) }.getOrNull()?.takeIf { !it.needsPassword }?.pageCount
            }
            pageCounts.value = map
        }
    }

    fun merge() = runOperation(R.string.op_merging) {
        val out = workspace.newFile("pdf")
        pdf.merge(inputs.value.map { it.file }, out) { progress(it) }
        val doc = saver.save(out, outputName.value.ifBlank { "merged" }, DocumentFormat.PDF, "merge")
        OpState.Done(listOf(doc.id))
    }
}

@Composable
fun MergeScreen(onBack: () -> Unit, onDone: (OpState.Done) -> Unit, vm: MergeViewModel = hiltViewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val counts by vm.pageCounts.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val inputError by vm.inputError.collectAsStateWithLifecycle()
    val name by vm.outputName.collectAsStateWithLifecycle()
    val pickers = rememberPickers(vm::addUris)
    ToolScaffold(stringResource(R.string.tool_merge), onBack, Availability.OFFLINE) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult, onDone)) return@ToolScaffold
        Text(stringResource(R.string.merge_hint))
        inputError?.let { ErrorBlock(stringResource(R.string.error_title), stringResource(errorMessage(it.code)), retryLabel = stringResource(R.string.action_ok), onRetry = vm::clearInputError) }
        InputFileList(inputs) { i ->
            Row {
                val pages = counts[inputs[i].file.path]
                if (pages != null) Text(stringResource(R.string.details_pages, pages), style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                IconButton(onClick = { vm.move(i, -1) }, enabled = i > 0) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.action_move_up)) }
                IconButton(onClick = { vm.move(i, 1) }, enabled = i < inputs.lastIndex) { Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.action_move_down)) }
                IconButton(onClick = { vm.remove(i) }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_remove)) }
            }
        }
        OutlinedButton(onClick = { pickers.pickMany(Mime.PDF) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_add_pdfs)) }
        val total = inputs.sumOf { counts[it.file.path] ?: 0 }
        if (inputs.size >= 2) Text(stringResource(R.string.merge_total_pages, total))
        OutlinedTextField(value = name, onValueChange = { vm.outputName.value = it }, label = { Text(stringResource(R.string.label_output_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PrimaryAction(stringResource(R.string.action_merge), enabled = inputs.size >= 2) { vm.merge() }
    }
}

// ---------------------------------------------------------------- Split

enum class SplitMode { EXTRACT, PER_RANGE, EACH_PAGE, EVERY_N }

@HiltViewModel
class SplitViewModel @Inject constructor(
    savedState: SavedStateHandle,
    workspaces: WorkspaceManager,
    repository: DocumentRepository,
    private val pdf: PdfEngine,
    private val saver: OutputSaver,
) : InputsViewModel(workspaces, repository) {
    override val allowed = setOf(DocumentFormat.PDF)
    val pageCount = MutableStateFlow<Int?>(null)

    init {
        preload(savedState.toRoute<Routes.Split>().documentId)
    }

    override fun onInputsChanged() {
        viewModelScope.launch { pageCount.value = inputs.value.firstOrNull()?.let { runCatching { pdf.inspect(it.file) }.getOrNull()?.pageCount } }
    }

    fun validate(mode: SplitMode, ranges: String, n: Int): PageRangeParser.Result? {
        val count = pageCount.value ?: return null
        return when (mode) {
            SplitMode.EXTRACT, SplitMode.PER_RANGE -> PageRangeParser.parse(ranges, count)
            else -> null
        }
    }

    fun split(mode: SplitMode, ranges: String, everyN: Int) = runOperation(R.string.op_splitting) {
        val input = inputs.value.single()
        val count = pageCount.value ?: pdf.inspect(input.file).pageCount
        val base = FileNames.baseName(input.displayName)
        val groups: List<List<Int>> = when (mode) {
            SplitMode.EXTRACT -> listOf(parse(ranges, count).pageIndices)
            SplitMode.PER_RANGE -> parse(ranges, count).ranges.map { r -> r.map { it - 1 } }
            SplitMode.EACH_PAGE -> PageRangeParser.chunks(count, 1)
            SplitMode.EVERY_N -> PageRangeParser.chunks(count, everyN.coerceAtLeast(1))
        }
        val files = pdf.split(input.file, groups, workspace.dir, { i -> "part-$i.pdf" }) { progress(it * 0.9f) }
        val ids = files.mapIndexed { i, f ->
            val label = PageRangeParser.format(groups[i])
            saver.save(f, "${base}_pages_$label".replace(", ", "_"), DocumentFormat.PDF, "split").id
        }
        OpState.Done(ids)
    }

    private fun parse(ranges: String, count: Int): PageRangeParser.Result.Valid =
        PageRangeParser.parse(ranges, count) as? PageRangeParser.Result.Valid
            ?: throw com.oneminutedocs.domain.DocException(com.oneminutedocs.domain.ErrorCode.INVALID_PAGE_RANGE)
}

@Composable
fun SplitScreen(onBack: () -> Unit, onDone: (OpState.Done) -> Unit, vm: SplitViewModel = hiltViewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val pageCount by vm.pageCount.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(SplitMode.EXTRACT) }
    var ranges by rememberSaveable { mutableStateOf("1") }
    var everyN by rememberSaveable { mutableStateOf("2") }
    val pickers = rememberPickers(vm::addUris)
    ToolScaffold(stringResource(R.string.tool_split), onBack, Availability.OFFLINE) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult, onDone)) return@ToolScaffold
        InputFileList(inputs)
        OutlinedButton(onClick = { pickers.pickOne(Mime.PDF) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (inputs.isEmpty()) R.string.action_choose_pdf else R.string.action_change_file)) }
        pageCount?.let { Text(stringResource(R.string.details_pages, it)) }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SplitMode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = {
                    Text(stringResource(when (m) {
                        SplitMode.EXTRACT -> R.string.split_extract
                        SplitMode.PER_RANGE -> R.string.split_per_range
                        SplitMode.EACH_PAGE -> R.string.split_each_page
                        SplitMode.EVERY_N -> R.string.split_every_n
                    }))
                })
            }
        }
        var error: String? = null
        when (mode) {
            SplitMode.EXTRACT, SplitMode.PER_RANGE -> {
                OutlinedTextField(value = ranges, onValueChange = { ranges = it }, label = { Text(stringResource(R.string.label_page_ranges)) }, supportingText = { Text(stringResource(R.string.split_ranges_help)) }, modifier = Modifier.fillMaxWidth())
                val v = vm.validate(mode, ranges, 0)
                if (v is PageRangeParser.Result.Invalid) error = stringResource(R.string.error_page_range_detail, v.token)
            }
            SplitMode.EVERY_N -> OutlinedTextField(value = everyN, onValueChange = { everyN = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.label_every_n)) }, modifier = Modifier.fillMaxWidth())
            SplitMode.EACH_PAGE -> Unit
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        InfoBanner(stringResource(R.string.original_kept))
        PrimaryAction(stringResource(R.string.action_split), enabled = inputs.isNotEmpty() && pageCount != null && error == null && (mode != SplitMode.EVERY_N || (everyN.toIntOrNull() ?: 0) > 0)) {
            vm.split(mode, ranges, everyN.toIntOrNull() ?: 1)
        }
    }
}

// ---------------------------------------------------------------- Compress

@HiltViewModel
class CompressViewModel @Inject constructor(
    savedState: SavedStateHandle,
    workspaces: WorkspaceManager,
    repository: DocumentRepository,
    private val pdf: PdfEngine,
    private val saver: OutputSaver,
    private val cloud: CloudJobRunner,
    val gate: CloudGate,
) : InputsViewModel(workspaces, repository) {
    override val allowed = setOf(DocumentFormat.PDF)

    /** Set when the result is not smaller: the user decides whether to keep the original. */
    private val _notSmaller = MutableStateFlow<Pair<File, SizeReport>?>(null)
    val notSmaller: StateFlow<Pair<File, SizeReport>?> = _notSmaller.asStateFlow()

    init {
        preload(savedState.toRoute<Routes.Compress>().documentId)
    }

    fun compress(preset: CompressionPreset, useCloud: Boolean, report: (SizeReport) -> String) {
        if (useCloud) viewModelScope.launch { gate.request { runCompression(preset, true, report) } } else runCompression(preset, false, report)
    }

    private fun runCompression(preset: CompressionPreset, useCloud: Boolean, report: (SizeReport) -> String) = runOperation(R.string.op_compressing) {
        val input = inputs.value.single()
        val out = workspace.newFile("pdf")
        val size = if (useCloud) {
            val options = buildJsonObject { put("preset", JsonPrimitive(when (preset) { CompressionPreset.HIGH_QUALITY -> "high_quality"; CompressionPreset.BALANCED -> "balanced"; CompressionPreset.SMALL_FILE -> "small_file" })) }
            cloud.run("compress_pdf", listOf(UploadFile(input.file, input.displayName, "application/pdf")), options, out, repository.limits.maxPdfBytes) { p -> progress(p.percent?.div(100f)) }
            SizeReport(input.sizeBytes, out.length())
        } else {
            pdf.compress(input.file, out, preset) { progress(it) }
        }
        if (!size.isSmaller) {
            _notSmaller.value = out to size
            OpState.Done(emptyList())
        } else {
            val doc = saver.save(out, FileNames.derived(input.displayName, "compressed", DocumentFormat.PDF), DocumentFormat.PDF, "compress")
            OpState.Done(listOf(doc.id), report(size))
        }
    }

    fun keepCompressedAnyway(onSaved: (String) -> Unit) {
        val (file, _) = _notSmaller.value ?: return
        _notSmaller.value = null
        viewModelScope.launch {
            val input = inputs.value.single()
            onSaved(saver.save(file, FileNames.derived(input.displayName, "compressed", DocumentFormat.PDF), DocumentFormat.PDF, "compress").id)
        }
    }

    fun keepOriginal() {
        _notSmaller.value = null
        dismissResult()
    }
}

@Composable
fun CompressScreen(onBack: () -> Unit, onDone: (OpState.Done) -> Unit, onSavedSingle: (String) -> Unit, vm: CompressViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val notSmaller by vm.notSmaller.collectAsStateWithLifecycle()
    val pendingCloud by vm.gate.pending.collectAsStateWithLifecycle()
    var preset by rememberSaveable { mutableStateOf(CompressionPreset.BALANCED) }
    var useCloud by rememberSaveable { mutableStateOf(false) }
    val pickers = rememberPickers(vm::addUris)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val reportText: (SizeReport) -> String = { r ->
        context.getString(R.string.compress_report, formatSize(context, r.originalBytes), formatSize(context, r.outputBytes), r.reductionPercent.toString())
    }
    ToolScaffold(stringResource(R.string.tool_compress), onBack, Availability.OFFLINE_WITH_ONLINE_ENHANCEMENT) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult) { done -> if (done.documentIds.isNotEmpty()) onDone(done) }) return@ToolScaffold
        InputFileList(inputs)
        OutlinedButton(onClick = { pickers.pickOne(Mime.PDF) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (inputs.isEmpty()) R.string.action_choose_pdf else R.string.action_change_file)) }
        Text(stringResource(R.string.compress_preset), style = MaterialTheme.typography.titleSmall)
        CompressionPreset.entries.forEach { p ->
            FilterChip(selected = preset == p, onClick = { preset = p }, label = {
                Text(stringResource(when (p) {
                    CompressionPreset.HIGH_QUALITY -> R.string.compress_high_quality
                    CompressionPreset.BALANCED -> R.string.compress_balanced
                    CompressionPreset.SMALL_FILE -> R.string.compress_small
                }))
            }, modifier = Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.compress_use_cloud), modifier = Modifier.weight(1f))
            Switch(checked = useCloud, onCheckedChange = { useCloud = it }, enabled = vm.gate.serverConfigured)
        }
        if (!vm.gate.serverConfigured) Text(stringResource(R.string.error_server_not_configured), style = MaterialTheme.typography.bodySmall)
        if (useCloud) AvailabilityPill(Availability.INTERNET_REQUIRED)
        InfoBanner(stringResource(R.string.compress_explain))
        PrimaryAction(stringResource(R.string.action_compress), enabled = inputs.isNotEmpty()) { vm.compress(preset, useCloud, reportText) }
    }
    notSmaller?.let { (_, r) ->
        AlertDialog(
            onDismissRequest = vm::keepOriginal,
            title = { Text(stringResource(R.string.compress_not_smaller_title)) },
            text = { Text(stringResource(R.string.compress_not_smaller_body, formatSize(context, r.originalBytes), formatSize(context, r.outputBytes))) },
            confirmButton = { TextButton(onClick = vm::keepOriginal) { Text(stringResource(R.string.compress_keep_original)) } },
            dismissButton = { TextButton(onClick = { vm.keepCompressedAnyway(onSavedSingle) }) { Text(stringResource(R.string.compress_save_anyway)) } },
        )
    }
    if (pendingCloud != null) {
        CloudConsentDialog(
            whatIsUploaded = stringResource(R.string.consent_upload_pdf),
            onAccept = { remember -> scope.launch { vm.gate.accept(remember) } },
            onDecline = vm.gate::decline,
        )
    }
}
