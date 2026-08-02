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
    private var currentList: List<DocumentEntity> = emptyList()
    /** "all" / "started" / "finished" - koja kartica je trenutno aktivna. */
    private var currentTab: String = "all"

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { handlePickedFile(it) }
        }
    }

    /** Otvara standardni sistemski birač fajlova, BEZ ikakvog usmeravanja na tačno određenu
     * lokaciju. NAMERNO bez ogranicenja na tipove fajlova (MIME tipovi) - lokalni fajlovi na
     * telefonu (posebno .mobi/.fb2/.azw) cesto imaju "pogresno" ili genericki prijavljen tip
     * fajla kod razlicitih provajdera, pa bi filter sakrio ispravne fajlove iz birača.
     * Prepoznavanje formata radi sama app, po nastavku imena fajla (vidi
     * DocumentParser.detectFormat) - filter ovde nije ni potreban.
     *
     * RANIJE smo slale EXTRA_INITIAL_URI da bi se birac odmah otvorio na telefonu ili Disku -
     * ali se ispostavilo da to gura spisak "korena" (svih izvora) van vidokruga, pa se birac
     * otvara zaglavljen na SAMO JEDNOM izvoru dok se rucno ne pronadje skriven prekidac
     * "Prikazi korene" (tesko dostupan preko TalkBack-a). Bez ikakvog usmeravanja, birac se
     * otvara na SVOM podrazumevanom ekranu, koji bi trebalo da odmah pokaze sve izvore. */
    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        pickFileLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = DocumentListAdapter(
            onOpen = { doc -> openDocument(doc) },
            onLongPress = { doc -> showActionsMenu(doc) },
            onSelectionChanged = { count -> updateGeneralActionsLabel(count) }
        )
        binding.recyclerDocuments.layoutManager = LinearLayoutManager(this)
        binding.recyclerDocuments.adapter = adapter

        binding.btnAddDocument.setOnClickListener {
            launchPicker()
        }

        binding.btnGeneralActions.setOnClickListener {
            showGeneralActionsMenu()
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
                currentList = list
                applyTabFilter()
            }
        }

        binding.groupLibraryTabs.setOnCheckedChangeListener { _, checkedId ->
            currentTab = when (checkedId) {
                binding.tabStartedBooks.id -> "started"
                binding.tabFinishedBooks.id -> "finished"
                else -> "all"
            }
            applyTabFilter()
        }

        // Ako je aplikacija otvorena preko "Otvori sa" ili "Podeli" (npr. iz Google Diska)
        handleIncomingIntent(intent)

        // "Automatski citaj aktivni dokument" - "Pri otvaranju aplikacije": NAMERNO ovde
        // (onCreate, koji se izvrsi SAMO jednom kad se ovaj ekran stvarno TEK otvori), ne u
        // onResume() (koji bi se ponavljao SVAKI put kad se korisnica samo vrati na ovaj
        // spisak - npr. posle otvaranja DRUGOG dokumenta rucno) - inace bi ova funkcija
        // "otimala" citanje nazad na stari dokument svaki put kad se spisak ponovo pojavi,
        // cak i posle svesnog izbora da se cita nesto drugo.
        if (settings.autoReadEnabled && settings.autoReadTrigger == "app") {
            com.recporec.app.tts.PlaybackController.autoResumeLastActiveDocument(this)
        }
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
            val bottomOrder = (db.documentDao().maxSortOrder() ?: 0) + 1
            db.documentDao().insert(
                DocumentEntity(
                    title = name.substringBeforeLast("."),
                    uri = localUri.toString(),
                    format = format,
                    // Namerno NE "zamrzavamo" trenutnu opštu vrednost ovde - nov dokument prati
                    // opšta podešavanja dinamički (kao Jezik/Glas), sve dok se za NJEGA
                    // posebno nešto ne promeni. -1 znači "nije posebno postavljeno" za
                    // brzinu/visinu (0 i naviše su ispravne vrednosti).
                    speechRate = -1f,
                    pitch = -1f,
                    volumePercent = -1,
                    voiceName = null,
                    voiceEngine = null,
                    languageTag = null,
                    sortOrder = bottomOrder
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

    /** Primenjuje trenutno odabranu karticu (Sve/Započete/Pročitane) na PUNU listu iz baze
     * (currentList) i prikazuje samo odgovarajući podskup - currentList i dalje ostaje
     * kompletna, necu neophodno za pomeranje gore/dole koje mora da radi sa PRAVIM
     * susedima u punom redosledu, ne samo unutar filtrirane kartice. */
    private fun applyTabFilter() {
        val filtered = when (currentTab) {
            "started" -> currentList.filter { it.totalCharacters > 0 && it.currentCharacterOffset in 1 until it.totalCharacters }
            "finished" -> currentList.filter { it.totalCharacters > 0 && it.currentCharacterOffset >= it.totalCharacters }
            else -> currentList
        }
        adapter.submitList(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.recyclerDocuments.visibility = if (filtered.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.emptyView.text = when (currentTab) {
            "started" -> "Nema započetih knjiga."
            "finished" -> "Nema pročitanih knjiga."
            else -> getString(com.recporec.app.R.string.no_documents)
        }
    }

    private fun queryFileName(uri: Uri): String? {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment
    }

    private fun openDocument(doc: DocumentEntity) {
        val intent = Intent(this, ReaderActivity::class.java)
        intent.putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, doc.id)
        startActivity(intent)
    }

    /** "Opšte radnje" dugme - van režima biranja nudi "Odaberi sve", unutar režima nudi
     * "Obriši odabrano" i "Otkaži izbor". */
    private fun showGeneralActionsMenu() {
        if (adapter.isSelectionMode()) {
            AlertDialog.Builder(this)
                .setTitle("Opšte radnje")
                .setItems(arrayOf("Obriši odabrano", "Otkaži izbor")) { _, which ->
                    when (which) {
                        0 -> confirmDeleteSelected()
                        1 -> {
                            adapter.cancelSelection()
                            updateGeneralActionsLabel(0)
                            android.widget.Toast.makeText(this, "Odabir je opozvan.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Opšte radnje")
                .setItems(arrayOf("Odaberi sve")) { _, _ ->
                    adapter.selectAll()
                    val count = adapter.getSelectedIds().size
                    updateGeneralActionsLabel(count)
                    android.widget.Toast.makeText(this, "Odabrane su sve stavke.", android.widget.Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun updateGeneralActionsLabel(selectedCount: Int) {
        binding.btnGeneralActions.text = if (adapter.isSelectionMode()) {
            "Odabrano: $selectedCount"
        } else {
            "Opšte radnje"
        }
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.getSelectedIds()
        if (ids.isEmpty()) {
            android.widget.Toast.makeText(this, "Ništa nije odabrano.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(com.recporec.app.R.string.confirm_delete_title))
            .setMessage("Obrisati ${ids.size} odabranih dokumenata? Ova radnja se ne može poništiti.")
            .setNegativeButton(getString(com.recporec.app.R.string.cancel), null)
            .setPositiveButton(getString(com.recporec.app.R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    // Ako je medju odabranim i dokument koji trenutno cita (npr. u pozadini),
                    // zaustavi citanje pre brisanja - ista bezbednosna provera kao za
                    // pojedinacno brisanje.
                    if (com.recporec.app.tts.PlaybackController.currentDocument?.id in ids) {
                        com.recporec.app.service.ReadingService.stop(this@DocumentListActivity)
                        com.recporec.app.tts.PlaybackController.release()
                    }
                    db.documentDao().deleteByIds(ids.toList())
                    adapter.cancelSelection()
                    updateGeneralActionsLabel(0)
                    android.widget.Toast.makeText(
                        this@DocumentListActivity, "Obrisano.", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun showActionsMenu(doc: DocumentEntity) {
        AlertDialog.Builder(this)
            .setTitle(doc.title)
            .setItems(arrayOf("Premesti nagore", "Premesti nadole", "Obriši")) { _, which ->
                when (which) {
                    0 -> moveDocument(doc, up = true)
                    1 -> moveDocument(doc, up = false)
                    2 -> confirmDelete(doc)
                }
            }
            .show()
    }

    private fun moveDocument(doc: DocumentEntity, up: Boolean) {
        val index = currentList.indexOfFirst { it.id == doc.id }
        if (index < 0) return
        val targetIndex = if (up) index - 1 else index + 1
        if (targetIndex < 0 || targetIndex >= currentList.size) {
            val msg = if (up) "Već je na vrhu liste." else "Već je na dnu liste."
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val target = currentList[targetIndex]
        lifecycleScope.launch {
            // Zamena mesta - dokument nosi sortOrder suseda i obrnuto.
            db.documentDao().updateSortOrder(doc.id, target.sortOrder)
            db.documentDao().updateSortOrder(target.id, doc.sortOrder)
        }
    }

    private fun confirmDelete(doc: DocumentEntity) {
        AlertDialog.Builder(this)
            .setTitle(getString(com.recporec.app.R.string.confirm_delete_title))
            .setMessage(getString(com.recporec.app.R.string.confirm_delete_message))
            .setNegativeButton(getString(com.recporec.app.R.string.cancel), null)
            .setPositiveButton(getString(com.recporec.app.R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    // Ako je ovo dokument koji trenutno svira (npr. u pozadini),
                    // zaustavi čitanje i ugasi servis pre brisanja - inače bi nastavilo
                    // da čita fajl koji upravo brišemo, sa "zaglavljenom" notifikacijom.
                    if (com.recporec.app.tts.PlaybackController.currentDocument?.id == doc.id) {
                        com.recporec.app.service.ReadingService.stop(this@DocumentListActivity)
                        com.recporec.app.tts.PlaybackController.release()
                    }
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
