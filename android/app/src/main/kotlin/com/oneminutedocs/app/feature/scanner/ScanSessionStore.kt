package com.oneminutedocs.app.feature.scanner

import android.content.Context
import android.graphics.Bitmap
import com.oneminutedocs.imaging.Point
import com.oneminutedocs.imaging.Quad
import com.oneminutedocs.processing.image.Adjustments
import com.oneminutedocs.processing.image.BitmapLoader
import com.oneminutedocs.processing.image.PageFilter
import com.oneminutedocs.processing.image.PageImageProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One captured or imported page. The original photo is never altered; crop corners ([quad], normalised 0..1),
 * rotation and filter are applied when the page is rendered or exported, so every change is reversible.
 */
data class ScanPage(
    val id: String,
    val original: File,
    val quad: Quad?,
    val rotation: Int = 0,
    val filter: PageFilter = PageFilter.ENHANCED,
    val adjustments: Adjustments = Adjustments(),
)

/**
 * Holds scan sessions (camera captures, imported photos, imported PDF pages) shared by the scanner, the review screen
 * and the handwriting workflow. Files live in app-private storage until the session is discarded.
 */
@Singleton
class ScanSessionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val processor: PageImageProcessor,
) {
    private val root = File(context.filesDir, "scan_sessions").apply { mkdirs() }
    private val sessions = HashMap<String, MutableStateFlow<List<ScanPage>>>()

    fun newSession(): String = UUID.randomUUID().toString().also { id ->
        File(root, id).mkdirs()
        sessions[id] = MutableStateFlow(emptyList())
    }

    fun pages(sessionId: String): StateFlow<List<ScanPage>> = flow(sessionId).asStateFlow()

    private fun flow(sessionId: String) = sessions.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

    fun newImageFile(sessionId: String): File = File(File(root, sessionId).apply { mkdirs() }, "${UUID.randomUUID()}.jpg")

    /** Adds a page from [file] (already inside the session folder) and auto-detects its outline. */
    suspend fun addPage(sessionId: String, file: File, replaceId: String? = null): ScanPage = withContext(Dispatchers.Default) {
        val quad = runCatching {
            val bmp = BitmapLoader.decode(file, 1024)
            val q = processor.detectPage(bmp)?.let { normalise(it, bmp.width, bmp.height) }
            bmp.recycle()
            q
        }.getOrNull()
        val page = ScanPage(UUID.randomUUID().toString(), file, quad)
        val f = flow(sessionId)
        f.value = if (replaceId != null) f.value.map { if (it.id == replaceId) page.also { _ -> it.original.delete() } else it } else f.value + page
        page
    }

    fun update(sessionId: String, page: ScanPage) {
        val f = flow(sessionId)
        f.value = f.value.map { if (it.id == page.id) page else it }
    }

    fun remove(sessionId: String, pageId: String) {
        val f = flow(sessionId)
        f.value.firstOrNull { it.id == pageId }?.original?.delete()
        f.value = f.value.filterNot { it.id == pageId }
    }

    fun move(sessionId: String, pageId: String, delta: Int) {
        val f = flow(sessionId)
        val list = f.value.toMutableList()
        val i = list.indexOfFirst { it.id == pageId }
        val t = i + delta
        if (i < 0 || t !in list.indices) return
        list.add(t, list.removeAt(i))
        f.value = list
    }

    /**
     * Produces the final page image: perspective-corrected crop, rotation, then the filter.
     * [maxSide] bounds memory; 2400 px gives ~280 dpi on A4.
     */
    suspend fun render(page: ScanPage, maxSide: Int = BitmapLoader.DEFAULT_MAX_SIDE, filter: PageFilter = page.filter): Bitmap = withContext(Dispatchers.Default) {
        val src = BitmapLoader.decode(page.original, maxSide)
        val cropped = page.quad?.let { q ->
            val abs = q.scaled(1.0).let { Quad(it.topLeft.mul(src.width, src.height), it.topRight.mul(src.width, src.height), it.bottomRight.mul(src.width, src.height), it.bottomLeft.mul(src.width, src.height)) }
            processor.rectify(src, abs, maxSide)
        } ?: src
        if (cropped !== src) src.recycle()
        val rotated = BitmapLoader.rotate(cropped, page.rotation)
        if (rotated !== cropped) cropped.recycle()
        val filtered = processor.applyFilter(rotated, filter, page.adjustments)
        if (filtered !== rotated) rotated.recycle()
        filtered
    }

    /** Renders every page to JPEG files for export (one bitmap in memory at a time). */
    suspend fun exportJpegs(sessionId: String, dir: File, filter: PageFilter? = null, maxSide: Int = BitmapLoader.DEFAULT_MAX_SIDE, onPage: (Int, Int) -> Unit = { _, _ -> }): List<File> {
        val list = pages(sessionId).value
        return list.mapIndexed { i, p ->
            val bmp = render(p, maxSide, filter ?: p.filter)
            val out = File(dir, "page-$i.jpg")
            withContext(Dispatchers.IO) { BitmapLoader.saveJpeg(bmp, out, 88) }
            bmp.recycle()
            onPage(i + 1, list.size)
            out
        }
    }

    fun discard(sessionId: String) {
        sessions.remove(sessionId)
        File(root, sessionId).deleteRecursively()
    }

    private fun Point.mul(w: Int, h: Int) = Point(x * w, y * h)

    companion object {
        fun normalise(q: Quad, w: Int, h: Int): Quad {
            fun Point.n() = Point(x / w, y / h)
            return Quad(q.topLeft.n(), q.topRight.n(), q.bottomRight.n(), q.bottomLeft.n())
        }
    }
}
