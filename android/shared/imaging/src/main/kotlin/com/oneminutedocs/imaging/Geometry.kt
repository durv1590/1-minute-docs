package com.oneminutedocs.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

data class Point(val x: Double, val y: Double) {
    operator fun minus(o: Point) = Point(x - o.x, y - o.y)
    fun distanceTo(o: Point) = hypot(x - o.x, y - o.y)
}

/** Four corners in order top-left, top-right, bottom-right, bottom-left (image coordinates, y down). */
data class Quad(val topLeft: Point, val topRight: Point, val bottomRight: Point, val bottomLeft: Point) {
    val points: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun scaled(f: Double) = Quad(topLeft.s(f), topRight.s(f), bottomRight.s(f), bottomLeft.s(f))
    private fun Point.s(f: Double) = Point(x * f, y * f)

    /** Shoelace area. */
    val area: Double get() {
        val p = points
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2
    }

    val isConvex: Boolean get() {
        val p = points
        var sign = 0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Output size for a perspective-corrected page: the longer of each pair of opposite edges. */
    fun rectifiedSize(): Pair<Int, Int> {
        val w = max(topLeft.distanceTo(topRight), bottomLeft.distanceTo(bottomRight))
        val h = max(topLeft.distanceTo(bottomLeft), topRight.distanceTo(bottomRight))
        return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }

    fun clampTo(width: Int, height: Int) = Quad(topLeft.c(width, height), topRight.c(width, height), bottomRight.c(width, height), bottomLeft.c(width, height))
    private fun Point.c(w: Int, h: Int) = Point(x.coerceIn(0.0, (w - 1).toDouble()), y.coerceIn(0.0, (h - 1).toDouble()))

    companion object {
        /** Orders four arbitrary points into TL, TR, BR, BL using coordinate sums and differences. */
        fun fromUnordered(points: List<Point>): Quad {
            require(points.size == 4)
            val tl = points.minBy { it.x + it.y }
            val br = points.maxBy { it.x + it.y }
            val rest = points.filter { it !== tl && it !== br }.let { if (it.size == 2) it else points - tl - br }
            val tr = rest.maxBy { it.x - it.y }
            val bl = rest.minBy { it.x - it.y }
            return Quad(tl, tr, br, bl)
        }

        /** A rectangle inset by [fraction] on each side; the default crop when detection fails. */
        fun inset(width: Int, height: Int, fraction: Double = 0.05): Quad {
            val dx = width * fraction; val dy = height * fraction
            return Quad(Point(dx, dy), Point(width - 1 - dx, dy), Point(width - 1 - dx, height - 1 - dy), Point(dx, height - 1 - dy))
        }
    }
}

/** 3×3 projective transform. */
class Homography(private val m: DoubleArray) {
    init { require(m.size == 9) }

    fun map(p: Point): Point {
        val w = m[6] * p.x + m[7] * p.y + m[8]
        return Point((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
    }

    fun values(): DoubleArray = m.copyOf()

    companion object {
        /** Solves the homography mapping each src[i] to dst[i] (Direct Linear Transform with h33 = 1). */
        fun between(src: List<Point>, dst: List<Point>): Homography {
            require(src.size == 4 && dst.size == 4)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = src[i]; val (u, v) = dst[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val h = solve(a)
            return Homography(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
        }

        /** Gaussian elimination with partial pivoting on an n×(n+1) augmented matrix. */
        private fun solve(a: Array<DoubleArray>): DoubleArray {
            val n = a.size
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                require(abs(a[pivot][col]) > 1e-12) { "degenerate quadrilateral" }
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                for (r in 0 until n) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    for (c in col..n) a[r][c] -= f * a[col][c]
                }
            }
            return DoubleArray(n) { a[it][n] / a[it][it] }
        }
    }
}

object Perspective {
    /**
     * Warps the region [quad] of an ARGB image into an upright [outW]×[outH] image with bilinear sampling.
     * Android uses `Matrix.setPolyToPoly` for speed; this implementation is the reference used in tests and for
     * platforms without a GPU path.
     */
    fun warp(src: IntArray, srcW: Int, srcH: Int, quad: Quad, outW: Int, outH: Int): IntArray {
        val dst = listOf(Point(0.0, 0.0), Point(outW - 1.0, 0.0), Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0))
        val inverse = Homography.between(dst, quad.points) // output pixel -> source pixel
        val out = IntArray(outW * outH)
        for (y in 0 until outH) for (x in 0 until outW) {
            val p = inverse.map(Point(x.toDouble(), y.toDouble()))
            out[y * outW + x] = sampleBilinear(src, srcW, srcH, p.x, p.y)
        }
        return out
    }

    private fun sampleBilinear(src: IntArray, w: Int, h: Int, fx: Double, fy: Double): Int {
        val x = fx.coerceIn(0.0, w - 1.0); val y = fy.coerceIn(0.0, h - 1.0)
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = minOf(w - 1, x0 + 1); val y1 = minOf(h - 1, y0 + 1)
        val ax = x - x0; val ay = y - y0
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        var result = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val top = ch(src[y0 * w + x0], shift) * (1 - ax) + ch(src[y0 * w + x1], shift) * ax
            val bottom = ch(src[y1 * w + x0], shift) * (1 - ax) + ch(src[y1 * w + x1], shift) * ax
            result = result or ((top * (1 - ay) + bottom * ay).toInt().coerceIn(0, 255) shl shift)
        }
        return result
    }
}
