package com.oneminutedocs.imaging

/**
 * Builds 4×5 colour matrices in Android `ColorMatrix` layout (row-major, offsets in 0..255 units) so enhancement
 * settings are defined and tested once and applied on the GPU via `ColorMatrixColorFilter`.
 */
object ColorAdjust {
    data class Settings(
        /** -100..100 */
        val brightness: Int = 0,
        /** 0.5..2.0 */
        val contrast: Float = 1f,
        val grayscale: Boolean = false,
    )

    fun matrix(s: Settings): FloatArray {
        val c = s.contrast.coerceIn(0.5f, 2f)
        val b = s.brightness.coerceIn(-100, 100) * 1.275f
        // Contrast around mid-grey, then brightness offset.
        val offset = (1f - c) * 127.5f + b
        val base = if (s.grayscale) {
            floatArrayOf(
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        } else identity()
        val out = base.copyOf()
        for (row in 0 until 3) {
            for (col in 0 until 4) out[row * 5 + col] = base[row * 5 + col] * c
            out[row * 5 + 4] = base[row * 5 + 4] * c + offset
        }
        return out
    }

    fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    /** Applies a matrix to one ARGB pixel (reference implementation for tests and CPU fallback). */
    fun apply(m: FloatArray, argb: Int): Int {
        val a = (argb ushr 24) and 0xFF; val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        fun row(i: Int) = (m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 3] * a + m[i * 5 + 4]).toInt().coerceIn(0, 255)
        return (row(3) shl 24) or (row(0) shl 16) or (row(1) shl 8) or row(2)
    }
}
