package com.recporec.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
}
