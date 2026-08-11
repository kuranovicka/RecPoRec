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

    /** "Poslednji aktivni dokument" za automatsko čitanje - već započet (ima napredak),
     * ali još nije završen, poslednji otvoren. */
    @Query(
        "SELECT * FROM documents WHERE currentCharacterOffset > 0 " +
            "AND totalCharacters > 0 AND currentCharacterOffset < totalCharacters " +
            "ORDER BY lastOpenedTimestamp DESC LIMIT 1"
    )
    suspend fun getLastActiveDocument(): DocumentEntity?

    @Query("UPDATE documents SET lastOpenedTimestamp = :timestamp WHERE id = :id")
    suspend fun updateLastOpenedTimestamp(id: Long, timestamp: Long)

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

    @Query("DELETE FROM documents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
