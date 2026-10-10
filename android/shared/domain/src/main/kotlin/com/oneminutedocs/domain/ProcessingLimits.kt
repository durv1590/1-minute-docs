package com.oneminutedocs.domain

/**
 * Input limits for on-device processing. Defaults are conservative so budget devices (2–3 GB RAM) stay responsive;
 * they are configurable and shown to users in the tool catalogue.
 */
data class ProcessingLimits(
    val maxPdfBytes: Long = 200L * MB,
    val maxImageBytes: Long = 40L * MB,
    val maxOfficeBytes: Long = 50L * MB,
    val maxPages: Int = 2_000,
    val maxMergeFiles: Int = 50,
    val maxImagesPerPdf: Int = 200,
    val maxTextChars: Int = 500_000,
    val cloudMaxUploadBytes: Long = 50L * MB,
    val minFreeSpaceMultiplier: Double = 2.5,
) {
    fun maxBytesFor(format: DocumentFormat): Long = when {
        format == DocumentFormat.PDF -> maxPdfBytes
        format.isImage -> maxImageBytes
        format == DocumentFormat.TXT -> maxTextChars.toLong() * 4
        else -> maxOfficeBytes
    }

    fun checkSize(format: DocumentFormat, sizeBytes: Long): Outcome<Unit> =
        if (sizeBytes > maxBytesFor(format)) Outcome.failure(ErrorCode.TOO_LARGE, "size=$sizeBytes limit=${maxBytesFor(format)}")
        else Outcome.Success(Unit)

    fun checkPages(pages: Int): Outcome<Unit> =
        if (pages > maxPages) Outcome.failure(ErrorCode.TOO_MANY_PAGES, "pages=$pages limit=$maxPages") else Outcome.Success(Unit)

    /** True if [freeBytes] is enough to safely write an output derived from inputs totalling [inputBytes]. */
    fun hasSpaceFor(inputBytes: Long, freeBytes: Long): Boolean =
        freeBytes >= (inputBytes * minFreeSpaceMultiplier).toLong() + 10L * MB

    companion object {
        const val MB = 1024L * 1024L
        val DEFAULT = ProcessingLimits()
    }
}

/** Mirrors backend job states. COMPLETED is only reported after the output was validated. */
enum class JobStatus {
    QUEUED, VALIDATING, PROCESSING, FINALIZING, COMPLETED, FAILED, CANCELLED, EXPIRED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED

    companion object {
        fun parse(value: String): JobStatus? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** Size change reported after compression; values come from real file lengths. */
data class SizeReport(val originalBytes: Long, val outputBytes: Long) {
    val savedBytes: Long get() = originalBytes - outputBytes
    val isSmaller: Boolean get() = outputBytes < originalBytes
    /** Percentage reduction (negative if the output grew), rounded to one decimal. */
    val reductionPercent: Double
        get() = if (originalBytes <= 0) 0.0 else Math.round(savedBytes * 1000.0 / originalBytes) / 10.0
}
