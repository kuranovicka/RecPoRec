package com.recporec.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CombinedVoiceDao {

    @Query("SELECT * FROM combined_voice_languages WHERE scopeId = :scopeId ORDER BY id ASC")
    suspend fun getLanguages(scopeId: Long): List<CombinedVoiceLanguageEntity>

    @Query("SELECT * FROM combined_voice_languages WHERE scopeId = :scopeId AND languageTag = :languageTag LIMIT 1")
    suspend fun findLanguage(scopeId: Long, languageTag: String): CombinedVoiceLanguageEntity?

    @Insert
    suspend fun insertLanguage(lang: CombinedVoiceLanguageEntity): Long

    @Query("DELETE FROM combined_voice_languages WHERE id = :id")
    suspend fun deleteLanguage(id: Long)

    @Query("DELETE FROM combined_voice_entries WHERE scopeId = :scopeId AND languageTag = :languageTag")
    suspend fun deleteVoicesForLanguage(scopeId: Long, languageTag: String)

    @Query("SELECT * FROM combined_voice_entries WHERE scopeId = :scopeId ORDER BY orderIndex ASC")
    suspend fun getVoices(scopeId: Long): List<CombinedVoiceEntryEntity>

    @Query("SELECT MAX(orderIndex) FROM combined_voice_entries WHERE scopeId = :scopeId")
    suspend fun maxVoiceOrder(scopeId: Long): Int?

    @Insert
    suspend fun insertVoice(voice: CombinedVoiceEntryEntity): Long

    @Query("DELETE FROM combined_voice_entries WHERE id = :id")
    suspend fun deleteVoice(id: Long)

    @Query("SELECT * FROM combined_voice_settings WHERE scopeId = :scopeId LIMIT 1")
    suspend fun getSettings(scopeId: Long): CombinedVoiceSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSettings(settings: CombinedVoiceSettingsEntity)

    @Query("DELETE FROM combined_voice_languages WHERE scopeId = :scopeId")
    suspend fun deleteAllLanguagesForScope(scopeId: Long)

    @Query("DELETE FROM combined_voice_entries WHERE scopeId = :scopeId")
    suspend fun deleteAllVoicesForScope(scopeId: Long)

    @Query("DELETE FROM combined_voice_settings WHERE scopeId = :scopeId")
    suspend fun deleteSettingsForScope(scopeId: Long)

    /** Potpuno briše kombinovane glasove/jezike/broj rečenica za dati opseg (dokument). */
    suspend fun clearScope(scopeId: Long) {
        deleteAllLanguagesForScope(scopeId)
        deleteAllVoicesForScope(scopeId)
        deleteSettingsForScope(scopeId)
    }
}
