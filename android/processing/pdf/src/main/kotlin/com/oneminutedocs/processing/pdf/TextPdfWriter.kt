package com.oneminutedocs.processing.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.oneminutedocs.document.Alignment
import com.oneminutedocs.document.Block
import com.oneminutedocs.document.RichDocument
import com.oneminutedocs.document.TextRun
import com.oneminutedocs.domain.Margins
import com.oneminutedocs.domain.Orientation
import com.oneminutedocs.domain.PageGeometry
import com.oneminutedocs.domain.PageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

enum class FontFamily { SANS, SERIF, MONO }

data class TextPdfOptions(
    val pageSize: PageSize = PageSize.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
    val margins: Margins = Margins.NORMAL,
    val font: FontFamily = FontFamily.SANS,
    val fontSizePt: Float = 11f,
    val lineSpacing: Float = 1.3f,
    val pageNumbers: Boolean = true,
    val header: String? = null,
    val footer: String? = null,
    /** Formats the page number shown in the footer (localised by the caller). */
    val pageLabel: (Int) -> String = { it.toString() },
)

/**
 * Renders a [RichDocument] to PDF with the platform text stack (`StaticLayout`), which shapes complex scripts such as
 * Devanagari correctly and embeds subsetted fonts, so text stays selectable and searchable. Long paragraphs and tables
 * flow across pages; nothing is truncated.
 */
@Singleton
class TextPdfWriter @Inject constructor() {

    suspend fun write(doc: RichDocument, options: TextPdfOptions, out: File, progress: Progress = {}): Int = withContext(Dispatchers.Default) {
        val box = PageGeometry.pageBox(if (options.pageSize == PageSize.FIT_IMAGE) PageSize.A4 else options.pageSize, if (options.orientation == Orientation.AUTO) Orientation.PORTRAIT else options.orientation)
        val pdf = PdfDocument()
        try {
            val r = Renderer(pdf, box.widthPt.toInt(), box.heightPt.toInt(), options)
            doc.blocks.forEachIndexed { i, block ->
                coroutineContext.ensureActive()
                r.block(block, doc.blocks.getOrNull(i - 1))
                progress((i + 1f) / doc.blocks.size.coerceAtLeast(1) * 0.9f)
            }
            r.finish()
            withContext(Dispatchers.IO) { FileOutputStream(out).use { pdf.writeTo(it) } }
            r.pageCount
        } finally {
            pdf.close()
        }
    }

    private class Renderer(val pdf: PdfDocument, val pageW: Int, val pageH: Int, val o: TextPdfOptions) {
        val margin = o.margins.pt.coerceAtLeast(18f)
        val contentW = (pageW - 2 * margin).toInt()
        val headerSpace = if (o.header.isNullOrBlank()) 0f else o.fontSizePt * 2f
        val footerSpace = if (o.pageNumbers || !o.footer.isNullOrBlank()) o.fontSizePt * 2f else 0f
        val top = margin + headerSpace
        val bottom = pageH - margin - footerSpace
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = top
        var pageCount = 0

        private val baseTypeface = when (o.font) {
            FontFamily.SANS -> Typeface.SANS_SERIF
            FontFamily.SERIF -> Typeface.SERIF
            FontFamily.MONO -> Typeface.MONOSPACE
        }

        fun paint(sizePt: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePt
            color = Color.BLACK
            typeface = Typeface.create(baseTypeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

        fun ensurePage() {
            if (page == null) newPage()
        }

        fun newPage() {
            closePage()
            pageCount++
            val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageCount).create()
            page = pdf.startPage(info)
            canvas = page!!.canvas
            y = top
            val small = paint(o.fontSizePt * 0.8f).apply { color = Color.DKGRAY }
            o.header?.takeIf { it.isNotBlank() }?.let { canvas!!.drawText(it, margin, margin + small.textSize, small) }
            val footerY = pageH - margin
            o.footer?.takeIf { it.isNotBlank() }?.let { canvas!!.drawText(it, margin, footerY, small) }
            if (o.pageNumbers) {
                val label = o.pageLabel(pageCount)
                canvas!!.drawText(label, pageW - margin - small.measureText(label), footerY, small)
            }
        }

        fun closePage() {
            page?.let { pdf.finishPage(it) }
            page = null
            canvas = null
        }

        fun finish() {
            ensurePage() // an empty document still produces one valid page
            closePage()
        }

        /** Numbering per nesting level; reset by any non-list block so each list starts at 1. */
        private val counters = IntArray(3)

        fun block(b: Block, @Suppress("UNUSED_PARAMETER") previous: Block?) {
            ensurePage()
            if (b !is Block.ListItem) counters.fill(0)
            when (b) {
                is Block.Heading -> {
                    val scale = when (b.level) { 1 -> 1.6f; 2 -> 1.3f; else -> 1.12f }
                    space(o.fontSizePt * if (y == top) 0f else 0.9f)
                    // Keep the heading with at least two lines of the following text.
                    if (y + o.fontSizePt * scale * 1.4f + o.fontSizePt * o.lineSpacing * 2 > bottom) newPage()
                    flow(layout(b.runs, paint(o.fontSizePt * scale, bold = true), contentW, Alignment.START), margin)
                    space(o.fontSizePt * 0.4f)
                }
                is Block.Paragraph -> {
                    flow(layout(b.runs, paint(o.fontSizePt), contentW, b.alignment), margin)
                    space(o.fontSizePt * 0.7f)
                }
                is Block.ListItem -> {
                    val indent = o.fontSizePt * 1.6f * (b.level + 1)
                    val bulletPaint = paint(o.fontSizePt)
                    val level = b.level.coerceIn(0, 2)
                    for (deeper in level + 1 until counters.size) counters[deeper] = 0
                    val marker = if (b.ordered) {
                        counters[level]++
                        "${counters[level]}."
                    } else {
                        counters[level] = 0
                        listOf("•", "◦", "▪")[level]
                    }
                    val lay = layout(b.runs, bulletPaint, (contentW - indent).toInt(), Alignment.START)
                    val firstLineFits = y + (lay.getLineBottom(0) - lay.getLineTop(0)) <= bottom
                    if (!firstLineFits) newPage()
                    val markerX = margin + indent - bulletPaint.measureText(marker) - o.fontSizePt * 0.5f
                    canvas!!.drawText(marker, markerX, y - lay.getLineTop(0) + lay.getLineBaseline(0), bulletPaint)
                    flow(lay, margin + indent)
                    space(o.fontSizePt * 0.25f)
                }
                is Block.Table -> table(b)
                Block.PageBreak -> newPage()
            }
        }

        private fun space(h: Float) {
            y += h
            if (y > bottom) newPage()
        }

        /** Draws [layout] at the cursor, splitting across pages at line boundaries. */
        fun flow(layout: StaticLayout, x: Float) {
            var line = 0
            while (line < layout.lineCount) {
                ensurePage()
                val startTop = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && y + (layout.getLineBottom(end) - startTop) <= bottom) end++
                if (end == line) {
                    if (y == top) end = line + 1 else { newPage(); continue }
                }
                val endBottom = layout.getLineBottom(end - 1)
                val c = canvas!!
                c.save()
                c.translate(x, y - startTop)
                c.clipRect(0f, startTop.toFloat(), layout.width.toFloat(), endBottom.toFloat())
                layout.draw(c)
                c.restore()
                y += (endBottom - startTop)
                line = end
                if (line < layout.lineCount) newPage()
            }
        }

        fun table(t: Block.Table) {
            val cols = t.rows.maxOfOrNull { it.size } ?: return
            if (cols == 0) return
            val pad = o.fontSizePt * 0.4f
            val colW = contentW / cols.toFloat()
            val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = Color.GRAY }
            val shade = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(235, 238, 245) }
            space(o.fontSizePt * 0.3f)
            t.rows.forEachIndexed { r, row ->
                val header = r == 0 && t.hasHeader
                val p = paint(o.fontSizePt * 0.95f, bold = header)
                val cells = (0 until cols).map { c -> layout(listOf(TextRun(row.getOrElse(c) { "" })), p, (colW - 2 * pad).toInt().coerceAtLeast(10), Alignment.START) }
                val rowH = (cells.maxOf { it.height } + 2 * pad).coerceAtMost(bottom - top)
                if (y + rowH > bottom) newPage()
                val c = canvas!!
                if (header) c.drawRect(margin, y, margin + contentW, y + rowH, shade)
                cells.forEachIndexed { i, lay ->
                    val x = margin + i * colW
                    c.save()
                    c.translate(x + pad, y + pad)
                    c.clipRect(0f, 0f, colW - 2 * pad, rowH - 2 * pad)
                    lay.draw(c)
                    c.restore()
                    c.drawRect(x, y, x + colW, y + rowH, border)
                }
                y += rowH
            }
            space(o.fontSizePt * 0.7f)
        }

        fun layout(runs: List<TextRun>, paint: TextPaint, width: Int, align: Alignment): StaticLayout {
            val text = SpannableStringBuilder()
            for (r in runs) {
                val start = text.length
                text.append(r.text)
                val end = text.length
                if (end == start) continue
                val style = when {
                    r.bold && r.italic -> Typeface.BOLD_ITALIC
                    r.bold -> Typeface.BOLD
                    r.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                if (style != Typeface.NORMAL) text.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.underline) text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (text.isEmpty()) text.append(" ")
            val alignment = when (align) {
                Alignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                Alignment.END -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(10))
                .setAlignment(alignment)
                .setLineSpacing(0f, o.lineSpacing)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setJustificationMode(if (align == Alignment.JUSTIFY) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
                .build()
        }
    }
}
