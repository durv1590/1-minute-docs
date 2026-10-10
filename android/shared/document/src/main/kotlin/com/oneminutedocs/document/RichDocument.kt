package com.oneminutedocs.document

/** Inline text with simple character formatting. */
data class TextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)

enum class Alignment { START, CENTER, END, JUSTIFY }

/**
 * Engine-independent document model used by the editor, the AI creator, handwriting export and template
 * rendering. Renderers (Android PDF writer, [DocxWriter]) consume it; nothing here knows about a provider or engine.
 */
sealed interface Block {
    data class Heading(val level: Int, val runs: List<TextRun>) : Block {
        init { require(level in 1..3) { "heading level must be 1..3" } }
    }
    data class Paragraph(val runs: List<TextRun>, val alignment: Alignment = Alignment.START) : Block
    data class ListItem(val runs: List<TextRun>, val ordered: Boolean, val level: Int = 0) : Block
    data class Table(val rows: List<List<String>>, val hasHeader: Boolean = true) : Block
    data object PageBreak : Block
}

data class RichDocument(val blocks: List<Block>, val title: String? = null) {
    val isBlank: Boolean get() = blocks.all { it is Block.PageBreak || it.plainText().isBlank() }

    fun plainText(): String = blocks.joinToString("\n\n") { it.plainText() }.trim()

    companion object {
        fun ofParagraphs(text: String): RichDocument =
            RichDocument(text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }.map { Block.Paragraph(listOf(TextRun(it.trim()))) })
    }
}

fun List<TextRun>.plainText(): String = joinToString("") { it.text }

fun Block.plainText(): String = when (this) {
    is Block.Heading -> runs.plainText()
    is Block.Paragraph -> runs.plainText()
    is Block.ListItem -> runs.plainText()
    is Block.Table -> rows.joinToString("\n") { it.joinToString("\t") }
    Block.PageBreak -> ""
}

/** Simple measurements used for OCR evaluation (character error rate) and editor statistics. */
object TextMetrics {
    fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

    fun levenshtein(a: String, b: String): Int {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        var prev = IntArray(y.size + 1) { it }
        var cur = IntArray(y.size + 1)
        for (i in 1..x.size) {
            cur[0] = i
            for (j in 1..y.size) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[y.size]
    }

    /** Character error rate of [hypothesis] against [reference], with whitespace normalised. */
    fun characterErrorRate(reference: String, hypothesis: String): Double {
        val r = reference.replace(Regex("\\s+"), " ").trim()
        val h = hypothesis.replace(Regex("\\s+"), " ").trim()
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
        return levenshtein(r, h).toDouble() / r.codePointCount(0, r.length)
    }
}
