package com.oneminutedocs.domain

/**
 * Identifies a file's real format from its leading bytes, so that file extensions and
 * caller-supplied MIME types are never trusted on their own.
 */
object FileSignature {
    /** Number of leading bytes callers should read before calling [sniff]. */
    const val HEADER_SIZE = 1024

    enum class Kind { PDF, JPEG, PNG, WEBP, HEIF, ZIP, OLE2, RTF, TEXT, UNKNOWN }

    fun sniff(header: ByteArray): Kind {
        if (header.isEmpty()) return Kind.UNKNOWN
        if (startsWith(header, "%PDF-".toByteArray()) || pdfMarkerNearStart(header)) return Kind.PDF
        if (startsWith(header, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) return Kind.JPEG
        if (startsWith(header, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) return Kind.PNG
        if (header.size >= 12 && ascii(header, 0, 4) == "RIFF" && ascii(header, 8, 4) == "WEBP") return Kind.WEBP
        if (header.size >= 12 && ascii(header, 4, 4) == "ftyp") {
            val brand = ascii(header, 8, 4)
            if (brand in setOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1", "avif")) return Kind.HEIF
        }
        if (startsWith(header, byteArrayOf(0x50, 0x4B, 0x03, 0x04))) return Kind.ZIP
        if (startsWith(header, byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))) return Kind.OLE2
        if (startsWith(header, "{\\rtf".toByteArray())) return Kind.RTF
        if (looksLikeUtf8Text(header)) return Kind.TEXT
        return Kind.UNKNOWN
    }

    /**
     * Resolves a ZIP container to an office format using its entry names and, for ODF, the `mimetype` entry.
     * Returns null for ZIPs that are not supported documents.
     */
    fun classifyZip(entryNames: Collection<String>, odfMimetype: String?): DocumentFormat? {
        if ("[Content_Types].xml" in entryNames && entryNames.any { it == "word/document.xml" }) return DocumentFormat.DOCX
        if (odfMimetype?.trim() == DocumentFormat.ODT.mimeType) return DocumentFormat.ODT
        return null
    }

    /** Maps a sniffed kind to a format; ZIP and OLE2 need further inspection and return null. */
    fun formatOf(kind: Kind): DocumentFormat? = when (kind) {
        Kind.PDF -> DocumentFormat.PDF
        Kind.JPEG -> DocumentFormat.JPEG
        Kind.PNG -> DocumentFormat.PNG
        Kind.WEBP -> DocumentFormat.WEBP
        Kind.HEIF -> DocumentFormat.HEIF
        Kind.RTF -> DocumentFormat.RTF
        Kind.TEXT -> DocumentFormat.TXT
        Kind.OLE2 -> DocumentFormat.DOC // Legacy .doc; also .xls/.ppt share OLE2 — conversion engine validates further.
        Kind.ZIP, Kind.UNKNOWN -> null
    }

    // Some PDF writers emit a few junk bytes before the header; readers accept the marker within the first 1024 bytes.
    private fun pdfMarkerNearStart(header: ByteArray): Boolean {
        val marker = "%PDF-".toByteArray()
        val limit = minOf(header.size, HEADER_SIZE) - marker.size
        for (i in 1..limit) {
            if ((marker.indices).all { header[i + it] == marker[it] }) return true
        }
        return false
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean =
        data.size >= prefix.size && prefix.indices.all { data[it] == prefix[it] }

    private fun ascii(data: ByteArray, offset: Int, length: Int): String =
        String(data, offset, length, Charsets.ISO_8859_1)

    private fun looksLikeUtf8Text(data: ByteArray): Boolean {
        var i = 0
        var control = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            when {
                b == 0 -> return false
                b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C -> control++
                b >= 0x80 -> {
                    val extra = when {
                        b and 0xE0 == 0xC0 -> 1
                        b and 0xF0 == 0xE0 -> 2
                        b and 0xF8 == 0xF0 -> 3
                        else -> return false
                    }
                    // A multi-byte sequence cut off by the header boundary is acceptable.
                    for (k in 1..extra) {
                        if (i + k >= data.size) return control * 20 < data.size
                        if ((data[i + k].toInt() and 0xC0) != 0x80) return false
                    }
                    i += extra
                }
            }
            i++
        }
        return control * 20 < data.size
    }
}
