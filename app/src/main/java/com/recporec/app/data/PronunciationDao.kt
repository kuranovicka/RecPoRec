package com.recporec.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PronunciationDao {

    @Query("SELECT * FROM pronunciation_entries ORDER BY originalWord COLLATE NOCASE ASC")
    suspend fun getAll(): List<PronunciationEntity>

    @Insert
    suspend fun insert(entry: PronunciationEntity): Long

    @Query("DELETE FROM pronunciation_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pronunciation_entries")
    suspend fun deleteAll()
}
