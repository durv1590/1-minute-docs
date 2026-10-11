package com.oneminutedocs.app.feature.ocr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
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
import com.oneminutedocs.app.common.progress
import com.oneminutedocs.app.common.rememberPickers
import com.oneminutedocs.app.navigation.Routes
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.drafts.DraftRepository
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.core.network.CloudJobRunner
import com.oneminutedocs.core.network.UploadFile
import com.oneminutedocs.document.Block
import com.oneminutedocs.document.DocxWriter
import com.oneminutedocs.document.MarkdownLite
import com.oneminutedocs.document.NoteStructurer
import com.oneminutedocs.document.RichDocument
import com.oneminutedocs.domain.Availability
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.FileNames
import com.oneminutedocs.processing.image.BitmapLoader
import com.oneminutedocs.processing.ocr.OcrEngine
import com.oneminutedocs.processing.ocr.OcrScript
import com.oneminutedocs.processing.pdf.PdfPageRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject

enum class OcrLanguage(val script: OcrScript, val tesseract: String) { ENGLISH(OcrScript.LATIN, "eng"), HINDI(OcrScript.DEVANAGARI, "hin+eng") }

@HiltViewModel
class OcrViewModel @Inject constructor(
    savedState: SavedStateHandle,
    workspaces: WorkspaceManager,
    repository: DocumentRepository,
    private val ocr: OcrEngine,
    private val cloud: CloudJobRunner,
    private val drafts: DraftRepository,
    private val saver: OutputSaver,
    val gate: CloudGate,
) : InputsViewModel(workspaces, repository) {
    override val allowed = setOf(DocumentFormat.PDF, DocumentFormat.JPEG, DocumentFormat.PNG, DocumentFormat.WEBP, DocumentFormat.HEIF)
    /** Recognised text, editable; pages separated by a form feed. */
    val text = MutableStateFlow<String?>(null)

    init {
        preload(savedState.toRoute<Routes.Ocr>().documentId)
    }

    override fun onInputsChanged() {
        text.value = null
    }

    fun recognizeOnDevice(lang: OcrLanguage) = runOperation(R.string.op_recognizing) {
        val input = inputs.value.single()
        val pages = mutableListOf<String>()
        if (input.format == DocumentFormat.PDF) {
            PdfPageRenderer.open(input.file).use { r ->
                for (i in 0 until r.pageCount) {
                    val bmp = r.renderAtDpi(i, 200)
                    try { pages += ocr.recognize(bmp, lang.script).text } finally { bmp.recycle() }
                    progress((i + 1f) / r.pageCount)
                }
            }
        } else {
            val bmp = withContext(Dispatchers.Default) { BitmapLoader.decode(input.file, 2400) }
            try { pages += ocr.recognize(bmp, lang.script).text } finally { bmp.recycle() }
        }
        text.value = pages.joinToString("\n\u000C\n")
        OpState.Done(emptyList())
    }

    fun recognizeInCloud(lang: OcrLanguage, searchablePdf: Boolean) {
        viewModelScope.launch { gate.request { runCloud(lang, searchablePdf) } }
    }

    private fun runCloud(lang: OcrLanguage, searchablePdf: Boolean) = runOperation(R.string.op_recognizing_cloud) {
        val input = inputs.value.single()
        val out = workspace.newFile(if (searchablePdf) "pdf" else "txt")
        val options = buildJsonObject {
            put("language", JsonPrimitive(lang.tesseract))
            put("output", JsonPrimitive(if (searchablePdf) "pdf" else "txt"))
        }
        cloud.run("ocr", listOf(UploadFile(input.file, input.displayName, input.format.mimeType)), options, out, repository.limits.maxPdfBytes) { progress(it.percent?.div(100f)) }
        if (searchablePdf) {
            OpState.Done(listOf(saver.save(out, FileNames.derived(input.displayName, "searchable", DocumentFormat.PDF), DocumentFormat.PDF, "ocr_searchable_pdf").id))
        } else {
            text.value = withContext(Dispatchers.IO) { out.readText() }
            OpState.Done(emptyList())
        }
    }

    private fun structured(): RichDocument {
        val t = text.value ?: throw DocException(ErrorCode.NOTHING_TO_DO)
        val blocks = mutableListOf<Block>()
        t.split('\u000C').forEachIndexed { i, page ->
            if (i > 0) blocks += Block.PageBreak
            blocks += NoteStructurer.structure(page).blocks
        }
        return RichDocument(blocks)
    }

    fun saveTxt() = runOperation(R.string.op_saving) {
        val input = inputs.value.single()
        val out = workspace.newFile("txt")
        withContext(Dispatchers.IO) { out.writeText(text.value.orEmpty().replace("\u000C", ""), Charsets.UTF_8) }
        OpState.Done(listOf(saver.save(out, FileNames.derived(input.displayName, "text", DocumentFormat.TXT), DocumentFormat.TXT, "ocr").id))
    }

    fun saveDocx() = runOperation(R.string.op_saving) {
        val input = inputs.value.single()
        val out = workspace.newFile("docx")
        val doc = structured()
        withContext(Dispatchers.IO) { out.outputStream().use { DocxWriter().write(doc, it) } }
        OpState.Done(listOf(saver.save(out, FileNames.derived(input.displayName, "text", DocumentFormat.DOCX), DocumentFormat.DOCX, "ocr").id))
    }

    fun openInEditor(onDraft: (String) -> Unit) {
        viewModelScope.launch {
            val input = inputs.value.single()
            onDraft(drafts.create(FileNames.baseName(input.displayName), MarkdownLite.toMarkdown(structured()), aiGenerated = false, sourceOperation = "ocr").id)
        }
    }
}

@Composable
fun OcrScreen(onBack: () -> Unit, onDone: (OpState.Done) -> Unit, onEditor: (String) -> Unit, vm: OcrViewModel = hiltViewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val pending by vm.gate.pending.collectAsStateWithLifecycle()
    var lang by rememberSaveable { mutableStateOf(OcrLanguage.ENGLISH) }
    var query by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val pickers = rememberPickers(vm::addUris)
    ToolScaffold(stringResource(R.string.tool_ocr), onBack, Availability.OFFLINE_WITH_ONLINE_ENHANCEMENT) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult) { if (it.documentIds.isNotEmpty()) onDone(it) else vm.dismissResult() }) return@ToolScaffold
        InputFileList(inputs)
        OutlinedButton(onClick = { pickers.pickOne(Mime.PDF_OR_IMAGES) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (inputs.isEmpty()) R.string.action_choose_file else R.string.action_change_file)) }
        Text(stringResource(R.string.label_language), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = lang == OcrLanguage.ENGLISH, onClick = { lang = OcrLanguage.ENGLISH }, label = { Text("English") })
            FilterChip(selected = lang == OcrLanguage.HINDI, onClick = { lang = OcrLanguage.HINDI }, label = { Text("हिन्दी + English") })
        }
        InfoBanner(stringResource(R.string.ocr_explain))
        val t = text
        if (t == null) {
            PrimaryAction(stringResource(R.string.action_recognize_device), enabled = inputs.isNotEmpty()) { vm.recognizeOnDevice(lang) }
            AvailabilityPill(Availability.OFFLINE)
            OutlinedButton(onClick = { vm.recognizeInCloud(lang, false) }, enabled = inputs.isNotEmpty() && vm.gate.serverConfigured, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_recognize_cloud)) }
            OutlinedButton(onClick = { vm.recognizeInCloud(lang, true) }, enabled = inputs.isNotEmpty() && vm.gate.serverConfigured, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_searchable_pdf)) }
            AvailabilityPill(Availability.INTERNET_REQUIRED)
        } else {
            if (t.isBlank()) InfoBanner(stringResource(R.string.ocr_no_text))
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.ocr_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (query.isNotBlank()) Text(stringResource(R.string.ocr_matches, Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(t).count()))
            OutlinedTextField(value = t.replace("\u000C", "———"), onValueChange = { vm.text.value = it.replace("———", "\u000C") }, label = { Text(stringResource(R.string.ocr_result)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(t.replace("\u000C", "\n"))) }) { Text(stringResource(R.string.action_copy)) }
                OutlinedButton(onClick = vm::saveTxt) { Text("TXT") }
                OutlinedButton(onClick = vm::saveDocx) { Text("DOCX") }
            }
            PrimaryAction(stringResource(R.string.action_open_editor)) { vm.openInEditor(onEditor) }
        }
    }
    if (pending != null) CloudConsentDialog(stringResource(R.string.consent_upload_ocr), onAccept = { r -> scope.launch { vm.gate.accept(r) } }, onDecline = vm.gate::decline)
}
