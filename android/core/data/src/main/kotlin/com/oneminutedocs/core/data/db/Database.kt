package com.oneminutedocs.core.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Metadata for a document managed by the app. The bytes live in app-specific storage, never in the database. */
@Entity(tableName = "documents", indices = [Index("modifiedAt"), Index("displayName")])
data class DocumentEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val format: String,
    val sizeBytes: Long,
    val pageCount: Int?,
    /** File name inside the managed documents directory (server/user names are never used as paths). */
    val storedName: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val sourceOperation: String?,
    val aiGenerated: Boolean,
)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY modifiedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: String): DocumentEntity?

    @Query("SELECT displayName FROM documents")
    suspend fun allNames(): List<String>

    @Query("SELECT * FROM documents")
    suspend fun all(): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DocumentEntity)

    @Query("UPDATE documents SET displayName = :name, modifiedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("DELETE FROM documents WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}

@Database(entities = [DocumentEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
}
