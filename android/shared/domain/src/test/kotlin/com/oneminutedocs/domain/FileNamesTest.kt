package com.oneminutedocs.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FileNamesTest {
    @Test fun stripsPathSeparators() {
        val s = FileNames.sanitizeBase("../../etc/passwd")
        assertFalse(s.contains('/'))
        assertFalse(s.startsWith("."))
    }

    @Test fun keepsHindi() = assertEquals("मेरा दस्तावेज़", FileNames.sanitizeBase("  मेरा दस्तावेज़  "))
    @Test fun emptyFallsBack() = assertEquals("document", FileNames.sanitizeBase("///"))
    @Test fun reservedName() = assertEquals("CON_", FileNames.sanitizeBase("con".uppercase()))
    @Test fun controlChars() = assertEquals("a_b", FileNames.sanitizeBase("a\u0000b"))
    @Test fun truncates() = assertEquals(FileNames.MAX_BASE_LENGTH, FileNames.sanitizeBase("x".repeat(500)).length)
    @Test fun withExtensionReplacesKnown() = assertEquals("report.pdf", FileNames.withExtension("report.docx", DocumentFormat.PDF))
    @Test fun withExtensionKeepsUnknownDot() = assertEquals("v1.2 notes.pdf", FileNames.withExtension("v1.2 notes", DocumentFormat.PDF))
    @Test fun unique() = assertEquals("a (3).pdf", FileNames.unique("a.pdf", setOf("A.pdf", "a (2).pdf")))
    @Test fun uniqueNoClash() = assertEquals("b.pdf", FileNames.unique("b.pdf", setOf("a.pdf")))
    @Test fun derived() = assertEquals("scan_compressed.pdf", FileNames.derived("scan.pdf", "compressed", DocumentFormat.PDF))
}
