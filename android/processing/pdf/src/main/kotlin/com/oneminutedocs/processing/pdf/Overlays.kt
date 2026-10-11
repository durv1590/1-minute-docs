package com.oneminutedocs.processing.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.processing.image.BitmapLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Annotation placed on a page. All coordinates are normalised (0..1) relative to the page *as displayed*
 * (after its /Rotate), origin top-left, so the same model drives the on-screen editor and export.
 */
sealed interface Overlay {
    val page: Int

    data class Ink(override val page: Int, val points: List<Pair<Float, Float>>, val color: Int, val width: Float) : Overlay
    data class Shape(override val page: Int, val kind: ShapeKind, val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int, val width: Float) : Overlay
    data class Highlight(override val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int) : Overlay
    data class Note(override val page: Int, val x: Float, val y: Float, val text: String, val color: Int, val size: Float, val maxWidth: Float = 0.6f) : Overlay
    /** Image or drawn signature (PNG with transparency) stretched into the rectangle. */
    data class Picture(override val page: Int, val file: File, val left: Float, val top: Float, val right: Float, val bottom: Float) : Overlay
}

enum class ShapeKind { RECTANGLE, ELLIPSE, LINE, UNDERLINE }

/** Draws overlays onto any Canvas sized [w]×[h] (screen preview or export bitmap). */
object OverlayPainter {
    fun draw(canvas: Canvas, overlays: List<Overlay>, w: Float, h: Float, images: (File) -> Bitmap?) {
        for (o in overlays) when (o) {
            is Overlay.Ink -> {
                if (o.points.size < 2) continue
                val path = Path().apply {
                    moveTo(o.points[0].first * w, o.points[0].second * h)
                    for (p in o.points.drop(1)) lineTo(p.first * w, p.second * h)
                }
                canvas.drawPath(path, stroke(o.color, o.width * w))
            }
            is Overlay.Shape -> {
                val r = RectF(o.left * w, o.top * h, o.right * w, o.bottom * h)
                val p = stroke(o.color, o.width * w)
                when (o.kind) {
                    ShapeKind.RECTANGLE -> canvas.drawRect(r, p)
                    ShapeKind.ELLIPSE -> canvas.drawOval(r, p)
                    ShapeKind.LINE -> canvas.drawLine(r.left, r.top, r.right, r.bottom, p)
                    ShapeKind.UNDERLINE -> canvas.drawLine(r.left, r.bottom, r.right, r.bottom, p)
                }
            }
            is Overlay.Highlight -> canvas.drawRect(
                o.left * w, o.top * h, o.right * w, o.bottom * h,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = (o.color and 0x00FFFFFF) or (0x66 shl 24) },
            )
            is Overlay.Note -> {
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = o.color; textSize = o.size * w }
                val layout = StaticLayout.Builder.obtain(o.text, 0, o.text.length, tp, (o.maxWidth * w).toInt().coerceAtLeast(10))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
                canvas.save()
                canvas.translate(o.x * w, o.y * h)
                layout.draw(canvas)
                canvas.restore()
            }
            is Overlay.Picture -> images(o.file)?.let { bmp ->
                canvas.drawBitmap(bmp, null, RectF(o.left * w, o.top * h, o.right * w, o.bottom * h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
        }
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = width.coerceAtLeast(1f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}

/**
 * Burns overlays into a copy of the PDF as a transparent image layer per page. The original page content (including
 * selectable text) is untouched underneath; this is an annotation layer, not an edit of the document's own text.
 */
@Singleton
class OverlayExporter @Inject constructor(@ApplicationContext context: Context) {
    private val tempDir = File(context.cacheDir, "pdfbox").apply { mkdirs() }

    suspend fun apply(input: File, overlays: List<Overlay>, out: File, progress: Progress = {}): Int = withContext(Dispatchers.IO) {
        if (overlays.isEmpty()) throw DocException(ErrorCode.NOTHING_TO_DO)
        val cache = HashMap<File, Bitmap?>()
        try {
            PDDocument.load(input, "", MemoryUsageSetting.setupMixed(32L * 1024 * 1024).setTempDir(tempDir)).use { doc ->
                if (doc.isEncrypted) throw DocException(ErrorCode.ENCRYPTED)
                val byPage = overlays.groupBy { it.page }
                var done = 0
                for ((index, list) in byPage) {
                    coroutineContext.ensureActive()
                    if (index !in 0 until doc.numberOfPages) continue
                    val page = doc.getPage(index)
                    val box = page.cropBox
                    val rotation = ((page.rotation % 360) + 360) % 360
                    val displayedW = if (rotation == 90 || rotation == 270) box.height else box.width
                    val displayedH = if (rotation == 90 || rotation == 270) box.width else box.height
                    // ~200 dpi layer, capped for memory.
                    val scale = (200f / 72f).coerceAtMost(2600f / maxOf(displayedW, displayedH))
                    val bw = (displayedW * scale).toInt().coerceAtLeast(1)
                    val bh = (displayedH * scale).toInt().coerceAtLeast(1)
                    val layer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    OverlayPainter.draw(Canvas(layer), list, bw.toFloat(), bh.toFloat()) { f -> cache.getOrPut(f) { runCatching { BitmapLoader.decode(f, 1600) }.getOrNull() } }
                    // Undo the page's display rotation so the layer lines up with unrotated page space.
                    val upright = if (rotation == 0) layer else Bitmap.createBitmap(layer, 0, 0, bw, bh, Matrix().apply { postRotate(-rotation.toFloat()) }, true).also { layer.recycle() }
                    val image = LosslessFactory.createFromImage(doc, upright)
                    upright.recycle()
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.drawImage(image, box.lowerLeftX, box.lowerLeftY, box.width, box.height)
                    }
                    progress(++done / byPage.size.toFloat() * 0.9f)
                }
                doc.save(out)
                doc.numberOfPages
            }
        } finally {
            cache.values.forEach { it?.recycle() }
        }
    }
}
