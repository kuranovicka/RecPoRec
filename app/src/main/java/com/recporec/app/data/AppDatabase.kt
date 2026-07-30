package com.recporec.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DocumentEntity::class,
        BookmarkEntity::class,
        CombinedVoiceLanguageEntity::class,
        CombinedVoiceEntryEntity::class,
        CombinedVoiceSettingsEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun combinedVoiceDao(): CombinedVoiceDao

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

        /** Nova tabela za oznake (bookmarks) - ne dira postojeće podatke, samo dodaje novu tabelu. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        documentId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        characterOffset INTEGER NOT NULL,
                        dateAdded INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** Nove tabele za kombinovane glasove - ne dira postojeće podatke. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS combined_voice_languages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        scopeId INTEGER NOT NULL,
                        languageTag TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS combined_voice_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        scopeId INTEGER NOT NULL,
                        voiceName TEXT NOT NULL,
                        voiceEngine TEXT NOT NULL,
                        languageTag TEXT NOT NULL,
                        orderIndex INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS combined_voice_settings (
                        scopeId INTEGER PRIMARY KEY NOT NULL,
                        sentencesPerVoice INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** Dodaje visinu (ton) glasa i pamcenje pozicije poslednjeg tajmera - ne dira
         * postojece podatke, samo dodaje dve nove kolone sa bezopasnim podrazumevanim
         * vrednostima. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN pitch REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE documents ADD COLUMN lastTimerStartOffset INTEGER")
            }
        }

        /** Dodaje pamcenje na koliko minuta je bio poslednji tajmer - ne dira postojece
         * podatke, samo dodaje kolonu sa bezopasnom podrazumevanom vrednoscu. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN lastTimerMinutes INTEGER")
            }
        }

        /** Brzina/visina/jacina sada dinamicki prate opsta podesavanja (kao jezik/glas) umesto
         * fiksne vrednosti "zamrznute" pri dodavanju knjige - -1 znaci "nije posebno
         * postavljeno". Postojece knjige vec imaju konkretne (stare) vrednosti za brzinu i
         * visinu - te NE diramo, da ne izgubimo eventualno rucno prilagodjenje. Jacina
         * (volumePercent) je, medjutim, do sada bila potpuno neiskoriscena (nikad se nigde
         * nije primenjivala), pa je bezbedno da je za SVE knjige resetujemo na "prati opste" -
         * nema stvarnog prilagodjavanja koje bi se time izgubilo. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE documents SET volumePercent = -1")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "recporec.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
