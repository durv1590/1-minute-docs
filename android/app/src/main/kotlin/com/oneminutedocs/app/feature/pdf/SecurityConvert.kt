package com.oneminutedocs.app.feature.pdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.core.designsystem.InfoBanner
import com.oneminutedocs.core.network.CloudJobRunner
import com.oneminutedocs.core.network.UploadFile
import com.oneminutedocs.document.Block
import com.oneminutedocs.document.DocxOptions
import com.oneminutedocs.document.DocxReader
import com.oneminutedocs.document.DocxWriter
import com.oneminutedocs.document.NoteStructurer
import com.oneminutedocs.document.RichDocument
import com.oneminutedocs.domain.Availability
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.FileNames
import com.oneminutedocs.processing.ocr.OcrEngine
import com.oneminutedocs.processing.ocr.OcrScript
import com.oneminutedocs.processing.pdf.PdfEngine
import com.oneminutedocs.processing.pdf.PdfPageRenderer
import com.oneminutedocs.processing.pdf.PdfPermissions
import com.oneminutedocs.processing.pdf.TextPdfOptions
import com.oneminutedocs.processing.pdf.TextPdfWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject

// ---------------------------------------------------------------- Protect / unlock

@HiltViewModel
class SecurityViewModel @Inject constructor(
    savedState: SavedStateHandle,
    workspaces: WorkspaceManager,
    repository: DocumentRepository,
    private val pdf: PdfEngine,
    private val saver: OutputSaver,
) : InputsViewModel(workspaces, repository) {
    override val allowed = setOf(DocumentFormat.PDF)
    val unlockMode = savedState.toRoute<Routes.Security>().unlock

    /** Passwords are held only for the duration of the call and never stored or logged. */
    fun protect(password: CharArray, permissions: PdfPermissions) = runOperation(R.string.op_protecting) {
        val input = inputs.value.single()
        val out = workspace.newFile("pdf")
        try {
            pdf.protect(input.file, out, String(password), null, permissions)
        } finally {
            password.fill('\u0000')
        }
        // Validation re-opened the file with the password inside protect(); record it without re-validation.
        OpState.Done(listOf(saver.save(out, FileNames.derived(input.displayName, "protected", DocumentFormat.PDF), DocumentFormat.PDF, "protect").id))
    }

    fun unlock(password: CharArray) = runOperation(R.string.op_unlocking) {
        val input = inputs.value.single()
        val out = workspace.newFile("pdf")
        try {
            pdf.unlock(input.file, String(password), out)
        } finally {
            password.fill('\u0000')
        }
        OpState.Done(listOf(saver.save(out, FileNames.derived(input.displayName, "unlocked", DocumentFormat.PDF), DocumentFormat.PDF, "unlock").id))
    }
}

@Composable
fun SecurityScreen(onBack: () -> Unit, onDone: (OpState.Done) -> Unit, vm: SecurityViewModel = hiltViewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    // Not saveable on purpose: passwords must not be written into saved instance state.
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var allowPrint by rememberSaveable { mutableStateOf(true) }
    var allowCopy by rememberSaveable { mutableStateOf(true) }
    var allowEdit by rememberSaveable { mutableStateOf(false) }
    val pickers = rememberPickers(vm::addUris)
    ToolScaffold(stringResource(if (vm.unlockMode) R.string.tool_unlock else R.string.tool_protect), onBack, Availability.OFFLINE) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult) { password = ""; confirm = ""; onDone(it) }) return@ToolScaffold
        InputFileList(inputs)
        OutlinedButton(onClick = { pickers.pickOne(Mime.PDF) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (inputs.isEmpty()) R.string.action_choose_pdf else R.string.action_change_file)) }
        InfoBanner(stringResource(if (vm.unlockMode) R.string.unlock_explain else R.string.protect_explain))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.label_password)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!vm.unlockMode) {
            OutlinedTextField(
                value = confirm, onValueChange = { confirm = it }, label = { Text(stringResource(R.string.label_confirm_password)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = confirm.isNotEmpty() && confirm != password, modifier = Modifier.fillMaxWidth(),
            )
            if (password.isNotEmpty() && password.length < 6) Text(stringResource(R.string.protect_weak), color = MaterialTheme.colorScheme.error)
            PermissionRow(stringResource(R.string.permission_print), allowPrint) { allowPrint = it }
            PermissionRow(stringResource(R.string.permission_copy), allowCopy) { allowCopy = it }
            PermissionRow(stringResource(R.string.permission_edit), allowEdit) { allowEdit = it }
            PrimaryAction(stringResource(R.string.action_protect), enabled = inputs.isNotEmpty() && password.length >= 6 && password == confirm) {
                vm.protect(password.toCharArray(), PdfPermissions(allowPrint, allowCopy, allowEdit))
            }
        } else {
            PrimaryAction(stringResource(R.string.action_unlock), enabled = inputs.isNotEmpty() && password.isNotEmpty()) { vm.unlock(password.toCharArray()) }
        }
    }
}

@Composable
private fun PermissionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- Conversion (PDF ⇄ Word)

enum class ConversionMode { DEVICE, CLOUD }

@HiltViewModel
class ConvertViewModel @Inject constructor(
    savedState: SavedStateHandle,
    workspaces: WorkspaceManager,
    repository: DocumentRepository,
    private val pdf: PdfEngine,
    private val ocr: OcrEngine,
    private val textPdf: TextPdfWriter,
    private val cloud: CloudJobRunner,
    private val saver: OutputSaver,
    val gate: CloudGate,
) : InputsViewModel(workspaces, repository) {
    val toWord = savedState.toRoute<Routes.Convert>().toWord
    override val allowed: Set<DocumentFormat> =
        if (toWord) setOf(DocumentFormat.PDF) else setOf(DocumentFormat.DOCX, DocumentFormat.DOC, DocumentFormat.ODT, DocumentFormat.RTF)

    fun convert(mode: ConversionMode, notice: (Int) -> String) {
        if (mode == ConversionMode.CLOUD) viewModelScope.launch { gate.request { runCloud(notice) } } else runDevice(notice)
    }

    private fun runCloud(notice: (Int) -> String) = runOperation(R.string.op_converting_cloud) {
        val input = inputs.value.single()
        val outFormat = if (toWord) DocumentFormat.DOCX else DocumentFormat.PDF
        val out = workspace.newFile(outFormat.extension)
        val options: JsonObject = if (toWord) buildJsonObject { put("ocrLanguage", JsonPrimitive("eng+hin")) } else JsonObject(emptyMap())
        val result = cloud.run(if (toWord) "pdf_to_docx" else "office_to_pdf", listOf(UploadFile(input.file, input.displayName, input.format.mimeType)), options, out, repository.limits.maxOfficeBytes * 4) { p ->
            progress(p.percent?.div(100f))
        }
        val mode = result.job.output?.meta?.get("mode")?.toString()?.trim('"')
        val doc = saver.save(out, FileNames.withExtension(input.displayName, outFormat), outFormat, if (toWord) "pdf_to_word_cloud" else "word_to_pdf_cloud")
        val message = when {
            !toWord -> null
            mode == "ocr_text" -> notice(R.string.convert_notice_scanned)
            else -> notice(R.string.convert_notice_layout)
        }
        OpState.Done(listOf(doc.id), message)
    }

    private fun runDevice(notice: (Int) -> String) = runOperation(R.string.op_converting) {
        val input = inputs.value.single()
        if (toWord) {
            val pages = pdf.extractText(input.file)
            val chars = pages.sumOf { it.count { c -> !c.isWhitespace() } }
            val scanned = chars < 25 * pages.size
            val texts = if (scanned) ocrPdf(input.file) else pages
            val blocks = mutableListOf<Block>()
            texts.forEachIndexed { i, t ->
                if (i > 0) blocks += Block.PageBreak
                blocks += NoteStructurer.structure(t).blocks
            }
            val out = workspace.newFile("docx")
            withContext(Dispatchers.IO) { out.outputStream().use { DocxWriter(DocxOptions(title = FileNames.baseName(input.displayName))).write(RichDocument(blocks), it) } }
            val doc = saver.save(out, FileNames.withExtension(input.displayName, DocumentFormat.DOCX), DocumentFormat.DOCX, "pdf_to_word_device")
            OpState.Done(listOf(doc.id), notice(if (scanned) R.string.convert_notice_device_ocr else R.string.convert_notice_text_only))
        } else {
            if (input.format != DocumentFormat.DOCX) throw DocException(ErrorCode.UNSUPPORTED_FORMAT, "device mode supports DOCX only")
            val docModel = withContext(Dispatchers.IO) { input.file.inputStream().use { DocxReader.read(it) } }
            val out = workspace.newFile("pdf")
            textPdf.write(docModel, TextPdfOptions(pageNumbers = false), out) { progress(it) }
            val doc = saver.save(out, FileNames.withExtension(input.displayName, DocumentFormat.PDF), DocumentFormat.PDF, "word_to_pdf_device")
            OpState.Done(listOf(doc.id), notice(R.string.convert_notice_basic_pdf))
        }
    }

    /** Renders each page and runs on-device OCR (Devanagari model also reads Latin text). */
    private suspend fun com.oneminutedocs.app.common.OperationViewModel.OperationScope.ocrPdf(file: java.io.File): List<String> = PdfPageRenderer.open(file).use { r ->
        (0 until r.pageCount).map { i ->
            val bmp = r.renderAtDpi(i, 200)
            try {
                ocr.recognize(bmp, OcrScript.DEVANAGARI).text
            } finally {
                bmp.recycle()
                progress((i + 1f) / r.pageCount * 0.9f)
            }
        }
    }
}

@Composable
fun ConvertScreen(onBack: () -> Unit, onDone: (OpState.Done) -> Unit, vm: ConvertViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val pending by vm.gate.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(if (vm.gate.serverConfigured) ConversionMode.CLOUD else ConversionMode.DEVICE) }
    val pickers = rememberPickers(vm::addUris)
    ToolScaffold(
        stringResource(if (vm.toWord) R.string.tool_pdf_to_word else R.string.tool_word_to_pdf), onBack,
        Availability.OFFLINE_WITH_ONLINE_ENHANCEMENT,
    ) {
        if (OperationStatus(op, vm::cancel, vm::dismissResult, onDone)) return@ToolScaffold
        InputFileList(inputs)
        OutlinedButton(onClick = { pickers.pickOne(if (vm.toWord) Mime.PDF else Mime.OFFICE) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (inputs.isEmpty()) R.string.action_choose_file else R.string.action_change_file))
        }
        Text(stringResource(R.string.convert_mode), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == ConversionMode.CLOUD, enabled = vm.gate.serverConfigured, onClick = { mode = ConversionMode.CLOUD }, label = { Text(stringResource(R.string.convert_mode_cloud)) })
            FilterChip(selected = mode == ConversionMode.DEVICE, onClick = { mode = ConversionMode.DEVICE }, label = { Text(stringResource(R.string.convert_mode_device)) })
        }
        AvailabilityPill(if (mode == ConversionMode.CLOUD) Availability.INTERNET_REQUIRED else Availability.OFFLINE)
        InfoBanner(stringResource(when {
            vm.toWord && mode == ConversionMode.CLOUD -> R.string.convert_explain_cloud_word
            vm.toWord -> R.string.convert_explain_device_word
            mode == ConversionMode.CLOUD -> R.string.convert_explain_cloud_pdf
            else -> R.string.convert_explain_device_pdf
        }))
        if (!vm.gate.serverConfigured) Text(stringResource(R.string.error_server_not_configured), style = MaterialTheme.typography.bodySmall)
        PrimaryAction(stringResource(R.string.action_convert), enabled = inputs.isNotEmpty()) { vm.convert(mode) { context.getString(it) } }
    }
    if (pending != null) {
        CloudConsentDialog(
            whatIsUploaded = stringResource(if (vm.toWord) R.string.consent_upload_pdf else R.string.consent_upload_office),
            onAccept = { r -> scope.launch { vm.gate.accept(r) } },
            onDecline = vm.gate::decline,
        )
    }
}
