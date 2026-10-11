package com.oneminutedocs.processing.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.oneminutedocs.imaging.ColorAdjust
import com.oneminutedocs.imaging.DocumentDetector
import com.oneminutedocs.imaging.GrayImage
import com.oneminutedocs.imaging.GrayOps
import com.oneminutedocs.imaging.Quad
import javax.inject.Inject
import javax.inject.Singleton

/** Enhancement presets offered for scanned pages and handwritten notes. */
enum class PageFilter { ORIGINAL, ENHANCED, GRAYSCALE, BLACK_AND_WHITE, SHADOW_REMOVAL }

data class Adjustments(val brightness: Int = 0, val contrast: Float = 1f)

/**
 * Scanner image pipeline: document edge detection, perspective correction and enhancement filters.
 * Pixel algorithms come from the pure-Kotlin `imaging` module (unit-tested on the JVM); this class adapts them to
 * Android bitmaps. All methods are CPU-bound and must be called off the main thread.
 */
@Singleton
class PageImageProcessor @Inject constructor() {

    /** Finds the page outline, in [bitmap] coordinates, or null if no confident page was found. */
    fun detectPage(bitmap: Bitmap): Quad? {
        val small = BitmapLoader.scaleDown(bitmap.copy(Bitmap.Config.ARGB_8888, false), 480)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val quad = DocumentDetector.detect(GrayImage.fromArgb(px, small.width, small.height))
        val f = bitmap.width.toDouble() / small.width
        small.recycle()
        return quad?.scaled(f)?.clampTo(bitmap.width, bitmap.height)
    }

    /** Warps the [quad] region into an upright rectangle (perspective correction) using the GPU-backed Canvas path. */
    fun rectify(bitmap: Bitmap, quad: Quad, maxSide: Int = BitmapLoader.DEFAULT_MAX_SIDE): Bitmap {
        var (w, h) = quad.rectifiedSize()
        val longest = maxOf(w, h)
        if (longest > maxSide) {
            val f = maxSide.toFloat() / longest
            w = (w * f).toInt().coerceAtLeast(1); h = (h * f).toInt().coerceAtLeast(1)
        }
        val src = quad.points.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray()
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(src, 0, dst, 0, 4)) return bitmap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Applies [filter] and manual [adjustments]; never modifies [src]. */
    fun applyFilter(src: Bitmap, filter: PageFilter, adjustments: Adjustments = Adjustments()): Bitmap = when (filter) {
        PageFilter.ORIGINAL -> colorMatrix(src, ColorAdjust.Settings(adjustments.brightness, adjustments.contrast, grayscale = false))
        PageFilter.ENHANCED -> colorMatrix(src, ColorAdjust.Settings(adjustments.brightness + 8, adjustments.contrast * 1.25f, grayscale = false))
        PageFilter.GRAYSCALE -> colorMatrix(src, ColorAdjust.Settings(adjustments.brightness, adjustments.contrast * 1.1f, grayscale = true))
        PageFilter.BLACK_AND_WHITE -> grayPipeline(src) { GrayOps.adaptiveThreshold(GrayOps.normalizeIllumination(it)) }
        PageFilter.SHADOW_REMOVAL -> grayPipeline(src) { GrayOps.normalizeIllumination(it) }
    }

    private fun colorMatrix(src: Bitmap, settings: ColorAdjust.Settings): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorAdjust.matrix(settings)) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * Runs a grayscale algorithm at no more than [GRAY_MAX_SIDE] pixels (≈190 dpi for A4) to bound peak memory
     * (pixel, gray and integral-image arrays) on low-RAM devices.
     */
    private inline fun grayPipeline(src: Bitmap, op: (GrayImage) -> GrayImage): Bitmap {
        val work = if (maxOf(src.width, src.height) > GRAY_MAX_SIDE) BitmapLoader.scaleDown(src.copy(Bitmap.Config.ARGB_8888, false), GRAY_MAX_SIDE) else src
        val w = work.width
        val h = work.height
        val px = IntArray(w * h)
        work.getPixels(px, 0, w, 0, 0, w, h)
        if (work !== src) work.recycle()
        val result = op(GrayImage.fromArgb(px, w, h))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result.toArgb(), 0, w, 0, 0, w, h)
        return out
    }

    companion object {
        const val GRAY_MAX_SIDE = 1600
    }
}
