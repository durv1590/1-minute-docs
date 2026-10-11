package com.oneminutedocs.core.data.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A private scratch directory for one operation (inputs copied from SAF, intermediate files, unvalidated outputs).
 * Closing it deletes everything; outputs that pass validation are moved out first by [com.oneminutedocs.core.data.DocumentRepository.saveOutput].
 */
class Workspace internal constructor(val dir: File, private val onClose: (Workspace) -> Unit) : AutoCloseable {
    fun file(name: String): File = File(dir, name).also { require(it.parentFile == dir) { "nested paths are not allowed" } }

    fun newFile(extension: String): File = File(dir, "${UUID.randomUUID()}.$extension")

    override fun close() {
        dir.deleteRecursively()
        onClose(this)
    }
}

/**
 * Creates and cleans up [Workspace]s under `cacheDir/work`. Directories of operations that are still running are
 * tracked in memory so the cleanup worker never deletes active inputs; after a process restart nothing is active,
 * so leftovers from interrupted operations are removed.
 */
@Singleton
class WorkspaceManager @Inject constructor(@ApplicationContext context: Context) {
    private val root = File(context.cacheDir, "work")
    private val active = ConcurrentHashMap.newKeySet<String>()

    fun create(): Workspace {
        val dir = File(root, UUID.randomUUID().toString())
        check(dir.mkdirs()) { "could not create workspace" }
        active += dir.name
        return Workspace(dir) { active -= it.dir.name }
    }

    /** Deletes inactive workspaces older than [maxAgeMs]. Returns bytes freed. */
    fun sweep(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Long {
        var freed = 0L
        root.listFiles()?.forEach { dir ->
            if (dir.name in active) return@forEach
            if (now - dir.lastModified() < maxAgeMs) return@forEach
            freed += dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            dir.deleteRecursively()
        }
        return freed
    }

    fun sizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
