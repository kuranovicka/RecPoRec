package com.recporec.app.ui

import android.content.Intent
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
 * "Podeli sa" - hvata standardni Android meni za deljenje teksta iz drugih aplikacija
 * (pregledač, email, i slično). Napravi NORMALAN, trajan dokument (isto kao "Dodaj dokument"),
 * odmah ga otvori za čitanje sa trenutno odabranim glasom.
 *
 * NAMERNO se NE briše automatski (ranija verzija je to radila - uklonjeno po korisničkoj
 * odluci: rizik da se nešto vredno/veliko podeli, pa se samo od sebe obriše, veći je problem
 * od toga da ostane u listi kao i svaki drugi dodat dokument).
 *
 * Providna, bez sopstvenog izgleda - samo prosledi na ReaderActivity, isti obrazac kao
 * prečica sa ikonice (naučeno tada: ne koristiti Theme.Transparent za ovo, radije direktno
 * targetirati pravi ekran - ovde nema potrebe za "trampolinom" jer nema asinhronog cekanja
 * pre nego sto znamo kuda dalje, sem same DB operacije koju i tako radimo u lifecycleScope).
 */
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isImageShareOrView =
            (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_VIEW) &&
                intent?.type?.startsWith("image/") == true

        if (isImageShareOrView) {
            val imageUri: android.net.Uri? = if (intent.action == Intent.ACTION_VIEW) {
                intent.data
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
            }
            handleSharedImage(imageUri)
            return
        }

        val sharedText = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else null

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
                    android.net.Uri.fromFile(destFile)
                } catch (_: Exception) {
                    null
                }
            }
            if (localUri == null) {
                finish()
                return@launch
            }
            val bottomOrder = (db.documentDao().maxSortOrder() ?: 0) + 1
            val newId = db.documentDao().insert(
                DocumentEntity(
                    title = title,
                    uri = localUri.toString(),
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

    /** Isti OCR koji koristi "Dodaj dokument" kad se izabere slika (DocumentListActivity) -
     * potpuno offline (ML Kit). Radi tiho (bez "da li si sigurna" dijaloga) - deljenje ili
     * otvaranje slike je vec eksplicitna korisnicka namera. */
    private fun handleSharedImage(imageUri: android.net.Uri?) {
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
                    android.net.Uri.fromFile(destFile)
                } catch (_: Exception) {
                    null
                }
            }
            if (localUri == null) {
                finish()
                return@launch
            }
            val bottomOrder = (db.documentDao().maxSortOrder() ?: 0) + 1
            val newId = db.documentDao().insert(
                DocumentEntity(
                    title = "Tekst sa slike",
                    uri = localUri.toString(),
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
}
