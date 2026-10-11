package com.oneminutedocs.core.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.oneminutedocs.core.data.db.DocumentDao
import com.oneminutedocs.core.data.db.DocumentEntity
import com.oneminutedocs.core.data.files.FileInspector
import com.oneminutedocs.core.data.files.Workspace
import com.oneminutedocs.core.data.files.WorkspaceManager
import com.oneminutedocs.domain.DocumentFormat
import com.oneminutedocs.domain.ErrorCode
import com.oneminutedocs.domain.FileNames
import com.oneminutedocs.domain.Outcome
import com.oneminutedocs.domain.ProcessingLimits
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class ManagedDocument(
    val id: String,
    val displayName: String,
    val format: DocumentFormat,
    val sizeBytes: Long,
    val pageCount: Int?,
    val file: File,
    val createdAt: Long,
    val modifiedAt: Long,
    val sourceOperation: String?,
    val aiGenerated: Boolean,
)

/** A user-selected file copied into a private workspace for processing. The original is never modified. */
data class LocalInput(
    val file: File,
    val displayName: String,
    val format: DocumentFormat,
    val sizeBytes: Long,
    val sourceUri: Uri?,
)

data class StorageStats(val documentCount: Int, val documentBytes: Long, val temporaryBytes: Long, val freeBytes: Long)

@Singleton
class DocumentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DocumentDao,
    private val workspaces: WorkspaceManager,
) {
    private val documentsDir: File = File(context.filesDir, "documents").apply { mkdirs() }
    private val shareDir: File = File(context.cacheDir, "shared").apply { mkdirs() }
    val limits: ProcessingLimits = ProcessingLimits.DEFAULT

    fun observeDocuments(): Flow<List<ManagedDocument>> = dao.observeAll().map { list -> list.mapNotNull { it.toModel() } }

    suspend fun get(id: String): ManagedDocument? = dao.get(id)?.toModel()

    /**
     * Copies a SAF document into [workspace], enforcing size limits while streaming and verifying the real format.
     * [allowed] restricts accepted formats; null accepts any recognised format.
     */
    suspend fun importToWorkspace(uri: Uri, workspace: Workspace, allowed: Set<DocumentFormat>? = null): Outcome<LocalInput> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (name, declaredSize) = queryNameAndSize(resolver, uri)
        val maxBytes = allowed?.maxOfOrNull { limits.maxBytesFor(it) } ?: limits.maxPdfBytes
        if (declaredSize != null && declaredSize > maxBytes) return@withContext Outcome.failure(ErrorCode.TOO_LARGE)
        if (declaredSize != null && !limits.hasSpaceFor(declaredSize, context.cacheDir.usableSpace)) {
            return@withContext Outcome.failure(ErrorCode.NO_SPACE)
        }
        val target = workspace.newFile("in")
        try {
            val input = resolver.openInputStream(uri) ?: return@withContext Outcome.failure(ErrorCode.FILE_NOT_FOUND)
            var total = 0L
            input.use { ins ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) {
                            target.delete()
                            return@withContext Outcome.failure(ErrorCode.TOO_LARGE)
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) {
                target.delete()
                return@withContext Outcome.failure(ErrorCode.CORRUPT_FILE, "empty")
            }
            val format = FileInspector.detect(target)
            if (format == null || (allowed != null && format !in allowed)) {
                target.delete()
                return@withContext Outcome.failure(ErrorCode.UNSUPPORTED_FORMAT)
            }
            Outcome.Success(LocalInput(target, name ?: "document.${format.extension}", format, total, uri))
        } catch (e: SecurityException) {
            target.delete()
            Outcome.failure(ErrorCode.PERMISSION_REVOKED)
        } catch (e: FileNotFoundException) {
            target.delete()
            Outcome.failure(ErrorCode.FILE_NOT_FOUND)
        } catch (e: IOException) {
            target.delete()
            if (context.cacheDir.usableSpace < 5 * ProcessingLimits.MB) Outcome.failure(ErrorCode.NO_SPACE) else Outcome.failure(ErrorCode.UNKNOWN, e.javaClass.simpleName)
        }
    }

    /** Wraps an existing managed document as a processing input without copying. */
    fun asInput(doc: ManagedDocument): LocalInput = LocalInput(doc.file, doc.displayName, doc.format, doc.sizeBytes, null)

    /**
     * Moves a validated output into managed storage and records it. The caller must have validated [temp]
     * (re-opened it with the relevant engine) before calling — success is only reported for real files.
     */
    suspend fun saveOutput(
        temp: File,
        displayName: String,
        format: DocumentFormat,
        pageCount: Int?,
        sourceOperation: String,
        aiGenerated: Boolean = false,
    ): Outcome<ManagedDocument> = withContext(Dispatchers.IO) {
        if (!temp.isFile || temp.length() == 0L) return@withContext Outcome.failure(ErrorCode.CONVERSION_FAILED, "missing output")
        val id = UUID.randomUUID().toString()
        val storedName = "$id.${format.extension}"
        val dest = File(documentsDir, storedName)
        if (!temp.renameTo(dest)) {
            try {
                temp.copyTo(dest, overwrite = false)
                temp.delete()
            } catch (e: IOException) {
                dest.delete()
                return@withContext Outcome.failure(if (documentsDir.usableSpace < temp.length()) ErrorCode.NO_SPACE else ErrorCode.UNKNOWN)
            }
        }
        val name = FileNames.unique(FileNames.withExtension(displayName, format), dao.allNames().toSet())
        val now = System.currentTimeMillis()
        val entity = DocumentEntity(id, name, format.name, dest.length(), pageCount, storedName, now, now, sourceOperation, aiGenerated)
        dao.insert(entity)
        Outcome.Success(entity.toModel()!!)
    }

    suspend fun rename(id: String, newName: String): Outcome<ManagedDocument> {
        val doc = get(id) ?: return Outcome.failure(ErrorCode.FILE_NOT_FOUND)
        val others = dao.allNames().filter { !it.equals(doc.displayName, ignoreCase = true) }.toSet()
        val name = FileNames.unique(FileNames.withExtension(newName, doc.format), others)
        dao.rename(id, name, System.currentTimeMillis())
        return Outcome.Success(get(id)!!)
    }

    suspend fun delete(ids: List<String>) = withContext(Dispatchers.IO) {
        ids.mapNotNull { dao.get(it) }.forEach { File(documentsDir, it.storedName).delete() }
        dao.delete(ids)
    }

    /** Copies a managed document to a user-chosen SAF destination (from ACTION_CREATE_DOCUMENT). */
    suspend fun exportTo(id: String, destination: Uri): Outcome<Unit> = withContext(Dispatchers.IO) {
        val doc = get(id) ?: return@withContext Outcome.failure(ErrorCode.FILE_NOT_FOUND)
        try {
            val out = context.contentResolver.openOutputStream(destination, "wt") ?: return@withContext Outcome.failure(ErrorCode.PERMISSION_REVOKED)
            out.use { o -> doc.file.inputStream().use { it.copyTo(o) } }
            Outcome.Success(Unit)
        } catch (e: SecurityException) {
            Outcome.failure(ErrorCode.PERMISSION_REVOKED)
        } catch (e: IOException) {
            Outcome.failure(ErrorCode.NO_SPACE, e.javaClass.simpleName)
        }
    }

    /** Copies several documents into a user-chosen folder (from ACTION_OPEN_DOCUMENT_TREE). Returns how many were written. */
    suspend fun exportToTree(ids: List<String>, treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        var written = 0
        for (id in ids) {
            val doc = get(id) ?: continue
            try {
                val target = DocumentsContract.createDocument(resolver, parent, doc.format.mimeType, doc.displayName) ?: continue
                resolver.openOutputStream(target, "wt")?.use { out -> doc.file.inputStream().use { it.copyTo(out) } } ?: continue
                written++
            } catch (_: Exception) {
                // Skip this file; the count tells the user how many were exported.
            }
        }
        written
    }

    /**
     * Content URI for sharing/opening with another app. A copy carrying the display name is made so receiving apps see
     * a meaningful file name; copies are cleaned up by the cleanup worker.
     */
    suspend fun shareUri(id: String): Uri? = withContext(Dispatchers.IO) {
        val doc = get(id) ?: return@withContext null
        val dir = File(shareDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val copy = File(dir, FileNames.withExtension(doc.displayName, doc.format))
        doc.file.copyTo(copy, overwrite = true)
        FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
    }

    suspend fun storageStats(): StorageStats = withContext(Dispatchers.IO) {
        val docs = dao.all()
        StorageStats(
            documentCount = docs.size,
            documentBytes = docs.sumOf { File(documentsDir, it.storedName).length() },
            temporaryBytes = workspaces.sizeBytes() + shareDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() },
            freeBytes = context.filesDir.usableSpace,
        )
    }

    /** Deletes inactive temporary files and share copies. Never touches managed documents. */
    suspend fun clearTemporaryFiles(maxAgeMs: Long = 0L): Long = withContext(Dispatchers.IO) {
        var freed = workspaces.sweep(maxAgeMs)
        val now = System.currentTimeMillis()
        shareDir.listFiles()?.forEach { dir ->
            if (now - dir.lastModified() >= maxAgeMs) {
                freed += dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                dir.deleteRecursively()
            }
        }
        freed
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null to null
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
            name to size
        } ?: (null to null)
    } catch (_: Exception) {
        null to null
    }

    private fun DocumentEntity.toModel(): ManagedDocument? {
        val fmt = runCatching { DocumentFormat.valueOf(format) }.getOrNull() ?: return null
        return ManagedDocument(id, displayName, fmt, sizeBytes, pageCount, File(documentsDir, storedName), createdAt, modifiedAt, sourceOperation, aiGenerated)
    }
}
