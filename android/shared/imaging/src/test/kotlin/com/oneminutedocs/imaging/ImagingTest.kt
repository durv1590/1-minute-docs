package com.oneminutedocs.imaging

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImagingTest {
    /** Renders a filled convex quad of [fg] on [bg] using point-in-polygon tests. */
    private fun synth(w: Int, h: Int, quad: Quad, fg: Int = 230, bg: Int = 40, noise: Int = 0): GrayImage {
        val rnd = java.util.Random(7)
        val img = GrayImage(w, h)
        val p = quad.points
        for (y in 0 until h) for (x in 0 until w) {
            var inside = true
            for (i in 0 until 4) {
                val a = p[i]; val b = p[(i + 1) % 4]
                if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) < 0) { inside = false; break }
            }
            val base = if (inside) fg else bg
            img[x, y] = (base + if (noise > 0) rnd.nextInt(2 * noise + 1) - noise else 0).coerceIn(0, 255)
        }
        return img
    }

    private fun assertNear(expected: Point, actual: Point, tol: Double) =
        assertTrue(expected.distanceTo(actual) <= tol, "expected $expected got $actual")

    @Test fun detectsRotatedPage() {
        val truth = Quad(Point(120.0, 80.0), Point(620.0, 140.0), Point(560.0, 860.0), Point(70.0, 800.0))
        val q = assertNotNull(DocumentDetector.detect(synth(700, 950, truth, noise = 12)))
        truth.points.zip(q.points).forEach { (e, a) -> assertNear(e, a, tol = 12.0) }
    }

    @Test fun noPageInUniformImage() = assertNull(DocumentDetector.detect(GrayImage(300, 400, IntArray(300 * 400) { 128 })))

    @Test fun rejectsTinyRegion() {
        val tiny = Quad(Point(10.0, 10.0), Point(40.0, 10.0), Point(40.0, 40.0), Point(10.0, 40.0))
        assertNull(DocumentDetector.detect(synth(400, 400, tiny)))
    }

    @Test fun homographyMapsCorners() {
        val src = listOf(Point(10.0, 20.0), Point(300.0, 5.0), Point(320.0, 400.0), Point(0.0, 380.0))
        val dst = listOf(Point(0.0, 0.0), Point(210.0, 0.0), Point(210.0, 297.0), Point(0.0, 297.0))
        val hmg = Homography.between(src, dst)
        src.zip(dst).forEach { (s, d) -> assertNear(d, hmg.map(s), 1e-6) }
    }

    @Test fun warpRectifiesKnownPattern() {
        // Left half dark, right half light inside a skewed quad; after warping, columns should split cleanly.
        val w = 200; val h = 200
        val quad = Quad(Point(20.0, 30.0), Point(180.0, 10.0), Point(190.0, 190.0), Point(10.0, 170.0))
        val hm = Homography.between(listOf(Point(0.0, 0.0), Point(99.0, 0.0), Point(99.0, 99.0), Point(0.0, 99.0)), quad.points)
        val src = IntArray(w * h) { 0xFF000000.toInt() }
        // Paint by forward-mapping a dense grid of the canonical square.
        for (v in 0..990) for (u in 0..990) {
            val p = hm.map(Point(u / 10.0, v / 10.0))
            val g = if (u < 495) 30 else 220
            src[p.y.toInt().coerceIn(0, h - 1) * w + p.x.toInt().coerceIn(0, w - 1)] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val out = Perspective.warp(src, w, h, quad, 100, 100)
        val gray = GrayImage.fromArgb(out, 100, 100)
        assertTrue(gray[20, 50] < 80, "left should be dark: ${gray[20, 50]}")
        assertTrue(gray[80, 50] > 170, "right should be light: ${gray[80, 50]}")
    }

    @Test fun rectifiedSizeUsesLongerEdges() {
        val q = Quad(Point(0.0, 0.0), Point(100.0, 0.0), Point(120.0, 200.0), Point(0.0, 180.0))
        val (w, hh) = q.rectifiedSize()
        assertEquals(121, w); assertEquals(200, hh)
    }

    @Test fun orderCorners() {
        val q = Quad.fromUnordered(listOf(Point(100.0, 100.0), Point(0.0, 0.0), Point(0.0, 100.0), Point(100.0, 0.0)))
        assertEquals(Point(0.0, 0.0), q.topLeft); assertEquals(Point(100.0, 0.0), q.topRight)
        assertEquals(Point(100.0, 100.0), q.bottomRight); assertEquals(Point(0.0, 100.0), q.bottomLeft)
        assertTrue(q.isConvex)
    }

    @Test fun otsuSplitsBimodal() {
        val img = GrayImage(100, 1, IntArray(100) { if (it < 50) 40 else 200 })
        val t = GrayOps.otsuThreshold(img)
        assertTrue(t in 40 until 200)
    }

    @Test fun illuminationNormalizationFlattensShadow() {
        // Paper brightness falls from 240 to 120 left→right (a shadow); ink pixels are 40% of local paper.
        val w = 256; val h = 64
        val img = GrayImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val paper = 240 - x * 120 / w
            img[x, y] = if (x % 32 == 16 && y in 20..40) paper * 4 / 10 else paper
        }
        val n = GrayOps.normalizeIllumination(img, radius = 16)
        assertTrue(abs(n[5, 5] - n[250, 5]) < 20, "paper should be even: ${n[5, 5]} vs ${n[250, 5]}")
        val bw = GrayOps.adaptiveThreshold(img)
        assertEquals(0, bw[16, 30]); assertEquals(0, bw[240, 30]) // ink detected under both lighting conditions
        assertEquals(255, bw[100, 5])
    }

    @Test fun colorMatrix() {
        val white = 0xFFFFFFFF.toInt(); val grey = 0xFF808080.toInt()
        assertEquals(white, ColorAdjust.apply(ColorAdjust.matrix(ColorAdjust.Settings()), white))
        val brighter = ColorAdjust.apply(ColorAdjust.matrix(ColorAdjust.Settings(brightness = 50)), grey)
        assertTrue((brighter and 0xFF) > 0x80)
        val gray = ColorAdjust.apply(ColorAdjust.matrix(ColorAdjust.Settings(grayscale = true)), 0xFFFF0000.toInt())
        assertEquals((gray shr 16) and 0xFF, gray and 0xFF)
        val contrast = ColorAdjust.apply(ColorAdjust.matrix(ColorAdjust.Settings(contrast = 2f)), 0xFFC0C0C0.toInt())
        assertTrue((contrast and 0xFF) > 0xC0)
    }

    @Test fun downscaleAveragesArea() {
        val img = GrayImage(4, 4, IntArray(16) { if (it % 2 == 0) 0 else 200 })
        val (s, f) = GrayOps.downscale(img, 2)
        assertEquals(0.5, f); assertEquals(100, s[0, 0])
    }

    @Test fun convexHull() {
        val hull = DocumentDetector.convexHull(listOf(Point(0.0, 0.0), Point(2.0, 0.0), Point(1.0, 1.0), Point(2.0, 2.0), Point(0.0, 2.0)))
        assertEquals(4, hull.size)
    }
}
