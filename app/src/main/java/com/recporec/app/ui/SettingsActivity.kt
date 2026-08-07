package com.recporec.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.recporec.app.data.AppSettings
import com.recporec.app.databinding.ActivitySettingsBinding
import com.recporec.app.util.requestAccessibilityFocusNow

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { AppSettings(this) }

    // Cuva "sledeci korak" u lancu dozvola dok cekamo da se SISTEMSKI dijalog (za dozvolu)
    // zatvori - isti obrazac kao u DocumentListActivity.
    private var pendingPermissionChainNext: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    private val phoneStatePermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        pendingPermissionChainNext?.invoke()
        pendingPermissionChainNext = null
    }

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

        binding.btnCheckPermissions.setOnClickListener {
            checkAllPermissionsManually()
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

    /** Dugme "Proveri dozvole" - ponovo prolazi kroz SVE cetiri dozvole, ponasajuci se kao da
     * se app prvi put pokrece: ako je nesto vec odobreno, tiho se preskace; ako nije, pita
     * ponovo (ili, za obavestenja/pun ekran, ponudi direktan put do Podesavanja ako sistemski
     * dijalog vise ne moze da se prikaze). NAMERNO zaobilazi "vec pitano" zastavice za stanje
     * telefona i bateriju (za razliku od automatskog lanca pri pokretanju app-e) - ovo je
     * eksplicitna, rucna radnja korisnice, pa ima smisla da uvek stvarno proveri, ne da cutke
     * preskoci samo zato sto je nekad davno vec pitano. */
    private fun checkAllPermissionsManually() {
        checkNotificationPermissionManually {
            checkPhoneStatePermissionManually {
                checkBatteryOptimizationManually {
                    checkFullScreenIntentPermission()
                }
            }
        }
    }

    private fun checkNotificationPermissionManually(onDone: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onDone()
            return
        }
        if (!settings.notificationPermissionRequestedOnce) {
            settings.notificationPermissionRequestedOnce = true
            pendingPermissionChainNext = onDone
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            android.app.AlertDialog.Builder(this)
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

    private fun checkPhoneStatePermissionManually(onDone: () -> Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onDone()
            return
        }
        android.app.AlertDialog.Builder(this)
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

    private fun checkBatteryOptimizationManually(onDone: () -> Unit) {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            onDone()
            return
        }
        android.app.AlertDialog.Builder(this)
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
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:$packageName")
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
    private fun checkFullScreenIntentPermission() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.canUseFullScreenIntent()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Dozvola za buđenje preko zaključanog ekrana")
                .setMessage(
                    "Da bi \"Probudi me u\" moglo pouzdano da otvori ceo ekran i kad je telefon zaključan, " +
                        "potrebno je ručno da dozvoliš to u podešavanjima telefona, na sledećem ekranu."
                )
                .setNegativeButton("Ne sada", null)
                .setPositiveButton("Otvori podešavanja") { _, _ ->
                    try {
                        startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
                .show()
        }
    }
}
