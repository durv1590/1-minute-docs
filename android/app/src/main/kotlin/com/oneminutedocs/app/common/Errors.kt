package com.oneminutedocs.app.common

import androidx.annotation.StringRes
import com.oneminutedocs.app.R
import com.oneminutedocs.domain.DocError
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** User-facing message for every error code. Messages never include document content. */
@StringRes
fun errorMessage(code: ErrorCode): Int = when (code) {
    ErrorCode.UNSUPPORTED_FORMAT -> R.string.error_unsupported_format
    ErrorCode.CORRUPT_FILE -> R.string.error_corrupt
    ErrorCode.ENCRYPTED -> R.string.error_encrypted
    ErrorCode.WRONG_PASSWORD -> R.string.error_wrong_password
    ErrorCode.TOO_LARGE -> R.string.error_too_large
    ErrorCode.TOO_MANY_PAGES -> R.string.error_too_many_pages
    ErrorCode.TOO_MANY_FILES -> R.string.error_too_many_files
    ErrorCode.INVALID_PAGE_RANGE -> R.string.error_page_range
    ErrorCode.INVALID_INPUT -> R.string.error_invalid_input
    ErrorCode.NO_SPACE -> R.string.error_no_space
    ErrorCode.OUT_OF_MEMORY -> R.string.error_out_of_memory
    ErrorCode.PERMISSION_REVOKED -> R.string.error_permission_revoked
    ErrorCode.FILE_NOT_FOUND -> R.string.error_file_not_found
    ErrorCode.NETWORK -> R.string.error_network
    ErrorCode.SERVER -> R.string.error_server
    ErrorCode.SERVER_BUSY -> R.string.error_server_busy
    ErrorCode.RATE_LIMITED -> R.string.error_rate_limited
    ErrorCode.CANCELLED -> R.string.error_cancelled
    ErrorCode.EXPIRED -> R.string.error_expired
    ErrorCode.AI_NOT_CONFIGURED -> R.string.error_ai_not_configured
    ErrorCode.AI_REFUSED -> R.string.error_ai_refused
    ErrorCode.SERVER_NOT_CONFIGURED -> R.string.error_server_not_configured
    ErrorCode.TIMEOUT -> R.string.error_timeout
    ErrorCode.OCR_MODEL_MISSING -> R.string.error_ocr_model
    ErrorCode.OCR_FAILED -> R.string.error_ocr_failed
    ErrorCode.CONVERSION_FAILED -> R.string.error_conversion_failed
    ErrorCode.CAMERA_UNAVAILABLE -> R.string.error_camera
    ErrorCode.NOTHING_TO_DO -> R.string.error_nothing_to_do
    ErrorCode.UNKNOWN -> R.string.error_unknown
}

/** Converts any failure into a [DocError]. Cancellation is re-thrown so coroutines stop cleanly. */
fun Throwable.toDocError(): DocError = when (this) {
    is CancellationException -> throw this
    is DocException -> error
    is OutOfMemoryError -> DocError(ErrorCode.OUT_OF_MEMORY)
    is SecurityException -> DocError(ErrorCode.PERMISSION_REVOKED)
    is IOException -> DocError(ErrorCode.CORRUPT_FILE, javaClass.simpleName)
    is IllegalArgumentException -> DocError(ErrorCode.INVALID_INPUT, message)
    else -> DocError(ErrorCode.UNKNOWN, javaClass.simpleName)
}
