package com.oneminutedocs.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NoteStructurerTest {
    @Test fun structuresTypicalNotes() {
        val raw = """
            Photosynthesis

            Definition:
            Plants convert light energy into chem-
            ical energy stored in glucose.
            • Needs sunlight
            • Needs chlorophyll
            1. Light reaction
            2. Dark reaction
        """.trimIndent()
        val b = NoteStructurer.structure(raw).blocks
        assertEquals(Block.Heading(1, listOf(TextRun("Photosynthesis"))), b[0])
        assertEquals(Block.Heading(2, listOf(TextRun("Definition"))), b[1])
        assertEquals("Plants convert light energy into chemical energy stored in glucose.", b[2].plainText())
        assertEquals(listOf(false, false, true, true), b.drop(3).map { (it as Block.ListItem).ordered })
    }

    @Test fun hindiNotes() {
        val raw = "प्रकाश संश्लेषण\n\nपौधे सूर्य के प्रकाश से\nभोजन बनाते हैं।\n- क्लोरोफिल आवश्यक है"
        val b = NoteStructurer.structure(raw).blocks
        assertIs<Block.Heading>(b[0])
        assertEquals("पौधे सूर्य के प्रकाश से भोजन बनाते हैं।", b[1].plainText())
        assertIs<Block.ListItem>(b[2])
    }

    @Test fun neverDropsText() {
        val raw = "A. P. J. Abdul Kalam was born in 1931\nin Rameswaram.\n\nSHORT CAPS\nmore text"
        val out = NoteStructurer.structure(raw).plainText().replace(Regex("\\s+"), "")
        assertEquals(raw.replace(Regex("\\s+"), ""), out)
    }

    @Test fun formFeedIsPageBreak() = assertEquals(Block.PageBreak, NoteStructurer.structure("page one text.\u000Cpage two text.").blocks[1])
}
