package com.recporec.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DocumentEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Dodaje kolonu za ručni redosled i popunjava je tako da postojeća lista ostane
         * u istom redosledu kao pre (po dateAdded, najnovije prvo) - niko ne gubi
         * dokumente niti im se menja poredak posle ažuriranja aplikacije. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE documents SET sortOrder = (
                        SELECT COUNT(*) FROM documents AS d2
                        WHERE d2.dateAdded > documents.dateAdded
                           OR (d2.dateAdded = documents.dateAdded AND d2.id > documents.id)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "recporec.db"
                ).addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
