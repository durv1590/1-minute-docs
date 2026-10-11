package com.oneminutedocs.core.data.drafts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Editable text document (Markdown-lite) produced by templates, OCR, handwriting recognition or AI. */
data class Draft(
    val id: String,
    val title: String,
    val markdown: String,
    /** Content was produced or transformed by AI and must be labelled as such. */
    val aiGenerated: Boolean,
    val sourceOperation: String,
    val updatedAt: Long,
)

/**
 * Persists drafts as small JSON files so editor content survives navigation and process death (autosave).
 * Drafts are private app data and never uploaded unless the user runs an AI action on them.
 */
@Singleton
class DraftRepository @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.filesDir, "drafts").apply { mkdirs() }

    suspend fun create(title: String, markdown: String, aiGenerated: Boolean, sourceOperation: String): Draft {
        val draft = Draft(UUID.randomUUID().toString(), title, markdown, aiGenerated, sourceOperation, System.currentTimeMillis())
        save(draft)
        return draft
    }

    suspend fun save(draft: Draft) = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("id", draft.id)
            .put("title", draft.title)
            .put("markdown", draft.markdown)
            .put("aiGenerated", draft.aiGenerated)
            .put("sourceOperation", draft.sourceOperation)
            .put("updatedAt", draft.updatedAt)
        val target = file(draft.id)
        val tmp = File(dir, "${draft.id}.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    suspend fun load(id: String): Draft? = withContext(Dispatchers.IO) {
        val f = file(id)
        if (!f.isFile) return@withContext null
        runCatching {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            Draft(o.getString("id"), o.getString("title"), o.getString("markdown"), o.getBoolean("aiGenerated"), o.optString("sourceOperation"), o.getLong("updatedAt"))
        }.getOrNull()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { file(id).delete() }

    /** Removes drafts not touched for [maxAgeMs]. */
    suspend fun prune(maxAgeMs: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.filter { now - it.lastModified() > maxAgeMs }?.forEach { it.delete() }
    }

    private fun file(id: String): File {
        require(Regex("^[0-9a-f-]{36}$").matches(id)) { "invalid draft id" }
        return File(dir, "$id.json")
    }
}
