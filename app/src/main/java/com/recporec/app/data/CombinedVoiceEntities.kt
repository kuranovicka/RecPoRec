package com.recporec.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Jedan dodati jezik za kombinovane glasove. scopeId = 0 znači opšta podešavanja,
 * inače je to id konkretnog dokumenta. */
@Entity(tableName = "combined_voice_languages")
data class CombinedVoiceLanguageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scopeId: Long,
    val languageTag: String
)

/** Jedan dodati glas za kombinovane glasove, sa redosledom u kom se smenjuju. */
@Entity(tableName = "combined_voice_entries")
data class CombinedVoiceEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scopeId: Long,
    val voiceName: String,
    val voiceEngine: String,
    val languageTag: String,
    val orderIndex: Int
)

/** Broj rečenica po glasu, po opsegu (0 = opšta podešavanja, inače id dokumenta). */
@Entity(tableName = "combined_voice_settings")
data class CombinedVoiceSettingsEntity(
    @PrimaryKey val scopeId: Long,
    val sentencesPerVoice: Int = 1
)
