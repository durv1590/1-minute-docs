package com.oneminutedocs.document

/**
 * A deliberately small Markdown subset used as the editable text form of a [RichDocument]:
 * `#`/`##`/`###` headings, `-`/`*`/`•` bullets (two spaces per nesting level), `1.`/`1)` numbered items,
 * `|a|b|` tables, `**bold**`, `*italic*`, `++underline++`, and `<!-- pagebreak -->` (or `\newpage`) page breaks.
 * Consecutive lines form one paragraph and keep their line breaks; a blank line starts a new paragraph.
 * AI providers are asked to answer in this subset, so their output renders without a Markdown library.
 */
object MarkdownLite {
    const val PAGE_BREAK = "<!-- pagebreak -->"

    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*•·]\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(?:\\d+|[०-९]+)[.)]\\s+(.*)$")
    private val tableSeparator = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
    private val thematicBreak = Regex("^(\\*\\s*){3,}$|^(-\\s*){3,}$|^(_\\s*){3,}$")

    fun parse(text: String, title: String? = null): RichDocument {
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        val table = mutableListOf<List<String>>()
        var tableHasHeader = false

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(parseInline(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }
        fun flushTable() {
            if (table.isNotEmpty()) {
                val width = table.maxOf { it.size }
                blocks += Block.Table(table.map { row -> row + List(width - row.size) { "" } }, tableHasHeader)
                table.clear(); tableHasHeader = false
            }
        }

        for (rawLine in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.startsWith("|") && trimmed.length > 1) {
                flushParagraph()
                if (tableSeparator.matches(trimmed)) { if (table.size == 1) tableHasHeader = true; continue }
                table += trimmed.trim('|').split('|').map { stripInlineMarkers(it.trim()) }
                continue
            } else flushTable()

            when {
                trimmed.isEmpty() -> flushParagraph()
                trimmed == PAGE_BREAK || trimmed == "\\newpage" || trimmed == "\u000C" -> { flushParagraph(); blocks += Block.PageBreak }
                thematicBreak.matches(trimmed) -> flushParagraph()
                heading.matches(trimmed) -> {
                    flushParagraph()
                    val m = heading.find(trimmed)!!
                    blocks += Block.Heading(m.groupValues[1].length.coerceAtMost(3), parseInline(m.groupValues[2].trim().trimEnd('#').trim()))
                }
                bullet.matches(line) && !line.trimStart().startsWith("**") -> {
                    flushParagraph()
                    val m = bullet.find(line)!!
                    blocks += Block.ListItem(parseInline(m.groupValues[2]), ordered = false, level = indentLevel(m.groupValues[1]))
                }
                ordered.matches(line) -> {
                    flushParagraph()
                    val m = ordered.find(line)!!
                    blocks += Block.ListItem(parseInline(m.groupValues[2]), ordered = true, level = indentLevel(m.groupValues[1]))
                }
                else -> paragraph += trimmed
            }
        }
        flushParagraph(); flushTable()
        return RichDocument(blocks, title)
    }

    fun toMarkdown(doc: RichDocument): String {
        val sb = StringBuilder()
        var previous: Block? = null
        for (block in doc.blocks) {
            val separator = if (previous is Block.ListItem && block is Block.ListItem) "\n" else "\n\n"
            if (sb.isNotEmpty()) sb.append(separator)
            when (block) {
                is Block.Heading -> sb.append("#".repeat(block.level)).append(' ').append(inline(block.runs))
                is Block.Paragraph -> sb.append(inline(block.runs))
                is Block.ListItem -> {
                    sb.append("  ".repeat(block.level))
                    sb.append(if (block.ordered) "${orderedNumber(doc.blocks, block)}. " else "- ").append(inline(block.runs))
                }
                is Block.Table -> block.rows.forEachIndexed { i, row ->
                    if (i > 0) sb.append('\n')
                    sb.append("| ").append(row.joinToString(" | ") { it.replace("|", "/") }).append(" |")
                    if (i == 0 && block.hasHeader) sb.append('\n').append("|").append(row.joinToString("|") { " --- " }).append("|")
                }
                Block.PageBreak -> sb.append(PAGE_BREAK)
            }
            previous = block
        }
        return sb.toString()
    }

    /** Parses `**bold**`, `__bold__`, `*italic*`, `_italic_` (word-boundary), `++underline++`. Unmatched markers stay literal. */
    fun parseInline(text: String): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        val buf = StringBuilder()
        var bold = false; var italic = false; var underline = false
        fun flush() { if (buf.isNotEmpty()) { runs += TextRun(buf.toString(), bold, italic, underline); buf.clear() } }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '\\' && next != null && next in "*_+\\#|" -> { buf.append(next); i += 2; continue }
                (c == '*' || c == '_') && next == c && (bold || hasClosing(text, i + 2, "$c$c")) -> { flush(); bold = !bold; i += 2; continue }
                c == '+' && next == '+' && (underline || hasClosing(text, i + 2, "++")) -> { flush(); underline = !underline; i += 2; continue }
                c == '*' || c == '_' -> {
                    if (italic && (c == '*' || isClosingBoundary(text, i))) { flush(); italic = false; i++; continue }
                    val opens = (c == '*' || isBoundary(text, i)) && next != null && next != ' ' && hasClosing(text, i + 1, c.toString())
                    if (!italic && opens) { flush(); italic = true; i++; continue }
                }
            }
            buf.append(c); i++
        }
        flush()
        return mergeAdjacent(runs).ifEmpty { listOf(TextRun("")) }
    }

    private fun inline(runs: List<TextRun>): String = runs.joinToString("") { r ->
        var t = r.text.replace("\\", "\\\\").replace("*", "\\*").replace("++", "\\+\\+")
        if (t.isBlank()) return@joinToString t
        val lead = t.takeWhile { it == ' ' }; val trail = t.takeLastWhile { it == ' ' }
        t = t.trim()
        if (r.italic) t = "*$t*"
        if (r.bold) t = "**$t**"
        if (r.underline) t = "++$t++"
        lead + t + trail
    }

    private fun orderedNumber(blocks: List<Block>, item: Block.ListItem): Int {
        val idx = blocks.indexOf(item)
        var n = 1
        var i = idx - 1
        while (i >= 0) {
            val b = blocks[i] as? Block.ListItem ?: break
            if (b.level == item.level && b.ordered) n++ else if (b.level < item.level) break
            i--
        }
        return n
    }

    private fun hasClosing(text: String, from: Int, marker: String): Boolean {
        val idx = text.indexOf(marker, from)
        return idx > from
    }

    private fun isBoundary(text: String, i: Int): Boolean {
        val before = text.getOrNull(i - 1)
        return before == null || !before.isLetterOrDigit()
    }

    private fun isClosingBoundary(text: String, i: Int): Boolean {
        val after = text.getOrNull(i + 1)
        return after == null || !after.isLetterOrDigit()
    }

    private fun stripInlineMarkers(s: String) = parseInline(s).plainText()

    private fun indentLevel(indent: String): Int = (indent.replace("\t", "  ").length / 2).coerceIn(0, 2)

    private fun mergeAdjacent(runs: List<TextRun>): List<TextRun> {
        val out = mutableListOf<TextRun>()
        for (r in runs) {
            val last = out.lastOrNull()
            if (last != null && last.bold == r.bold && last.italic == r.italic && last.underline == r.underline) {
                out[out.lastIndex] = last.copy(text = last.text + r.text)
            } else out += r
        }
        return out
    }
}
