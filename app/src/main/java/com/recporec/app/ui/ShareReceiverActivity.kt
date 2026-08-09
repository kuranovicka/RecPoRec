package com.recporec.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * "Podeli sa" / "Otvori pomoću" - hvata standardni Android meni za deljenje/otvaranje iz
 * drugih aplikacija. Dve grane:
 * - Obican tekst (ACTION_SEND, text/plain) - pravi NORMALAN, trajan dokument, isto kao
 *   "Dodaj dokument", odmah ga otvori za čitanje.
 * - Slika (ACTION_SEND ili ACTION_VIEW, image/*) - isti OCR koji vec koristi "Dodaj
 *   dokument" kad se izabere slika - cita tekst sa slike (potpuno offline, ML Kit), pa isto
 *   otvori za citanje.
 *
 * NAMERNO se NE briše automatski (korisnička odluka: rizik da se nešto vredno/veliko podeli,
 * pa se samo od sebe obriše, veći je problem od toga da ostane u listi kao i svaki drugi
 * dodat dokument).
 *
 * Providna, bez sopstvenog izgleda - samo prosledi na ReaderActivity.
 */
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val type = intent?.type

        when {
            action == Intent.ACTION_SEND && type == "text/plain" -> handleSharedText()
            action == Intent.ACTION_SEND && type?.startsWith("image/") == true -> {
                val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                handleSharedImage(imageUri)
            }
            action == Intent.ACTION_VIEW && type?.startsWith("image/") == true -> {
                handleSharedImage(intent.data)
            }
            else -> finish()
        }
    }

    private fun handleSharedText() {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText.isNullOrBlank()) {
            finish()
            return
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
        val title = subject ?: "Podeljen tekst"

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val localUri = withContext(Dispatchers.IO) {
                try {
                    val dir = java.io.File(filesDir, "documents").apply { mkdirs() }
                    val destFile = java.io.File(dir, "${java.util.UUID.randomUUID()}.txt")
                    destFile.writeText(sharedText)
                    Uri.fromFile(destFile)
                } catch (_: Exception) {
                    null
                }
            }
            if (localUri == null) {
                finish()
                return@launch
            }
            insertAndOpen(db, title, localUri)
        }
    }

    /** Isti OCR koji koristi "Dodaj dokument" kad se izabere slika (DocumentListActivity) -
     * potpuno offline (ML Kit), nije 100% pouzdano, zato radi tiho ovde bez posebnog
     * "da li si sigurna" dijaloga - deljenje/otvaranje slike je vec eksplicitna korisnicka
     * namera, za razliku od slucajnog izbora slike u "Dodaj dokument". */
    private fun handleSharedImage(imageUri: Uri?) {
        if (imageUri == null) {
            finish()
            return
        }
        android.widget.Toast.makeText(this, "Prepoznavanje teksta u toku...", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val recognizedText = withContext(Dispatchers.IO) {
                try {
                    val bitmap = contentResolver.openInputStream(imageUri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    } ?: return@withContext null
                    val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
                    val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                        com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
                    )
                    recognizer.process(image).await().text
                } catch (_: Exception) {
                    null
                }
            }
            if (recognizedText.isNullOrBlank()) {
                android.widget.Toast.makeText(
                    this@ShareReceiverActivity,
                    "Nije uspelo prepoznavanje teksta sa ove slike.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            val localUri = withContext(Dispatchers.IO) {
                try {
                    val dir = java.io.File(filesDir, "documents").apply { mkdirs() }
                    val destFile = java.io.File(dir, "${java.util.UUID.randomUUID()}.txt")
                    destFile.writeText(recognizedText)
                    Uri.fromFile(destFile)
                } catch (_: Exception) {
                    null
                }
            }
            if (localUri == null) {
                finish()
                return@launch
            }
            insertAndOpen(db, "Tekst sa slike", localUri)
        }
    }

    private suspend fun insertAndOpen(db: AppDatabase, title: String, contentUri: Uri) {
        val bottomOrder = (db.documentDao().maxSortOrder() ?: 0) + 1
        val newId = db.documentDao().insert(
            DocumentEntity(
                title = title,
                uri = contentUri.toString(),
                format = "txt",
                speechRate = -1f,
                pitch = -1f,
                volumePercent = -1,
                voiceName = null,
                voiceEngine = null,
                languageTag = null,
                sortOrder = bottomOrder
            )
        )
        startActivity(
            Intent(this@ShareReceiverActivity, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, newId)
                .putExtra(ReaderActivity.EXTRA_AUTOPLAY, true)
        )
        finish()
    }
}
