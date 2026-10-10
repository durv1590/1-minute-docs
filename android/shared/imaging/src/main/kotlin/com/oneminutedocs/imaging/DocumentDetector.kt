package com.oneminutedocs.imaging

/**
 * Finds the outline of a sheet of paper in a photo.
 *
 * Approach (no OpenCV): downscale → blur → Otsu split into bright/dark → largest bright connected region (the page is
 * usually lighter than the surface under it) → convex hull of that region → four corners chosen to maximise the enclosed
 * area. The result is rejected when it is too small, too close to the full frame, or not convex, in which case the UI
 * offers manual cropping. Works best with contrast between page and background; white paper on a white desk will not
 * be detected (documented limitation).
 */
object DocumentDetector {
    data class Params(
        val workingSize: Int = 320,
        val minAreaFraction: Double = 0.12,
        val maxAreaFraction: Double = 0.98,
    )

    fun detect(image: GrayImage, params: Params = Params()): Quad? {
        val (small, scale) = GrayOps.downscale(image, params.workingSize)
        val blurred = GrayOps.boxBlur(small, 2)
        val t = GrayOps.otsuThreshold(blurred)
        val mask = BooleanArray(blurred.pixels.size) { blurred.pixels[it] > t }
        val region = largestComponent(mask, small.width, small.height) ?: return null
        val total = small.width * small.height
        if (region.size < total * params.minAreaFraction) return null

        val hull = convexHull(boundaryPoints(region, small.width))
        if (hull.size < 4) return null
        val quad = bestQuad(hull) ?: return null
        val frac = quad.area / total
        if (frac < params.minAreaFraction || frac > params.maxAreaFraction || !quad.isConvex) return null
        return quad.scaled(1.0 / scale).clampTo(image.width, image.height)
    }

    /** Largest 4-connected component of true pixels, as pixel indices. Iterative to avoid stack overflow. */
    internal fun largestComponent(mask: BooleanArray, w: Int, h: Int): IntArray? {
        val label = IntArray(mask.size)
        var best: IntArray? = null
        val stack = IntArray(mask.size)
        var next = 1
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            val members = ArrayList<Int>()
            var sp = 0
            stack[sp++] = start; label[start] = next
            while (sp > 0) {
                val p = stack[--sp]
                members += p
                val x = p % w; val y = p / w
                if (x > 0 && mask[p - 1] && label[p - 1] == 0) { label[p - 1] = next; stack[sp++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && label[p + 1] == 0) { label[p + 1] = next; stack[sp++] = p + 1 }
                if (y > 0 && mask[p - w] && label[p - w] == 0) { label[p - w] = next; stack[sp++] = p - w }
                if (y < h - 1 && mask[p + w] && label[p + w] == 0) { label[p + w] = next; stack[sp++] = p + w }
            }
            if (best == null || members.size > best.size) best = members.toIntArray()
            next++
        }
        return best
    }

    /** Leftmost and rightmost pixel of each row — sufficient input for a convex hull. */
    private fun boundaryPoints(region: IntArray, w: Int): List<Point> {
        val minX = HashMap<Int, Int>(); val maxX = HashMap<Int, Int>()
        for (p in region) {
            val x = p % w; val y = p / w
            minX[y] = minOf(minX[y] ?: x, x); maxX[y] = maxOf(maxX[y] ?: x, x)
        }
        return minX.keys.flatMap { y -> listOf(Point(minX.getValue(y).toDouble(), y.toDouble()), Point(maxX.getValue(y).toDouble(), y.toDouble())) }
    }

    /** Andrew's monotone chain; returns hull in counter-clockwise order without collinear points. */
    internal fun convexHull(points: List<Point>): List<Point> {
        val pts = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (pts.size < 3) return pts
        fun cross(o: Point, a: Point, b: Point) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Point>()
        for (p in pts) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex); lower += p }
        val upper = ArrayList<Point>()
        for (p in pts.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex); upper += p }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /**
     * Picks four hull vertices enclosing the largest area. Starts from the coordinate-extreme corners and improves each
     * corner in turn (coordinate ascent), which converges in a few passes for page-like shapes.
     */
    private fun bestQuad(hull: List<Point>): Quad? {
        val n = hull.size
        val start = Quad.fromUnordered(listOf(hull.minBy { it.x + it.y }, hull.maxBy { it.x - it.y }, hull.maxBy { it.x + it.y }, hull.minBy { it.x - it.y }))
        val idx = start.points.map { p -> hull.indexOf(p) }.toIntArray()
        if (idx.toSet().size < 4) return null
        fun area(ix: IntArray): Double = Quad(hull[ix[0]], hull[ix[1]], hull[ix[2]], hull[ix[3]]).area
        var bestArea = area(idx)
        repeat(4) {
            for (k in 0 until 4) {
                for (candidate in 0 until n) {
                    if (candidate in idx) continue
                    val trial = idx.copyOf().also { it[k] = candidate }
                    val q = Quad(hull[trial[0]], hull[trial[1]], hull[trial[2]], hull[trial[3]])
                    if (q.isConvex && q.area > bestArea) { bestArea = q.area; idx[k] = candidate }
                }
            }
        }
        return Quad.fromUnordered(idx.map { hull[it] })
    }
}
