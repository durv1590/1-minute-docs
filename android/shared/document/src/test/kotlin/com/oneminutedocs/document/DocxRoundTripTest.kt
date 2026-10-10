package com.oneminutedocs.document

import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.Orientation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class DocxRoundTripTest {
    private val sample = MarkdownLite.parse(
        """
        # Report शीर्षक
        Intro with **bold**, *italic* & <special> chars.
        Second line.

        ## Items
        - bullet one
        - bullet दो
          - nested
        1. first
        2. second

        | Name | मात्रा |
        | --- | --- |
        | Pen | 2 |

        <!-- pagebreak -->

        Final paragraph.
        """.trimIndent(),
    )

    private fun write(doc: RichDocument = sample, options: DocxOptions = DocxOptions()): ByteArray =
        ByteArrayOutputStream().also { DocxWriter(options).write(doc, it) }.toByteArray()

    @Test fun packageContainsRequiredPartsAndWellFormedXml() {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(write())).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() }
        }
        for (part in listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/numbering.xml")) {
            assertTrue(part in entries, "missing $part")
        }
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        entries.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (_, bytes) ->
            dbf.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }
        val documentXml = String(entries.getValue("word/document.xml"))
        assertTrue(documentXml.contains("&lt;special&gt;"))
        assertTrue(documentXml.contains("w:type=\"page\""))
    }

    @Test fun readerRecoversStructure() {
        val back = DocxReader.read(ByteArrayInputStream(write()))
        // Inline formatting, list types/levels, tables and page breaks survive; paragraph soft breaks become '\n'.
        assertEquals(sample.blocks, back.blocks)
    }

    @Test fun landscapeSwapsPageSize() {
        val xml = unzipPart(write(options = DocxOptions(orientation = Orientation.LANDSCAPE)), "word/document.xml")
        assertTrue(xml.contains("w:orient=\"landscape\""))
    }

    @Test fun invalidXmlCharsAreDropped() {
        val doc = RichDocument(listOf(Block.Paragraph(listOf(TextRun("a\u0001b￾c")))))
        assertEquals("abc", DocxReader.read(ByteArrayInputStream(write(doc))).plainText())
    }

    @Test fun notAZipIsCorrupt() {
        val e = assertFailsWith<DocException> { DocxReader.read(ByteArrayInputStream("%PDF-1.4".toByteArray())) }
        assertEquals(ErrorCode.UNSUPPORTED_FORMAT, e.error.code)
    }

    @Test fun doctypeIsRejected() {
        val bytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("word/document.xml"))
                z.write("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><w:document xmlns:w=\"${DocxWriter.W_NS}\"><w:body><w:p><w:r><w:t>&e;</w:t></w:r></w:p></w:body></w:document>".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        val e = assertFailsWith<DocException> { DocxReader.read(ByteArrayInputStream(bytes)) }
        assertEquals(ErrorCode.CORRUPT_FILE, e.error.code)
    }

    /** Opens the generated DOCX in LibreOffice (if installed) to prove an independent office engine accepts it. */
    @Test fun libreOfficeOpensOutput() {
        val soffice = listOf("/usr/bin/soffice", "/usr/lib/libreoffice/program/soffice").map(::File).firstOrNull { it.canExecute() }
        assumeTrue(soffice != null, "LibreOffice not installed")
        val dir = Files.createTempDirectory("docx-lo").toFile()
        try {
            val docx = File(dir, "sample.docx").apply { writeBytes(write()) }
            val proc = ProcessBuilder(
                soffice!!.path, "--headless", "--norestore", "-env:UserInstallation=file://${dir.absolutePath}/profile",
                "--convert-to", "txt:Text (encoded):UTF8", "--outdir", dir.absolutePath, docx.absolutePath,
            ).redirectErrorStream(true).start()
            assertTrue(proc.waitFor(120, TimeUnit.SECONDS), "soffice timed out")
            val txt = File(dir, "sample.txt").readText()
            assertTrue(txt.contains("Report शीर्षक"), txt)
            assertTrue(txt.contains("bullet दो"), txt)
            assertTrue(txt.contains("Final paragraph."), txt)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun unzipPart(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return String(z.readBytes()) }
        }
        error("no $name")
    }
}
