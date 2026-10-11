package com.oneminutedocs.core.network

import com.oneminutedocs.core.data.settings.ClientTokenStore
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiBaseUrl

data class UploadFile(val file: File, val fileName: String, val mimeType: String)

/**
 * HTTPS client for the One-Minute Docs backend (/api/v1). Holds no secrets beyond the anonymous client token,
 * never logs request or response bodies, and maps every failure to a domain [ErrorCode].
 */
@Singleton
class BackendClient @Inject constructor(
    private val http: OkHttpClient,
    private val settings: ClientTokenStore,
    @ApiBaseUrl private val baseUrlRaw: String,
) {
    internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private val base: HttpUrl? = baseUrlRaw.trim().takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()

    val isConfigured: Boolean get() = base != null

    suspend fun capabilities(): CapabilitiesDto = get("api/v1/capabilities", authenticated = false) { json.decodeFromString(it) }

    suspend fun submitJob(operation: String, files: List<UploadFile>, options: JsonObject, idempotencyKey: String): JobDto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("operation", operation)
            addFormDataPart("options", options.toString())
            files.forEach { f -> addFormDataPart("files", f.fileName, f.file.asRequestBody(f.mimeType.toMediaType())) }
        }.build()
        return send(authenticated = true) { token ->
            Request.Builder().url(url("api/v1/jobs")).post(body).header("Idempotency-Key", idempotencyKey).auth(token).build()
        }.let { json.decodeFromString(it) }
    }

    suspend fun job(id: String): JobDto = get("api/v1/jobs/${pathSafe(id)}") { json.decodeFromString(it) }

    suspend fun cancel(id: String) {
        send(authenticated = true) { t -> Request.Builder().url(url("api/v1/jobs/${pathSafe(id)}/cancel")).post(ByteArray(0).toRequestBody()).auth(t).build() }
    }

    suspend fun delete(id: String) {
        send(authenticated = true) { t -> Request.Builder().url(url("api/v1/jobs/${pathSafe(id)}")).delete().auth(t).build() }
    }

    /** Streams the job output to [dest]; partial files are removed on failure. */
    suspend fun download(id: String, dest: File, maxBytes: Long) = withContext(Dispatchers.IO) {
        val response = execute(authenticated = true) { t -> Request.Builder().url(url("api/v1/jobs/${pathSafe(id)}/download")).get().auth(t).build() }
        response.use { r ->
            if (!r.isSuccessful) throw mapHttpError(r.code, r.body?.string())
            val body = r.body ?: throw DocException(ErrorCode.SERVER, "empty body")
            try {
                var total = 0L
                body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw DocException(ErrorCode.TOO_LARGE, "download exceeds cap")
                            out.write(buf, 0, n)
                        }
                    }
                }
            } catch (e: Exception) {
                dest.delete()
                throw if (e is IOException) DocException(ErrorCode.NETWORK, e.javaClass.simpleName, e) else e
            }
        }
    }

    suspend fun aiGenerate(request: AiRequestDto): AiResultDto {
        val body = json.encodeToString(AiRequestDto.serializer(), request).toRequestBody(JSON)
        val text = send(authenticated = true) { t -> Request.Builder().url(url("api/v1/ai/generate")).post(body).auth(t).build() }
        return json.decodeFromString(text)
    }

    // --- plumbing -------------------------------------------------------------------------------------------------

    private suspend fun <T> get(path: String, authenticated: Boolean = true, parse: (String) -> T): T =
        parse(send(authenticated) { t -> Request.Builder().url(url(path)).get().apply { if (t != null) auth(t) }.build() })

    /** Executes and returns the body text for 2xx; retries exactly once with a fresh token on 401. */
    private suspend fun send(authenticated: Boolean, build: (String?) -> Request): String = withContext(Dispatchers.IO) {
        execute(authenticated, build).use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw mapHttpError(r.code, text)
            text
        }
    }

    private suspend fun execute(authenticated: Boolean, build: (String?) -> Request): Response {
        if (base == null) throw DocException(ErrorCode.SERVER_NOT_CONFIGURED)
        val token = if (authenticated) token() else null
        val first = call(build(token))
        if (authenticated && first.code == 401) {
            first.close()
            settings.clearClientToken()
            return call(build(token(forceRefresh = true)))
        }
        return first
    }

    private suspend fun token(forceRefresh: Boolean = false): String {
        if (!forceRefresh) settings.clientToken()?.let { return it }
        val request = Request.Builder().url(url("api/v1/auth/anonymous")).post(ByteArray(0).toRequestBody()).build()
        val text = call(request).use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw mapHttpError(r.code, t)
            t
        }
        val parsed = json.decodeFromString<TokenResponse>(text)
        val expiry = runCatching { Instant.parse(parsed.expiresAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis() + 7 * 86_400_000L)
        settings.saveClientToken(parsed.token, expiry)
        return parsed.token
    }

    private suspend fun call(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(
                    DocException(if (e is InterruptedIOException) ErrorCode.TIMEOUT else ErrorCode.NETWORK, e.javaClass.simpleName, e),
                )
            }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private fun url(path: String): HttpUrl = base!!.newBuilder().addPathSegments(path).build()

    private fun Request.Builder.auth(token: String?): Request.Builder = apply { if (token != null) header("Authorization", "Bearer $token") }

    private fun pathSafe(id: String): String {
        require(Regex("^[0-9a-fA-F-]{36}$").matches(id)) { "invalid job id" }
        return id
    }

    internal fun mapHttpError(status: Int, body: String?): DocException {
        val apiCode = body?.let { runCatching { json.decodeFromString<ErrorEnvelope>(it).error.code }.getOrNull() }
        val code = when {
            apiCode == "AI_NOT_CONFIGURED" -> ErrorCode.AI_NOT_CONFIGURED
            apiCode == "AI_REFUSED" -> ErrorCode.AI_REFUSED
            apiCode == "CONSENT_REQUIRED" -> ErrorCode.INVALID_INPUT
            apiCode == "UNSUPPORTED_FORMAT" || status == 415 -> ErrorCode.UNSUPPORTED_FORMAT
            apiCode == "PAYLOAD_TOO_LARGE" || status == 413 -> ErrorCode.TOO_LARGE
            apiCode == "QUOTA_EXCEEDED" || apiCode == "RATE_LIMITED" || status == 429 -> ErrorCode.RATE_LIMITED
            apiCode == "JOB_EXPIRED" || status == 410 -> ErrorCode.EXPIRED
            apiCode == "TIMEOUT" || status == 504 -> ErrorCode.TIMEOUT
            status == 404 -> ErrorCode.FILE_NOT_FOUND
            status == 400 -> ErrorCode.INVALID_INPUT
            status == 503 -> ErrorCode.SERVER_BUSY
            else -> ErrorCode.SERVER
        }
        return DocException(code, "http $status ${apiCode.orEmpty()}")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Maps a failed job's error code from the server to a domain code. */
        fun jobErrorCode(serverCode: String?): ErrorCode = when (serverCode) {
            "ENCRYPTED_DOCUMENT" -> ErrorCode.ENCRYPTED
            "CORRUPT_FILE" -> ErrorCode.CORRUPT_FILE
            "TOO_MANY_PAGES" -> ErrorCode.TOO_MANY_PAGES
            "UNSUPPORTED_FORMAT" -> ErrorCode.UNSUPPORTED_FORMAT
            "TIMEOUT" -> ErrorCode.TIMEOUT
            "CANCELLED" -> ErrorCode.CANCELLED
            "EXPIRED" -> ErrorCode.EXPIRED
            "AI_NOT_CONFIGURED" -> ErrorCode.AI_NOT_CONFIGURED
            "AI_REFUSED" -> ErrorCode.AI_REFUSED
            "CONVERSION_FAILED" -> ErrorCode.CONVERSION_FAILED
            "SERVICE_UNAVAILABLE", "AI_PROVIDER_ERROR" -> ErrorCode.SERVER_BUSY
            else -> ErrorCode.SERVER
        }
    }
}

