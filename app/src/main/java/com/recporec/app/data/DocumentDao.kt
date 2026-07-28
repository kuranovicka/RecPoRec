package com.recporec.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents ORDER BY sortOrder ASC, dateAdded DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY sortOrder ASC, dateAdded DESC")
    suspend fun observeAllOnce(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT MIN(sortOrder) FROM documents")
    suspend fun minSortOrder(): Int?

    @Query("SELECT MAX(sortOrder) FROM documents")
    suspend fun maxSortOrder(): Int?

    @Query("UPDATE documents SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)

    @Insert
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long)
}
