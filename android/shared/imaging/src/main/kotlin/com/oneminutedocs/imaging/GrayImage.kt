package com.oneminutedocs.imaging

/** 8-bit grayscale image stored as ints 0..255, row-major. Small and allocation-light for scanner previews. */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init { require(width > 0 && height > 0 && pixels.size == width * height) }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    operator fun set(x: Int, y: Int, v: Int) { pixels[y * width + x] = v }

    fun toArgb(): IntArray = IntArray(pixels.size) { val g = pixels[it]; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }

    companion object {
        /** Converts ARGB_8888 pixels using ITU-R BT.601 luma. */
        fun fromArgb(argb: IntArray, width: Int, height: Int): GrayImage {
            val out = IntArray(width * height)
            for (i in out.indices) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[i] = (r * 299 + g * 587 + b * 114) / 1000
            }
            return GrayImage(width, height, out)
        }
    }
}

object GrayOps {
    /** Area-average downscale so the longest side is at most [maxSide]. Returns the image and the scale factor applied. */
    fun downscale(src: GrayImage, maxSide: Int): Pair<GrayImage, Double> {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src to 1.0
        val scale = maxSide.toDouble() / longest
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())
        val out = GrayImage(w, h)
        val fx = src.width.toDouble() / w
        val fy = src.height.toDouble() / h
        for (y in 0 until h) {
            val y0 = (y * fy).toInt(); val y1 = minOf(src.height, maxOf(y0 + 1, ((y + 1) * fy).toInt()))
            for (x in 0 until w) {
                val x0 = (x * fx).toInt(); val x1 = minOf(src.width, maxOf(x0 + 1, ((x + 1) * fx).toInt()))
                var sum = 0L; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) { sum += src[xx, yy]; n++ }
                out[x, y] = (sum / n).toInt()
            }
        }
        return out to (w.toDouble() / src.width)
    }

    /** Summed-area table with one extra row/column of zeros. */
    fun integral(src: GrayImage): LongArray {
        val w = src.width + 1
        val table = LongArray(w * (src.height + 1))
        for (y in 1..src.height) {
            var rowSum = 0L
            for (x in 1..src.width) {
                rowSum += src[x - 1, y - 1]
                table[y * w + x] = table[(y - 1) * w + x] + rowSum
            }
        }
        return table
    }

    private fun boxMean(table: LongArray, imgW: Int, imgH: Int, x: Int, y: Int, r: Int): Int {
        val w = imgW + 1
        val x0 = maxOf(0, x - r); val y0 = maxOf(0, y - r)
        val x1 = minOf(imgW, x + r + 1); val y1 = minOf(imgH, y + r + 1)
        val sum = table[y1 * w + x1] - table[y0 * w + x1] - table[y1 * w + x0] + table[y0 * w + x0]
        return (sum / ((x1 - x0) * (y1 - y0))).toInt()
    }

    fun boxBlur(src: GrayImage, radius: Int): GrayImage {
        if (radius <= 0) return src
        val t = integral(src)
        return GrayImage(src.width, src.height, IntArray(src.pixels.size) { boxMean(t, src.width, src.height, it % src.width, it / src.width, radius) })
    }

    fun histogram(src: GrayImage): IntArray = IntArray(256).also { h -> src.pixels.forEach { h[it]++ } }

    /** Otsu's method: threshold maximising between-class variance. Pixels > threshold are foreground (bright). */
    fun otsuThreshold(src: GrayImage): Int {
        val hist = histogram(src)
        val total = src.pixels.size.toDouble()
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var threshold = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }

    fun threshold(src: GrayImage, t: Int): GrayImage = GrayImage(src.width, src.height, IntArray(src.pixels.size) { if (src.pixels[it] > t) 255 else 0 })

    /**
     * Bradley–Roth adaptive threshold: a pixel is black if it is [percent]% darker than its local mean.
     * Handles uneven lighting on photographed pages far better than a global threshold.
     */
    fun adaptiveThreshold(src: GrayImage, radius: Int = maxOf(8, maxOf(src.width, src.height) / 32), percent: Int = 12): GrayImage {
        val t = integral(src)
        return GrayImage(src.width, src.height, IntArray(src.pixels.size) {
            val mean = boxMean(t, src.width, src.height, it % src.width, it / src.width, radius)
            if (src.pixels[it] * 100 < mean * (100 - percent)) 0 else 255
        })
    }

    /**
     * Shadow reduction / illumination flattening: divides each pixel by a heavily blurred background estimate and rescales,
     * so paper becomes uniformly light while ink keeps its contrast.
     */
    fun normalizeIllumination(src: GrayImage, radius: Int = maxOf(8, maxOf(src.width, src.height) / 16)): GrayImage {
        val background = boxBlur(src, radius)
        return GrayImage(src.width, src.height, IntArray(src.pixels.size) {
            val bg = maxOf(1, background.pixels[it])
            minOf(255, src.pixels[it] * 255 / bg)
        })
    }

    /** Sobel gradient magnitude, clamped to 0..255. */
    fun sobel(src: GrayImage): GrayImage {
        val out = GrayImage(src.width, src.height)
        for (y in 1 until src.height - 1) for (x in 1 until src.width - 1) {
            val gx = -src[x - 1, y - 1] - 2 * src[x - 1, y] - src[x - 1, y + 1] + src[x + 1, y - 1] + 2 * src[x + 1, y] + src[x + 1, y + 1]
            val gy = -src[x - 1, y - 1] - 2 * src[x, y - 1] - src[x + 1, y - 1] + src[x - 1, y + 1] + 2 * src[x, y + 1] + src[x + 1, y + 1]
            out[x, y] = minOf(255, (Math.abs(gx) + Math.abs(gy)) / 2)
        }
        return out
    }
}
