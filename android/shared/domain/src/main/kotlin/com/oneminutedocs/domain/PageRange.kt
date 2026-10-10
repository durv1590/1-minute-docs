package com.oneminutedocs.domain

/**
 * Parses user page selections such as `1-3, 5, 8-` (1-based, inclusive, `8-` meaning "8 to the end").
 * Hindi (Devanagari) digits are accepted as well as ASCII digits.
 */
object PageRangeParser {

    sealed interface Result {
        data class Valid(val ranges: List<IntRange>) : Result {
            /** Distinct 0-based page indices in the order the user listed them. */
            val pageIndices: List<Int> get() = ranges.flatMap { it.toList() }.map { it - 1 }.distinct()
        }
        data class Invalid(val reason: Reason, val token: String) : Result
    }

    enum class Reason { EMPTY, SYNTAX, ZERO_OR_NEGATIVE, OUT_OF_BOUNDS, REVERSED }

    fun parse(input: String, pageCount: Int): Result {
        require(pageCount >= 0)
        val normalized = normalizeDigits(input).replace('–', '-').replace('—', '-').trim()
        if (normalized.isEmpty()) return Result.Invalid(Reason.EMPTY, "")
        val ranges = mutableListOf<IntRange>()
        for (raw in normalized.split(',', ';')) {
            val token = raw.trim()
            if (token.isEmpty()) continue
            val dash = token.indexOf('-')
            val (start, end) = if (dash < 0) {
                val n = token.toIntOrNull() ?: return Result.Invalid(Reason.SYNTAX, token)
                n to n
            } else {
                val left = token.substring(0, dash).trim()
                val right = token.substring(dash + 1).trim()
                val s = if (left.isEmpty()) 1 else left.toIntOrNull() ?: return Result.Invalid(Reason.SYNTAX, token)
                val e = if (right.isEmpty()) pageCount else right.toIntOrNull() ?: return Result.Invalid(Reason.SYNTAX, token)
                s to e
            }
            if (start <= 0 || end <= 0) return Result.Invalid(Reason.ZERO_OR_NEGATIVE, token)
            if (start > pageCount || end > pageCount) return Result.Invalid(Reason.OUT_OF_BOUNDS, token)
            if (start > end) return Result.Invalid(Reason.REVERSED, token)
            ranges += start..end
        }
        if (ranges.isEmpty()) return Result.Invalid(Reason.EMPTY, input)
        return Result.Valid(ranges)
    }

    /** Groups for "split every [n] pages": returns 0-based index lists. */
    fun chunks(pageCount: Int, n: Int): List<List<Int>> {
        require(n > 0) { "chunk size must be positive" }
        return (0 until pageCount).chunked(n)
    }

    /** Formats 0-based indices back into a compact 1-based range string, e.g. [0,1,2,4] -> "1-3, 5". */
    fun format(indices: List<Int>): String {
        if (indices.isEmpty()) return ""
        val sorted = indices.distinct().sorted()
        val parts = mutableListOf<String>()
        var runStart = sorted[0]
        var prev = sorted[0]
        for (i in 1..sorted.size) {
            val cur = sorted.getOrNull(i)
            if (cur != prev + 1) {
                parts += if (runStart == prev) "${runStart + 1}" else "${runStart + 1}-${prev + 1}"
                if (cur != null) runStart = cur
            }
            if (cur != null) prev = cur
        }
        return parts.joinToString(", ")
    }

    private fun normalizeDigits(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in '०'..'९') '0' + (c - '०') else c)
    }
}
