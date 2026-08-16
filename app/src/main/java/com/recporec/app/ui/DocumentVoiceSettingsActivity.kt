package com.recporec.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.recporec.app.R
import com.recporec.app.util.requestAccessibilityFocusNow
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.AppSettings
import com.recporec.app.data.DocumentEntity
import com.recporec.app.databinding.ActivityDocumentVoiceSettingsBinding
import com.recporec.app.tts.TtsEngineUtil
import com.recporec.app.tts.VoiceOption
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** "Podešavanja za ovaj dokument" - isti ekran/logika kao Opšta podešavanja glasa, samo
 * piše u DocumentEntity polja (speechRate, pitch, volumePercent, voiceName, voiceEngine,
 * languageTag) umesto u AppSettings. Ta polja i logika čitanja (dokument ima prednost,
 * -1/null znači "koristi opšte") već postoje i rade u PlaybackController - ovo je samo
 * ekran da se ta polja mogu postaviti. Kombinovani glasovi koriste isti scopeId sistem,
 * samo sa ID dokumenta umesto 0L. */
class DocumentVoiceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentVoiceSettingsBinding
    private val settings by lazy { AppSettings(this) }
    private val db by lazy { AppDatabase.getInstance(this) }
    private var allVoices: List<VoiceOption> = emptyList()
    private var documentId: Long = -1L
    private var document: DocumentEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentVoiceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        documentId = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L)
        if (documentId <= 0L) {
            finish()
            return
        }

        loadVoices()
        lifecycleScope.launch {
            document = db.documentDao().getById(documentId)
            if (document == null) {
                finish()
                return@launch
            }
            setupControls()
            refreshStatusTexts()
        }
    }

    private fun setupControls() {
        val doc = document ?: return

        binding.btnLanguage.setOnClickListener { showLanguagePicker() }
        binding.btnVoice.setOnClickListener { showVoicePicker() }
        binding.btnCombinedVoices.setOnClickListener {
            // VAŽNO: čita SVEŽ "document" (ne zarobljeni "doc" iznad, koji je snimak od
            // otvaranja ekrana) - inače bi Kombinovani glasovi uvek video jezik/glas OD PRE
            // eventualne izmene, i tiho posezao za opštim umesto za upravo izabranim
            // dokument-specifičnim jezikom/glasom.
            val fresh = document ?: return@setOnClickListener
            startActivity(
                android.content.Intent(this, CombinedVoicesActivity::class.java)
                    .putExtra(CombinedVoicesActivity.EXTRA_SCOPE_ID, documentId)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_LANGUAGE_TAG, fresh.languageTag ?: settings.globalLanguageTag)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_VOICE_NAME, fresh.voiceName ?: settings.globalVoiceName)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_VOICE_ENGINE, fresh.voiceEngine ?: settings.globalVoiceEngine)
            )
        }

        // Brzina: klizač 0..270 predstavlja stopu 0.30x .. 3.00x (korak 0.01). "Nije
        // posebno postavljeno" (-1) prikazuje se kao trenutna EFEKTIVNA (opšta) vrednost,
        // ali se ne upisuje u dokument dok korisnica stvarno ne pomeri klizač.
        val effRate = doc.speechRate.let { if (it > 0f) it else settings.globalSpeechRate }
        binding.seekSpeed.max = 270
        binding.seekSpeed.progress = ((effRate * 100).roundToInt() - 30).coerceIn(0, 270)
        binding.seekSpeed.requestAccessibilityFocusNow()
        binding.seekSpeed.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                updateDocument { it.copy(speechRate = (progress + 30) / 100f) }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val effVolume = doc.volumePercent.let { if (it >= 0) it else settings.globalVolumePercent }
        binding.seekVolume.max = 100
        binding.seekVolume.progress = effVolume
        binding.seekVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                updateDocument { it.copy(volumePercent = progress) }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Visina: klizač 0..150 predstavlja visinu 0.50x .. 2.00x (korak 0.01)
        val effPitch = doc.pitch.let { if (it > 0f) it else settings.globalPitch }
        binding.seekPitch.max = 150
        binding.seekPitch.progress = ((effPitch * 100).roundToInt() - 50).coerceIn(0, 150)
        binding.seekPitch.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                updateDocument { it.copy(pitch = (progress + 50) / 100f) }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.btnResetVoiceDefaults.setOnClickListener { confirmResetDocumentDefaults() }
        binding.btnBack.setOnClickListener { finish() }
    }

    /** Menja SAMO ovaj dokument u bazi (ne AppSettings) i osvežava lokalnu kopiju + prikaz.
     * VAŽNO: ako je ovo TRENUTNO aktivan dokument u PlaybackController (npr. čita se u
     * pozadini), mora se osvežiti i ta KEŠIRANA kopija (PlaybackController.currentDocument) -
     * inače bi sledeće periodično čuvanje pozicije (persistCurrentDocument, koje upisuje
     * CEO entitet iz te kešrane kopije) tiho vratilo ovu promenu nazad na staro, i izgledalo
     * bi kao da se ništa ne pamti. */
    private fun updateDocument(change: (DocumentEntity) -> DocumentEntity) {
        val current = document ?: return
        val updated = change(current)
        document = updated
        lifecycleScope.launch {
            db.documentDao().update(updated)
            if (com.recporec.app.tts.PlaybackController.currentDocument?.id == documentId) {
                com.recporec.app.tts.PlaybackController.currentDocument = updated
            }
            refreshStatusTexts()
        }
    }

    private fun confirmResetDocumentDefaults() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Vrati na zadano")
            .setMessage("Vraća podešavanja OVOG DOKUMENTA (glas, jezik, brzinu, jačinu, visinu, kombinovane glasove) da opet prate opšta podešavanja. Ne dira opšta podešavanja programa.")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    val reset = (document ?: return@launch).copy(
                        speechRate = -1f,
                        pitch = -1f,
                        volumePercent = -1,
                        voiceName = null,
                        voiceEngine = null,
                        languageTag = null
                    )
                    document = reset
                    db.documentDao().update(reset)
                    db.combinedVoiceDao().clearScope(documentId)
                    if (com.recporec.app.tts.PlaybackController.currentDocument?.id == documentId) {
                        com.recporec.app.tts.PlaybackController.currentDocument = reset
                    }
                    setupControls()
                    refreshStatusTexts()
                    android.widget.Toast.makeText(
                        this@DocumentVoiceSettingsActivity, "Podešavanja ovog dokumenta su vraćena na zadano.", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun loadVoices() {
        lifecycleScope.launch {
            allVoices = try {
                TtsEngineUtil.listAllVoices(this@DocumentVoiceSettingsActivity)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun showLanguagePicker() {
        val voices = allVoices.ifEmpty {
            binding.textLanguageStatus.text = "Učitavanje glasova, sačekaj trenutak i probaj ponovo"
            return
        }
        val languages = TtsEngineUtil.distinctLanguages(voices)
        val labels = languages.map { it.displayLanguage.replaceFirstChar { c -> c.uppercase() } }
        val currentTag = document?.languageTag
        val current = currentTag?.let { code ->
            languages.firstOrNull { it.language == code }?.displayLanguage
        }
        PickerDialog.show(this, "Izaberi jezik za ovaj dokument", labels, current, autoConfirm = true) { index ->
            val chosen = languages[index]
            updateDocument { it.copy(languageTag = chosen.language) }
        }
    }

    private fun showVoicePicker() {
        val voices = allVoices.ifEmpty {
            binding.textVoiceStatus.text = "Učitavanje glasova, sačekaj trenutak i probaj ponovo"
            return
        }
        val languageFilter = document?.languageTag ?: settings.globalLanguageTag
        val filtered = if (languageFilter != null) {
            voices.filter { it.voice.locale.language == languageFilter }.ifEmpty { voices }
        } else voices

        val labels = TtsEngineUtil.disambiguatedLabels(filtered)
        val current = document?.voiceName?.let { name ->
            filtered.firstOrNull { it.voice.name == name }?.displayLabel
        }
        PickerDialog.show(
            this, "Izaberi glas za ovaj dokument", labels, current,
            onSelectionPreview = { index -> TtsEngineUtil.previewVoice(this, filtered[index]) },
            autoConfirm = true
        ) { index ->
            val chosen = filtered[index]
            updateDocument { it.copy(voiceName = chosen.voice.name, voiceEngine = chosen.enginePackage) }
        }
    }

    private fun refreshStatusTexts() {
        val doc = document ?: return

        val langTag = doc.languageTag
        binding.textLanguageStatus.text = if (langTag != null) {
            "Potvrđeno za ovaj dokument: ${Locale(langTag).displayLanguage.replaceFirstChar { it.uppercase() }}"
        } else "Nije posebno izabrano (koristi se opšti jezik)"

        val voiceName = doc.voiceName
        binding.textVoiceStatus.text = if (voiceName != null) {
            "Potvrđeno za ovaj dokument: ${allVoices.firstOrNull { it.voice.name == voiceName }?.displayLabel ?: voiceName}"
        } else "Nije posebno izabran (koristi se opšti glas)"

        val effRate = doc.speechRate.let { if (it > 0f) it else settings.globalSpeechRate }
        val effVolume = doc.volumePercent.let { if (it >= 0) it else settings.globalVolumePercent }
        val effPitch = doc.pitch.let { if (it > 0f) it else settings.globalPitch }
        binding.textSpeedStatus.text = "Brzina: ${(effRate * 100).roundToInt()}%" + if (doc.speechRate <= 0f) " (opšte)" else ""
        binding.textVolumeStatus.text = "Jačina: ${effVolume}%" + if (doc.volumePercent < 0) " (opšte)" else ""
        binding.textPitchStatus.text = "Visina: ${(effPitch * 100).roundToInt()}%" + if (doc.pitch <= 0f) " (opšte)" else ""
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "extra_document_id"
    }
}
