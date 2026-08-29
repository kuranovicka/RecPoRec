package com.recporec.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.recporec.app.data.AppSettings
import com.recporec.app.databinding.ActivitySettingsBinding
import com.recporec.app.util.requestAccessibilityFocusNow
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private val db by lazy { com.recporec.app.data.AppDatabase.getInstance(this) }

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
        // "Prekidac u prekidacu" - korisnicki zahtev: umesto da opcije (Blago/Srednje/Jako)
        // trajno vise ispod prekidaca (siri listu podesavanja bez potrebe), sad se odmah
        // otvara mali izbor cim se prekidac ukljuci, izabere se, i vrati direktno u
        // Podesavanja. Za KASNIJU izmenu (dok je vec ukljuceno) - dug pritisak na prekidac,
        // isti obrazac "dug pritisak = dodatna radnja" koji vec koristi sva dugmad u citacu.
        binding.switchShake.setOnCheckedChangeListener { _, checked ->
            settings.shakeEnabled = checked
            if (checked) showShakeSensitivityPicker()
            updateShakeLabel()
        }
        binding.switchShake.setOnLongClickListener {
            if (settings.shakeEnabled) showShakeSensitivityPicker()
            true
        }
        binding.switchSound.setOnCheckedChangeListener { _, checked ->
            settings.soundFeedbackEnabled = checked
        }
        // "Prekidac u prekidacu" - isti obrazac kao Drmanje: dijalog sa klizacem se otvara
        // ODMAH cim se prekidac ukljuci (ne stoji trajno vidljiv na ekranu), a za KASNIJU
        // izmenu (dok je vec ukljuceno) - dug pritisak, isto kao svuda drugde.
        binding.switchSentencePause.setOnCheckedChangeListener { _, checked ->
            settings.sentencePauseEnabled = checked
            if (checked) showPauseMsPicker(isSentence = true)
            updateSentencePauseLabel()
        }
        binding.switchSentencePause.setOnLongClickListener {
            if (settings.sentencePauseEnabled) showPauseMsPicker(isSentence = true)
            true
        }
        binding.switchParagraphPause.setOnCheckedChangeListener { _, checked ->
            settings.paragraphPauseEnabled = checked
            if (checked) showPauseMsPicker(isSentence = false)
            updateParagraphPauseLabel()
        }
        binding.switchParagraphPause.setOnLongClickListener {
            if (settings.paragraphPauseEnabled) showPauseMsPicker(isSentence = false)
            true
        }
        binding.switchAutoNext.setOnCheckedChangeListener { _, checked ->
            settings.autoNextDocumentEnabled = checked
        }
        binding.switchDeleteOriginalAudioFolder.setOnCheckedChangeListener { _, checked ->
            settings.deleteOriginalAudioFolder = checked
        }
        binding.switchAutoRead.setOnCheckedChangeListener { _, checked ->
            settings.autoReadEnabled = checked
            if (checked) showAutoReadTriggerPicker()
            updateAutoReadLabel()
        }
        binding.switchAutoRead.setOnLongClickListener {
            if (settings.autoReadEnabled) showAutoReadTriggerPicker()
            true
        }

        binding.btnNavigationMode.setOnClickListener {
            val currentLabel = navLabels[navValues.indexOf(settings.navigationMode).coerceAtLeast(0)]
            // showSearch = false - korisnicka prijava: pretraga je suvisna za ovako kratku
            // listu, i pravila je probleme (video se nepotreban element).
            PickerDialog.show(this, "Izaberi način navigacije", navLabels, currentLabel, autoConfirm = true, showSearch = false) { index ->
                val chosenMode = navValues[index]
                settings.navigationMode = chosenMode
                refreshNavButton()
                // "Minuti" - konsolidovano iz tri odvojene opcije (1/5/10 minuta) u jednu, sa
                // pod-izborom - isti obrazac kao "Rečenice" ispod.
                if (chosenMode == "minute") {
                    val minuteLabels = listOf("1 minut", "2 minuta", "5 minuta", "10 minuta")
                    val minuteValues = listOf(1, 2, 5, 10)
                    val currentMinuteLabel = minuteLabels[minuteValues.indexOf(settings.minuteNavigationCount).coerceAtLeast(0)]
                    PickerDialog.show(this, "Koliko minuta po koraku", minuteLabels, currentMinuteLabel, autoConfirm = true, showSearch = false) { minIndex ->
                        settings.minuteNavigationCount = minuteValues[minIndex]
                    }
                }
                // "Rečenice" - korisnicki zahtev (isti obrazac kao izbor jedinice za
                // Automatski listaj dokument) - odmah posle izbora nacina, pita se KOLIKO
                // recenica po koraku (1, 3 ili 5 - namerno OGRANICEN, ne slobodan unos: "5
                // recenica je vec jedan minut", dalje nema smisla).
                if (chosenMode == "sentence") {
                    val countLabels = listOf("1 rečenica", "3 rečenice", "5 rečenica", "10 rečenica")
                    val countValues = listOf(1, 3, 5, 10)
                    val currentCountLabel = countLabels[countValues.indexOf(settings.sentenceNavigationCount).coerceAtLeast(0)]
                    PickerDialog.show(this, "Koliko rečenica po koraku", countLabels, currentCountLabel, autoConfirm = true, showSearch = false) { countIndex ->
                        settings.sentenceNavigationCount = countValues[countIndex]
                    }
                }
            }
        }

        binding.btnExportSettings.setOnClickListener {
            exportLauncher.launch("recporec-podesavanja.json")
        }

        binding.btnImportSettings.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        binding.btnResetGeneralDefaults.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Vrati na zadano")
                .setMessage("Vraća SVA podešavanja (glas, jezik, brzina, jačina, visina, kombinovani glasovi, navigacija i sve prekidače) na podrazumevano stanje.")
                .setNegativeButton(com.recporec.app.R.string.cancel, null)
                .setPositiveButton(getString(com.recporec.app.R.string.delete)) { _, _ ->
                    settings.resetAllSettingsToDefaults()
                    // Korisnicka prijava: kombinovani glasovi se nisu resetovali odavde (samo
                    // sa drugog ekrana) - ovaj ekran uopste nije imao pristup bazi za to.
                    lifecycleScope.launch {
                        db.combinedVoiceDao().clearScope(0L)
                    }
                    // Direktno osvežavamo prikaz umesto da se oslanjamo na recreate() - na
                    // nekim uređajima recreate() ume da se ne pokrene pouzdano posle dijaloga.
                    refreshFromSettings()
                    android.widget.Toast.makeText(this, "Sva podešavanja su vraćena na zadano.", android.widget.Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private val navLabels = listOf("Stranica", "Minuti", "Oznaka", "Rečenice")
    private val navValues = listOf("page", "minute", "bookmark", "sentence")

    private fun showAutoReadTriggerPicker() {
        val labels = listOf("Pri otvaranju aplikacije", "Pri otvaranju dokumenta")
        val currentLabel = if (settings.autoReadTrigger == "document") labels[1] else labels[0]
        PickerDialog.show(this, "Kada automatski čitati", labels, currentLabel, autoConfirm = true, showSearch = false) { index ->
            settings.autoReadTrigger = if (index == 1) "document" else "app"
            updateAutoReadLabel()
        }
    }

    private fun updateAutoReadLabel() {
        val baseLabel = "Automatski čitaj aktivni dokument"
        if (settings.autoReadEnabled) {
            val triggerLabel = if (settings.autoReadTrigger == "document") "Pri otvaranju dokumenta" else "Pri otvaranju aplikacije"
            binding.switchAutoRead.text = "$baseLabel - $triggerLabel"
            binding.switchAutoRead.contentDescription =
                "$baseLabel. Trenutno: $triggerLabel. Dug pritisak: promeni."
        } else {
            binding.switchAutoRead.text = baseLabel
            binding.switchAutoRead.contentDescription = baseLabel
        }
    }

    private fun showShakeSensitivityPicker() {
        val labels = listOf("Blago", "Srednje", "Jako")
        val currentLabel = labels[settings.shakeSensitivity.coerceIn(0, 2)]
        PickerDialog.show(this, "Jačina drmanja", labels, currentLabel, autoConfirm = true, showSearch = false) { index ->
            settings.shakeSensitivity = index
            updateShakeLabel()
        }
    }

    /** Korisnicki zahtev: prekidac sam po sebi (tekst i sazetak za citac ekrana) sad pokazuje
     * TRENUTNO odabranu podopciju - isti princip kao "Prethodne rečenice" dugme u citacu koje
     * pokazuje tacan broj. Bez ovoga bi korisnica morala da pamti sta je izabrala, ili da
     * ponovo otvara izbornik samo da proveri. */
    private fun updateShakeLabel() {
        val baseLabel = getString(com.recporec.app.R.string.setting_shake)
        if (settings.shakeEnabled) {
            val levelLabel = listOf("Blago", "Srednje", "Jako")[settings.shakeSensitivity.coerceIn(0, 2)]
            binding.switchShake.text = "$baseLabel - $levelLabel"
            binding.switchShake.contentDescription =
                "$baseLabel. Trenutno: $levelLabel. Dug pritisak: promeni jačinu."
        } else {
            binding.switchShake.text = baseLabel
            binding.switchShake.contentDescription = baseLabel
        }
    }

    /** Isti "dinamički prekidač" princip kao Drmanje - dijalog sa klizačem se otvara ODMAH
     * čim se prekidač uključi, a za kasniju izmenu služi dug pritisak. Klizač NIJE trajno
     * vidljiv na ekranu (isto kao Tajmer u čitaču). */
    private fun showPauseMsPicker(isSentence: Boolean) {
        val view = layoutInflater.inflate(com.recporec.app.R.layout.dialog_pause_ms, null)
        val seek = view.findViewById<android.widget.SeekBar>(com.recporec.app.R.id.seekPauseMs)
        val status = view.findViewById<android.widget.TextView>(com.recporec.app.R.id.textPauseStatus)
        val current = if (isSentence) settings.sentencePauseMs else settings.paragraphPauseMs
        seek.max = 1000
        seek.progress = current.coerceIn(0, 1000)
        status.text = "${seek.progress} ms"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                status.text = "$progress ms"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        val title = if (isSentence) "Pauza između rečenica" else "Pauza između pasusa"
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(com.recporec.app.R.string.cancel, null)
            .setPositiveButton("Postavi") { _, _ ->
                if (isSentence) {
                    settings.sentencePauseMs = seek.progress
                    updateSentencePauseLabel()
                } else {
                    settings.paragraphPauseMs = seek.progress
                    updateParagraphPauseLabel()
                }
            }
            .show()
        seek.requestAccessibilityFocusNow()
    }

    private fun updateSentencePauseLabel() {
        val baseLabel = getString(com.recporec.app.R.string.setting_sentence_pause)
        if (settings.sentencePauseEnabled) {
            binding.switchSentencePause.text = "$baseLabel - ${settings.sentencePauseMs} ms"
            binding.switchSentencePause.contentDescription =
                "$baseLabel. Trenutno: ${settings.sentencePauseMs} ms. Dug pritisak: promeni."
        } else {
            binding.switchSentencePause.text = baseLabel
            binding.switchSentencePause.contentDescription = baseLabel
        }
    }

    private fun updateParagraphPauseLabel() {
        val baseLabel = "Pauza između pasusa"
        if (settings.paragraphPauseEnabled) {
            binding.switchParagraphPause.text = "$baseLabel - ${settings.paragraphPauseMs} ms"
            binding.switchParagraphPause.contentDescription =
                "$baseLabel. Trenutno: ${settings.paragraphPauseMs} ms. Dug pritisak: promeni."
        } else {
            binding.switchParagraphPause.text = baseLabel
            binding.switchParagraphPause.contentDescription = baseLabel
        }
    }

    private fun refreshNavButton() {
        val idx = navValues.indexOf(settings.navigationMode).coerceAtLeast(0)
        binding.btnNavigationMode.text = navLabels[idx]
    }

    /** Učitava SVE prikazane vrednosti direktno iz sačuvanih podešavanja - koristi se i pri
     * otvaranju ekrana i posle "Vrati na zadano", umesto oslanjanja na recreate(). */
    private fun refreshFromSettings() {
        binding.switchBackground.isChecked = settings.backgroundEnabled
        binding.switchUninterrupted.isChecked = settings.uninterruptedEnabled
        // Bez ovoga bi postavljanje isChecked OVDE (programski, ne dodirom) ponovo pokrenulo
        // dole prikacen listener, i izbornik za jacinu drmanja bi iskakao SVAKI PUT kad se
        // ekran otvori (ako je drmanje vec ukljuceno) - ne samo kad korisnica STVARNO dodirne
        // prekidac.
        binding.switchShake.setOnCheckedChangeListener(null)
        binding.switchShake.isChecked = settings.shakeEnabled
        binding.switchShake.setOnCheckedChangeListener { _, checked ->
            settings.shakeEnabled = checked
            if (checked) showShakeSensitivityPicker()
            updateShakeLabel()
        }
        updateShakeLabel()
        binding.switchSound.isChecked = settings.soundFeedbackEnabled
        binding.switchAutoNext.isChecked = settings.autoNextDocumentEnabled
        binding.switchDeleteOriginalAudioFolder.isChecked = settings.deleteOriginalAudioFolder
        // Isti razlog kao kod Drmanja iznad - bez detach/reattach, izbor "kada citati" bi
        // iskakao SVAKI PUT kad se ekran otvori (ako je vec ukljuceno), ne samo pri dodiru.
        binding.switchAutoRead.setOnCheckedChangeListener(null)
        binding.switchAutoRead.isChecked = settings.autoReadEnabled
        binding.switchAutoRead.setOnCheckedChangeListener { _, checked ->
            settings.autoReadEnabled = checked
            if (checked) showAutoReadTriggerPicker()
            updateAutoReadLabel()
        }
        updateAutoReadLabel()

        binding.switchBuiltInPronunciationDict.isChecked = settings.builtInPronunciationDictionaryEnabled
        binding.switchBuiltInPronunciationDict.setOnCheckedChangeListener { _, checked ->
            settings.builtInPronunciationDictionaryEnabled = checked
        }
        binding.btnPronunciationDictionary.setOnClickListener {
            startActivity(android.content.Intent(this, PronunciationActivity::class.java))
        }

        // Isti razlog kao kod Drmanja - bez detach/reattach, dijalog sa klizacem bi iskakao
        // SVAKI PUT kad se ekran otvori (ako je pauza vec ukljucena), ne samo pri dodiru.
        binding.switchSentencePause.setOnCheckedChangeListener(null)
        binding.switchSentencePause.isChecked = settings.sentencePauseEnabled
        binding.switchSentencePause.setOnCheckedChangeListener { _, checked ->
            settings.sentencePauseEnabled = checked
            if (checked) showPauseMsPicker(isSentence = true)
            updateSentencePauseLabel()
        }
        updateSentencePauseLabel()

        binding.switchParagraphPause.setOnCheckedChangeListener(null)
        binding.switchParagraphPause.isChecked = settings.paragraphPauseEnabled
        binding.switchParagraphPause.setOnCheckedChangeListener { _, checked ->
            settings.paragraphPauseEnabled = checked
            if (checked) showPauseMsPicker(isSentence = false)
            updateParagraphPauseLabel()
        }
        updateParagraphPauseLabel()

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

}
