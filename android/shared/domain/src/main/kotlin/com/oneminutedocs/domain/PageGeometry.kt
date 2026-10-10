package com.oneminutedocs.domain

/** Page sizes in PostScript points (1/72 inch). */
enum class PageSize(val widthPt: Float, val heightPt: Float) {
    A4(595.28f, 841.89f),
    A5(419.53f, 595.28f),
    LETTER(612f, 792f),
    LEGAL(612f, 1008f),
    /** Page takes the size of each image (Images → PDF only). */
    FIT_IMAGE(0f, 0f),
}

enum class Orientation { PORTRAIT, LANDSCAPE, AUTO }

enum class Margins(val pt: Float) { NONE(0f), SMALL(18f), NORMAL(36f), LARGE(72f) }

enum class ImageScaleMode { FIT, FILL, ORIGINAL }

data class RectF(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

data class PageBox(val widthPt: Float, val heightPt: Float)

object PageGeometry {
    /** Resolves a page box, swapping axes for landscape, or following the image aspect for AUTO. */
    fun pageBox(size: PageSize, orientation: Orientation, imageAspect: Float? = null): PageBox {
        val (w, h) = size.widthPt to size.heightPt
        val landscape = when (orientation) {
            Orientation.PORTRAIT -> false
            Orientation.LANDSCAPE -> true
            Orientation.AUTO -> imageAspect != null && imageAspect > 1f
        }
        return if (landscape) PageBox(h, w) else PageBox(w, h)
    }

    /**
     * Places an image of [imageW]×[imageH] (any unit; only the aspect matters for FIT/FILL, pixels at [imageDpi] for ORIGINAL)
     * inside [page] minus [marginPt], centered. FILL may exceed the content box (caller clips).
     */
    fun placeImage(
        imageW: Int,
        imageH: Int,
        page: PageBox,
        marginPt: Float,
        mode: ImageScaleMode,
        imageDpi: Float = 150f,
    ): RectF {
        require(imageW > 0 && imageH > 0)
        val boxW = (page.widthPt - 2 * marginPt).coerceAtLeast(1f)
        val boxH = (page.heightPt - 2 * marginPt).coerceAtLeast(1f)
        val scale = when (mode) {
            ImageScaleMode.FIT -> minOf(boxW / imageW, boxH / imageH)
            ImageScaleMode.FILL -> maxOf(boxW / imageW, boxH / imageH)
            ImageScaleMode.ORIGINAL -> minOf(72f / imageDpi, minOf(boxW / imageW, boxH / imageH))
        }
        val w = imageW * scale
        val h = imageH * scale
        return RectF(marginPt + (boxW - w) / 2f, marginPt + (boxH - h) / 2f, w, h)
    }

    /**
     * Computes a sampling factor (power of two) for decoding an image of [srcW]×[srcH] so that it is at least
     * [targetW]×[targetH]. Mirrors BitmapFactory.Options.inSampleSize semantics.
     */
    fun inSampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        var sample = 1
        if (srcH > targetH || srcW > targetW) {
            val halfH = srcH / 2
            val halfW = srcW / 2
            while (halfH / sample >= targetH && halfW / sample >= targetW) sample *= 2
        }
        return sample
    }

    /** Pixel size to render a page of [pageWPt]×[pageHPt] at [dpi], capped so the bitmap stays under [maxPixels]. */
    fun renderSize(pageWPt: Float, pageHPt: Float, dpi: Int, maxPixels: Long = 24_000_000L): Pair<Int, Int> {
        var w = (pageWPt / 72f * dpi).toDouble()
        var h = (pageHPt / 72f * dpi).toDouble()
        val pixels = w * h
        if (pixels > maxPixels) {
            val f = Math.sqrt(maxPixels / pixels)
            w *= f; h *= f
        }
        return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }
}

/** Image recompression policy for PDF compression. Text and vector content are never rasterised. */
enum class CompressionPreset(val maxDpi: Int, val jpegQuality: Int, val grayscale: Boolean = false) {
    HIGH_QUALITY(maxDpi = 200, jpegQuality = 85),
    BALANCED(maxDpi = 144, jpegQuality = 70),
    SMALL_FILE(maxDpi = 96, jpegQuality = 50);

    /**
     * Target pixel size for an image of [pixelW]×[pixelH] that is displayed at [displayWPt]×[displayHPt] on the page.
     * Returns null when the image is already at or below the preset's resolution (no downsampling needed).
     */
    fun targetPixels(pixelW: Int, pixelH: Int, displayWPt: Float, displayHPt: Float): Pair<Int, Int>? {
        if (displayWPt <= 0f || displayHPt <= 0f) return null
        val effectiveDpi = maxOf(pixelW / (displayWPt / 72f), pixelH / (displayHPt / 72f))
        if (effectiveDpi <= maxDpi * 1.1f) return null
        val factor = maxDpi / effectiveDpi
        return maxOf(1, (pixelW * factor).toInt()) to maxOf(1, (pixelH * factor).toInt())
    }
}
