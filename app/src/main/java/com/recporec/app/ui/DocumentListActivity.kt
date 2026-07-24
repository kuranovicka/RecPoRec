package com.recporec.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.DocumentEntity
import com.recporec.app.databinding.ActivityDocumentListBinding
import com.recporec.app.parser.DocumentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentListBinding
    private lateinit var adapter: DocumentListAdapter
    private val db by lazy { AppDatabase.getInstance(this) }
    private val settings by lazy { com.recporec.app.data.AppSettings(this) }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handlePickedFile(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = DocumentListAdapter(
            onOpen = { doc -> openDocument(doc) },
            onDelete = { doc -> confirmDelete(doc) }
        )
        binding.recyclerDocuments.layoutManager = LinearLayoutManager(this)
        binding.recyclerDocuments.adapter = adapter

        binding.btnAddDocument.setOnClickListener {
            pickFileLauncher.launch(
                arrayOf(
                    "text/plain",
                    "text/html",
                    "text/rtf",
                    "application/rtf",
                    "application/pdf",
                    "application/epub+zip",
                    "application/x-fictionbook+xml",
                    "application/x-mobipocket-ebook",
                    "application/vnd.amazon.ebook",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
        }

        binding.btnExit.setOnClickListener {
            com.recporec.app.service.ReadingService.stop(this)
            com.recporec.app.tts.PlaybackController.release()
            finishAffinity()
        }

        binding.btnOverflow.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(this, view)
            popup.menuInflater.inflate(com.recporec.app.R.menu.menu_main_options, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    com.recporec.app.R.id.action_global_voice -> {
                        startActivity(Intent(this, GlobalVoiceSettingsActivity::class.java))
                        true
                    }
                    com.recporec.app.R.id.action_settings -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        true
                    }
                    com.recporec.app.R.id.action_help -> {
                        startActivity(Intent(this, HelpActivity::class.java))
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        lifecycleScope.launch {
            db.documentDao().observeAll().collect { list ->
                adapter.submitList(list)
                binding.emptyView.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                binding.recyclerDocuments.visibility = if (list.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
        }

        // Ako je aplikacija otvorena preko "Otvori sa" ili "Podeli" (npr. iz Google Diska)
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let { handlePickedFile(it) }
    }

    private fun handlePickedFile(uri: Uri) {
        val name = queryFileName(uri) ?: "dokument"
        val mimeType = contentResolver.getType(uri)
        val format = DocumentParser.detectFormat(name, mimeType) ?: run {
            AlertDialog.Builder(this)
                .setMessage("Format ovog fajla nije podržan.")
                .setPositiveButton("U redu", null)
                .show()
            return
        }

        lifecycleScope.launch {
            val localUri = withContext(Dispatchers.IO) { copyToLocalStorage(uri, format) }
            if (localUri == null) {
                AlertDialog.Builder(this@DocumentListActivity)
                    .setMessage("Nije moguće pročitati fajl.")
                    .setPositiveButton("U redu", null)
                    .show()
                return@launch
            }
            db.documentDao().insert(
                DocumentEntity(
                    title = name.substringBeforeLast("."),
                    uri = localUri.toString(),
                    format = format,
                    speechRate = settings.globalSpeechRate,
                    volumePercent = settings.globalVolumePercent,
                    voiceName = settings.globalVoiceName,
                    voiceEngine = settings.globalVoiceEngine,
                    languageTag = settings.globalLanguageTag
                )
            )
        }
    }

    /** Kopira sadržaj u internu memoriju aplikacije da bi pristup bio trajan bez obzira na izvor (birač, Disk, Podeli). */
    private fun copyToLocalStorage(sourceUri: Uri, format: String): Uri? {
        return try {
            val dir = java.io.File(filesDir, "documents").apply { mkdirs() }
            val destFile = java.io.File(dir, "${java.util.UUID.randomUUID()}.$format")
            contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Uri.fromFile(destFile)
        } catch (e: Exception) {
            null
        }
    }

    private fun queryFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun openDocument(doc: DocumentEntity) {
        val intent = Intent(this, ReaderActivity::class.java)
        intent.putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, doc.id)
        startActivity(intent)
    }

    private fun confirmDelete(doc: DocumentEntity) {
        AlertDialog.Builder(this)
            .setTitle(getString(com.recporec.app.R.string.confirm_delete_title))
            .setMessage(getString(com.recporec.app.R.string.confirm_delete_message))
            .setNegativeButton(getString(com.recporec.app.R.string.cancel), null)
            .setPositiveButton(getString(com.recporec.app.R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    db.documentDao().deleteById(doc.id)
                    withContext(Dispatchers.IO) {
                        try {
                            val uri = Uri.parse(doc.uri)
                            if (uri.scheme == "file") {
                                uri.path?.let { java.io.File(it).delete() }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
            .show()
    }
}
