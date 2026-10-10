package com.oneminutedocs.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownLiteTest {
    @Test fun headingsParagraphsLists() {
        val doc = MarkdownLite.parse(
            """
            # Title
            First line
            second line

            ## Section
            - one
            - two
              - nested
            1. first
            2) second
            """.trimIndent(),
        )
        val b = doc.blocks
        assertEquals(Block.Heading(1, listOf(TextRun("Title"))), b[0])
        assertEquals("First line\nsecond line", b[1].plainText())
        assertIs<Block.Heading>(b[2])
        assertEquals(Block.ListItem(listOf(TextRun("one")), ordered = false, level = 0), b[3])
        assertEquals(1, (b[5] as Block.ListItem).level)
        assertEquals(true, (b[6] as Block.ListItem).ordered)
        assertEquals(true, (b[7] as Block.ListItem).ordered)
    }

    @Test fun inlineFormatting() {
        val runs = MarkdownLite.parseInline("a **bold** and *it* and ++u++ end")
        assertEquals(
            listOf(
                TextRun("a "), TextRun("bold", bold = true), TextRun(" and "), TextRun("it", italic = true),
                TextRun(" and "), TextRun("u", underline = true), TextRun(" end"),
            ),
            runs,
        )
    }

    @Test fun snakeCaseIsNotItalic() = assertEquals(listOf(TextRun("file_name_here")), MarkdownLite.parseInline("file_name_here"))
    @Test fun unmatchedStarIsLiteral() = assertEquals("2 * 3 = 6", MarkdownLite.parseInline("2 * 3 = 6").plainText())
    @Test fun escapedStar() = assertEquals("*not italic*", MarkdownLite.parseInline("\\*not italic\\*").plainText())

    @Test fun tableWithHeader() {
        val t = MarkdownLite.parse("| A | B |\n| --- | --- |\n| 1 | 2 |").blocks.single() as Block.Table
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), t.rows)
        assertEquals(true, t.hasHeader)
    }

    @Test fun pageBreakAndThematicBreak() {
        val b = MarkdownLite.parse("a\n\n<!-- pagebreak -->\n\nb\n\n---\n\nc").blocks
        assertEquals(listOf("a", "", "b", "c"), b.map { it.plainText() })
        assertEquals(Block.PageBreak, b[1])
    }

    @Test fun hindiList() {
        val b = MarkdownLite.parse("१. पहला\n२. दूसरा").blocks
        assertEquals(2, b.size)
        assertEquals("दूसरा", b[1].plainText())
    }

    @Test fun roundTrip() {
        val src = "# शीर्षक\n\nPara with **bold** text\n\n- a\n- b\n\n1. x\n2. y\n\n| H1 | H2 |\n| --- | --- |\n| c | d |\n\n<!-- pagebreak -->\n\nEnd"
        val doc = MarkdownLite.parse(src)
        assertEquals(doc, MarkdownLite.parse(MarkdownLite.toMarkdown(doc)))
    }
}
