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
    /** Brzina čitanja za ovaj dokument. -1 znači "nije posebno postavljeno" - koristi se
     * opšta (globalna) vrednost, i to se osvežava ako se opšta vrednost kasnije promeni. */
    val speechRate: Float = -1f,
    /** Jačina za ovaj dokument (0-100), vezano za TTS, NE za sistemsku jačinu telefona.
     * -1 znači "nije posebno postavljeno" - koristi se opšta vrednost. */
    val volumePercent: Int = -1,
    val voiceName: String? = null,
    val voiceEngine: String? = null,
    val languageTag: String? = null,
    val elapsedSeconds: Long = 0,
    val timerMinutes: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    /** Ručni redosled u listi (manje = više gore). Popunjava se migracijom za postojeće
     * knjige, a nove knjige dobijaju vrednost manju od svih postojećih (idu na vrh). */
    val sortOrder: Int = 0,
    /** Visina (ton) glasa - 1.0 je normalno. -1 znači "nije posebno postavljeno" - koristi
     * se opšta vrednost, i osvežava ako se opšta vrednost kasnije promeni. */
    val pitch: Float = -1f,
    /** Pozicija (karakter) u dokumentu na kojoj je poslednji put POKRENUT tajmer -
     * koristi se za "Vrati se na poslednji tajmer". Null ako nikad nije postavljen. */
    val lastTimerStartOffset: Int? = null,
    /** Na koliko minuta je bio postavljen poslednji tajmer - koristi se da se korisniku
     * javi na koji tajmer se tačno vraća (npr. "Poslednji tajmer je odbrojavao 30 minuta"). */
    val lastTimerMinutes: Int? = null,
    /** Kad je dokument poslednji put otvoren (System.currentTimeMillis()) - koristi se da
     * se pronađe "poslednji aktivni dokument" za automatsko čitanje pri otvaranju
     * aplikacije. 0 znači nikad otvoren (ili otvoren pre nego što je ovo polje uvedeno). */
    val lastOpenedTimestamp: Long = 0,
    /** Za audio knjige (format = "audio"): redni broj trenutnog zvučnog fajla unutar
     * zip arhive (0 = prvi fajl u folderu/"odeljku"). Nebitno za tekstualne formate. */
    val audioFileIndex: Int = 0,
    /** Za audio knjige: pozicija u milisekundama unutar TRENUTNOG zvučnog fajla
     * (audioFileIndex). Nebitno za tekstualne formate. */
    val audioPositionMs: Long = 0,
    /** Za audio knjige: trajanje trenutnog zvučnog fajla u milisekundama, radi prikaza
     * napretka. 0 znači "još nije izmereno". Nebitno za tekstualne formate. */
    val audioDurationMs: Long = 0
)
