package com.oneminutedocs.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryAndLimitsTest {
    @Test fun landscapeSwapsAxes() {
        val box = PageGeometry.pageBox(PageSize.A4, Orientation.LANDSCAPE)
        assertTrue(box.widthPt > box.heightPt)
    }

    @Test fun autoOrientationFollowsImage() {
        assertTrue(PageGeometry.pageBox(PageSize.A4, Orientation.AUTO, imageAspect = 1.5f).widthPt > 800f)
        assertTrue(PageGeometry.pageBox(PageSize.A4, Orientation.AUTO, imageAspect = 0.7f).widthPt < 600f)
    }

    @Test fun fitKeepsAspectAndCenters() {
        val page = PageBox(600f, 800f)
        val r = PageGeometry.placeImage(2000, 1000, page, 50f, ImageScaleMode.FIT)
        assertEquals(500f, r.width, 0.01f)
        assertEquals(250f, r.height, 0.01f)
        assertEquals(50f, r.left, 0.01f)
        assertEquals(400f, r.top + r.height / 2, 0.01f)
    }

    @Test fun fillCoversBox() {
        val r = PageGeometry.placeImage(1000, 1000, PageBox(600f, 800f), 0f, ImageScaleMode.FILL)
        assertEquals(800f, r.width, 0.01f)
    }

    @Test fun sampleSize() {
        assertEquals(4, PageGeometry.inSampleSize(4000, 3000, 1000, 750))
        assertEquals(1, PageGeometry.inSampleSize(800, 600, 1000, 750))
    }

    @Test fun renderSizeIsCapped() {
        val (w, h) = PageGeometry.renderSize(595f, 842f, 600, maxPixels = 4_000_000)
        assertTrue(w.toLong() * h <= 4_000_000)
    }

    @Test fun compressionSkipsLowResImages() {
        // 600px across 8.27in = ~72 dpi, below BALANCED max.
        assertNull(CompressionPreset.BALANCED.targetPixels(600, 848, 595f, 842f))
    }

    @Test fun compressionDownsamplesHighRes() {
        // 2480px across A4 width = 300 dpi -> 144 dpi.
        val (w, _) = CompressionPreset.BALANCED.targetPixels(2480, 3508, 595.28f, 841.89f)!!
        assertEquals(1190, w, "expected ~144dpi width")
    }

    @Test fun sizeReport() {
        val r = SizeReport(1000, 750)
        assertEquals(25.0, r.reductionPercent)
        assertTrue(r.isSmaller)
        assertEquals(-10.0, SizeReport(1000, 1100).reductionPercent)
    }

    @Test fun sizeLimits() {
        val l = ProcessingLimits(maxPdfBytes = 100)
        assertIs<Outcome.Failure>(l.checkSize(DocumentFormat.PDF, 101))
        assertIs<Outcome.Success<Unit>>(l.checkSize(DocumentFormat.PDF, 100))
        assertEquals(ErrorCode.TOO_MANY_PAGES, (l.checkPages(10_000) as Outcome.Failure).error.code)
    }

    @Test fun jobStatusTerminal() {
        assertTrue(JobStatus.COMPLETED.isTerminal)
        assertEquals(JobStatus.PROCESSING, JobStatus.parse("processing"))
    }
}
