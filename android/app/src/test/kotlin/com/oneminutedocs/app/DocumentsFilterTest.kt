package com.oneminutedocs.app

import com.oneminutedocs.app.feature.documents.DateFilter
import com.oneminutedocs.app.feature.documents.DocumentsQuery
import com.oneminutedocs.app.feature.documents.DocumentsViewModel
import com.oneminutedocs.app.feature.documents.SortOrder
import com.oneminutedocs.app.feature.documents.TypeFilter
import com.oneminutedocs.app.feature.editor.EditorViewModel
import com.oneminutedocs.core.data.ManagedDocument
import com.oneminutedocs.domain.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DocumentsFilterTest {
    private val now = 10_000_000_000L
    private val day = 86_400_000L
    private fun doc(name: String, fmt: DocumentFormat, size: Long, ageDays: Int) =
        ManagedDocument(name, name, fmt, size, null, File(name), now - ageDays * day, now - ageDays * day, null, false)

    private val docs = listOf(
        doc("Report.pdf", DocumentFormat.PDF, 300, 0),
        doc("notes.docx", DocumentFormat.DOCX, 100, 3),
        doc("photo.jpg", DocumentFormat.JPEG, 900, 20),
        doc("old.pdf", DocumentFormat.PDF, 50, 90),
    )

    private fun names(q: DocumentsQuery) = DocumentsViewModel.apply(docs, q, now).map { it.displayName }

    @Test fun newestFirstByDefault() = assertEquals(listOf("Report.pdf", "notes.docx", "photo.jpg", "old.pdf"), names(DocumentsQuery()))
    @Test fun filtersByType() = assertEquals(listOf("Report.pdf", "old.pdf"), names(DocumentsQuery(type = TypeFilter.PDF)))
    @Test fun filtersWordAndImages() {
        assertEquals(listOf("notes.docx"), names(DocumentsQuery(type = TypeFilter.WORD)))
        assertEquals(listOf("photo.jpg"), names(DocumentsQuery(type = TypeFilter.IMAGES)))
    }
    @Test fun filtersByDate() = assertEquals(listOf("Report.pdf", "notes.docx"), names(DocumentsQuery(date = DateFilter.WEEK)))
    @Test fun searchIsCaseInsensitive() = assertEquals(listOf("Report.pdf"), names(DocumentsQuery(text = "report")))
    @Test fun sortsBySizeAndName() {
        assertEquals("photo.jpg", names(DocumentsQuery(sort = SortOrder.SIZE)).first())
        assertEquals(listOf("notes.docx", "old.pdf", "photo.jpg", "Report.pdf"), names(DocumentsQuery(sort = SortOrder.NAME)))
    }

    @Test fun detectsDocumentLanguage() {
        assertEquals("hi", EditorViewModel.detectLanguage("यह हिंदी पाठ है with some English"))
        assertEquals("en", EditorViewModel.detectLanguage("Plain English text"))
        assertEquals(null, EditorViewModel.detectLanguage("1234 !!"))
    }
}
