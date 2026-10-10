package com.oneminutedocs.document

/**
 * Turns raw recognised text (OCR or handwriting recognition, one visual line per line) into structured blocks without AI:
 * merges wrapped lines into paragraphs, de-hyphenates, detects bullets, numbered items and simple headings.
 * The result is a starting point that the user reviews; it never invents text.
 */
object NoteStructurer {
    private val bullet = Regex("^[-–•·*▪➤→>o]\\s+(.+)$")
    private val numbered = Regex("^(?:\\(?(?:\\d{1,3}|[०-९]{1,3}|[a-zA-Z])[.)])\\s+(.+)$")
    private const val PAGE_SENTINEL = "⟪pagebreak⟫"
    private val sentenceEnd = charArrayOf('.', '!', '?', '।', '॥', ':', ';')

    fun structure(rawText: String): RichDocument {
        val lines = rawText.replace("\r\n", "\n").replace("\u000C", "\n$PAGE_SENTINEL\n").split('\n').map { it.trim() }
        val blocks = mutableListOf<Block>()
        val para = StringBuilder()
        var seenContent = false

        fun flush() {
            if (para.isNotEmpty()) {
                blocks += Block.Paragraph(listOf(TextRun(para.toString())))
                para.clear()
            }
        }

        for ((index, line) in lines.withIndex()) {
            if (line.isEmpty()) { flush(); continue }
            if (line == PAGE_SENTINEL) { flush(); blocks += Block.PageBreak; continue }
            val nextBlank = lines.getOrNull(index + 1)?.isEmpty() ?: true
            val bulletMatch = bullet.find(line)
            val numberedMatch = numbered.find(line)?.takeIf { looksLikeListNumber(line) }
            when {
                bulletMatch != null -> {
                    flush(); blocks += Block.ListItem(listOf(TextRun(bulletMatch.groupValues[1])), ordered = false)
                }
                numberedMatch != null -> {
                    flush(); blocks += Block.ListItem(listOf(TextRun(numberedMatch.groupValues[1])), ordered = true)
                }
                else -> {
                    val heading = headingLevel(line, isFirst = !seenContent, nextBlank = nextBlank, inParagraph = para.isNotEmpty())
                    if (heading != null) {
                        flush(); blocks += Block.Heading(heading, listOf(TextRun(line.trimEnd(':').trim())))
                    } else {
                        appendLine(para, line)
                    }
                }
            }
            seenContent = true
        }
        flush()
        return RichDocument(blocks)
    }

    private fun appendLine(para: StringBuilder, line: String) {
        if (para.isEmpty()) { para.append(line); return }
        val last = para.last()
        if (last == '-' && para.length > 1 && para[para.length - 2].isLetter() && line.first().isLowerCase()) {
            para.setLength(para.length - 1) // de-hyphenate "docu-\nment"
            para.append(line)
        } else {
            para.append(' ').append(line)
        }
    }

    private fun looksLikeListNumber(line: String): Boolean {
        // Avoid treating "A. P. J. Abdul Kalam" or decimals as list items: single letters need a ')' form or uppercase-free continuation.
        val marker = line.substringBefore(' ')
        return marker.any { it.isDigit() } || marker.endsWith(")")
    }

    private fun headingLevel(line: String, isFirst: Boolean, nextBlank: Boolean, inParagraph: Boolean): Int? {
        if (inParagraph || line.length > 70) return null
        val words = line.split(' ').size
        val letters = line.filter { it.isLetter() }
        val latinCaps = letters.length >= 3 && letters.all { it !in 'a'..'z' } && letters.any { it in 'A'..'Z' }
        return when {
            line.endsWith(":") && words <= 8 -> 2
            latinCaps && words <= 10 -> 2
            isFirst && words <= 10 && line.last() !in sentenceEnd && line.last() != ',' && nextBlank -> 1
            else -> null
        }
    }
}
