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

    /** Za uvoz iz fajla - poslednji unos sa istom reči (bez razlike velikih/malih slova)
     * pobeđuje, kao i pri samom čitanju fajla (jednostavno pravilo, bez upozorenja). */
    @Query("DELETE FROM pronunciation_entries WHERE originalWord = :originalWord COLLATE NOCASE")
    suspend fun deleteByWord(originalWord: String)
}
