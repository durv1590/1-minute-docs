package com.oneminutedocs.app.common

import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.ManagedDocument
import com.oneminutedocs.core.data.files.FileInspector
import com.oneminutedocs.document.DocxReader
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.Outcome
import com.oneminutedocs.processing.image.BitmapLoader
import com.oneminutedocs.processing.pdf.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates an output by re-opening it with an independent reader, then moves it into managed storage.
 * This is the only path to a "success" screen, so success is never reported for a missing or unreadable file.
 */
@Singleton
class OutputSaver @Inject constructor(
    private val repository: DocumentRepository,
    private val pdf: PdfEngine,
) {
    suspend fun save(file: File, displayName: String, format: DocumentFormat, operation: String, aiGenerated: Boolean = false): ManagedDocument {
        val pages = validate(file, format)
        return when (val r = repository.saveOutput(file, displayName, format, pages, operation, aiGenerated)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> throw DocException(r.error)
        }
    }

    /** Returns the page count where meaningful. */
    private suspend fun validate(file: File, format: DocumentFormat): Int? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) throw DocException(ErrorCode.CONVERSION_FAILED, "missing output")
        val detected = FileInspector.detect(file)
        when (format) {
            DocumentFormat.PDF -> {
                if (detected != DocumentFormat.PDF) throw DocException(ErrorCode.CONVERSION_FAILED, "not a pdf")
                pdf.validate(file)
            }
            DocumentFormat.DOCX -> {
                if (detected != DocumentFormat.DOCX) throw DocException(ErrorCode.CONVERSION_FAILED, "not a docx")
                file.inputStream().use { DocxReader.read(it) }
                null
            }
            DocumentFormat.PNG, DocumentFormat.JPEG -> {
                BitmapLoader.bounds(file)
                null
            }
            DocumentFormat.TXT -> {
                // Must be valid UTF-8 text.
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(file.readBytes()))
                null
            }
            else -> null
        }
    }
}
