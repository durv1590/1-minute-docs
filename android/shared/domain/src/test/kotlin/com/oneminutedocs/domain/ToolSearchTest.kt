package com.oneminutedocs.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolSearchTest {
    private val titles = mapOf(ToolId.MERGE_PDF to "पीडीएफ़ मर्ज करें", ToolId.COMPRESS_PDF to "Compress PDF")
    private fun t(id: ToolId) = titles[id] ?: id.name
    private fun d(@Suppress("UNUSED_PARAMETER") id: ToolId) = ""

    @Test fun emptyQueryReturnsAll() = assertEquals(ToolId.entries.size, ToolSearch.filter("  ", title = ::t, description = ::d).size)
    @Test fun keywordMatches() = assertTrue(ToolId.MERGE_PDF in ToolSearch.filter("combine", title = ::t, description = ::d))
    @Test fun caseInsensitive() = assertTrue(ToolId.COMPRESS_PDF in ToolSearch.filter("COMPRESS", title = ::t, description = ::d))
    @Test fun hindiWithoutNukta() = assertTrue(ToolId.MERGE_PDF in ToolSearch.filter("पीडीएफ", title = ::t, description = ::d))
    @Test fun allTermsRequired() = assertEquals(listOf(ToolId.COMPRESS_PDF), ToolSearch.filter("compress smaller", title = ::t, description = ::d))
    @Test fun everyToolHasOutputs() = ToolId.entries.forEach { assertTrue(it.outputs.isNotEmpty(), it.name) }
}
