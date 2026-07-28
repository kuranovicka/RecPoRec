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
    val dateAdded: Long = System.currentTimeMillis()
)
