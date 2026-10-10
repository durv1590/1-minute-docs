package com.oneminutedocs.document

internal object Xml {
    /** Escapes text for XML 1.0 and drops characters XML 1.0 cannot represent. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            when {
                cp == '&'.code -> sb.append("&amp;")
                cp == '<'.code -> sb.append("&lt;")
                cp == '>'.code -> sb.append("&gt;")
                cp == '"'.code -> sb.append("&quot;")
                cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF -> sb.appendCodePoint(cp)
                else -> Unit
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
