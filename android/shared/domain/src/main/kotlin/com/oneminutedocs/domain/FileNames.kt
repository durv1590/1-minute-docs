package com.oneminutedocs.domain

/** Makes user-supplied names safe for app storage and for SAF `CreateDocument` suggestions. */
object FileNames {
    const val MAX_BASE_LENGTH = 120
    private val reservedWindowsNames = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
    private val forbidden = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /**
     * Returns a base name (without extension) that contains no path separators, control or reserved characters,
     * is trimmed, and is not empty. Unicode letters (including Devanagari) are preserved.
     */
    fun sanitizeBase(input: String, fallback: String = "document"): String {
        var s = input.replace(forbidden, "_").trim().trim('.').trim()
        s = s.replace(Regex("_+"), "_").replace(Regex("\\s+"), " ")
        if (s.length > MAX_BASE_LENGTH) s = s.take(MAX_BASE_LENGTH).trimEnd()
        if (s.isEmpty() || s.all { it == '_' }) s = fallback
        if (s.uppercase() in reservedWindowsNames) s = "${s}_"
        return s
    }

    /** Strips a known extension from [name] if present. */
    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val ext = name.substring(dot + 1)
        return if (DocumentFormat.fromExtension(ext) != null) name.substring(0, dot) else name
    }

    fun withExtension(base: String, format: DocumentFormat): String = "${sanitizeBase(baseName(base))}.${format.extension}"

    /** Returns [desired] or "name (2).ext", "name (3).ext"… so it does not collide with [existing] (case-insensitive). */
    fun unique(desired: String, existing: Set<String>): String {
        val lower = existing.mapTo(HashSet()) { it.lowercase() }
        if (desired.lowercase() !in lower) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase() !in lower) return candidate
            n++
        }
    }

    /** Default output name for an operation, e.g. "report_compressed.pdf". */
    fun derived(sourceName: String, suffix: String, format: DocumentFormat): String =
        withExtension("${sanitizeBase(baseName(sourceName))}_$suffix", format)
}
