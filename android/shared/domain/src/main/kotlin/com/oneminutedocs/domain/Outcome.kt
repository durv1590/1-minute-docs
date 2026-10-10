package com.oneminutedocs.domain

/**
 * Closed set of failure reasons shown to users. Each code maps to a localized string in the app.
 * [detail] is for logs/diagnostics only and must never contain document text, passwords or tokens.
 */
enum class ErrorCode {
    UNSUPPORTED_FORMAT,
    CORRUPT_FILE,
    ENCRYPTED,
    WRONG_PASSWORD,
    TOO_LARGE,
    TOO_MANY_PAGES,
    TOO_MANY_FILES,
    INVALID_PAGE_RANGE,
    INVALID_INPUT,
    NO_SPACE,
    OUT_OF_MEMORY,
    PERMISSION_REVOKED,
    FILE_NOT_FOUND,
    NETWORK,
    SERVER,
    SERVER_BUSY,
    RATE_LIMITED,
    CANCELLED,
    EXPIRED,
    AI_NOT_CONFIGURED,
    OCR_MODEL_MISSING,
    OCR_FAILED,
    CONVERSION_FAILED,
    CAMERA_UNAVAILABLE,
    NOTHING_TO_DO,
    UNKNOWN,
}

data class DocError(val code: ErrorCode, val detail: String? = null) {
    override fun toString(): String = if (detail == null) code.name else "${code.name}: $detail"
}

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: DocError) : Outcome<Nothing>

    companion object {
        fun failure(code: ErrorCode, detail: String? = null): Failure = Failure(DocError(code, detail))
    }
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

fun <T> Outcome<T>.errorOrNull(): DocError? = (this as? Outcome.Failure)?.error

/** Thrown inside processing code to abort with a specific user-facing code; converted to [Outcome.Failure] at the boundary. */
class DocException(val error: DocError, cause: Throwable? = null) : Exception(error.toString(), cause) {
    constructor(code: ErrorCode, detail: String? = null, cause: Throwable? = null) : this(DocError(code, detail), cause)
}
