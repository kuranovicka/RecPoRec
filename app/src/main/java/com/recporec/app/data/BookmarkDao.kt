package com.recporec.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId ORDER BY dateAdded ASC")
    suspend fun getForDocument(documentId: Long): List<BookmarkEntity>

    @Query("SELECT COUNT(*) FROM bookmarks WHERE documentId = :documentId")
    suspend fun countForDocument(documentId: Long): Int

    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId AND name = :name LIMIT 1")
    suspend fun findByName(documentId: Long, name: String): BookmarkEntity?

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE documentId = :documentId AND name = :name")
    suspend fun deleteByName(documentId: Long, name: String): Int

    @Query("DELETE FROM bookmarks WHERE documentId = :documentId")
    suspend fun deleteAllForDocument(documentId: Long)
}
