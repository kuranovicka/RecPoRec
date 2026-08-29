package com.recporec.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Jedna oznaka (bookmark) unutar određenog dokumenta. */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val name: String,
    val characterOffset: Int,
    val dateAdded: Long = System.currentTimeMillis(),
    /** Za oznake u AUDIO knjizi: redni broj zvučnog fajla u folderu (0 = prvi) i pozicija u
     * milisekundama unutar njega. -1 znači "ovo NIJE audio oznaka" (obična, tekstualna
     * oznaka koristi characterOffset iznad, kao i do sad). */
    val audioFileIndex: Int = -1,
    val audioPositionMs: Long = 0
)
