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
    val dateAdded: Long = System.currentTimeMillis(),
    /** Ručni redosled u listi (manje = više gore). Popunjava se migracijom za postojeće
     * knjige, a nove knjige dobijaju vrednost manju od svih postojećih (idu na vrh). */
    val sortOrder: Int = 0,
    /** Visina (ton) glasa - 1.0 je normalno. */
    val pitch: Float = 1.0f,
    /** Pozicija (karakter) u dokumentu na kojoj je poslednji put POKRENUT tajmer -
     * koristi se za "Vrati se na poslednji tajmer". Null ako nikad nije postavljen. */
    val lastTimerStartOffset: Int? = null,
    /** Na koliko minuta je bio postavljen poslednji tajmer - koristi se da se korisniku
     * javi na koji tajmer se tačno vraća (npr. "Poslednji tajmer je odbrojavao 30 minuta"). */
    val lastTimerMinutes: Int? = null
)
