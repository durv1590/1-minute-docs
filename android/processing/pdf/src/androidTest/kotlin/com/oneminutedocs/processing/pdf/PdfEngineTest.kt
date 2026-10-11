package com.oneminutedocs.processing.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oneminutedocs.document.MarkdownLite
import com.oneminutedocs.domain.CompressionPreset
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.PageSize
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs real PDF operations on a device/emulator and verifies the outputs by re-opening them. */
@RunWith(AndroidJUnit4::class)
class PdfEngineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var engine: PdfEngine
    private lateinit var dir: File

    @Before fun setUp() {
        PDFBoxResourceLoader.init(context)
        engine = PdfEngine(context)
        dir = File(context.cacheDir, "pdftest-${System.nanoTime()}").apply { mkdirs() }
    }

    /** Creates a PDF whose page i contains the text "<label> page i+1". */
    private fun textPdf(name: String, label: String, pages: Int, landscape: Boolean = false): File {
        val f = File(dir, name)
        PDDocument().use { doc ->
            repeat(pages) { i ->
                val box = if (landscape) PDRectangle(842f, 595f) else PDRectangle.A4
                val page = PDPage(box)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(72f, 700f.coerceAtMost(box.height - 72f))
                    cs.showText("$label page ${i + 1}"); cs.endText()
                }
            }
            doc.save(f)
        }
        return f
    }

    private fun texts(f: File) = runBlocking { engine.extractText(f) }

    @Test fun mergeKeepsOrderAndOrientation() = runBlocking {
        val a = textPdf("a.pdf", "Alpha", 2)
        val b = textPdf("b.pdf", "Beta", 1, landscape = true)
        val aBytes = a.readBytes()
        val out = File(dir, "merged.pdf")
        assertEquals(3, engine.merge(listOf(a, b), out))
        val t = texts(out)
        assertTrue(t[0].contains("Alpha page 1")); assertTrue(t[1].contains("Alpha page 2")); assertTrue(t[2].contains("Beta page 1"))
        PDDocument.load(out).use { d -> assertTrue(d.getPage(2).mediaBox.width > d.getPage(2).mediaBox.height) }
        assertArrayEquals("input unchanged", aBytes, a.readBytes())
    }

    @Test fun rebuildReordersRotatesAndDeletes() = runBlocking {
        val src = textPdf("src.pdf", "S", 4)
        val out = File(dir, "rebuilt.pdf")
        assertEquals(2, engine.rebuild(src, listOf(PageSpec(3, 90), PageSpec(0)), out))
        val t = texts(out)
        assertTrue(t[0].contains("S page 4")); assertTrue(t[1].contains("S page 1"))
        PDDocument.load(out).use { assertEquals(90, it.getPage(0).rotation) }
    }

    @Test fun splitProducesRequestedGroups() = runBlocking {
        val src = textPdf("split.pdf", "P", 5)
        val outs = engine.split(src, listOf(listOf(0, 1), listOf(4)), dir, { "part$it.pdf" })
        assertEquals(2, outs.size)
        assertEquals(2, engine.validate(outs[0])); assertEquals(1, engine.validate(outs[1]))
        assertTrue(texts(outs[1])[0].contains("P page 5"))
    }

    @Test fun compressionPreservesTextAndShrinksImages() = runBlocking {
        val src = File(dir, "img.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            val bmp = Bitmap.createBitmap(2400, 3400, Bitmap.Config.ARGB_8888)
            for (y in 0 until 3400 step 4) for (x in 0 until 2400 step 4) bmp.setPixel(x, y, Color.rgb(x % 256, y % 256, (x + y) % 256))
            val img = LosslessFactory.createFromImage(doc, bmp)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(img, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(72f, 72f); cs.showText("Keep this text"); cs.endText()
            }
            doc.save(src)
        }
        val out = File(dir, "small.pdf")
        val report = engine.compress(src, out, CompressionPreset.SMALL_FILE)
        assertEquals(src.length(), report.originalBytes); assertEquals(out.length(), report.outputBytes)
        assertTrue("expected smaller: $report", report.isSmaller)
        assertTrue(texts(out)[0].contains("Keep this text"))
    }

    @Test fun protectThenUnlock() = runBlocking {
        val src = textPdf("plain.pdf", "Secret", 1)
        val locked = File(dir, "locked.pdf")
        engine.protect(src, locked, "open-sesame", null, PdfPermissions())
        assertTrue(engine.inspect(locked).needsPassword)
        try { engine.unlock(locked, "wrong", File(dir, "x.pdf")); fail() } catch (e: DocException) { assertEquals(ErrorCode.WRONG_PASSWORD, e.error.code) }
        val unlocked = File(dir, "unlocked.pdf")
        engine.unlock(locked, "open-sesame", unlocked)
        assertTrue(texts(unlocked)[0].contains("Secret page 1"))
    }

    @Test fun refusesEditingEncryptedAndCorruptFiles() = runBlocking {
        val locked = File(dir, "l.pdf")
        engine.protect(textPdf("p.pdf", "X", 1), locked, "pw123456", null, PdfPermissions())
        try { engine.rebuild(locked, listOf(PageSpec(0)), File(dir, "o.pdf")); fail() } catch (e: DocException) { assertEquals(ErrorCode.ENCRYPTED, e.error.code) }
        val corrupt = File(dir, "bad.pdf").apply { writeText("%PDF-1.4\ngarbage") }
        try { engine.merge(listOf(corrupt, corrupt), File(dir, "m.pdf")); fail() } catch (e: DocException) { assertEquals(ErrorCode.CORRUPT_FILE, e.error.code) }
    }

    @Test fun textPdfRendersHindiAndPaginates() = runBlocking {
        val md = "# शीर्षक: नमस्ते\n\n" + (1..120).joinToString("\n\n") { "अनुच्छेद $it — Paragraph $it with mixed हिंदी text." }
        val out = File(dir, "hindi.pdf")
        val pages = TextPdfWriter().write(MarkdownLite.parse(md), TextPdfOptions(pageSize = PageSize.A4), out)
        assertTrue("long text must flow across pages", pages > 2)
        assertEquals(pages, engine.validate(out))
        val all = texts(out).joinToString("\n")
        assertTrue(all.contains("Paragraph 120"))
        assertTrue("Devanagari text should be extractable", all.contains("नमस्ते") || all.contains("अनुच्छेद"))
    }

    @Test fun overlaysAreBurnedIntoCopy() = runBlocking {
        val src = textPdf("o.pdf", "Annot", 1)
        val out = File(dir, "annot.pdf")
        OverlayExporter(context).apply(src, listOf(Overlay.Highlight(0, 0.1f, 0.1f, 0.5f, 0.2f, Color.YELLOW), Overlay.Note(0, 0.2f, 0.5f, "टिप्पणी", Color.RED, 0.03f)), out)
        assertEquals(1, engine.validate(out))
        assertTrue("original text still selectable", texts(out)[0].contains("Annot page 1"))
    }
}
