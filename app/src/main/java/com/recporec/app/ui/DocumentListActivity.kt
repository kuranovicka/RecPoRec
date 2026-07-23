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
import kotlinx.coroutines.launch

class DocumentListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentListBinding
    private lateinit var adapter: DocumentListAdapter
    private val db by lazy { AppDatabase.getInstance(this) }

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
                    "application/pdf",
                    "application/epub+zip",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
        }

        lifecycleScope.launch {
            db.documentDao().observeAll().collect { list ->
                adapter.submitList(list)
                binding.emptyView.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                binding.recyclerDocuments.visibility = if (list.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
        }

        // Ako je aplikacija otvorena preko "Otvori sa" iz drugog fajl menadžera
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { handlePickedFile(it) }
        }
    }

    private fun handlePickedFile(uri: Uri) {
        val name = queryFileName(uri) ?: "dokument"
        val format = DocumentParser.detectFormat(name) ?: run {
            AlertDialog.Builder(this)
                .setMessage("Format ovog fajla nije podržan.")
                .setPositiveButton("U redu", null)
                .show()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) { /* neki provajderi ne dozvoljavaju, nastavljamo */ }

        lifecycleScope.launch {
            db.documentDao().insert(
                DocumentEntity(
                    title = name.substringBeforeLast("."),
                    uri = uri.toString(),
                    format = format
                )
            )
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
                lifecycleScope.launch { db.documentDao().deleteById(doc.id) }
            }
            .show()
    }
}
