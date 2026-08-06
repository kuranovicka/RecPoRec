package com.recporec.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.recporec.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cilj prečice sa ikonice aplikacije ("Nastavi čitanje", dug pritisak na ikonicu) - nema
 * sopstveni izgled, samo pronađe poslednji aktivan dokument i odmah otvori čitač na njemu,
 * ili listu dokumenata ako još ništa nije otvarano. Ne dira postojeću logiku automatskog
 * čitanja - samo OTVARA ekran, ne pokreće čitanje samo od sebe.
 */
class ContinueReadingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getInstance(applicationContext)
        lifecycleScope.launch {
            val last = withContext(Dispatchers.IO) { db.documentDao().getLastActiveDocument() }
            val intent = if (last != null) {
                Intent(this@ContinueReadingActivity, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, last.id)
                }
            } else {
                Intent(this@ContinueReadingActivity, DocumentListActivity::class.java)
            }
            startActivity(intent)
            finish()
        }
    }
}
