package com.oneminutedocs.document

import com.oneminutedocs.domain.Margins
import com.oneminutedocs.domain.Orientation
import com.oneminutedocs.domain.PageSize
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DocxOptions(
    val pageSize: PageSize = PageSize.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
    val margins: Margins = Margins.NORMAL,
    /** Font for Latin text. */
    val latinFont: String = "Calibri",
    /** Font for complex scripts such as Devanagari (Word selects it per character). */
    val complexScriptFont: String = "Nirmala UI",
    val bodySizePt: Int = 11,
    val title: String? = null,
    val creator: String = "One-Minute Docs",
    val language: String = "en-IN",
)

/**
 * Writes a [RichDocument] as an Office Open XML (WordprocessingML) .docx package: headings use built-in Heading styles,
 * lists use real Word numbering (editable as lists), tables are real tables, page breaks are real breaks.
 */
class DocxWriter(private val options: DocxOptions = DocxOptions()) {

    fun write(doc: RichDocument, out: OutputStream) {
        val numbering = NumberingPlan.from(doc.blocks)
        ZipOutputStream(out).use { zip ->
            zip.entry("[Content_Types].xml", contentTypes())
            zip.entry("_rels/.rels", rootRels())
            zip.entry("docProps/core.xml", coreProps(doc))
            zip.entry("docProps/app.xml", appProps())
            zip.entry("word/_rels/document.xml.rels", documentRels())
            zip.entry("word/styles.xml", styles())
            zip.entry("word/numbering.xml", numbering.numberingXml())
            zip.entry("word/settings.xml", settings())
            zip.entry("word/document.xml", documentXml(doc, numbering))
        }
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun documentXml(doc: RichDocument, numbering: NumberingPlan): String = buildString {
        append(XML_DECL)
        append("<w:document xmlns:w=\"$W_NS\" xmlns:r=\"$R_NS\"><w:body>")
        doc.blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Heading -> paragraph(styleId = "Heading${block.level}", runs = block.runs)
                is Block.Paragraph -> paragraph(runs = block.runs, alignment = block.alignment)
                is Block.ListItem -> paragraph(
                    styleId = "ListParagraph",
                    runs = block.runs,
                    numId = numbering.numIdFor(index),
                    ilvl = block.level,
                )
                is Block.Table -> table(block)
                Block.PageBreak -> append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }
        append(sectionProperties())
        append("</w:body></w:document>")
    }

    private fun StringBuilder.paragraph(
        styleId: String? = null,
        runs: List<TextRun>,
        alignment: Alignment = Alignment.START,
        numId: Int? = null,
        ilvl: Int = 0,
    ) {
        append("<w:p>")
        val jc = when (alignment) {
            Alignment.START -> null
            Alignment.CENTER -> "center"
            Alignment.END -> "end"
            Alignment.JUSTIFY -> "both"
        }
        if (styleId != null || numId != null || jc != null) {
            append("<w:pPr>")
            if (styleId != null) append("<w:pStyle w:val=\"$styleId\"/>")
            if (numId != null) append("<w:numPr><w:ilvl w:val=\"$ilvl\"/><w:numId w:val=\"$numId\"/></w:numPr>")
            if (jc != null) append("<w:jc w:val=\"$jc\"/>")
            append("</w:pPr>")
        }
        runs.forEach { run(it) }
        append("</w:p>")
    }

    private fun StringBuilder.run(r: TextRun) {
        if (r.text.isEmpty()) return
        append("<w:r>")
        if (r.bold || r.italic || r.underline) {
            append("<w:rPr>")
            if (r.bold) append("<w:b/><w:bCs/>")
            if (r.italic) append("<w:i/><w:iCs/>")
            if (r.underline) append("<w:u w:val=\"single\"/>")
            append("</w:rPr>")
        }
        r.text.split('\n').forEachIndexed { i, line ->
            if (i > 0) append("<w:br/>")
            line.split('\t').forEachIndexed { j, part ->
                if (j > 0) append("<w:tab/>")
                if (part.isNotEmpty()) append("<w:t xml:space=\"preserve\">").append(Xml.escape(part)).append("</w:t>")
            }
        }
        append("</w:r>")
    }

    private fun StringBuilder.table(t: Block.Table) {
        val cols = t.rows.maxOfOrNull { it.size } ?: return
        if (cols == 0) return
        val contentWidthTwips = contentWidthTwips()
        val colWidth = contentWidthTwips / cols
        append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"$contentWidthTwips\" w:type=\"dxa\"/>")
        append("<w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>")
        }
        append("</w:tblBorders><w:tblLook w:val=\"04A0\" w:firstRow=\"1\"/></w:tblPr><w:tblGrid>")
        repeat(cols) { append("<w:gridCol w:w=\"$colWidth\"/>") }
        append("</w:tblGrid>")
        t.rows.forEachIndexed { rowIndex, row ->
            val header = rowIndex == 0 && t.hasHeader
            append("<w:tr>")
            if (header) append("<w:trPr><w:tblHeader/></w:trPr>")
            for (c in 0 until cols) {
                append("<w:tc><w:tcPr><w:tcW w:w=\"$colWidth\" w:type=\"dxa\"/></w:tcPr>")
                paragraph(runs = listOf(TextRun(row.getOrElse(c) { "" }, bold = header)))
                append("</w:tc>")
            }
            append("</w:tr>")
        }
        append("</w:tbl>")
        // Word requires a paragraph between consecutive tables and after a table at the end of a cell/body.
        append("<w:p/>")
    }

    private fun pageTwips(): Pair<Int, Int> {
        val w = (options.pageSize.widthPt.takeIf { it > 0 } ?: PageSize.A4.widthPt) * 20
        val h = (options.pageSize.heightPt.takeIf { it > 0 } ?: PageSize.A4.heightPt) * 20
        return if (options.orientation == Orientation.LANDSCAPE) h.toInt() to w.toInt() else w.toInt() to h.toInt()
    }

    private fun contentWidthTwips(): Int = pageTwips().first - 2 * (options.margins.pt * 20).toInt()

    private fun sectionProperties(): String {
        val (w, h) = pageTwips()
        val m = (options.margins.pt * 20).toInt()
        val orient = if (options.orientation == Orientation.LANDSCAPE) " w:orient=\"landscape\"" else ""
        return "<w:sectPr><w:pgSz w:w=\"$w\" w:h=\"$h\"$orient/>" +
            "<w:pgMar w:top=\"$m\" w:right=\"$m\" w:bottom=\"$m\" w:left=\"$m\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>"
    }

    private fun styles(): String {
        val fonts = "<w:rFonts w:ascii=\"${options.latinFont}\" w:hAnsi=\"${options.latinFont}\" w:eastAsia=\"${options.latinFont}\" w:cs=\"${options.complexScriptFont}\"/>"
        val body = options.bodySizePt * 2
        fun heading(level: Int, sizePt: Int) =
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\"><w:name w:val=\"heading $level\"/><w:basedOn w:val=\"Normal\"/>" +
                "<w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/>" +
                "<w:outlineLvl w:val=\"${level - 1}\"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val=\"1F3864\"/><w:sz w:val=\"${sizePt * 2}\"/><w:szCs w:val=\"${sizePt * 2}\"/></w:rPr></w:style>"
        return XML_DECL + "<w:styles xmlns:w=\"$W_NS\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr>$fonts<w:sz w:val=\"$body\"/><w:szCs w:val=\"$body\"/>" +
            "<w:lang w:val=\"${options.language}\" w:bidi=\"hi-IN\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            heading(1, 18) + heading(2, 14) + heading(3, 12) +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:uiPriority w:val=\"34\"/><w:qFormat/><w:pPr><w:spacing w:after=\"60\"/><w:contextualSpacing/></w:pPr></w:style>" +
            "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:tblPr><w:tblCellMar>" +
            "<w:left w:w=\"108\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
            "</w:styles>"
    }

    private fun settings(): String = XML_DECL +
        "<w:settings xmlns:w=\"$W_NS\"><w:defaultTabStop w:val=\"720\"/><w:compat><w:compatSetting w:name=\"compatibilityMode\" " +
        "w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>"

    private fun contentTypes() = XML_DECL +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
        "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
        "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
        "</Types>"

    private fun rootRels() = XML_DECL +
        "<Relationships xmlns=\"$PKG_REL_NS\">" +
        "<Relationship Id=\"rId1\" Type=\"$OFFICE_REL/officeDocument\" Target=\"word/document.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"$OFFICE_REL/extended-properties\" Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

    private fun documentRels() = XML_DECL +
        "<Relationships xmlns=\"$PKG_REL_NS\">" +
        "<Relationship Id=\"rId1\" Type=\"$OFFICE_REL/styles\" Target=\"styles.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"$OFFICE_REL/numbering\" Target=\"numbering.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"$OFFICE_REL/settings\" Target=\"settings.xml\"/>" +
        "</Relationships>"

    private fun coreProps(doc: RichDocument): String {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val title = options.title ?: doc.title
        return XML_DECL +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            (if (title != null) "<dc:title>${Xml.escape(title)}</dc:title>" else "") +
            "<dc:creator>${Xml.escape(options.creator)}</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified>" +
            "</cp:coreProperties>"
    }

    private fun appProps() = XML_DECL +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>One-Minute Docs</Application></Properties>"

    /** Assigns a Word numbering instance to each contiguous list so numbered lists restart at 1. */
    private class NumberingPlan(private val numIdByBlock: Map<Int, Int>, private val instances: List<Boolean>) {
        fun numIdFor(blockIndex: Int): Int = numIdByBlock.getValue(blockIndex)

        fun numberingXml(): String = buildString {
            append(XML_DECL).append("<w:numbering xmlns:w=\"$W_NS\">")
            append(abstractNum(0, ordered = false))
            append(abstractNum(1, ordered = true))
            instances.forEachIndexed { i, ordered ->
                val numId = i + 1
                append("<w:num w:numId=\"$numId\"><w:abstractNumId w:val=\"${if (ordered) 1 else 0}\"/>")
                if (ordered) append("<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride>")
                append("</w:num>")
            }
            append("</w:numbering>")
        }

        private fun abstractNum(id: Int, ordered: Boolean): String = buildString {
            append("<w:abstractNum w:abstractNumId=\"$id\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
            val bullets = listOf("•", "◦", "▪")
            val formats = listOf("decimal", "lowerLetter", "lowerRoman")
            for (lvl in 0..2) {
                append("<w:lvl w:ilvl=\"$lvl\"><w:start w:val=\"1\"/>")
                if (ordered) append("<w:numFmt w:val=\"${formats[lvl]}\"/><w:lvlText w:val=\"%${lvl + 1}.\"/>")
                else append("<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"${bullets[lvl]}\"/>")
                append("<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 * (lvl + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
            }
            append("</w:abstractNum>")
        }

        companion object {
            fun from(blocks: List<Block>): NumberingPlan {
                val map = HashMap<Int, Int>()
                val instances = mutableListOf<Boolean>()
                var current: Int? = null
                var currentOrdered = false
                blocks.forEachIndexed { i, b ->
                    if (b is Block.ListItem) {
                        val rootOrdered = if (b.level == 0) b.ordered else currentOrdered
                        if (current == null || (b.level == 0 && b.ordered != currentOrdered)) {
                            instances += rootOrdered
                            current = instances.size
                            currentOrdered = rootOrdered
                        }
                        map[i] = current!!
                    } else {
                        current = null
                    }
                }
                return NumberingPlan(map, instances)
            }
        }
    }

    companion object {
        private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
        private const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    }
}
