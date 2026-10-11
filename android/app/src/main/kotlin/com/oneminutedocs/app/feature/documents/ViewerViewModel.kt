package com.oneminutedocs.app.feature.documents

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.oneminutedocs.app.common.toDocError
import com.oneminutedocs.app.navigation.Routes
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.ManagedDocument
import com.oneminutedocs.document.DocxReader
import com.oneminutedocs.document.MarkdownLite
import com.oneminutedocs.domain.DocError
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.Outcome
import com.oneminutedocs.processing.image.BitmapLoader
import com.oneminutedocs.processing.pdf.PdfPageRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ViewerContent {
    data object Loading : ViewerContent
    data class Pdf(val pageCount: Int) : ViewerContent
    data class Image(val bitmap: Bitmap) : ViewerContent
    data class Text(val text: String, val isStructuredPreview: Boolean) : ViewerContent
    data class Error(val error: DocError) : ViewerContent
}

@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    val repository: DocumentRepository,
) : ViewModel() {
    private val id = savedState.toRoute<Routes.Viewer>().documentId
    private val _doc = MutableStateFlow<ManagedDocument?>(null)
    val doc: StateFlow<ManagedDocument?> = _doc.asStateFlow()
    private val _content = MutableStateFlow<ViewerContent>(ViewerContent.Loading)
    val content: StateFlow<ViewerContent> = _content.asStateFlow()
    val deleted = MutableStateFlow(false)

    private var renderer: PdfPageRenderer? = null
    /** Small cache: only a handful of rendered pages are kept in memory at once. */
    private val cache = object : LruCache<String, Bitmap>(6) {}

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val d = repository.get(id)
        _doc.value = d
        if (d == null) {
            _content.value = ViewerContent.Error(DocError(com.oneminutedocs.domain.ErrorCode.FILE_NOT_FOUND))
            return
        }
        _content.value = try {
            when {
                d.format == DocumentFormat.PDF -> {
                    val r = PdfPageRenderer.open(d.file)
                    renderer = r
                    ViewerContent.Pdf(r.pageCount)
                }
                d.format.isImage -> ViewerContent.Image(withContext(Dispatchers.IO) { BitmapLoader.decode(d.file, 2048) })
                d.format == DocumentFormat.TXT -> ViewerContent.Text(withContext(Dispatchers.IO) { d.file.readText().take(500_000) }, false)
                d.format == DocumentFormat.DOCX -> ViewerContent.Text(
                    withContext(Dispatchers.IO) { MarkdownLite.toMarkdown(d.file.inputStream().use { DocxReader.read(it) }) },
                    true,
                )
                else -> ViewerContent.Text("", true)
            }
        } catch (t: Throwable) {
            ViewerContent.Error(t.toDocError())
        }
    }

    suspend fun page(index: Int, widthPx: Int): Bitmap? {
        val r = renderer ?: return null
        val key = "$index@$widthPx"
        cache.get(key)?.let { return it }
        return runCatching { r.render(index, widthPx) }.getOrNull()?.also { cache.put(key, it) }
    }

    fun rename(name: String) {
        viewModelScope.launch {
            val res = repository.rename(id, name)
            if (res is Outcome.Success) _doc.value = res.value
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.delete(listOf(id))
            deleted.value = true
        }
    }

    override fun onCleared() {
        renderer?.close()
        cache.evictAll()
    }
}
