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

/** Akcija koju koristi precica sa ikonice aplikacije ("Nastavi citanje") - vidi shortcuts.xml. */
private const val ACTION_CONTINUE_READING = "com.recporec.app.ACTION_CONTINUE_READING"

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
        // Statistika citanja: premestena sa liste u meniju na dug pritisak DUGMETA Opcije -
        // elegantnije, i dosledno obrascu "dug pritisak = dodatna radnja" koji vec koristi
        // sva ostala dugmad u citacu.
        binding.btnOverflow.setOnLongClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
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
                binding.tabStartedBooks.id -> "started"
                binding.tabFinishedBooks.id -> "finished"
                else -> "all"
            }
            applyTabFilter()
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
        // "Opšte radnje" (Odaberi sve / Obriši) nema smisla bez ijednog uvezenog dokumenta -
        // POTPUNO SE SAKRIVA (ne samo onemogući), tacno kako i tekst pomoci opisuje ("to dugme
        // se pojavljuje samo kada ima dokumenata"). Gleda se CEO spisak (currentList), ne samo
        // trenutno filtrirana kartica, jer se biranje/brisanje odnosi na dokumente uopšte, ne
        // samo na "Započete" ili "Pročitane".
        binding.btnGeneralActions.visibility = if (currentList.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
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
            .setItems(arrayOf("Premesti nagore", "Premesti nadole", "Preimenuj", "Obriši")) { _, which ->
                when (which) {
                    0 -> moveDocument(doc, up = true)
                    1 -> moveDocument(doc, up = false)
                    2 -> showRenameDialog(doc)
                    3 -> confirmDelete(doc)
                }
            }
            .show()
    }

    private fun showRenameDialog(doc: DocumentEntity) {
        val input = android.widget.EditText(this).apply {
            setText(doc.title)
            setSelection(text.length)
            hint = "Naziv dokumenta"
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Preimenuj")
            .setView(container)
            .setNegativeButton(getString(com.recporec.app.R.string.cancel), null)
            .setPositiveButton("Sačuvaj") { _, _ ->
                // Format (ekstenzija) se cuva ODVOJENO od naziva (doc.format) i ne menja se
                // ovde - korisnica menja samo prikazani naziv, ne stvarni fajl na disku.
                val newTitle = input.text?.toString()?.trim().orEmpty()
                if (newTitle.isEmpty()) {
                    android.widget.Toast.makeText(this, "Naziv ne može biti prazan.", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    db.documentDao().update(doc.copy(title = newTitle))
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
}
