package com.oneminutedocs.processing.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.PageGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class PageSizePt(val width: Float, val height: Float)

/**
 * Page-at-a-time rendering with the platform PdfRenderer. Only one page is open at a time (a PdfRenderer rule), so
 * memory stays bounded however long the document is. Close it when the screen goes away.
 */
class PdfPageRenderer private constructor(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) : AutoCloseable {
    private val lock = Mutex()
    val pageCount: Int = renderer.pageCount
    private val sizes = HashMap<Int, PageSizePt>()

    suspend fun pageSize(index: Int): PageSizePt = lock.withLock {
        sizes.getOrPut(index) { withContext(Dispatchers.IO) { renderer.openPage(index).use { PageSizePt(it.width.toFloat(), it.height.toFloat()) } } }
    }

    /** Renders page [index] to a bitmap [targetWidthPx] wide (height follows the page aspect), on a white background. */
    suspend fun render(index: Int, targetWidthPx: Int, maxPixels: Long = 12_000_000L): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val aspect = page.height.toFloat() / page.width
                var w = targetWidthPx.coerceAtLeast(1)
                var h = (w * aspect).toInt().coerceAtLeast(1)
                if (w.toLong() * h > maxPixels) {
                    val f = Math.sqrt(maxPixels.toDouble() / (w.toLong() * h))
                    w = (w * f).toInt(); h = (h * f).toInt()
                }
                val bmp = try {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    throw DocException(ErrorCode.OUT_OF_MEMORY, null, e)
                }
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    /** Renders at a print/OCR resolution in DPI, capped to keep memory bounded. */
    suspend fun renderAtDpi(index: Int, dpi: Int): Bitmap {
        val size = pageSize(index)
        val (w, _) = PageGeometry.renderSize(size.width, size.height, dpi, maxPixels = 16_000_000L)
        return render(index, w, maxPixels = 16_000_000L)
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        /** Opens [file]. Password-protected PDFs cannot be rendered by the platform before Android 15 and fail with ENCRYPTED. */
        suspend fun open(file: File): PdfPageRenderer = withContext(Dispatchers.IO) {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfPageRenderer(fd, PdfRenderer(fd))
            } catch (e: SecurityException) {
                fd.close()
                throw DocException(ErrorCode.ENCRYPTED, null, e)
            } catch (e: IOException) {
                fd.close()
                throw DocException(ErrorCode.CORRUPT_FILE, null, e)
            }
        }
    }
}

/** Exports pages to PNG/JPEG files at [dpi]. Returns the files in page order. */
suspend fun exportPagesAsImages(
    input: File,
    pages: List<Int>,
    dpi: Int,
    jpeg: Boolean,
    outDir: File,
    nameFor: (Int) -> String,
    progress: Progress = {},
): List<File> = PdfPageRenderer.open(input).use { r ->
    pages.mapIndexed { i, index ->
        if (index !in 0 until r.pageCount) throw DocException(ErrorCode.INVALID_PAGE_RANGE)
        val bmp = r.renderAtDpi(index, dpi)
        val out = File(outDir, nameFor(index))
        withContext(Dispatchers.IO) {
            out.outputStream().use { os ->
                bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) 90 else 100, os)
            }
        }
        bmp.recycle()
        progress((i + 1f) / pages.size)
        out
    }
}
