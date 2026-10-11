package com.oneminutedocs.app.feature.handwriting

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.CloudGate
import com.oneminutedocs.app.common.OpState
import com.oneminutedocs.app.common.OperationViewModel
import com.oneminutedocs.app.common.OutputSaver
import com.oneminutedocs.app.common.progress
import com.oneminutedocs.app.common.toDocError
import com.oneminutedocs.app.feature.scanner.ScanSessionStore
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.drafts.DraftRepository
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.core.network.AiRequestDto
import com.oneminutedocs.core.network.BackendClient
import com.oneminutedocs.core.network.CloudJobRunner
import com.oneminutedocs.core.network.UploadFile
import com.oneminutedocs.document.Block
import com.oneminutedocs.document.DocxOptions
import com.oneminutedocs.document.DocxWriter
import com.oneminutedocs.document.MarkdownLite
import com.oneminutedocs.document.NoteStructurer
import com.oneminutedocs.document.RichDocument
import com.oneminutedocs.domain.DocError
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.Outcome
import com.oneminutedocs.processing.image.BitmapLoader
import com.oneminutedocs.processing.image.PageFilter
import com.oneminutedocs.processing.ocr.OcrEngine
import com.oneminutedocs.processing.ocr.OcrScript
import com.oneminutedocs.processing.pdf.ImagePage
import com.oneminutedocs.processing.pdf.ImagesPdfOptions
import com.oneminutedocs.processing.pdf.ImagesToPdf
import com.oneminutedocs.processing.pdf.PdfPageRenderer
import com.oneminutedocs.processing.pdf.TextPdfOptions
import com.oneminutedocs.processing.pdf.TextPdfWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject

enum class HwStep { PAGES, OUTPUT, RECOGNIZE, REVIEW }
enum class HwOutput { SCANNED_PDF, EDITABLE_PDF, WORD, AI_NOTES }
enum class HwEngine { DEVICE, CLOUD }
enum class HwLanguage(val api: String, val script: OcrScript) { ENGLISH("en", OcrScript.LATIN), HINDI("hi", OcrScript.DEVANAGARI) }

/** AI transformations for recognised notes; the original scan is never altered. */
enum class NotesAction(val task: String) {
    ORGANIZE("organize_notes"), SUMMARY("summarize"), REVISION("extract_key_points"), QUESTIONS("generate_questions"),
    FLASHCARDS("generate_flashcards"), GRAMMAR("fix_grammar"), TRANSLATE("translate"),
}

@HiltViewModel
class HandwritingViewModel @Inject constructor(
    savedState: SavedStateHandle,
    workspaces: WorkspaceManager,
    val store: ScanSessionStore,
    private val repository: DocumentRepository,
    private val ocr: OcrEngine,
    private val cloud: CloudJobRunner,
    private val backend: BackendClient,
    private val images: ImagesToPdf,
    private val textPdf: TextPdfWriter,
    private val drafts: DraftRepository,
    private val saver: OutputSaver,
    val gate: CloudGate,
) : OperationViewModel(workspaces) {
    val sessionId: String = savedState.get<String>("session") ?: store.newSession().also { savedState["session"] = it }
    val pages = store.pages(sessionId)
    val step = MutableStateFlow(HwStep.PAGES)
    val output = MutableStateFlow(HwOutput.EDITABLE_PDF)
    val language = MutableStateFlow(HwLanguage.ENGLISH)
    /** Recognised text per page, editable by the user. */
    val pageTexts = MutableStateFlow<List<String>>(emptyList())
    val recognizedBy = MutableStateFlow<HwEngine?>(null)
    val importError = MutableStateFlow<DocError?>(null)
    val busy = MutableStateFlow(false)

    fun importImages(uris: List<Uri>) {
        viewModelScope.launch {
            busy.value = true
            for (uri in uris) {
                when (val r = repository.importToWorkspace(uri, workspace, setOf(DocumentFormat.JPEG, DocumentFormat.PNG, DocumentFormat.WEBP, DocumentFormat.HEIF))) {
                    is Outcome.Success -> {
                        val dest = store.newImageFile(sessionId)
                        withContext(Dispatchers.IO) { r.value.file.copyTo(dest, overwrite = true); r.value.file.delete() }
                        store.addPage(sessionId, dest)
                    }
                    is Outcome.Failure -> importError.value = r.error
                }
            }
            busy.value = false
        }
    }

    /** Renders each PDF page (200 dpi) into the session as a page image. */
    fun importPdf(uri: Uri) {
        viewModelScope.launch {
            busy.value = true
            try {
                when (val r = repository.importToWorkspace(uri, workspace, setOf(DocumentFormat.PDF))) {
                    is Outcome.Success -> PdfPageRenderer.open(r.value.file).use { renderer ->
                        if (renderer.pageCount > 60) throw DocException(ErrorCode.TOO_MANY_PAGES)
                        for (i in 0 until renderer.pageCount) {
                            val bmp = renderer.renderAtDpi(i, 200)
                            val dest = store.newImageFile(sessionId)
                            withContext(Dispatchers.IO) { BitmapLoader.saveJpeg(bmp, dest, 90) }
                            bmp.recycle()
                            store.addPage(sessionId, dest)
                        }
                    }
                    is Outcome.Failure -> importError.value = r.error
                }
            } catch (t: Throwable) {
                importError.value = t.toDocError()
            } finally {
                busy.value = false
            }
        }
    }

    fun chooseOutput(o: HwOutput) {
        output.value = o
        if (o == HwOutput.SCANNED_PDF) createScannedPdf() else step.value = HwStep.RECOGNIZE
    }

    // ---- Option A: scanned PDF (no recognition) ----
    private fun createScannedPdf() = runOperation(R.string.op_creating_pdf) {
        val files = store.exportJpegs(sessionId, workspace.dir) { i, n -> progress(i / n.toFloat() * 0.6f) }
        val out = workspace.newFile("pdf")
        images.write(files.map { ImagePage(it) }, ImagesPdfOptions(), out) { progress(0.6f + it * 0.35f) }
        OpState.Done(listOf(saver.save(out, "handwritten-notes", DocumentFormat.PDF, "handwriting_scanned_pdf").id))
    }

    // ---- Recognition ----
    fun recognize(engine: HwEngine) {
        if (engine == HwEngine.CLOUD) viewModelScope.launch { gate.request { runRecognition(HwEngine.CLOUD) } } else runRecognition(HwEngine.DEVICE)
    }

    private fun runRecognition(engine: HwEngine) = runOperation(if (engine == HwEngine.CLOUD) R.string.op_recognizing_cloud else R.string.op_recognizing) {
        val list = pages.value
        if (list.isEmpty()) throw DocException(ErrorCode.NOTHING_TO_DO)
        val texts: List<String> = if (engine == HwEngine.DEVICE) {
            list.mapIndexed { i, p ->
                // Recognition works best on the enhanced colour/grey image, not on hard black-and-white.
                val bmp = store.render(p, 2000, if (p.filter == PageFilter.BLACK_AND_WHITE) PageFilter.GRAYSCALE else p.filter)
                try { ocr.recognize(bmp, language.value.script).text } finally { bmp.recycle(); progress((i + 1f) / list.size) }
            }
        } else {
            val files = store.exportJpegs(sessionId, workspace.dir, maxSide = 2000) { i, n -> progress(i / n.toFloat() * 0.3f) }
            val out = workspace.newFile("txt")
            val options = buildJsonObject {
                put("language", JsonPrimitive(language.value.api))
                put("consent", JsonPrimitive(true))
            }
            cloud.run("handwriting_recognize", files.mapIndexed { i, f -> UploadFile(f, "page-${i + 1}.jpg", "image/jpeg") }, options, out, 10L * 1024 * 1024) { pr ->
                progress(0.3f + (pr.percent ?: 0) / 100f * 0.7f)
            }
            withContext(Dispatchers.IO) { out.readText() }.split('\u000C').map { it.trim('\n') }
        }
        pageTexts.value = texts
        recognizedBy.value = engine
        step.value = HwStep.REVIEW
        OpState.Done(emptyList())
    }

    fun editPage(i: Int, text: String) {
        pageTexts.value = pageTexts.value.toMutableList().also { if (i in it.indices) it[i] = text }
    }

    private fun structured(): RichDocument {
        val blocks = mutableListOf<Block>()
        pageTexts.value.forEachIndexed { i, t ->
            if (i > 0) blocks += Block.PageBreak
            blocks += NoteStructurer.structure(t).blocks
        }
        return RichDocument(blocks)
    }

    // ---- Option B: editable PDF ----
    fun createEditablePdf(pageLabel: (Int) -> String) = runOperation(R.string.op_creating_pdf) {
        val doc = structured()
        if (doc.isBlank) throw DocException(ErrorCode.NOTHING_TO_DO)
        val out = workspace.newFile("pdf")
        textPdf.write(doc, TextPdfOptions(pageLabel = pageLabel), out) { progress(it) }
        OpState.Done(listOf(saver.save(out, "handwritten-notes-text", DocumentFormat.PDF, "handwriting_editable_pdf").id))
    }

    // ---- Option C: Word ----
    fun createWord() = runOperation(R.string.op_creating_docx) {
        val doc = structured()
        if (doc.isBlank) throw DocException(ErrorCode.NOTHING_TO_DO)
        val out = workspace.newFile("docx")
        withContext(Dispatchers.IO) { out.outputStream().use { DocxWriter(DocxOptions(language = if (language.value == HwLanguage.HINDI) "hi-IN" else "en-IN")).write(doc, it) } }
        OpState.Done(listOf(saver.save(out, "handwritten-notes", DocumentFormat.DOCX, "handwriting_docx").id))
    }

    fun openInEditor(onDraft: (String) -> Unit) {
        viewModelScope.launch { onDraft(drafts.create("handwritten-notes", MarkdownLite.toMarkdown(structured()), aiGenerated = false, sourceOperation = "handwriting").id) }
    }

    // ---- Option D: AI-organised notes ----
    fun aiNotes(action: NotesAction, onDraft: (String) -> Unit) {
        viewModelScope.launch { gate.request { runAi(action, onDraft) } }
    }

    private fun runAi(action: NotesAction, onDraft: (String) -> Unit) = runOperation(R.string.ai_working) {
        val text = pageTexts.value.joinToString("\n\n").trim()
        if (text.isEmpty()) throw DocException(ErrorCode.NOTHING_TO_DO)
        val lang = language.value.api
        val result = backend.aiGenerate(
            AiRequestDto(
                task = action.task,
                language = lang,
                text = text,
                targetLanguage = if (action == NotesAction.TRANSLATE) (if (lang == "hi") "en" else "hi") else null,
            ),
        )
        val draft = drafts.create("notes-${action.name.lowercase()}", result.markdown, aiGenerated = true, sourceOperation = "handwriting_ai")
        onDraft(draft.id)
        OpState.Done(emptyList())
    }

    fun back() {
        step.value = when (step.value) {
            HwStep.REVIEW -> HwStep.RECOGNIZE
            HwStep.RECOGNIZE -> HwStep.OUTPUT
            else -> HwStep.PAGES
        }
    }
}
