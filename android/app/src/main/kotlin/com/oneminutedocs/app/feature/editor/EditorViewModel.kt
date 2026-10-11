package com.oneminutedocs.app.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.CloudGate
import com.oneminutedocs.app.di.ApplicationScope
import com.oneminutedocs.app.common.OpState
import com.oneminutedocs.app.common.OperationViewModel
import com.oneminutedocs.app.common.OutputSaver
import com.oneminutedocs.app.common.progress
import com.oneminutedocs.app.common.toDocError
import com.oneminutedocs.app.navigation.Routes
import com.oneminutedocs.core.data.drafts.Draft
import com.oneminutedocs.core.data.drafts.DraftRepository
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.core.data.settings.SettingsRepository
import com.oneminutedocs.core.network.AiRequestDto
import com.oneminutedocs.core.network.BackendClient
import com.oneminutedocs.document.DocxOptions
import com.oneminutedocs.document.DocxWriter
import com.oneminutedocs.document.MarkdownLite
import com.oneminutedocs.document.TextMetrics
import com.oneminutedocs.domain.DocError
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.Margins
import com.oneminutedocs.domain.Orientation
import com.oneminutedocs.domain.PageSize
import com.oneminutedocs.processing.pdf.FontFamily
import com.oneminutedocs.processing.pdf.TextPdfOptions
import com.oneminutedocs.processing.pdf.TextPdfWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

data class ExportSettings(
    val format: DocumentFormat = DocumentFormat.PDF,
    val pageSize: PageSize = PageSize.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
    val margins: Margins = Margins.NORMAL,
    val font: FontFamily = FontFamily.SANS,
    val fontSize: Float = 11f,
    val pageNumbers: Boolean = true,
    val header: String = "",
    val footer: String = "",
    val fileName: String = "",
)

/** AI actions available in the editor; they replace the text and can be undone. */
enum class AiAction(val task: String) { REWRITE("rewrite"), GRAMMAR("fix_grammar"), SUMMARIZE("summarize"), TRANSLATE_HI("translate"), TRANSLATE_EN("translate"), FORMAL("change_tone"), ORGANIZE("organize_notes") }

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    workspaces: WorkspaceManager,
    private val drafts: DraftRepository,
    private val settings: SettingsRepository,
    private val textPdf: TextPdfWriter,
    private val saver: OutputSaver,
    private val backend: BackendClient,
    val gate: CloudGate,
    @ApplicationScope private val appScope: CoroutineScope,
) : OperationViewModel(workspaces) {
    private val draftId = savedState.toRoute<Routes.Editor>().draftId
    private var draft: Draft? = null

    private val _text = MutableStateFlow(TextFieldValue(""))
    val text: StateFlow<TextFieldValue> = _text.asStateFlow()
    val title = MutableStateFlow("")
    val aiGenerated = MutableStateFlow(false)
    val export = MutableStateFlow(ExportSettings())
    val missing = MutableStateFlow(false)
    val aiBusy = MutableStateFlow(false)
    val aiError = MutableStateFlow<DocError?>(null)

    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var lastSnapshot = ""
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val d = drafts.load(draftId)
            if (d == null) {
                missing.value = true
                return@launch
            }
            draft = d
            _text.value = TextFieldValue(d.markdown)
            lastSnapshot = d.markdown
            title.value = d.title
            aiGenerated.value = d.aiGenerated
            val s = settings.current()
            export.value = export.value.copy(pageSize = s.defaultPageSize, margins = s.defaultMargins, fileName = d.title)
        }
    }

    val wordCount: Int get() = TextMetrics.wordCount(_text.value.text)

    fun onTextChange(v: TextFieldValue) {
        if (v.text != _text.value.text) {
            // Snapshot at word boundaries so undo steps are meaningful but not per keystroke.
            if (v.text.length - lastSnapshot.length !in -1..1 || v.text.endsWith(" ") || v.text.endsWith("\n")) pushUndo()
        }
        _text.value = v
        scheduleSave()
    }

    private fun pushUndo() {
        if (lastSnapshot != _text.value.text) {
            undo.addLast(lastSnapshot)
            if (undo.size > 100) undo.removeFirst()
            redo.clear()
            lastSnapshot = _text.value.text
        }
    }

    fun undo() {
        pushUndo()
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(_text.value.text)
        setText(previous)
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(_text.value.text)
        setText(next)
    }

    private fun setText(t: String) {
        lastSnapshot = t
        _text.value = TextFieldValue(t, TextRange(t.length))
        scheduleSave()
    }

    /** Toggles a line prefix (heading/list) on the line containing the cursor. */
    fun applyLinePrefix(prefix: String) {
        val v = _text.value
        val t = v.text
        val start = t.lastIndexOf('\n', (v.selection.start - 1).coerceAtLeast(0)).let { if (it < 0 || v.selection.start == 0) 0 else it + 1 }
        val lineEnd = t.indexOf('\n', start).let { if (it < 0) t.length else it }
        val line = t.substring(start, lineEnd)
        val stripped = line.replace(Regex("^(#{1,3} |- |\\d+\\. )"), "")
        val newLine = if (line.startsWith(prefix)) stripped else prefix + stripped
        pushUndo()
        val updated = t.substring(0, start) + newLine + t.substring(lineEnd)
        val cursor = (start + newLine.length).coerceAtMost(updated.length)
        lastSnapshot = updated
        _text.value = TextFieldValue(updated, TextRange(cursor))
        scheduleSave()
    }

    /** Wraps the selection with an inline marker (** or *). */
    fun wrapSelection(marker: String) {
        val v = _text.value
        val s = v.selection
        if (s.collapsed) return
        pushUndo()
        val t = v.text
        val updated = t.substring(0, s.min) + marker + t.substring(s.min, s.max) + marker + t.substring(s.max)
        lastSnapshot = updated
        _text.value = TextFieldValue(updated, TextRange(s.max + 2 * marker.length))
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            persist()
        }
    }

    private suspend fun persist() {
        val d = draft ?: return
        val updated = d.copy(markdown = _text.value.text, title = title.value.ifBlank { d.title }, aiGenerated = aiGenerated.value, updatedAt = System.currentTimeMillis())
        drafts.save(updated)
        draft = updated
    }

    fun runAi(action: AiAction) {
        if (_text.value.text.isBlank()) return
        viewModelScope.launch { gate.request { performAi(action) } }
    }

    private fun performAi(action: AiAction) {
        viewModelScope.launch {
            aiBusy.value = true
            aiError.value = null
            try {
                val lang = if (Locale.getDefault().language == "hi") "hi" else "en"
                val req = AiRequestDto(
                    task = action.task,
                    language = when (action) { AiAction.TRANSLATE_HI -> "en"; AiAction.TRANSLATE_EN -> "hi"; else -> detectLanguage(_text.value.text) ?: lang },
                    tone = if (action == AiAction.FORMAL) "formal" else null,
                    text = _text.value.text,
                    targetLanguage = when (action) { AiAction.TRANSLATE_HI -> "hi"; AiAction.TRANSLATE_EN -> "en"; else -> null },
                )
                val result = backend.aiGenerate(req)
                pushUndo()
                setText(result.markdown)
                aiGenerated.value = true
                if ("TRUNCATED" in result.warnings) aiError.value = DocError(ErrorCode.TOO_LARGE, "truncated")
            } catch (t: Throwable) {
                aiError.value = t.toDocError()
            } finally {
                aiBusy.value = false
            }
        }
    }

    fun clearAiError() {
        aiError.value = null
    }

    fun exportDocument(pageLabel: (Int) -> String) = runOperation(R.string.op_exporting_document) {
        persist()
        val s = export.value
        val doc = MarkdownLite.parse(_text.value.text, title.value)
        if (doc.isBlank) throw DocException(ErrorCode.NOTHING_TO_DO)
        val name = s.fileName.ifBlank { title.value.ifBlank { "document" } }
        val source = draft?.sourceOperation ?: "editor"
        val saved = when (s.format) {
            DocumentFormat.DOCX -> {
                val out = workspace.newFile("docx")
                withContext(Dispatchers.IO) {
                    out.outputStream().use {
                        DocxWriter(DocxOptions(pageSize = s.pageSize, orientation = s.orientation, margins = s.margins, title = title.value, language = if (detectLanguage(_text.value.text) == "hi") "hi-IN" else "en-IN")).write(doc, it)
                    }
                }
                progress(0.9f)
                saver.save(out, name, DocumentFormat.DOCX, source, aiGenerated.value)
            }
            else -> {
                val out = workspace.newFile("pdf")
                textPdf.write(
                    doc,
                    TextPdfOptions(s.pageSize, s.orientation, s.margins, s.font, s.fontSize, pageNumbers = s.pageNumbers, header = s.header.ifBlank { null }, footer = s.footer.ifBlank { null }, pageLabel = pageLabel),
                    out,
                ) { progress(it) }
                saver.save(out, name, DocumentFormat.PDF, source, aiGenerated.value)
            }
        }
        OpState.Done(listOf(saved.id))
    }

    override fun onCleared() {
        saveJob?.cancel()
        appScope.launch { persist() }
        super.onCleared()
    }

    companion object {
        /** "hi" when most letters are Devanagari, "en" when mostly Latin, else null. */
        fun detectLanguage(text: String): String? {
            var dev = 0; var lat = 0
            for (c in text) { if (c in 'ऀ'..'ॿ') dev++ else if (c in 'a'..'z' || c in 'A'..'Z') lat++ }
            return when { dev == 0 && lat == 0 -> null; dev >= lat -> "hi"; else -> "en" }
        }
    }
}
