package com.recporec.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.recporec.app.data.AppSettings
import com.recporec.app.databinding.ActivitySettingsBinding
import com.recporec.app.util.requestAccessibilityFocusNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { AppSettings(this) }

    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(settings.exportAsJson().toByteArray(Charsets.UTF_8))
            }
            android.widget.Toast.makeText(this, "Podešavanja su izvezena.", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Izvoz nije uspeo.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (text == null) {
                android.widget.Toast.makeText(this, "Uvoz nije uspeo.", android.widget.Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val count = settings.importFromJson(text)
            refreshFromSettings()
            android.widget.Toast.makeText(this, "Uvezeno podešavanja: $count.", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Fajl nije prepoznat kao ispravan izvoz podešavanja.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // "Rezervna kopija dokumenata" - dug pritisak na Izvezi/Uvezi podesavanja. Za razliku od
    // izvoza podesavanja (samo glas/prekidaci), ovo pakuje SVE dokumente (same fajlove,
    // pozicija citanja, oznake) u jedan .zip - korisnicki zahtev, isti obrazac dugmadi kao
    // vec postojeci izvoz/uvoz.
    private val backupLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        backupAllDocuments(uri)
    }

    private val restoreLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        restoreDocumentsBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        refreshFromSettings()

        binding.switchBackground.setOnCheckedChangeListener { _, checked ->
            settings.backgroundEnabled = checked
            if (!checked) binding.switchUninterrupted.isChecked = false
        }
        binding.switchUninterrupted.setOnCheckedChangeListener { _, checked ->
            if (checked && !settings.backgroundEnabled) {
                binding.switchBackground.isChecked = true
                settings.backgroundEnabled = true
            }
            settings.uninterruptedEnabled = checked
            if (checked) requestIgnoreBatteryOptimizations()
        }
        binding.switchShake.setOnCheckedChangeListener { _, checked ->
            settings.shakeEnabled = checked
            binding.groupShakeSensitivity.visibility =
                if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }
        binding.groupShakeSensitivity.setOnCheckedChangeListener { _, checkedId ->
            settings.shakeSensitivity = when (checkedId) {
                binding.radioShakeLight.id -> 0
                binding.radioShakeStrong.id -> 2
                else -> 1
            }
        }
        binding.switchSound.setOnCheckedChangeListener { _, checked ->
            settings.soundFeedbackEnabled = checked
        }
        binding.switchSentencePause.setOnCheckedChangeListener { _, checked ->
            settings.sentencePauseEnabled = checked
            binding.groupSentencePauseMs.visibility =
                if (checked) android.view.View.VISIBLE else android.view.View.GONE
            if (checked) binding.seekSentencePause.requestAccessibilityFocusNow()
        }
        binding.seekSentencePause.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings.sentencePauseMs = progress
                binding.textSentencePauseStatus.text = "Pauza između rečenica: $progress ms"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        binding.switchParagraphPause.setOnCheckedChangeListener { _, checked ->
            settings.paragraphPauseEnabled = checked
            binding.groupParagraphPauseMs.visibility =
                if (checked) android.view.View.VISIBLE else android.view.View.GONE
            if (checked) binding.seekParagraphPause.requestAccessibilityFocusNow()
        }
        binding.seekParagraphPause.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings.paragraphPauseMs = progress
                binding.textParagraphPauseStatus.text = "Pauza između pasusa: $progress ms"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        binding.switchAutoNext.setOnCheckedChangeListener { _, checked ->
            settings.autoNextDocumentEnabled = checked
        }
        binding.switchAutoRead.setOnCheckedChangeListener { _, checked ->
            settings.autoReadEnabled = checked
            binding.groupAutoReadTrigger.visibility =
                if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }
        binding.groupAutoReadTrigger.setOnCheckedChangeListener { _, checkedId ->
            settings.autoReadTrigger = when (checkedId) {
                binding.radioAutoReadDocument.id -> "document"
                else -> "app"
            }
        }

        binding.btnNavigationMode.setOnClickListener {
            val currentLabel = navLabels[navValues.indexOf(settings.navigationMode).coerceAtLeast(0)]
            PickerDialog.show(this, "Izaberi način navigacije", navLabels, currentLabel, autoConfirm = true) { index ->
                settings.navigationMode = navValues[index]
                refreshNavButton()
            }
        }

        binding.btnExportSettings.setOnClickListener {
            exportLauncher.launch("recporec-podesavanja.json")
        }
        binding.btnExportSettings.setOnLongClickListener {
            backupLauncher.launch("recporec-rezervna-kopija.zip")
            true
        }

        binding.btnImportSettings.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        binding.btnImportSettings.setOnLongClickListener {
            restoreLauncher.launch(arrayOf("application/zip", "*/*"))
            true
        }

        binding.btnResetGeneralDefaults.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Vrati na zadano")
                .setMessage("Vraća sva podešavanja na ovom ekranu (rad u pozadini, drmanje, zvuk, pauza između rečenica, automatski nastavak, automatsko čitanje, navigacija) na podrazumevano stanje.")
                .setNegativeButton(com.recporec.app.R.string.cancel, null)
                .setPositiveButton(getString(com.recporec.app.R.string.delete)) { _, _ ->
                    settings.resetGeneralSettingsToDefaults()
                    // Direktno osvežavamo prikaz umesto da se oslanjamo na recreate() - na
                    // nekim uređajima recreate() ume da se ne pokrene pouzdano posle dijaloga.
                    refreshFromSettings()
                    android.widget.Toast.makeText(this, "Podešavanja su vraćena na zadano.", android.widget.Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private val navLabels = listOf("Stranica", "1 minut", "5 minuta", "10 minuta", "Oznaka")
    private val navValues = listOf("page", "min1", "min5", "min10", "bookmark")

    private fun refreshNavButton() {
        val idx = navValues.indexOf(settings.navigationMode).coerceAtLeast(0)
        binding.btnNavigationMode.text = navLabels[idx]
    }

    /** Učitava SVE prikazane vrednosti direktno iz sačuvanih podešavanja - koristi se i pri
     * otvaranju ekrana i posle "Vrati na zadano", umesto oslanjanja na recreate(). */
    private fun refreshFromSettings() {
        binding.switchBackground.isChecked = settings.backgroundEnabled
        binding.switchUninterrupted.isChecked = settings.uninterruptedEnabled
        binding.switchShake.isChecked = settings.shakeEnabled
        binding.switchSound.isChecked = settings.soundFeedbackEnabled
        binding.switchSentencePause.isChecked = settings.sentencePauseEnabled
        binding.switchAutoNext.isChecked = settings.autoNextDocumentEnabled
        binding.switchAutoRead.isChecked = settings.autoReadEnabled
        binding.groupAutoReadTrigger.visibility =
            if (settings.autoReadEnabled) android.view.View.VISIBLE else android.view.View.GONE
        if (settings.autoReadTrigger == "document") {
            binding.radioAutoReadDocument.isChecked = true
        } else {
            binding.radioAutoReadApp.isChecked = true
        }

        binding.groupSentencePauseMs.visibility =
            if (settings.sentencePauseEnabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.seekSentencePause.max = 1000
        binding.seekSentencePause.progress = settings.sentencePauseMs.coerceIn(0, 1000)
        binding.textSentencePauseStatus.text = "Pauza između rečenica: ${settings.sentencePauseMs} ms"

        binding.switchParagraphPause.isChecked = settings.paragraphPauseEnabled
        binding.groupParagraphPauseMs.visibility =
            if (settings.paragraphPauseEnabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.seekParagraphPause.max = 1000
        binding.seekParagraphPause.progress = settings.paragraphPauseMs.coerceIn(0, 1000)
        binding.textParagraphPauseStatus.text = "Pauza između pasusa: ${settings.paragraphPauseMs} ms"

        binding.groupShakeSensitivity.visibility =
            if (settings.shakeEnabled) android.view.View.VISIBLE else android.view.View.GONE
        when (settings.shakeSensitivity) {
            0 -> binding.radioShakeLight.isChecked = true
            2 -> binding.radioShakeStrong.isChecked = true
            else -> binding.radioShakeMedium.isChecked = true
        }

        refreshNavButton()
    }

    /** Traži od sistema da ne ograničava aplikaciju radi štednje baterije, da bi čitanje
     * moglo pouzdano da nastavi i kada se ekran zaključa. */
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                android.widget.Toast.makeText(
                    this,
                    "Na sledećem ekranu izaberi \"Dozvoli\" ili \"Bez ograničenja\", da bi čitanje pouzdano radilo i kad je ekran zaključan.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } catch (_: Exception) {
            // Neki telefoni (npr. pojedini Xiaomi, Huawei modeli) imaju svoja dodatna
            // podešavanja štednje baterije van standardnog Android sistema, koja aplikacija
            // ne može programski da otvori - tada korisnik mora ručno da ih pronađe u
            // podešavanjima telefona (obično "Baterija" -> ime aplikacije -> "Bez ograničenja").
        }
        showManufacturerSpecificBatteryHint()
    }

    /** Standardna Android dozvola (iznad) NE pokriva dodatni sloj štednje baterije koji imaju
     * pojedini proizvođači (Samsung, Xiaomi, Huawei...) - za njega ne postoji nikakav
     * programski nacin da app sama zatrazi izuzece, cak ni sami programeri tih telefona to
     * ne mogu. Jedino sto mozemo je da JASNO uputimo korisnicu gde rucno da to pronadje. */
    private fun showManufacturerSpecificBatteryHint() {
        val manufacturer = android.os.Build.MANUFACTURER?.lowercase() ?: ""
        val message = when {
            manufacturer.contains("samsung") ->
                "Samsung telefoni imaju i DODATNU, posebnu listu \"uspavanih\" aplikacija, van gornjeg podešavanja. " +
                    "Idi u: Podešavanja telefona, Baterija i nega uređaja, Baterija, Ograničenja u pozadini. " +
                    "Dodaj Reč po reč na listu \"Nikad ne uspavljuj\", i proveri da nije na listi \"Uspavane\" ili \"Duboko uspavane\" aplikacije."
            manufacturer.contains("xiaomi") ->
                "Xiaomi telefoni imaju i dodatno podešavanje za automatsko pokretanje. " +
                    "Idi u: Podešavanja telefona, Aplikacije, Reč po reč, i uključi \"Automatsko pokretanje\"."
            manufacturer.contains("huawei") ->
                "Huawei telefoni imaju i dodatno podešavanje za pokretanje aplikacija. " +
                    "Idi u: Podešavanja telefona, Baterija, Pokretanje aplikacija, Reč po reč, i podesi ručno (uključi sve tri opcije)."
            else -> null
        }
        if (message != null) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Dodatno podešavanje baterije")
                .setMessage(message)
                .setPositiveButton("Razumem", null)
                .show()
        }
    }

    /** Pakuje SVE dokumente (same fajlove, poziciju čitanja, oznake) u jedan .zip - koristi
     * SAMO ugrađen java.util.zip i org.json (isti kao za izvoz podešavanja), bez ijedne nove
     * zavisnosti - namerno nizak rizik, isto kao postojeći izvoz. */
    private fun backupAllDocuments(uri: android.net.Uri) {
        lifecycleScope.launch {
            android.widget.Toast.makeText(this@SettingsActivity, "Pravljenje rezervne kopije...", android.widget.Toast.LENGTH_SHORT).show()
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
                this@SettingsActivity,
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
            android.widget.Toast.makeText(this@SettingsActivity, "Vraćanje rezervne kopije...", android.widget.Toast.LENGTH_SHORT).show()
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
            android.widget.Toast.makeText(
                this@SettingsActivity,
                if (result >= 0) "Vraćeno dokumenata: $result." else "Vraćanje rezervne kopije nije uspelo - proveri da li je fajl ispravan.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
