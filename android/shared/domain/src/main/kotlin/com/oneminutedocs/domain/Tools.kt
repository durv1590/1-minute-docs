package com.oneminutedocs.domain

enum class ToolCategory { PDF_TOOLS, SCAN, HANDWRITING, AI, CONVERSION, SECURITY }

/** Where an operation runs. Shown on every tool card (requirement P-5). */
enum class Availability { OFFLINE, INTERNET_REQUIRED, OFFLINE_WITH_ONLINE_ENHANCEMENT }

enum class ToolId(
    val category: ToolCategory,
    val inputs: Set<DocumentFormat>,
    val outputs: Set<DocumentFormat>,
    val availability: Availability,
    val multipleInputs: Boolean = false,
    /** English search keywords in addition to the localized title/description. */
    val keywords: List<String> = emptyList(),
) {
    CREATE_PDF(ToolCategory.PDF_TOOLS, setOf(DocumentFormat.TXT), setOf(DocumentFormat.PDF), Availability.OFFLINE, keywords = listOf("new", "text", "write", "template")),
    IMAGES_TO_PDF(ToolCategory.PDF_TOOLS, IMAGE_FORMATS, setOf(DocumentFormat.PDF), Availability.OFFLINE, multipleInputs = true, keywords = listOf("photo", "jpg", "png", "picture")),
    MERGE_PDF(ToolCategory.PDF_TOOLS, setOf(DocumentFormat.PDF), setOf(DocumentFormat.PDF), Availability.OFFLINE, multipleInputs = true, keywords = listOf("combine", "join")),
    SPLIT_PDF(ToolCategory.PDF_TOOLS, setOf(DocumentFormat.PDF), setOf(DocumentFormat.PDF), Availability.OFFLINE, keywords = listOf("extract", "separate", "pages")),
    COMPRESS_PDF(ToolCategory.PDF_TOOLS, setOf(DocumentFormat.PDF), setOf(DocumentFormat.PDF), Availability.OFFLINE, keywords = listOf("reduce", "size", "smaller", "shrink")),
    PAGE_MANAGER(ToolCategory.PDF_TOOLS, setOf(DocumentFormat.PDF), setOf(DocumentFormat.PDF), Availability.OFFLINE, keywords = listOf("rotate", "reorder", "delete", "organize")),
    ANNOTATE_PDF(ToolCategory.PDF_TOOLS, setOf(DocumentFormat.PDF), setOf(DocumentFormat.PDF), Availability.OFFLINE, keywords = listOf("edit", "highlight", "draw", "text", "comment")),
    SIGN_PDF(ToolCategory.PDF_TOOLS, setOf(DocumentFormat.PDF), setOf(DocumentFormat.PDF), Availability.OFFLINE, keywords = listOf("signature", "sign")),
    PDF_TO_IMAGES(ToolCategory.CONVERSION, setOf(DocumentFormat.PDF), setOf(DocumentFormat.PNG, DocumentFormat.JPEG), Availability.OFFLINE, keywords = listOf("jpg", "png", "export")),
    PDF_TO_WORD(ToolCategory.CONVERSION, setOf(DocumentFormat.PDF), setOf(DocumentFormat.DOCX), Availability.OFFLINE_WITH_ONLINE_ENHANCEMENT, keywords = listOf("docx", "word", "editable")),
    WORD_TO_PDF(ToolCategory.CONVERSION, setOf(DocumentFormat.DOCX, DocumentFormat.DOC, DocumentFormat.ODT, DocumentFormat.RTF), setOf(DocumentFormat.PDF), Availability.OFFLINE_WITH_ONLINE_ENHANCEMENT, keywords = listOf("docx", "office")),
    PROTECT_PDF(ToolCategory.SECURITY, setOf(DocumentFormat.PDF), setOf(DocumentFormat.PDF), Availability.OFFLINE, keywords = listOf("password", "encrypt", "lock")),
    UNLOCK_PDF(ToolCategory.SECURITY, setOf(DocumentFormat.PDF), setOf(DocumentFormat.PDF), Availability.OFFLINE, keywords = listOf("password", "decrypt", "remove")),
    SCAN_DOCUMENT(ToolCategory.SCAN, IMAGE_FORMATS, setOf(DocumentFormat.PDF), Availability.OFFLINE, keywords = listOf("camera", "scanner")),
    OCR(ToolCategory.SCAN, IMAGE_FORMATS + DocumentFormat.PDF, setOf(DocumentFormat.TXT, DocumentFormat.DOCX, DocumentFormat.PDF), Availability.OFFLINE_WITH_ONLINE_ENHANCEMENT, keywords = listOf("text", "recognize", "extract")),
    HANDWRITTEN_NOTES(ToolCategory.HANDWRITING, IMAGE_FORMATS + DocumentFormat.PDF, setOf(DocumentFormat.PDF, DocumentFormat.DOCX), Availability.OFFLINE_WITH_ONLINE_ENHANCEMENT, multipleInputs = true, keywords = listOf("notes", "handwriting", "class")),
    AI_CREATOR(ToolCategory.AI, emptySet(), setOf(DocumentFormat.PDF, DocumentFormat.DOCX), Availability.INTERNET_REQUIRED, keywords = listOf("ai", "generate", "write", "letter", "resume")),
    ;
}

private val IMAGE_FORMATS = setOf(DocumentFormat.JPEG, DocumentFormat.PNG, DocumentFormat.WEBP, DocumentFormat.HEIF)

object ToolSearch {
    /**
     * Filters tools whose localized title/description or English keywords contain every whitespace-separated term of [query].
     * Matching is case-insensitive and ignores Devanagari nukta/combining-mark differences.
     */
    fun filter(
        query: String,
        tools: List<ToolId> = ToolId.entries,
        title: (ToolId) -> String,
        description: (ToolId) -> String,
    ): List<ToolId> {
        val terms = normalize(query).split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return tools
        return tools.filter { tool ->
            val haystack = normalize(title(tool) + " " + description(tool) + " " + tool.keywords.joinToString(" "))
            terms.all { haystack.contains(it) }
        }
    }

    internal fun normalize(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace("़", "") // Devanagari nukta
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
}
