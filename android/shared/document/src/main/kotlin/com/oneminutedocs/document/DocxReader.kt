package com.oneminutedocs.document

import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the text structure of a .docx (paragraphs, headings, lists, tables, page breaks, bold/italic/underline).
 * Used for on-device "basic" DOCX→PDF and for importing documents into the editor. Images, headers/footers, text boxes,
 * fields and exact layout are not reproduced — callers must label the result accordingly; the backend LibreOffice
 * conversion is the faithful path.
 */
object DocxReader {
    /** Uncompressed size cap per XML part, protecting against zip bombs. */
    const val MAX_PART_BYTES = 64L * 1024 * 1024

    fun read(input: InputStream): RichDocument {
        var documentXml: ByteArray? = null
        var numberingXml: ByteArray? = null
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        "word/document.xml" -> documentXml = zip.readCapped()
                        "word/numbering.xml" -> numberingXml = zip.readCapped()
                    }
                }
            }
        } catch (e: DocException) {
            throw e
        } catch (e: Exception) {
            throw DocException(ErrorCode.CORRUPT_FILE, "zip: ${e.javaClass.simpleName}", e)
        }
        val docBytes = documentXml ?: throw DocException(ErrorCode.UNSUPPORTED_FORMAT, "no word/document.xml")
        val orderedNumIds = numberingXml?.let { parseOrderedNumIds(it) } ?: emptySet()
        return parseDocument(docBytes, orderedNumIds)
    }

    private fun ZipInputStream.readCapped(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_PART_BYTES) throw DocException(ErrorCode.TOO_LARGE, "docx part exceeds cap")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        // Hardening against XXE; not every platform parser supports every feature, so each is best-effort.
        for ((feature, value) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )) {
            try { factory.setFeature(feature, value) } catch (_: Exception) { }
        }
        try {
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw DocException(ErrorCode.CORRUPT_FILE, "xml: ${e.javaClass.simpleName}", e)
        }
    }

    private fun parseOrderedNumIds(bytes: ByteArray): Set<String> {
        val doc = parse(bytes)
        val orderedAbstract = HashSet<String>()
        for (abs in doc.documentElement.childElements("abstractNum")) {
            val firstLevel = abs.childElements("lvl").firstOrNull { it.wAttr("ilvl") == "0" }
            val fmt = firstLevel?.childElements("numFmt")?.firstOrNull()?.wAttr("val")
            if (fmt != null && fmt != "bullet" && fmt != "none") orderedAbstract += abs.wAttr("abstractNumId") ?: continue
        }
        return doc.documentElement.childElements("num")
            .filter { it.childElements("abstractNumId").firstOrNull()?.wAttr("val") in orderedAbstract }
            .mapNotNull { it.wAttr("numId") }
            .toSet()
    }

    private fun parseDocument(bytes: ByteArray, orderedNumIds: Set<String>): RichDocument {
        val doc = parse(bytes)
        val body = doc.documentElement.childElements("body").firstOrNull()
            ?: throw DocException(ErrorCode.CORRUPT_FILE, "no body")
        val blocks = mutableListOf<Block>()
        for (el in body.childElements()) {
            when (el.localName) {
                "p" -> paragraphBlocks(el, orderedNumIds, blocks)
                "tbl" -> blocks += tableBlock(el)
                "sdt" -> el.childElements("sdtContent").firstOrNull()?.childElements("p")?.forEach { paragraphBlocks(it, orderedNumIds, blocks) }
            }
        }
        return RichDocument(blocks)
    }

    private fun paragraphBlocks(p: Element, orderedNumIds: Set<String>, out: MutableList<Block>) {
        val pPr = p.childElements("pPr").firstOrNull()
        val style = pPr?.childElements("pStyle")?.firstOrNull()?.wAttr("val").orEmpty()
        val numPr = pPr?.childElements("numPr")?.firstOrNull()
        val pageBreakBefore = pPr?.childElements("pageBreakBefore")?.isNotEmpty() == true
        if (pageBreakBefore) out += Block.PageBreak

        val runs = mutableListOf<TextRun>()
        var breakAfter = false
        fun collect(container: Element) {
            for (child in container.childElements()) {
                when (child.localName) {
                    "r" -> {
                        val rPr = child.childElements("rPr").firstOrNull()
                        val bold = rPr?.isOn("b") == true
                        val italic = rPr?.isOn("i") == true
                        val underline = rPr?.childElements("u")?.firstOrNull()?.let { it.wAttr("val") != "none" } == true
                        val text = StringBuilder()
                        for (rc in child.childElements()) {
                            when (rc.localName) {
                                "t" -> text.append(rc.textContent)
                                "tab" -> text.append('\t')
                                "br", "cr" -> if (rc.wAttr("type") == "page") breakAfter = true else text.append('\n')
                                "lastRenderedPageBreak" -> Unit
                            }
                        }
                        if (text.isNotEmpty()) runs += TextRun(text.toString(), bold, italic, underline)
                    }
                    "hyperlink", "ins", "smartTag", "fldSimple" -> collect(child)
                }
            }
        }
        collect(p)
        val merged = mergeRuns(runs)
        if (merged.isNotEmpty() && merged.plainText().isNotBlank()) {
            val headingLevel = headingLevel(style)
            out += when {
                headingLevel != null -> Block.Heading(headingLevel, merged)
                numPr != null -> {
                    val numId = numPr.childElements("numId").firstOrNull()?.wAttr("val")
                    val level = numPr.childElements("ilvl").firstOrNull()?.wAttr("val")?.toIntOrNull() ?: 0
                    Block.ListItem(merged, ordered = numId in orderedNumIds, level = level.coerceIn(0, 2))
                }
                else -> Block.Paragraph(merged, alignment(pPr))
            }
        }
        if (breakAfter) out += Block.PageBreak
    }

    private fun tableBlock(tbl: Element): Block.Table {
        val rows = tbl.childElements("tr").map { tr ->
            tr.childElements("tc").map { tc ->
                tc.childElements("p").joinToString("\n") { p ->
                    val tmp = mutableListOf<Block>()
                    paragraphBlocks(p, emptySet(), tmp)
                    tmp.joinToString("\n") { it.plainText() }
                }
            }
        }
        return Block.Table(rows, hasHeader = true)
    }

    private fun headingLevel(style: String): Int? {
        val s = style.lowercase().replace(" ", "")
        return when {
            s == "title" -> 1
            s.startsWith("heading") -> s.removePrefix("heading").toIntOrNull()?.coerceIn(1, 3)
            else -> null
        }
    }

    private fun alignment(pPr: Element?): Alignment = when (pPr?.childElements("jc")?.firstOrNull()?.wAttr("val")) {
        "center" -> Alignment.CENTER
        "right", "end" -> Alignment.END
        "both", "distribute" -> Alignment.JUSTIFY
        else -> Alignment.START
    }

    private fun Element.isOn(name: String): Boolean {
        val el = childElements(name).firstOrNull() ?: return false
        val v = el.wAttr("val") ?: return true
        return v != "0" && v != "false" && v != "off"
    }

    private fun mergeRuns(runs: List<TextRun>): List<TextRun> {
        val out = mutableListOf<TextRun>()
        for (r in runs) {
            val last = out.lastOrNull()
            if (last != null && last.bold == r.bold && last.italic == r.italic && last.underline == r.underline) out[out.lastIndex] = last.copy(text = last.text + r.text)
            else out += r
        }
        return out
    }

    private fun Element.wAttr(name: String): String? =
        getAttributeNS(DocxWriter.W_NS, name).takeIf { it.isNotEmpty() } ?: getAttribute("w:$name").takeIf { it.isNotEmpty() }

    private fun Element.childElements(localName: String? = null): List<Element> {
        val result = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && (localName == null || n.localName == localName)) result += n
            n = n.nextSibling
        }
        return result
    }
}
