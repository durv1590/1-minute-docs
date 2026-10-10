package com.oneminutedocs.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSignatureTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun pdf() = assertEquals(FileSignature.Kind.PDF, FileSignature.sniff("%PDF-1.7\n%âãÏÓ".toByteArray()))
    @Test fun pdfWithLeadingJunk() = assertEquals(FileSignature.Kind.PDF, FileSignature.sniff("\u0000\u0000junk%PDF-1.4".toByteArray()))
    @Test fun jpeg() = assertEquals(FileSignature.Kind.JPEG, FileSignature.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
    @Test fun png() = assertEquals(FileSignature.Kind.PNG, FileSignature.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
    @Test fun webp() = assertEquals(FileSignature.Kind.WEBP, FileSignature.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
    @Test fun heic() = assertEquals(FileSignature.Kind.HEIF, FileSignature.sniff("\u0000\u0000\u0000\u0018ftypheic".toByteArray(Charsets.ISO_8859_1)))
    @Test fun zip() = assertEquals(FileSignature.Kind.ZIP, FileSignature.sniff(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0)))
    @Test fun ole2() = assertEquals(FileSignature.Kind.OLE2, FileSignature.sniff(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)))
    @Test fun rtf() = assertEquals(FileSignature.Kind.RTF, FileSignature.sniff("{\\rtf1\\ansi".toByteArray()))
    @Test fun hindiText() = assertEquals(FileSignature.Kind.TEXT, FileSignature.sniff("नमस्ते दुनिया\nHello".toByteArray()))

    @Test fun truncatedMultibyteAtBoundaryStillText() {
        val full = "नमस्ते".toByteArray()
        assertEquals(FileSignature.Kind.TEXT, FileSignature.sniff(full.copyOf(full.size - 1)))
    }

    @Test fun binaryIsUnknown() = assertEquals(FileSignature.Kind.UNKNOWN, FileSignature.sniff(bytes(0x00, 0x01, 0x02, 0xFE)))
    @Test fun emptyIsUnknown() = assertEquals(FileSignature.Kind.UNKNOWN, FileSignature.sniff(ByteArray(0)))

    @Test fun classifyDocx() = assertEquals(
        DocumentFormat.DOCX,
        FileSignature.classifyZip(listOf("[Content_Types].xml", "word/document.xml", "_rels/.rels"), null),
    )

    @Test fun classifyOdt() = assertEquals(DocumentFormat.ODT, FileSignature.classifyZip(listOf("mimetype", "content.xml"), "application/vnd.oasis.opendocument.text"))
    @Test fun classifyXlsxIsUnsupported() = assertNull(FileSignature.classifyZip(listOf("[Content_Types].xml", "xl/workbook.xml"), null))
    @Test fun renamedExtensionDoesNotMatter() = assertEquals(DocumentFormat.PDF, FileSignature.formatOf(FileSignature.sniff("%PDF-1.4".toByteArray())))
}
