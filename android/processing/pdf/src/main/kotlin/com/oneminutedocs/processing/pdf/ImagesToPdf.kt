package com.oneminutedocs.processing.pdf

import android.content.Context
import android.graphics.Bitmap
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.ImageScaleMode
import com.oneminutedocs.domain.Margins
import com.oneminutedocs.domain.Orientation
import com.oneminutedocs.domain.PageGeometry
import com.oneminutedocs.domain.PageSize
import com.oneminutedocs.processing.image.BitmapLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** A page image with optional crop (normalised 0..1 rectangle) and clockwise rotation. */
data class ImagePage(
    val file: File,
    val rotation: Int = 0,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
)

enum class ImageQuality(val jpegQuality: Float, val maxSidePx: Int) {
    HIGH(0.9f, 3000),
    STANDARD(0.8f, 2200),
    COMPACT(0.65f, 1600),
}

data class ImagesPdfOptions(
    val pageSize: PageSize = PageSize.A4,
    val orientation: Orientation = Orientation.AUTO,
    val margins: Margins = Margins.SMALL,
    val scaleMode: ImageScaleMode = ImageScaleMode.FIT,
    val quality: ImageQuality = ImageQuality.STANDARD,
)

/**
 * Builds a PDF from images (photos, scans, enhanced pages). Images are decoded with sub-sampling, one at a time, and
 * stored as JPEG at the chosen quality so files stay small.
 */
@Singleton
class ImagesToPdf @Inject constructor(@ApplicationContext context: Context) {
    private val tempDir = File(context.cacheDir, "pdfbox").apply { mkdirs() }

    suspend fun write(pages: List<ImagePage>, options: ImagesPdfOptions, out: File, progress: Progress = {}): Int = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) throw DocException(ErrorCode.NOTHING_TO_DO)
        PDDocument(MemoryUsageSetting.setupMixed(24L * 1024 * 1024).setTempDir(tempDir)).use { doc ->
            pages.forEachIndexed { i, p ->
                coroutineContext.ensureActive()
                val bitmap = prepare(p, options.quality.maxSidePx)
                try {
                    val image = JPEGFactory.createFromImage(doc, bitmap, options.quality.jpegQuality)
                    val aspect = bitmap.width.toFloat() / bitmap.height
                    val box = if (options.pageSize == PageSize.FIT_IMAGE) {
                        // Page matches the image at 150 dpi.
                        com.oneminutedocs.domain.PageBox(bitmap.width * 72f / 150f, bitmap.height * 72f / 150f)
                    } else {
                        PageGeometry.pageBox(options.pageSize, options.orientation, aspect)
                    }
                    val margin = if (options.pageSize == PageSize.FIT_IMAGE) 0f else options.margins.pt
                    val page = PDPage(PDRectangle(box.widthPt, box.heightPt))
                    doc.addPage(page)
                    val rect = PageGeometry.placeImage(bitmap.width, bitmap.height, box, margin, options.scaleMode)
                    PDPageContentStream(doc, page).use { cs ->
                        if (options.scaleMode == ImageScaleMode.FILL) {
                            cs.addRect(margin, margin, box.widthPt - 2 * margin, box.heightPt - 2 * margin)
                            cs.clip()
                        }
                        // PDF origin is bottom-left.
                        cs.drawImage(image, rect.left, box.heightPt - rect.top - rect.height, rect.width, rect.height)
                    }
                } finally {
                    bitmap.recycle()
                }
                progress((i + 1f) / pages.size * 0.95f)
            }
            doc.save(out)
            doc.numberOfPages
        }
    }

    private fun prepare(p: ImagePage, maxSide: Int): Bitmap {
        var bmp = BitmapLoader.decode(p.file, maxSide)
        if (p.rotation % 360 != 0) {
            val rotated = BitmapLoader.rotate(bmp, p.rotation)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        val l = (p.cropLeft.coerceIn(0f, 1f) * bmp.width).toInt()
        val t = (p.cropTop.coerceIn(0f, 1f) * bmp.height).toInt()
        val r = (p.cropRight.coerceIn(0f, 1f) * bmp.width).toInt()
        val b = (p.cropBottom.coerceIn(0f, 1f) * bmp.height).toInt()
        if (l > 0 || t > 0 || r < bmp.width || b < bmp.height) {
            if (r - l < 8 || b - t < 8) throw DocException(ErrorCode.INVALID_INPUT, "crop too small")
            val cropped = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
            if (cropped !== bmp) bmp.recycle()
            bmp = cropped
        }
        return bmp
    }
}
