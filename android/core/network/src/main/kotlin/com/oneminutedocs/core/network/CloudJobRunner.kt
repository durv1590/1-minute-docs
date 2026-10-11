package com.oneminutedocs.core.network

import com.oneminutedocs.domain.DocException
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.JobStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Progress as reported by the server; [percent] is null when the server cannot measure it. */
data class CloudProgress(val status: JobStatus, val percent: Int?)

data class CloudResult(val file: File, val job: JobDto)

/**
 * Runs one cloud job end to end: upload → poll → download to [destination] → delete server copies.
 * Cancelling the coroutine cancels the server job and deletes its files. Uploads are never retried automatically
 * (the idempotency key makes an explicit user retry safe); status polling retries transient network errors.
 */
@Singleton
class CloudJobRunner @Inject constructor(private val client: BackendClient) {

    suspend fun run(
        operation: String,
        files: List<UploadFile>,
        options: JsonObject,
        destination: File,
        maxOutputBytes: Long,
        onProgress: (CloudProgress) -> Unit,
    ): CloudResult {
        var jobId: String? = null
        var finished = false
        try {
            onProgress(CloudProgress(JobStatus.QUEUED, null))
            val created = client.submitJob(operation, files, options, UUID.randomUUID().toString())
            jobId = created.id
            var job = created
            var failures = 0
            var wait = 800L
            while (true) {
                val status = JobStatus.parse(job.status) ?: JobStatus.PROCESSING
                onProgress(CloudProgress(status, job.progress))
                if (status.isTerminal) break
                delay(wait)
                wait = (wait * 1.3).toLong().coerceAtMost(3000L)
                job = try {
                    client.job(created.id).also { failures = 0 }
                } catch (e: DocException) {
                    if (e.error.code != ErrorCode.NETWORK && e.error.code != ErrorCode.TIMEOUT || ++failures > 5) throw e
                    delay(1500L * failures)
                    job
                }
            }
            when (JobStatus.parse(job.status)) {
                JobStatus.COMPLETED -> Unit
                JobStatus.CANCELLED -> throw DocException(ErrorCode.CANCELLED)
                JobStatus.EXPIRED -> throw DocException(ErrorCode.EXPIRED)
                else -> throw DocException(BackendClient.jobErrorCode(job.error?.code), job.error?.code)
            }
            client.download(created.id, destination, maxOutputBytes)
            finished = true
            return CloudResult(destination, job)
        } catch (e: CancellationException) {
            throw e
        } finally {
            val id = jobId
            if (id != null) {
                withContext(NonCancellable) {
                    // Best effort: stop work and remove server copies now instead of waiting for expiry.
                    if (!finished) runCatching { client.cancel(id) }
                    runCatching { client.delete(id) }
                }
            }
        }
    }
}
