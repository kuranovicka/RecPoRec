package com.recporec.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Jedan unos u korisnikovom rečniku izgovora - "originalna reč" se pri čitanju zamenjuje
 * sa "zamena" pre nego što se pošalje TTS motoru (npr. John -> Džon). Ovo je odvojeno od
 * ugrađenog (bundled) rečnika, koji dolazi kao fajl u resursima aplikacije i ne dira se. */
@Entity(tableName = "pronunciation_entries")
data class PronunciationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalWord: String,
    val replacement: String,
    val dateAdded: Long = System.currentTimeMillis()
)
