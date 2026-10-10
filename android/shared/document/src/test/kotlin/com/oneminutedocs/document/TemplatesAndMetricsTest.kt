package com.oneminutedocs.document

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplatesAndMetricsTest {
    @Test fun invoiceTotals() {
        val inv = InvoiceCalculator.parse("Pens | 10 | 12.50\nNotebook | 2 | 1,250\nbad line\nTape | १ | ३०")
        assertEquals(3, inv.lines.size)
        assertEquals(listOf("bad line"), inv.rejectedLines)
        assertEquals(BigDecimal("2655.00"), inv.total)
    }

    @Test fun everyTemplateRendersInBothLanguages() {
        for (t in DocumentTemplate.entries) {
            val values = t.fields.associate { it.key to "Value ${it.key}" } + ("items" to "Item | 1 | 100")
            for (lang in ContentLanguage.entries) {
                val md = t.render(lang, values)
                val doc = MarkdownLite.parse(md)
                assertTrue(!doc.isBlank, "$t $lang")
                t.fields.filter { it.required && it.key != "items" }.forEach { assertTrue(md.contains("Value ${it.key}"), "$t missing ${it.key}") }
            }
        }
    }

    @Test fun invoiceRendersTable() {
        val md = DocumentTemplate.INVOICE.render(ContentLanguage.ENGLISH, mapOf("business" to "B", "customer" to "C", "items" to "Rice | 2 | 50"))
        assertTrue(md.contains("| Rice | 2 | 50.00 | 100.00 |"), md)
        assertTrue(md.contains("₹100.00"))
    }

    @Test fun missingRequired() = assertEquals(listOf("senderName", "recipient", "subject", "body"), DocumentTemplate.LETTER.missingRequired(emptyMap()))

    @Test fun cer() {
        assertEquals(0.0, TextMetrics.characterErrorRate("hello  world", "hello world"))
        assertEquals(0.1, TextMetrics.characterErrorRate("abcdefghij", "abcdefghiX"), 1e-9)
        assertEquals(1, TextMetrics.levenshtein("नमस्ते", "नमस्त"))
    }
}
