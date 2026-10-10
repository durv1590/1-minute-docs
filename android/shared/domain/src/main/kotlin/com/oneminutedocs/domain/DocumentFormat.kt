package com.oneminutedocs.domain

enum class DocumentFormat(val mimeType: String, val extension: String) {
    PDF("application/pdf", "pdf"),
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    HEIF("image/heif", "heic"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    DOC("application/msword", "doc"),
    ODT("application/vnd.oasis.opendocument.text", "odt"),
    RTF("application/rtf", "rtf"),
    TXT("text/plain", "txt"),
    ;

    val isImage: Boolean get() = this == JPEG || this == PNG || this == WEBP || this == HEIF
    val isOfficeDocument: Boolean get() = this == DOCX || this == DOC || this == ODT || this == RTF

    companion object {
        fun fromMimeType(mime: String?): DocumentFormat? {
            if (mime == null) return null
            val m = mime.substringBefore(';').trim().lowercase()
            return entries.firstOrNull { it.mimeType == m } ?: when (m) {
                "image/jpg" -> JPEG
                "image/heic" -> HEIF
                "text/rtf" -> RTF
                else -> null
            }
        }

        fun fromExtension(ext: String?): DocumentFormat? {
            val e = ext?.lowercase()?.removePrefix(".") ?: return null
            return entries.firstOrNull { it.extension == e } ?: when (e) {
                "jpeg" -> JPEG
                "heif" -> HEIF
                else -> null
            }
        }
    }
}
