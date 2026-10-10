package com.oneminutedocs.domain

import com.oneminutedocs.domain.PageRangeParser.Reason
import com.oneminutedocs.domain.PageRangeParser.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PageRangeParserTest {
    private fun valid(s: String, n: Int) = assertIs<Result.Valid>(PageRangeParser.parse(s, n))
    private fun invalid(s: String, n: Int) = assertIs<Result.Invalid>(PageRangeParser.parse(s, n))

    @Test fun singlePages() = assertEquals(listOf(0, 2, 4), valid("1,3,5", 5).pageIndices)
    @Test fun ranges() = assertEquals(listOf(0, 1, 2, 4), valid("1-3, 5", 10).pageIndices)
    @Test fun openEnded() = assertEquals(listOf(7, 8, 9), valid("8-", 10).pageIndices)
    @Test fun openStart() = assertEquals(listOf(0, 1), valid("-2", 10).pageIndices)
    @Test fun keepsUserOrderAndDedupes() = assertEquals(listOf(4, 0, 1), valid("5,1-2,1", 5).pageIndices)
    @Test fun devanagariDigits() = assertEquals(listOf(1, 2), valid("२-३", 5).pageIndices)
    @Test fun enDash() = assertEquals(listOf(0, 1), valid("1–2", 5).pageIndices)
    @Test fun outOfBounds() = assertEquals(Reason.OUT_OF_BOUNDS, invalid("4-9", 5).reason)
    @Test fun zero() = assertEquals(Reason.ZERO_OR_NEGATIVE, invalid("0", 5).reason)
    @Test fun reversed() = assertEquals(Reason.REVERSED, invalid("5-2", 5).reason)
    @Test fun garbage() = assertEquals(Reason.SYNTAX, invalid("abc", 5).reason)
    @Test fun empty() = assertEquals(Reason.EMPTY, invalid("  , ", 5).reason)
    @Test fun chunks() = assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), PageRangeParser.chunks(5, 2))
    @Test fun format() = assertEquals("1-3, 5, 7-8", PageRangeParser.format(listOf(6, 0, 1, 2, 4, 7)))
    @Test fun formatSingle() = assertEquals("4", PageRangeParser.format(listOf(3)))
}
