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
import com.recporec.app.data.AppSettings
import com.recporec.app.data.DocumentEntity
import com.recporec.app.databinding.ActivityDocumentListBinding
import com.recporec.app.parser.DocumentParser
import com.recporec.app.util.requestAccessibilityFocusNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Akcija koju koristi precica sa ikonice aplikacije ("Nastavi citanje") - vidi shortcuts.xml. */
private const val ACTION_CONTINUE_READING = "com.recporec.app.ACTION_CONTINUE_READING"

class DocumentListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentListBinding
    private lateinit var adapter: DocumentListAdapter
    private val db by lazy { AppDatabase.getInstance(this) }
    private var ocrToneGenerator: android.media.ToneGenerator? = null
    private val settings by lazy { com.recporec.app.data.AppSettings(this) }
    private var currentList: List<DocumentEntity> = emptyList()
    // "Poništi brisanje" - dokumenti ovde su OPTIMISTICNO sakriveni iz prikaza, ali JOS UVEK
    // postoje u bazi dok ne istekne kratak rok (undoHandler) - ako se u medjuvremenu pritisne
    // "Poništi", vracaju se u prikaz, bez ikad stvarno obrisanih.
    private val pendingDeleteIds = mutableSetOf<Long>()
    private val undoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** "all" / "started" / "finished" - koja kartica je trenutno aktivna. */
    private var currentTab: String = "all"

    // "Napravi rezervnu kopiju" - dugme desno, pre Izlaz. Za razliku od izvoza podesavanja
    // (samo glas/prekidaci), ovo pakuje SVE dokumente (same fajlove, pozicija citanja,
    // oznake) u jedan .zip. Ranije bilo na dug pritisak u Podesavanjima preko ScrollView-a,
    // ali je taj ekran "gutao" dug pritisak (poznat Android problem) - premesteno ovde, gde
    // NEMA ScrollView-a oko dugmadi.
    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        backupAllDocuments(uri)
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        restoreDocumentsBackup(uri)
    }

    // "Izvezi u txt" - radnja za jedan dokument (dug pritisak). Cuva ceo izvucen tekst
    // dokumenta (bilo kog podrzanog formata) kao obican .txt, uz osnovno ciscenje (visestruki
    // razmaci, prazni redovi, kontrolni znakovi, dekorativni separatori) - NE dira sam
    // dokument niti njegovo citanje, samo pravi ODVOJENU, ociscenu kopiju za cuvanje/deljenje
    // dalje. pendingExportDoc pamti KOJI dokument se izvozi, jer CreateDocument callback ne
    // nosi sopstvene podatke.
    private var pendingExportDoc: DocumentEntity? = null
    private val exportTxtLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val doc = pendingExportDoc
        pendingExportDoc = null
        if (uri == null || doc == null) return@registerForActivityResult
        exportDocumentAsTxt(doc, uri)
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { handlePickedFile(it) }
        }
    }

    // Cuva "sledeci korak" u lancu dozvola dok cekamo da se SISTEMSKI dijalog (za dozvolu)
    // zatvori - da se nas sledeci dijalog ne bi pojavio PREKO sistemskog, u isto vreme.
    private var pendingPermissionChainNext: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ako korisnik odbije, servis i dalje radi, samo bez vidljive notifikacije */
        maybeAskPhoneStatePermission { maybeAskBatteryOptimization { checkFullScreenIntentPermission() } }
    }

    private val phoneStatePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ako korisnik odbije, i dalje se pokusava pauza preko audio fokusa, samo bez
           rezervnog mehanizma za pozive */
        pendingPermissionChainNext?.invoke()
        pendingPermissionChainNext = null
    }

    // Odvojen od notificationPermissionLauncher iznad - taj je vezan za AUTOMATSKI lanac pri
    // pokretanju app-e, ovaj je za RUCNU proveru (dug pritisak na Dodaj dokument), koja ima
    // drugaciji sledeci korak.
    private val notificationPermissionManualLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingPermissionChainNext?.invoke()
        pendingPermissionChainNext = null
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

        // Precica sa ikonice ("Nastavak-pauza", dug pritisak na ikonicu app-e) cilja OVAJ
        // ekran direktno (ne poseban providan "trampolin" ekran - to je ranije pravilo
        // problem, Android ume da ne prikaze providne aktivnosti pokrenute iz precice kako
        // treba, pa je delovalo "mrtvo"). STVARNO naizmenicno pusta/pauzira (ne samo otvara):
        // ako je bas ova knjiga VEC aktivna i cita se, pauzira je; inace otvara citac i
        // pokrece citanje (koristi POSTOJECI, vec proveren EXTRA_AUTOPLAY mehanizam u
        // ReaderActivity - isti onaj koji je vec bezbedan i na hladnom pokretanju, ne
        // duplira se ovde nikakva nova logika za sam pocetak citanja).
        if (intent.action == ACTION_CONTINUE_READING) {
            lifecycleScope.launch {
                val last = withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(applicationContext).documentDao().getLastActiveDocument()
                }
                if (last != null) {
                    com.recporec.app.tts.PlaybackController.ensureInitialized(applicationContext)
                    val tts = com.recporec.app.tts.PlaybackController.ttsManager
                    val alreadyPlayingThis =
                        com.recporec.app.tts.PlaybackController.currentDocument?.id == last.id &&
                            tts?.isSpeaking == true
                    if (alreadyPlayingThis) {
                        tts?.pause()
                        settings.userManuallyPaused = true
                        startActivity(
                            Intent(this@DocumentListActivity, ReaderActivity::class.java)
                                .putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, last.id)
                        )
                    } else {
                        startActivity(
                            Intent(this@DocumentListActivity, ReaderActivity::class.java)
                                .putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, last.id)
                                .putExtra(ReaderActivity.EXTRA_AUTOPLAY, true)
                        )
                    }
                }
                finish()
            }
            return
        }

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
        // Korisnicki zahtev: zamenjeno sa Opcije - sad Dodaj dokument izgovara statistiku.
        binding.btnAddDocument.setOnLongClickListener {
            lifecycleScope.launch {
                val docs = withContext(Dispatchers.IO) { db.documentDao().observeAllOnce() }
                val text = com.recporec.app.util.StatsFormatter.buildStatsText(docs)
                android.widget.Toast.makeText(this@DocumentListActivity, text, android.widget.Toast.LENGTH_LONG).show()
            }
            true
        }

        // SVE dozvole (obavestenja, stanje telefona, izuzetak od stednje baterije, pun ekran
        // za budjenje) se traze ODMAH pri pokretanju aplikacije - ne tek kad se otvori prvi
        // dokument ili kad neka konkretna funkcija zatreba - da korisnika ne bi zbunilo
        // iskakanje dozvole u nezgodnom trenutku (npr. tokom poziva). Idu JEDNA PO JEDNA, u
        // lancu (ne sve odjednom - dva dijaloga istovremeno bi se pogazili).
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            if (!settings.notificationPermissionRequestedOnce) {
                settings.notificationPermissionRequestedOnce = true
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // VEC smo jednom prosli kroz sistemski zahtev, a dozvola i dalje nije data -
                // Android najverovatnije "trajno odbija" dalje pozive bez ikakvog dijaloga
                // (nema ni znaka da se to desilo, otud utisak "obavestenja nigde nema"). Umesto
                // cutke ponovnog (bezuspesnog) pokusaja, ponudi direktan put do Podesavanja.
                AlertDialog.Builder(this)
                    .setTitle("Dozvola za obaveštenja")
                    .setMessage(
                        "Bez dozvole za obaveštenja, ne možeš videti niti kontrolisati čitanje " +
                            "u pozadini preko obaveštenja (Pusti/Pauziraj/Izlaz). Telefon je " +
                            "ranije odbio ovaj zahtev, pa ga aplikacija više ne može sama " +
                            "ponovo ponuditi - potrebno je ručno uključiti u podešavanjima."
                    )
                    .setNegativeButton("Ne sada") { _, _ ->
                        maybeAskPhoneStatePermission { maybeAskBatteryOptimization { checkFullScreenIntentPermission() } }
                    }
                    .setPositiveButton("Otvori podešavanja") { _, _ ->
                        try {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            ).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                            startActivity(intent)
                        } catch (_: Exception) {
                        }
                        maybeAskPhoneStatePermission { maybeAskBatteryOptimization { checkFullScreenIntentPermission() } }
                    }
                    .setOnCancelListener {
                        maybeAskPhoneStatePermission { maybeAskBatteryOptimization { checkFullScreenIntentPermission() } }
                    }
                    .show()
            }
        } else {
            maybeAskPhoneStatePermission { maybeAskBatteryOptimization { checkFullScreenIntentPermission() } }
        }

        binding.btnGeneralActions.setOnClickListener {
            showGeneralActionsMenu()
        }

        binding.btnBackup.setOnClickListener {
            backupLauncher.launch("recporec-rezervna-kopija.zip")
        }
        binding.btnBackup.setOnLongClickListener {
            restoreLauncher.launch(arrayOf("*/*"))
            true
        }

        binding.btnExit.setOnClickListener {
            com.recporec.app.service.ReadingService.stop(this)
            com.recporec.app.tts.PlaybackController.release()
            finishAffinity()
        }
        binding.btnExit.setOnLongClickListener {
            checkExternalDevices()
            true
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
        // Korisnicki zahtev: zamenjeno sa Dodaj dokument - sad Opcije proverava dozvole.
        binding.btnOverflow.setOnLongClickListener {
            checkAllPermissionsManually()
            true
        }

        lifecycleScope.launch {
            db.documentDao().observeAll().collect { list ->
                currentList = list
                applyTabFilter()
            }
        }

        binding.groupLibraryTabs.setOnCheckedChangeListener { _, checkedId ->
            currentTab = when (checkedId) {
                binding.tabNewBooks.id -> "new"
                binding.tabStartedBooks.id -> "started"
                binding.tabFinishedBooks.id -> "finished"
                else -> "all"
            }
            settings.lastLibraryTab = currentTab
            applyTabFilter()
        }
        // Vrati na karticu na kojoj je korisnica poslednji put ostala (korisnicka ideja) -
        // podesi POSLE kacenja listenera iznad, da njegovo prirodno okidanje odmah postavi
        // i currentTab i filtriran prikaz, bez duplirane logike ovde.
        when (settings.lastLibraryTab) {
            "new" -> binding.tabNewBooks.isChecked = true
            "started" -> binding.tabStartedBooks.isChecked = true
            "finished" -> binding.tabFinishedBooks.isChecked = true
            else -> binding.tabAllBooks.isChecked = true
        }

        // Ako je aplikacija otvorena preko "Otvori sa" ili "Podeli" (npr. iz Google Diska)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // "Automatski čitaj aktivni dokument": u OBA rezima pripremamo poslednji aktivni
        // dokument OVDE (onResume, radi i kad se app samo vrati iz pozadine ili posle
        // potpunog izlaska) - da bi drmanje/medijski taster bili SPREMNI odmah, bez obzira na
        // rezim. Razlika je samo da li se citanje TAKODJE odmah cuje:
        // "Pri otvaranju aplikacije" -> da (autoPlay = true).
        // "Pri otvaranju dokumenta" -> ne, samo priprema (autoPlay = false) - citanje
        // pocinje kad korisnica RUCNO otvori neki dokument, ne pri samom pokretanju app-e.
        if (settings.autoReadEnabled) {
            val autoPlay = settings.autoReadTrigger == "app"
            com.recporec.app.tts.PlaybackController.autoResumeLastActiveDocument(this, autoPlay)
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
            if (looksLikeImage(name, mimeType)) {
                offerOcrForImage(uri, name)
            } else {
                AlertDialog.Builder(this)
                    .setMessage("Format ovog fajla nije podržan.")
                    .setPositiveButton("U redu", null)
                    .show()
            }
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

            // "Slikovni PDF" - PDF koji je zapravo samo skenirane slike, bez pravog teksta.
            // Obican PdfParser (izvlaci samo PRAVI tekst) bi vratio skoro prazno, tiho, bez
            // upozorenja. Proveri BRZO (parsiraj, izmeri kolicinu teksta) pre nego sto se
            // doda kao obican dokument - ako izgleda skeniran, ponudi OCR po stranicama.
            if (format == "pdf") {
                val looksScanned = withContext(Dispatchers.IO) { looksLikeScannedPdf(localUri) }
                if (looksScanned) {
                    offerOcrForScannedPdf(localUri, name)
                    return@launch
                }
            }

            insertDocument(localUri, name, format)
        }
    }

    private suspend fun insertDocument(localUri: Uri, name: String, format: String) {
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

    /** Primenjuje trenutno odabranu karticu (Sve/Nove/Započete/Pročitane) na PUNU listu iz baze
     * (currentList) i prikazuje samo odgovarajući podskup - currentList i dalje ostaje
     * kompletna, necu neophodno za pomeranje gore/dole koje mora da radi sa PRAVIM
     * susedima u punom redosledu, ne samo unutar filtrirane kartice. */
    private fun applyTabFilter() {
        val visible = currentList.filter { it.id !in pendingDeleteIds }
        val filtered = when (currentTab) {
            "new" -> visible.filter { it.currentCharacterOffset <= 0 }
            "started" -> visible.filter { it.totalCharacters > 0 && it.currentCharacterOffset in 1 until it.totalCharacters }
            "finished" -> visible.filter { it.totalCharacters > 0 && it.currentCharacterOffset >= it.totalCharacters }
            else -> visible
        }
        adapter.submitList(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.recyclerDocuments.visibility = if (filtered.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        // "Opšte radnje" (Odaberi sve / Obriši) nema smisla bez ijednog uvezenog dokumenta -
        // POTPUNO SE SAKRIVA (ne samo onemogući), tacno kako i tekst pomoci opisuje ("to dugme
        // se pojavljuje samo kada ima dokumenata"). Gleda se CEO spisak (currentList), ne samo
        // trenutno filtrirana kartica, jer se biranje/brisanje odnosi na dokumente uopšte, ne
        // samo na "Započete" ili "Pročitane".
        binding.btnGeneralActions.visibility = if (currentList.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.emptyView.text = when (currentTab) {
            "new" -> "Nema novih knjiga."
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
            .setMessage("Obrisati ${ids.size} odabranih dokumenata?")
            .setNegativeButton(getString(com.recporec.app.R.string.cancel), null)
            .setPositiveButton(getString(com.recporec.app.R.string.delete)) { _, _ ->
                adapter.cancelSelection()
                updateGeneralActionsLabel(0)
                scheduleDelete(ids.toList())
            }
            .show()
    }

    private fun showActionsMenu(doc: DocumentEntity) {
        AlertDialog.Builder(this)
            .setTitle(doc.title)
            .setItems(arrayOf("Premesti nagore", "Premesti nadole", "Preimenuj", "Podeli", "Izvezi u txt", "Obriši")) { _, which ->
                when (which) {
                    0 -> moveDocument(doc, up = true)
                    1 -> moveDocument(doc, up = false)
                    2 -> showRenameDialog(doc)
                    3 -> shareDocument(doc)
                    4 -> showExportDestinationDialog(doc)
                    5 -> confirmDelete(doc)
                }
            }
            .show()
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "dokument" }

    /** "Podeli" - deli SAM FAJL dokumenta (originalni format, ne izvucen tekst) sa drugom
     * aplikacijom - obrnut smer od "Podeli sa" (ShareReceiverActivity), koji PRIMA tekst.
     * Koristi FileProvider, isti obrazac vec proveren za deljenje teksta pomoci. */
    private fun shareDocument(doc: DocumentEntity) {
        try {
            val srcUri = Uri.parse(doc.uri)
            if (srcUri.scheme != "file") {
                android.widget.Toast.makeText(this, "Deljenje nije uspelo.", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val srcFile = java.io.File(srcUri.path!!)
            // KRITICNO: dokumenti se INTERNO cuvaju pod nasumicnim imenom (UUID), ne pod
            // svojim naslovom - da smo delile TAJ fajl direktno, primalac (npr. Telegram) bi
            // video baš to nasumicno ime, ne naziv knjige (korisnicka prijava). Zato se prvo
            // pravi PRIVREMENA kopija sa PRAVIM nazivom u "share" folderu (isti obrazac vec
            // proveren za deljenje teksta pomoci) - primalac vidi TU kopiju, sa tacnim
            // naslovom.
            val safeTitle = sanitizeFileName(doc.title)
            val shareDir = java.io.File(cacheDir, "share").apply { mkdirs() }
            val shareFile = java.io.File(shareDir, "$safeTitle.${doc.format}")
            srcFile.copyTo(shareFile, overwrite = true)
            val fileUri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", shareFile)
            val mimeType = when (doc.format) {
                "txt" -> "text/plain"
                "pdf" -> "application/pdf"
                "epub" -> "application/epub+zip"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "html" -> "text/html"
                "fb2" -> "text/xml"
                "rtf" -> "application/rtf"
                else -> "application/octet-stream"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Podeli dokument"))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Deljenje nije uspelo.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** "Izvezi u txt" - izvlaci ceo tekst dokumenta (bilo kog podrzanog formata) i cuva ga kao
     * obican .txt, uz OSNOVNO ciscenje. Logika ciscenja je preneta (portovana u Kotlin,
     * cisto regex/string operacije, bez ijedne nove zavisnosti) iz korisnicinog sopstvenog
     * Windows alata "IzmedjuKorica" - namerno izabran KONZERVATIVAN podskup (samo ocigledno
     * "smece": kontrolni znakovi, visestruki razmaci/prazni redovi, dekorativni separatori),
     * BEZ ijedne izmene koja bi mogla promeniti stvarni sadrzaj (npr. NIJE preneta zamena
     * decimalnog zareza tackom - to menja smisao brojeva, ne samo izgled). Radi na
     * ODVOJENOJ kopiji - NE dira sam dokument niti kako se cita naglas. */
    /** "Izvezi u txt" - prvo pita GDE: u samu biblioteku (Reč po reč, kao novi dokument -
     * korisno da se posle lakše podeli preko "Podeli" akcije) ili na telefon (sistemski
     * birač lokacije - Disk, memorija itd, isto kao do sad). */
    private fun showExportDestinationDialog(doc: DocumentEntity) {
        AlertDialog.Builder(this)
            .setTitle("Izvezi u txt")
            .setItems(arrayOf("Reč po reč", "Telefon")) { _, which ->
                when (which) {
                    0 -> exportDocumentAsTxtToLibrary(doc)
                    1 -> { pendingExportDoc = doc; exportTxtLauncher.launch("${sanitizeFileName(doc.title)}.txt") }
                }
            }
            .show()
    }

    /** "Reč po reč" grana izvoza - ociscen tekst se cuva kao NOV dokument u samoj biblioteci
     * (isti obrazac umetanja kao svuda drugde - copyToLocalStorage stilom), da bi se posle
     * mogao lako otvoriti za citanje ili podeliti preko vec postojece "Podeli" akcije. */
    private fun exportDocumentAsTxtToLibrary(doc: DocumentEntity) {
        android.widget.Toast.makeText(this, "Izvoz u toku...", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val parsed = com.recporec.app.parser.DocumentParser.parse(
                        applicationContext, Uri.parse(doc.uri), doc.format
                    )
                    val cleaned = basicCleanText(parsed.fullText)
                    val dir = java.io.File(filesDir, "documents").apply { mkdirs() }
                    val destFile = java.io.File(dir, "${java.util.UUID.randomUUID()}.txt")
                    destFile.writeText(cleaned, Charsets.UTF_8)
                    val bottomOrder = (db.documentDao().maxSortOrder() ?: 0) + 1
                    db.documentDao().insert(
                        com.recporec.app.data.DocumentEntity(
                            title = "${doc.title} (izvoz txt)",
                            uri = Uri.fromFile(destFile).toString(),
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
                    true
                } catch (_: Exception) {
                    false
                }
            }
            android.widget.Toast.makeText(
                this@DocumentListActivity,
                if (ok) "Izvezeno u Reč po reč." else "Izvoz nije uspeo.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun exportDocumentAsTxt(doc: DocumentEntity, destUri: Uri) {
        android.widget.Toast.makeText(this, "Izvoz u toku...", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val parsed = com.recporec.app.parser.DocumentParser.parse(
                        applicationContext, Uri.parse(doc.uri), doc.format
                    )
                    val cleaned = basicCleanText(parsed.fullText)
                    contentResolver.openOutputStream(destUri)?.use { out ->
                        out.write(cleaned.toByteArray(Charsets.UTF_8))
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            android.widget.Toast.makeText(
                this@DocumentListActivity,
                if (ok) "Izvezeno u txt." else "Izvoz nije uspeo.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun basicCleanText(text: String): String {
        var t = text
        // Kontrolni znakovi (osim novog reda/tabulatora, koji se resavaju posebno ispod).
        t = t.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        // Tabovi -> razmak, pa visestruki razmaci -> jedan.
        t = t.replace("\t", " ").replace(Regex("[ ]{2,}"), " ")
        // Obrisi razmake na kraju svake linije, i svedi svaku liniju (trim po liniji).
        t = t.lines().joinToString("\n") { it.trim() }
        // Dekorativne linije od 10+ istih znakova (---------- / ========== / itd).
        t = t.lines().filterNot { Regex("^([=\\-*_~])\\1{9,}$").matches(it) }.joinToString("\n")
        // Ekstremno duge linije "ASCII art" znakova (30+).
        t = t.lines().filterNot { Regex("^[#*+=\\-._~/\\\\|\\[\\](){}]{30,}$").matches(it) }.joinToString("\n")
        // Udvojena/utrojena interpunkcija (!!!, ????, .....) -> jedna/standardna elipsa.
        t = t.replace(Regex("!{2,}"), "!").replace(Regex("\\?{2,}"), "?").replace(Regex("\\.{4,}"), "...")
        // Vise od jednog praznog reda zaredom -> tacno jedan prazan red.
        t = t.replace(Regex("\n{3,}"), "\n\n")
        // Prazni redovi na samom kraju fajla.
        t = t.trimEnd('\n') + "\n"
        return t
    }

    private fun showRenameDialog(doc: DocumentEntity) {
        val input = android.widget.EditText(this).apply {
            setText(doc.title)
            setSelection(text.length)
            hint = "Naziv dokumenta"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        fun confirm() {
            // Format (ekstenzija) se cuva ODVOJENO od naziva (doc.format) i ne menja se
            // ovde - korisnica menja samo prikazani naziv, ne stvarni fajl na disku.
            val newTitle = input.text?.toString()?.trim().orEmpty()
            if (newTitle.isEmpty()) {
                android.widget.Toast.makeText(this, "Naziv ne može biti prazan.", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            lifecycleScope.launch {
                db.documentDao().update(doc.copy(title = newTitle))
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Preimenuj")
            .setView(container)
            .setNegativeButton(getString(com.recporec.app.R.string.cancel), null)
            .setPositiveButton("Sačuvaj") { _, _ -> confirm() }
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                confirm()
                dialog.dismiss()
                true
            } else false
        }
        dialog.show()
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
                scheduleDelete(listOf(doc.id))
            }
            .show()
    }

    /** "Poništi brisanje" - zajednicka funkcija za pojedinacno i grupno brisanje (Obriši sve).
     * Dokument(i) SE ODMAH SAKRIVAJU iz prikaza (applyTabFilter isključuje pendingDeleteIds),
     * ali se STVARNO brišu tek posle kratkog roka - ako se u medjuvremenu pritisne "Poništi"
     * na Snackbar-u, otkazuje se zakazano brisanje i dokumenti se vracaju u prikaz, bez ijedne
     * stvarne izmene baze. */
    private fun scheduleDelete(ids: List<Long>) {
        pendingDeleteIds.addAll(ids)
        applyTabFilter()
        val runnable = Runnable {
            finalizeDelete(ids)
            hideUndoBarIfShowing(ids)
        }
        pendingDeleteRunnables[ids] = runnable
        undoHandler.postDelayed(runnable, UNDO_DELETE_WINDOW_MS)
        val message = if (ids.size == 1) "Dokument obrisan." else "Obrisano dokumenata: ${ids.size}."
        binding.undoDeleteText.text = message
        binding.undoDeleteBar.visibility = android.view.View.VISIBLE
        binding.btnUndoDelete.setOnClickListener {
            undoHandler.removeCallbacks(runnable)
            pendingDeleteRunnables.remove(ids)
            pendingDeleteIds.removeAll(ids.toSet())
            binding.undoDeleteBar.visibility = android.view.View.GONE
            applyTabFilter()
        }
        // Odbrambeno: fokusiraj traku odmah, da je TalkBack sigurno "vidi" i najavi, umesto
        // da korisnica mora sama da je nadje prevlacenjem po ekranu.
        binding.undoDeleteBar.post {
            binding.btnUndoDelete.requestAccessibilityFocusNow()
        }
    }

    /** Sakriva traku SAMO ako jos uvek prikazuje bas OVO zakazano brisanje (ne dira je ako je
     * u medjuvremenu vec prikazuje NOVIJE brisanje - retko, ali moguce ako se brzo obrise
     * vise razlicitih dokumenata zaredom). */
    private fun hideUndoBarIfShowing(ids: List<Long>) {
        if (!pendingDeleteRunnables.containsKey(ids)) {
            runOnUiThread { binding.undoDeleteBar.visibility = android.view.View.GONE }
        }
    }

    private val pendingDeleteRunnables = mutableMapOf<List<Long>, Runnable>()

    private fun finalizeDelete(ids: List<Long>) {
        pendingDeleteRunnables.remove(ids)
        // NAMERNO ne koristi lifecycleScope - taj se gasi cim se ovaj ekran zatvori (npr.
        // otvori se neka knjiga, ili app ode u pozadinu i sistem je ugasi), sto bi znacilo da
        // se zakazano brisanje NIKAD stvarno ne izvrsi ako se to desi u tih 30 sekundi. Ovo
        // MORA da se zavrsi bez obzira da li je ovaj ekran jos uvek ziv - isti obrazac kao i
        // drugde u app-i za "mora da se zavrsi" zadatke.
        val docsToDelete = currentList.filter { it.id in ids }
        val stopReading = com.recporec.app.tts.PlaybackController.currentDocument?.id in ids
        if (stopReading) {
            com.recporec.app.service.ReadingService.stop(this@DocumentListActivity)
            com.recporec.app.tts.PlaybackController.release()
        }
        val appContext = applicationContext
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    com.recporec.app.data.AppDatabase.getInstance(appContext).documentDao().deleteByIds(ids)
                }
                docsToDelete.forEach { d ->
                    try {
                        val uri = Uri.parse(d.uri)
                        if (uri.scheme == "file") {
                            uri.path?.let { java.io.File(it).delete() }
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
        // NAMERNO se ne uklanja iz pendingDeleteIds ovde - dokument ce prirodno nestati iz
        // currentList cim baza STVARNO bude azurirana (observeAll Flow). Uklanjanje odavde
        // (pre nego sto je brisanje na pozadinskoj niti stvarno zavrseno) bi moglo da izazove
        // kratak "trep" - dokument se na tren vrati u prikaz pa opet nestane.
    }

    /** Pita SAMO JEDNOM (ikad) za dozvolu stanja telefona - rezervni mehanizam za pauzu pri
     * pozivu, odvojen od audio fokusa. Prvo objasnimo zašto, pa tek onda sistemski dijalog -
     * ovo nije uobičajena dozvola za čitač knjiga, pa zaslužuje kratko objašnjenje. Ako
     * korisnica odbije, ništa se ne pokvari - audio fokus i dalje pokušava da pauzira sam. */
    private fun maybeAskPhoneStatePermission(onDone: () -> Unit = {}) {
        if (settings.phoneStatePermissionAsked) { onDone(); return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            settings.phoneStatePermissionAsked = true
            onDone()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pauza pri pozivu")
            .setMessage(
                "Da bi čitanje pouzdanije prepoznalo dolazni poziv i samo se pauziralo, " +
                    "aplikacija može da traži dozvolu za stanje telefona (da li poziv postoji, " +
                    "ne i sa kim, niti sadržaj poziva). Nije obavezno - možeš i odbiti, " +
                    "čitanje će i dalje pokušati da se pauzira na uobičajen način."
            )
            .setNegativeButton("Ne sada") { _, _ -> settings.phoneStatePermissionAsked = true; onDone() }
            .setPositiveButton("Dozvoli") { _, _ ->
                settings.phoneStatePermissionAsked = true
                pendingPermissionChainNext = onDone
                phoneStatePermissionLauncher.launch(android.Manifest.permission.READ_PHONE_STATE)
            }
            .setOnCancelListener { onDone() }
            .show()
    }

    /** Pita SAMO JEDNOM (ikad) za izuzetak od štednje baterije - NEZAVISNO od "Čitanje bez
     * prekida" (koje ovo takodje trazi, ali samo ako se ukljuci taj prekidac). Bez ovoga, na
     * pojedinim uredjajima (narocito Samsung) sistem ume da uguši servis za citanje u pozadini
     * (i njegovu notifikaciju i medijsku sesiju za slusalice) pre nego sto stigne pouzdano da
     * proradi - primeceno kod korisnice sa Samsung telefonom i Bluetooth slusalicama. */
    private fun maybeAskBatteryOptimization(onDone: () -> Unit = {}) {
        if (settings.batteryOptimizationAsked) { onDone(); return }
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            settings.batteryOptimizationAsked = true
            onDone()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pouzdano čitanje u pozadini")
            .setMessage(
                "Da bi čitanje u pozadini i medijski tasteri na slušalicama pouzdano radili " +
                    "(posebno na Samsung telefonima), aplikacija može da zatraži izuzetak od " +
                    "štednje baterije. Nije obavezno - možeš i odbiti."
            )
            .setNegativeButton("Ne sada") { _, _ -> settings.batteryOptimizationAsked = true; onDone() }
            .setPositiveButton("Dozvoli") { _, _ ->
                settings.batteryOptimizationAsked = true
                try {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    // Neki proizvodjaci imaju svoja dodatna podesavanja stednje baterije van
                    // standardnog Android sistema - korisnica ih onda mora rucno pronaci.
                }
                onDone()
            }
            .setOnCancelListener { onDone() }
            .show()
    }

    /** Od Android 14 na dalje, pun ekran preko zaključanog ekrana (za pouzdano "Probudi me
     * u") može biti onemogućen dok korisnica to ručno ne dozvoli - ista dozvola koju imaju
     * i prave budilnik/poziv aplikacije. Poslednja karika u lancu - nema svoj onDone.
     * VAŽNO: za razliku od ostalih dozvola u lancu, OVDE se NAMERNO proverava STVARNO,
     * UŽIVO stanje pri svakom pokretanju - ne postoji "trajno zapamti da si pitala" izlaz,
     * jer bi to (kao što se i desilo - korisnička prijava) moglo zauvek da ostavi ovu
     * dozvolu neodobrenu bez ikakvog daljeg podsetnika, tiho onesposobljavajući puni ekran
     * budjenja preko zaključanog ekrana bez ikakvog znaka da se to desilo. */
    private fun checkFullScreenIntentPermission() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.canUseFullScreenIntent()) {
            AlertDialog.Builder(this)
                .setTitle("Dozvola za buđenje preko zaključanog ekrana")
                .setMessage(
                    "Da bi \"Probudi me u\" moglo pouzdano da otvori ceo ekran i kad je telefon zaključan, " +
                        "potrebno je ručno da dozvoliš to u podešavanjima telefona, na sledećem ekranu."
                )
                .setNegativeButton("Ne sada", null)
                .setPositiveButton("Otvori podešavanja") { _, _ ->
                    try {
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    } catch (_: Exception) {
                    }
                }
                .show()
        }
    }

    /** Dug pritisak na "Dodaj dokument" - ponovo prolazi kroz SVE cetiri dozvole, ponasajuci
     * se kao da se app prvi put pokrece: ako je nesto vec odobreno, tiho se preskace; ako
     * nije, pita ponovo (ili, za obavestenja/pun ekran, ponudi direktan put do Podesavanja
     * ako sistemski dijalog vise ne moze da se prikaze). NAMERNO zaobilazi "vec pitano"
     * zastavice za stanje telefona i bateriju (za razliku od automatskog lanca pri pokretanju
     * app-e) - ovo je eksplicitna, rucna radnja korisnice, pa ima smisla da uvek stvarno
     * proveri. Prati da li je BAS SVE bilo vec odobreno (bez ijednog dijaloga) - ako jeste,
     * na kraju prikazuje potvrdu; ako je bilo i jednog dijaloga, ne prikazuje ništa dodatno
     * (svaki dijalog vec sam objasnjava svoju stavku). */
    private fun checkAllPermissionsManually() {
        var allGranted = true
        checkNotificationPermissionManually(onPrompted = { allGranted = false }) {
            checkPhoneStatePermissionManually(onPrompted = { allGranted = false }) {
                checkBatteryOptimizationManually(onPrompted = { allGranted = false }) {
                    checkFullScreenIntentPermissionManually(onPrompted = { allGranted = false }) {
                        if (allGranted) {
                            AlertDialog.Builder(this)
                                .setTitle("Provera dozvola")
                                .setMessage("Sve dozvole su odobrene.")
                                .setPositiveButton("U redu", null)
                                .show()
                        }
                    }
                }
            }
        }
    }

    private fun checkNotificationPermissionManually(onPrompted: () -> Unit, onDone: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onDone()
            return
        }
        onPrompted()
        if (!settings.notificationPermissionRequestedOnce) {
            settings.notificationPermissionRequestedOnce = true
            pendingPermissionChainNext = onDone
            notificationPermissionManualLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Dozvola za obaveštenja")
                .setMessage(
                    "Dozvola za obaveštenja i dalje nije data, a telefon je ranije odbio " +
                        "ovaj zahtev, pa ga aplikacija više ne može sama ponovo ponuditi - " +
                        "potrebno je ručno uključiti u podešavanjima."
                )
                .setNegativeButton("Ne sada") { _, _ -> onDone() }
                .setPositiveButton("Otvori podešavanja") { _, _ ->
                    try {
                        startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                    } catch (_: Exception) {
                    }
                    onDone()
                }
                .setOnCancelListener { onDone() }
                .show()
        }
    }

    private fun checkPhoneStatePermissionManually(onPrompted: () -> Unit, onDone: () -> Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onDone()
            return
        }
        onPrompted()
        AlertDialog.Builder(this)
            .setTitle("Pauza pri pozivu")
            .setMessage(
                "Da bi čitanje pouzdanije prepoznalo dolazni poziv i samo se pauziralo, " +
                    "aplikacija može da traži dozvolu za stanje telefona. Nije obavezno."
            )
            .setNegativeButton("Ne sada") { _, _ -> onDone() }
            .setPositiveButton("Dozvoli") { _, _ ->
                pendingPermissionChainNext = onDone
                phoneStatePermissionLauncher.launch(android.Manifest.permission.READ_PHONE_STATE)
            }
            .setOnCancelListener { onDone() }
            .show()
    }

    private fun checkBatteryOptimizationManually(onPrompted: () -> Unit, onDone: () -> Unit) {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            onDone()
            return
        }
        onPrompted()
        AlertDialog.Builder(this)
            .setTitle("Pouzdano čitanje u pozadini")
            .setMessage(
                "Da bi čitanje u pozadini i medijski tasteri na slušalicama pouzdano radili " +
                    "(posebno na Samsung telefonima), aplikacija može da zatraži izuzetak od " +
                    "štednje baterije. Nije obavezno."
            )
            .setNegativeButton("Ne sada") { _, _ -> onDone() }
            .setPositiveButton("Dozvoli") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                }
                onDone()
            }
            .setOnCancelListener { onDone() }
            .show()
    }

    /** Ista provera kao pri pokretanju app-e (uvek uzivo stanje, bez "vec pitano" zastavice -
     * ova dozvola nema ni pravi sistemski dijalog, uvek se ide na Podesavanja). */
    private fun checkFullScreenIntentPermissionManually(onPrompted: () -> Unit, onDone: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 34) { onDone(); return }
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.canUseFullScreenIntent()) {
            onPrompted()
            AlertDialog.Builder(this)
                .setTitle("Dozvola za buđenje preko zaključanog ekrana")
                .setMessage(
                    "Da bi \"Probudi me u\" moglo pouzdano da otvori ceo ekran i kad je telefon zaključan, " +
                        "potrebno je ručno da dozvoliš to u podešavanjima telefona, na sledećem ekranu."
                )
                .setNegativeButton("Ne sada") { _, _ -> onDone() }
                .setPositiveButton("Otvori podešavanja") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {
                    }
                    onDone()
                }
                .setOnCancelListener { onDone() }
                .show()
        } else {
            onDone()
        }
    }

    /** Dug pritisak na "Izlaz" - "Otkrivanje spoljnog uređaja". NAMERNO samo PROVERAVA sta je
     * TRENUTNO povezano (Bluetooth slusalice, zicane slusalice, spoljna tastatura) - ne
     * pokusava SAMA da upari/poveze nista novo. Stvarno "trazenje i parovanje" novog Bluetooth
     * uredjaja bi zahtevalo novu, "opasnu" dozvolu (BLUETOOTH_CONNECT) i nezvanicne/skrivene
     * pozive sistemu (nestabilno, rizicno) - umesto toga, ako se nista ne pronadje, ponudi
     * direktan put do Android-ovih SOPSTVENIH Bluetooth podesavanja, gde se parovanje stvarno
     * i radi na bezbedan, standardan nacin. Koristi SAMO AudioManager/InputDevice provere -
     * ne dodaje nijednu novu dozvolu, ne dira Bluetooth medijske tastere koje smo vec sredile. */
    private fun checkExternalDevices() {
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val outputs = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val hasBluetooth = outputs.any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val hasWired = outputs.any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val hasKeyboard = android.view.InputDevice.getDeviceIds().any { id ->
            val dev = android.view.InputDevice.getDevice(id)
            dev != null && dev.isExternal &&
                (dev.sources and android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD &&
                dev.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC
        }

        val lines = StringBuilder("Otkrivanje spoljnog uređaja.\n\n")
        lines.append(if (hasBluetooth) "Bluetooth slušalice: povezane.\n" else "Bluetooth slušalice: nisu povezane.\n")
        lines.append(if (hasWired) "Žične slušalice: povezane.\n" else "Žične slušalice: nisu povezane.\n")
        lines.append(if (hasKeyboard) "Spoljna tastatura: povezana." else "Spoljna tastatura: nije povezana.")

        val builder = AlertDialog.Builder(this)
            .setTitle("Otkrivanje spoljnog uređaja")
            .setMessage(lines.toString())
            .setPositiveButton("U redu", null)
        if (!hasBluetooth && !hasWired && !hasKeyboard) {
            builder.setNegativeButton("Otvori Bluetooth podešavanja") { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
        builder.show()
    }

    /** Pakuje SVE dokumente (same fajlove, poziciju čitanja, oznake) u jedan .zip - koristi
     * SAMO ugrađen java.util.zip i org.json (isti kao za izvoz podešavanja), bez ijedne nove
     * zavisnosti - namerno nizak rizik, isto kao postojeći izvoz. */
    private fun backupAllDocuments(uri: android.net.Uri) {
        lifecycleScope.launch {
            android.widget.Toast.makeText(this@DocumentListActivity, "Pravljenje rezervne kopije...", android.widget.Toast.LENGTH_SHORT).show()
            val ok = withContext(Dispatchers.IO) {
                try {
                    val db = com.recporec.app.data.AppDatabase.getInstance(applicationContext)
                    val docs = db.documentDao().observeAllOnce()
                    val bookmarks = db.bookmarkDao().getAll()

                    val docsJson = org.json.JSONArray()
                    val zipFileNames = HashMap<Long, String>()
                    docs.forEach { d ->
                        val zipName = "doc_${d.id}.${d.format}"
                        zipFileNames[d.id] = zipName
                        val obj = org.json.JSONObject()
                        obj.put("origId", d.id)
                        obj.put("zipFileName", zipName)
                        obj.put("title", d.title)
                        obj.put("format", d.format)
                        obj.put("totalCharacters", d.totalCharacters)
                        obj.put("currentCharacterOffset", d.currentCharacterOffset)
                        obj.put("totalPages", d.totalPages)
                        obj.put("currentPage", d.currentPage)
                        obj.put("speechRate", d.speechRate.toDouble())
                        obj.put("volumePercent", d.volumePercent)
                        obj.put("voiceName", d.voiceName)
                        obj.put("voiceEngine", d.voiceEngine)
                        obj.put("languageTag", d.languageTag)
                        obj.put("elapsedSeconds", d.elapsedSeconds)
                        obj.put("timerMinutes", d.timerMinutes)
                        obj.put("dateAdded", d.dateAdded)
                        obj.put("sortOrder", d.sortOrder)
                        obj.put("pitch", d.pitch.toDouble())
                        obj.put("lastTimerStartOffset", d.lastTimerStartOffset)
                        obj.put("lastTimerMinutes", d.lastTimerMinutes)
                        obj.put("lastOpenedTimestamp", d.lastOpenedTimestamp)
                        docsJson.put(obj)
                    }
                    val bookmarksJson = org.json.JSONArray()
                    bookmarks.forEach { b ->
                        val obj = org.json.JSONObject()
                        obj.put("origDocumentId", b.documentId)
                        obj.put("name", b.name)
                        obj.put("characterOffset", b.characterOffset)
                        obj.put("dateAdded", b.dateAdded)
                        bookmarksJson.put(obj)
                    }
                    val manifest = org.json.JSONObject()
                    manifest.put("version", 1)
                    manifest.put("documents", docsJson)
                    manifest.put("bookmarks", bookmarksJson)

                    contentResolver.openOutputStream(uri)?.use { out ->
                        java.util.zip.ZipOutputStream(out).use { zip ->
                            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                            docs.forEach { d ->
                                val srcPath = android.net.Uri.parse(d.uri).path ?: return@forEach
                                val srcFile = java.io.File(srcPath)
                                if (!srcFile.exists()) return@forEach
                                zip.putNextEntry(java.util.zip.ZipEntry(zipFileNames[d.id]!!))
                                srcFile.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            android.widget.Toast.makeText(
                this@DocumentListActivity,
                if (ok) "Rezervna kopija napravljena." else "Pravljenje rezervne kopije nije uspelo.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Vraća rezervnu kopiju napravljenu preko backupAllDocuments() - novi ID-jevi se
     * generišu za svaki dokument (Room ih sam dodeljuje), pa se ID-jevi oznaka preslikaju
     * (stari -> novi) da bi ostale vezane za pravi dokument. */
    private fun restoreDocumentsBackup(uri: android.net.Uri) {
        lifecycleScope.launch {
            android.widget.Toast.makeText(this@DocumentListActivity, "Vraćanje rezervne kopije...", android.widget.Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) {
                try {
                    val db = com.recporec.app.data.AppDatabase.getInstance(applicationContext)
                    val dir = java.io.File(filesDir, "documents").apply { mkdirs() }
                    val entries = HashMap<String, ByteArray>()
                    contentResolver.openInputStream(uri)?.use { input ->
                        java.util.zip.ZipInputStream(input).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                entries[entry.name] = zip.readBytes()
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                    }
                    val manifestBytes = entries["manifest.json"] ?: return@withContext -1
                    val manifest = org.json.JSONObject(String(manifestBytes, Charsets.UTF_8))
                    val docsJson = manifest.getJSONArray("documents")
                    val bookmarksJson = manifest.optJSONArray("bookmarks")

                    val idMap = HashMap<Long, Long>()
                    var restoredCount = 0
                    for (i in 0 until docsJson.length()) {
                        val obj = docsJson.getJSONObject(i)
                        val zipFileName = obj.getString("zipFileName")
                        val fileBytes = entries[zipFileName] ?: continue
                        val format = obj.getString("format")
                        val destFile = java.io.File(dir, "${java.util.UUID.randomUUID()}.$format")
                        destFile.writeBytes(fileBytes)

                        val newId = db.documentDao().insert(
                            com.recporec.app.data.DocumentEntity(
                                title = obj.getString("title"),
                                uri = android.net.Uri.fromFile(destFile).toString(),
                                format = format,
                                totalCharacters = obj.optInt("totalCharacters", 0),
                                currentCharacterOffset = obj.optInt("currentCharacterOffset", 0),
                                totalPages = obj.optInt("totalPages", 0),
                                currentPage = obj.optInt("currentPage", 0),
                                speechRate = obj.optDouble("speechRate", -1.0).toFloat(),
                                volumePercent = obj.optInt("volumePercent", -1),
                                voiceName = obj.optString("voiceName", null),
                                voiceEngine = obj.optString("voiceEngine", null),
                                languageTag = obj.optString("languageTag", null),
                                elapsedSeconds = obj.optLong("elapsedSeconds", 0),
                                timerMinutes = obj.optInt("timerMinutes", 0),
                                dateAdded = obj.optLong("dateAdded", System.currentTimeMillis()),
                                sortOrder = obj.optInt("sortOrder", 0),
                                pitch = obj.optDouble("pitch", -1.0).toFloat(),
                                lastTimerStartOffset = if (obj.isNull("lastTimerStartOffset")) null else obj.optInt("lastTimerStartOffset"),
                                lastTimerMinutes = if (obj.isNull("lastTimerMinutes")) null else obj.optInt("lastTimerMinutes"),
                                lastOpenedTimestamp = obj.optLong("lastOpenedTimestamp", 0)
                            )
                        )
                        idMap[obj.getLong("origId")] = newId
                        restoredCount++
                    }
                    if (bookmarksJson != null) {
                        for (i in 0 until bookmarksJson.length()) {
                            val obj = bookmarksJson.getJSONObject(i)
                            val newDocId = idMap[obj.getLong("origDocumentId")] ?: continue
                            db.bookmarkDao().insert(
                                com.recporec.app.data.BookmarkEntity(
                                    documentId = newDocId,
                                    name = obj.getString("name"),
                                    characterOffset = obj.getInt("characterOffset"),
                                    dateAdded = obj.optLong("dateAdded", System.currentTimeMillis())
                                )
                            )
                        }
                    }
                    restoredCount
                } catch (_: Exception) {
                    -1
                }
            }
            if (result >= 0) {
                // Zip vise nije potreban posle uspesnog vracanja - obrisi ga (korisnicki
                // zahtev). DocumentsContract.deleteDocument radi za fajlove izabrane preko
                // OpenDocument (SAF) - ne uspe li (npr. neki provajder ne dozvoljava
                // brisanje), tiho preskoci, ne prekida ostatak toka.
                withContext(Dispatchers.IO) {
                    try {
                        android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                    } catch (_: Exception) {
                    }
                }
            }
            android.widget.Toast.makeText(
                this@DocumentListActivity,
                if (result >= 0) "Vraćeno dokumenata: $result." else "Vraćanje rezervne kopije nije uspelo - proveri da li je fajl ispravan.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Prepoznaje da li je izabran fajl SLIKA (ne podržan tekst format) - po ekstenziji ili
     * MIME tipu. Koristi se da se ponudi OCR umesto obicne "format nije podrzan" poruke. */
    private fun looksLikeImage(fileName: String, mimeType: String?): Boolean {
        val lower = fileName.lowercase()
        val byExtension = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".bmp") || lower.endsWith(".webp")
        return byExtension || mimeType?.startsWith("image/") == true
    }

    /** OCR (citanje teksta sa slike) - potpuno OFFLINE, na samom uredjaju (ML Kit), nista se
     * ne salje preko interneta. NIJE 100% pouzdano (rukom pisan tekst, los kvalitet slike,
     * ili nestandardno pismo mogu dati lose rezultate) - zato se korisnica UVEK PITA prvo,
     * i tekst se jasno oznacava kao "iz slike" u nazivu, da zna otkud je dosao. */
    /** Brza provera "da li je ovo skeniran PDF" (slike, bez pravog teksta) - parsira PDF
     * (izvlaci samo PRAVI, ugradjen tekst, isto sto vec radi PdfParser za normalno citanje)
     * i meri kolicinu. Prag je namerno vrlo nizak (200 karaktera za CEO dokument) - obican
     * PDF sa pravim tekstom ce imati hiljade karaktera i na SAMO prvoj strani, dok skeniran
     * PDF (bez tekstualnog sloja) vraca prazno ili skoro prazno. */
    /** Isti "zvuk dugmadi" korišćen svuda drugde (podleže istom prekidaču "Zvuk") - zvučna
     * potvrda da je prepoznavanje teksta sa slike/skeniranog PDF-a uspešno završeno, uz
     * postojeću tekstualnu poruku - bitno za pristupačnost sa čitačima ekrana. */
    private fun playOcrDoneSound() {
        if (!AppSettings(this).soundFeedbackEnabled) return
        try {
            if (ocrToneGenerator == null) {
                ocrToneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70)
            }
            ocrToneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 60)
        } catch (_: Exception) {
        }
    }

    private fun looksLikeScannedPdf(localUri: Uri): Boolean {
        return try {
            val parsed = com.recporec.app.parser.DocumentParser.parse(this, localUri, "pdf")
            parsed.fullText.trim().length < 200
        } catch (_: Exception) {
            false // ne uspe li provera, ne guraj na OCR - pusti obican tok da prijavi gresku
        }
    }

    /** "Slikovni PDF" - PDF koji izgleda kao samo skenirane slike, bez pravog teksta. Umesto
     * direktnog dodavanja (koje bi rezultovalo praznim/skoro praznim dokumentom), pita da li
     * da se svaka stranica pretvori u sliku i pusti kroz OCR (isti kao za obicne slike). */
    private fun offerOcrForScannedPdf(localUri: Uri, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Skeniran PDF")
            .setMessage(
                "Ovaj PDF izgleda kao skeniran - slike, ne pravi tekst. Da probam da pročitam " +
                    "tekst sa slika svake stranice? Ova opcija nije 100% pouzdana, i može " +
                    "potrajati za veće dokumente."
            )
            .setNegativeButton("Ne, dodaj kao obično") { _, _ ->
                lifecycleScope.launch { insertDocument(localUri, name, "pdf") }
            }
            .setPositiveButton("Probaj") { _, _ -> runOcrOnPdfPages(localUri, name) }
            .show()
    }

    /** Isti princip kao OCR za jednu sliku, ponovljen za svaku stranicu PDF-a - svaka
     * stranica se pretvori u sliku (PDFRenderer, vec deo postojece PDFBox zavisnosti, bez
     * ijedne nove biblioteke), pa ide kroz isti ML Kit prepoznavalac (JEDNA instanca,
     * ponovo koriscena za sve stranice - preporuceno od strane ML Kit-a, brze od pravljenja
     * nove za svaku stranicu). Stranica koja ne uspe se preskace, ne prekida ostatak. */
    private fun runOcrOnPdfPages(localUri: Uri, name: String) {
        android.widget.Toast.makeText(
            this, "Prepoznavanje teksta u toku, može potrajati...", android.widget.Toast.LENGTH_LONG
        ).show()
        lifecycleScope.launch {
            val combinedText = withContext(Dispatchers.IO) {
                try {
                    val sb = StringBuilder()
                    contentResolver.openInputStream(localUri)?.use { input ->
                        com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
                            val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
                            )
                            for (i in 0 until doc.numberOfPages) {
                                try {
                                    val bitmap = renderer.renderImage(
                                        i, 2f, com.tom_roush.pdfbox.rendering.ImageType.RGB
                                    )
                                    val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
                                    val pageText = recognizer.process(image).await().text
                                    sb.append(pageText).append("\n\n")
                                } catch (_: Exception) {
                                    // Preskoci samo ovu stranicu, nastavi sa ostalim.
                                }
                            }
                        }
                    }
                    sb.toString()
                } catch (_: Exception) {
                    null
                }
            }
            if (combinedText.isNullOrBlank()) {
                AlertDialog.Builder(this@DocumentListActivity)
                    .setMessage("Nije uspelo prepoznavanje teksta iz ovog PDF-a.")
                    .setPositiveButton("U redu", null)
                    .show()
                return@launch
            }
            val savedUri = withContext(Dispatchers.IO) {
                try {
                    val dir = java.io.File(filesDir, "documents").apply { mkdirs() }
                    val destFile = java.io.File(dir, "${java.util.UUID.randomUUID()}.txt")
                    destFile.writeText(combinedText)
                    Uri.fromFile(destFile)
                } catch (_: Exception) {
                    null
                }
            }
            if (savedUri == null) return@launch
            val bottomOrder = (db.documentDao().maxSortOrder() ?: 0) + 1
            db.documentDao().insert(
                com.recporec.app.data.DocumentEntity(
                    title = "${name.substringBeforeLast(".")} (iz slika)",
                    uri = savedUri.toString(),
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
            playOcrDoneSound()
            android.widget.Toast.makeText(
                this@DocumentListActivity, "Tekst slike je spreman.", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun offerOcrForImage(uri: Uri, fileName: String) {
        AlertDialog.Builder(this)
            .setTitle("Slika, ne tekst")
            .setMessage(
                "Ovo izgleda kao slika, ne podržan tekst format. Da probam da pročitam tekst " +
                    "sa nje? Ova opcija nije 100% pouzdana - zavisi od kvaliteta slike i teksta na njoj."
            )
            .setNegativeButton("Ne, hvala", null)
            .setPositiveButton("Probaj") { _, _ -> runOcrAndInsert(uri, fileName) }
            .show()
    }

    private fun runOcrAndInsert(uri: Uri, fileName: String) {
        android.widget.Toast.makeText(this, "Prepoznavanje teksta u toku...", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val recognizedText = withContext(Dispatchers.IO) {
                try {
                    val bitmap = contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    } ?: return@withContext null
                    val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
                    val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                        com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
                    )
                    // ML Kit-ov API je asinhron (Task) - .await() (kotlinx-coroutines-play-services)
                    // ga cisto povezuje sa suspend funkcijom, bez rucnog blokiranja niti.
                    recognizer.process(image).await().text
                } catch (_: Exception) {
                    null
                }
            }
            if (recognizedText.isNullOrBlank()) {
                AlertDialog.Builder(this@DocumentListActivity)
                    .setMessage("Nije uspelo prepoznavanje teksta sa ove slike.")
                    .setPositiveButton("U redu", null)
                    .show()
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
            if (localUri == null) return@launch
            val bottomOrder = (db.documentDao().maxSortOrder() ?: 0) + 1
            db.documentDao().insert(
                com.recporec.app.data.DocumentEntity(
                    title = "${fileName.substringBeforeLast(".")} (iz slike)",
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
            playOcrDoneSound()
            android.widget.Toast.makeText(this@DocumentListActivity, "Tekst slike je spreman.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        // "Poništi brisanje" - korisnicki zahtev: do 30 sekundi (Snackbar-ova podrazumevana
        // kratka najduza traje samo par sekundi, nedovoljno da se stigne pronaci i pritisnuti
        // dugme preko TalkBack-a).
        private const val UNDO_DELETE_WINDOW_MS = 30000L
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrToneGenerator?.release()
    }
}
