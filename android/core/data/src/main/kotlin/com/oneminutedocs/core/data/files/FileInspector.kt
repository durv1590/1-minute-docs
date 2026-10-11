package com.oneminutedocs.core.data.files

import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.FileSignature
import java.io.File
import java.util.zip.ZipFile

/** Detects a local file's real format from its content (extensions and SAF MIME types are not trusted). */
object FileInspector {
    private const val MAX_ZIP_ENTRIES = 5000

    fun detect(file: File): DocumentFormat? {
        val header = ByteArray(FileSignature.HEADER_SIZE)
        val n = file.inputStream().use { it.read(header) }
        if (n <= 0) return null
        val kind = FileSignature.sniff(header.copyOf(n))
        return when (kind) {
            FileSignature.Kind.ZIP -> classifyZip(file)
            else -> FileSignature.formatOf(kind)
        }
    }

    private fun classifyZip(file: File): DocumentFormat? = try {
        ZipFile(file).use { zip ->
            if (zip.size() > MAX_ZIP_ENTRIES) return null
            val names = zip.entries().asSequence().map { it.name }.toList()
            val mimetype = zip.getEntry("mimetype")?.takeIf { it.size in 0..256 }?.let { e ->
                zip.getInputStream(e).use { String(it.readBytes(), Charsets.US_ASCII) }
            }
            FileSignature.classifyZip(names, mimetype)
        }
    } catch (_: Exception) {
        null
    }
}
