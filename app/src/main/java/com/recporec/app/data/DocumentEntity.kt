package com.recporec.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Jedan dokument (knjiga) sa svim upamćenim stanjem čitanja.
 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val uri: String,
    val format: String, // txt, epub, pdf, docx
    val totalCharacters: Int = 0,
    val currentCharacterOffset: Int = 0,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    val speechRate: Float = 1.0f,
    val volumePercent: Int = 100,
    val voiceName: String? = null,
    val voiceEngine: String? = null,
    val languageTag: String? = null,
    val elapsedSeconds: Long = 0,
    val timerMinutes: Int = 0,
    val dateAdded: Long = System.currentTimeMillis()
)
