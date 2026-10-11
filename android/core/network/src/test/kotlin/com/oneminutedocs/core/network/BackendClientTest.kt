package com.oneminutedocs.core.network

import com.oneminutedocs.core.data.settings.ClientTokenStore
import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.JobStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class BackendClientTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val store = object : ClientTokenStore {
        var token: String? = null
        override suspend fun clientToken() = token
        override suspend fun saveClientToken(token: String, expiresAtMs: Long) { this.token = token }
        override suspend fun clearClientToken() { token = null }
    }
    private val jobId = "11111111-2222-4333-8444-555555555555"
    private var pollsBeforeDone = 2
    private var finalStatus = "COMPLETED"
    private var errorCode: String? = null

    private fun job(status: String, progress: Int? = null): String {
        val output = if (status == "COMPLETED") ""","output":{"fileName":"a.pdf","contentType":"application/pdf","sizeBytes":8,"meta":{"pages":1}}""" else ""
        val error = if (errorCode != null) ""","error":{"code":"$errorCode","message":"m"}""" else ""
        return """{"id":"$jobId","operation":"office_to_pdf","status":"$status","progress":${progress ?: "null"},"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","expiresAt":"2026-01-01T01:00:00Z","inputs":[]$output$error}"""
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty()
                return when {
                    path == "/api/v1/auth/anonymous" -> MockResponse().setResponseCode(201).setBody("""{"token":"tok-${requests.size}","expiresAt":"2099-01-01T00:00:00Z"}""")
                    request.getHeader("Authorization") == "Bearer expired" -> MockResponse().setResponseCode(401).setBody("""{"error":{"code":"UNAUTHORIZED","message":"x","requestId":"r"}}""")
                    path == "/api/v1/jobs" && request.method == "POST" -> MockResponse().setResponseCode(202).setBody(job("QUEUED"))
                    path == "/api/v1/jobs/$jobId" && request.method == "GET" -> {
                        pollsBeforeDone--
                        MockResponse().setBody(if (pollsBeforeDone > 0) job("PROCESSING", 50) else job(finalStatus))
                    }
                    path == "/api/v1/jobs/$jobId/download" -> MockResponse().setBody("%PDF-1.7")
                    path == "/api/v1/jobs/$jobId" && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path == "/api/v1/jobs/$jobId/cancel" -> MockResponse().setBody(job("CANCELLED"))
                    path == "/api/v1/ai/generate" -> MockResponse().setResponseCode(503).setBody("""{"error":{"code":"AI_NOT_CONFIGURED","message":"x","requestId":"r"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun client(base: String = server.url("/").toString()) = BackendClient(OkHttpClient(), store, base)

    private fun tempFile(): File = Files.createTempFile("omd", ".bin").toFile().apply { writeText("PK test") }

    @Test fun runsJobEndToEndAndDeletesServerCopy() = runBlocking {
        val progress = mutableListOf<CloudProgress>()
        val dest = Files.createTempFile("out", ".pdf").toFile()
        val result = CloudJobRunner(client()).run("office_to_pdf", listOf(UploadFile(tempFile(), "a.docx", "application/octet-stream")), JsonObject(emptyMap()), dest, 1000) { progress += it }
        assertEquals("%PDF-1.7", result.file.readText())
        assertEquals(JobStatus.COMPLETED, progress.last().status)
        assertTrue(progress.any { it.percent == 50 })
        val submit = requests.first { it.path == "/api/v1/jobs" }
        assertTrue(submit.getHeader("Idempotency-Key")!!.isNotBlank())
        assertTrue(submit.getHeader("Authorization")!!.startsWith("Bearer tok-"))
        assertTrue(requests.any { it.method == "DELETE" })
        assertFalse(requests.any { it.path!!.endsWith("/cancel") })
    }

    @Test fun failedJobMapsServerCode() = runBlocking {
        finalStatus = "FAILED"; errorCode = "ENCRYPTED_DOCUMENT"
        try {
            CloudJobRunner(client()).run("compress_pdf", listOf(UploadFile(tempFile(), "a.pdf", "application/pdf")), JsonObject(emptyMap()), File.createTempFile("out", ".pdf"), 1000) {}
            fail("expected failure")
        } catch (e: DocException) {
            assertEquals(ErrorCode.ENCRYPTED, e.error.code)
        }
        assertTrue("server copy deleted", requests.any { it.method == "DELETE" })
    }

    @Test fun refreshesExpiredTokenOnce() = runBlocking {
        store.token = "expired"
        client().job(jobId)
        assertTrue(store.token!!.startsWith("tok-"))
    }

    @Test fun mapsAiNotConfigured() = runBlocking {
        try {
            client().aiGenerate(AiRequestDto(task = "summarize", language = "en", text = "x"))
            fail()
        } catch (e: DocException) {
            assertEquals(ErrorCode.AI_NOT_CONFIGURED, e.error.code)
        }
    }

    @Test fun unconfiguredServerFailsClearly() = runBlocking {
        val c = client(base = "")
        assertFalse(c.isConfigured)
        try {
            c.capabilities(); fail()
        } catch (e: DocException) {
            assertEquals(ErrorCode.SERVER_NOT_CONFIGURED, e.error.code)
        }
    }

    @Test fun omitsNullFieldsInAiRequests() {
        val body = client().json.encodeToString(AiRequestDto.serializer(), AiRequestDto(task = "summarize", language = "hi", text = "x"))
        assertEquals("""{"task":"summarize","language":"hi","text":"x"}""", body)
    }

    @Test fun mapsHttpStatuses() {
        val c = client()
        assertEquals(ErrorCode.TOO_LARGE, c.mapHttpError(413, null).error.code)
        assertEquals(ErrorCode.RATE_LIMITED, c.mapHttpError(429, """{"error":{"code":"QUOTA_EXCEEDED","message":"x"}}""").error.code)
        assertEquals(ErrorCode.UNSUPPORTED_FORMAT, c.mapHttpError(415, null).error.code)
        assertEquals(ErrorCode.SERVER, c.mapHttpError(500, "<html>").error.code)
    }
}
