package com.recporec.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.recporec.app.R
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.CombinedVoiceEntryEntity
import com.recporec.app.data.CombinedVoiceLanguageEntity
import com.recporec.app.data.CombinedVoiceSettingsEntity
import com.recporec.app.databinding.ActivityCombinedVoicesBinding
import com.recporec.app.tts.TtsEngineUtil
import com.recporec.app.tts.VoiceOption
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Ekran "Kombinovani glasovi" - koristi se i za opšta podešavanja (scopeId = 0)
 * i za pojedinačni dokument (scopeId = id tog dokumenta). Svako dugme je posebna,
 * jasna radnja - bez ugnježdenih menija.
 */
class CombinedVoicesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCombinedVoicesBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var scopeId: Long = 0L
    private var defaultLanguageTag: String? = null
    private var defaultVoiceName: String? = null
    private var defaultVoiceEngine: String? = null
    private var allVoices: List<VoiceOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCombinedVoicesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        scopeId = intent.getLongExtra(EXTRA_SCOPE_ID, 0L)
        defaultLanguageTag = intent.getStringExtra(EXTRA_DEFAULT_LANGUAGE_TAG)
        defaultVoiceName = intent.getStringExtra(EXTRA_DEFAULT_VOICE_NAME)
        defaultVoiceEngine = intent.getStringExtra(EXTRA_DEFAULT_VOICE_ENGINE)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = if (scopeId == 0L) "Kombinovani glasovi (opšte)" else "Kombinovani glasovi (ovaj dokument)"

        lifecycleScope.launch {
            allVoices = try {
                TtsEngineUtil.listAllVoices(this@CombinedVoicesActivity)
            } catch (e: Exception) {
                emptyList()
            }
            refreshStatusTexts()
        }

        binding.btnAddLanguage.setOnClickListener { showAddLanguageDialog() }
        binding.btnRemoveLanguage.setOnClickListener { showRemoveLanguageDialog() }
        binding.btnAddVoice.setOnClickListener { showAddVoiceDialog() }
        binding.btnRemoveVoice.setOnClickListener { showRemoveVoiceDialog() }
        binding.btnSentenceCount.setOnClickListener { showSentenceCountDialog() }
    }

    private fun langLabel(tag: String): String =
        Locale(tag).displayLanguage.replaceFirstChar { it.uppercase() }

    private fun showAddLanguageDialog() {
        if (allVoices.isEmpty()) {
            Toast.makeText(this, "Učitavanje glasova, sačekaj trenutak i probaj ponovo.", Toast.LENGTH_SHORT).show()
            return
        }
        val languages = TtsEngineUtil.distinctLanguages(allVoices)
        val labels = languages.map { it.displayLanguage.replaceFirstChar { c -> c.uppercase() } }

        fun addLanguage(index: Int) {
            val tag = languages[index].language
            lifecycleScope.launch {
                val existing = db.combinedVoiceDao().findLanguage(scopeId, tag)
                if (existing != null) {
                    Toast.makeText(this@CombinedVoicesActivity, "Taj jezik je već dodat.", Toast.LENGTH_SHORT).show()
                } else {
                    db.combinedVoiceDao().insertLanguage(CombinedVoiceLanguageEntity(scopeId = scopeId, languageTag = tag))
                    Toast.makeText(this@CombinedVoicesActivity, "Dodat jezik: ${labels[index]}.", Toast.LENGTH_SHORT).show()
                    refreshStatusTexts()
                }
            }
        }

        // Kad postoji samo jedan jezik za izbor, nema potrebe za celim ekranom sa
        // pretragom i listom - direktno se dodaje.
        if (languages.size == 1) {
            addLanguage(0)
        } else {
            PickerDialog.show(this, "Dodaj jezik", labels, null, autoConfirm = true) { index -> addLanguage(index) }
        }
    }

    private fun showRemoveLanguageDialog() {
        lifecycleScope.launch {
            val languages = db.combinedVoiceDao().getLanguages(scopeId)
            if (languages.isEmpty()) {
                AlertDialog.Builder(this@CombinedVoicesActivity)
                    .setTitle("Ukloni jezik")
                    .setMessage("Nema dodatih jezika.")
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }

            fun confirmRemove(lang: CombinedVoiceLanguageEntity) {
                AlertDialog.Builder(this@CombinedVoicesActivity)
                    .setTitle("Ukloni jezik")
                    .setMessage("Ukloniti \"${langLabel(lang.languageTag)}\"? Uklonićeš i sve dodate glasove tog jezika.")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        lifecycleScope.launch {
                            db.combinedVoiceDao().deleteVoicesForLanguage(scopeId, lang.languageTag)
                            db.combinedVoiceDao().deleteLanguage(lang.id)
                            refreshStatusTexts()
                        }
                    }
                    .show()
            }

            // Kad postoji samo jedan dodat jezik, nema potrebe da se prvo bira sa liste -
            // odmah se pita samo za potvrdu brisanja.
            if (languages.size == 1) {
                confirmRemove(languages[0])
                return@launch
            }
            val labels = languages.map { langLabel(it.languageTag) }.toTypedArray()
            AlertDialog.Builder(this@CombinedVoicesActivity)
                .setTitle("Ukloni jezik")
                .setItems(labels) { _, which -> confirmRemove(languages[which]) }
                .show()
        }
    }

    private fun showAddVoiceDialog() {
        lifecycleScope.launch {
            val addedLanguages = db.combinedVoiceDao().getLanguages(scopeId)
            // I bez eksplicitnog "Dodaj jezik", uvek se moze dodati jos jedan glas iz vec
            // izabranog (obicnog) jezika za ovaj opseg - ako taj jezik ima vise glasova
            // (npr. muski i zenski), nema potrebe da se isti jezik posebno dodaje.
            val languageTags = (addedLanguages.map { it.languageTag } + listOfNotNull(defaultLanguageTag)).toSet()
            if (languageTags.isEmpty()) {
                Toast.makeText(this@CombinedVoicesActivity, "Prvo dodaj bar jedan jezik, ili izaberi obični jezik.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val candidates = allVoices.filter { it.voice.locale.language in languageTags }

            if (candidates.isEmpty()) {
                Toast.makeText(this@CombinedVoicesActivity, "Nema dostupnih glasova za dodate jezike.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = TtsEngineUtil.disambiguatedLabels(candidates)

            fun addVoice(index: Int) {
                val chosen = candidates[index]
                lifecycleScope.launch {
                    val already = db.combinedVoiceDao().getVoices(scopeId).any { it.voiceName == chosen.voice.name }
                    if (already) {
                        Toast.makeText(this@CombinedVoicesActivity, "Taj glas je već dodat.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val nextOrder = (db.combinedVoiceDao().maxVoiceOrder(scopeId) ?: -1) + 1
                    db.combinedVoiceDao().insertVoice(
                        CombinedVoiceEntryEntity(
                            scopeId = scopeId,
                            voiceName = chosen.voice.name,
                            voiceEngine = chosen.enginePackage,
                            languageTag = chosen.voice.locale.language,
                            orderIndex = nextOrder
                        )
                    )
                    Toast.makeText(this@CombinedVoicesActivity, "Dodat glas: ${labels[index]}.", Toast.LENGTH_SHORT).show()
                    refreshStatusTexts()
                }
            }

            // Kad postoji samo jedan glas za izbor, nema potrebe za celim ekranom sa
            // pretragom i listom - direktno se dodaje (prvi glas je vec izabran drugde).
            if (candidates.size == 1) {
                addVoice(0)
            } else {
                PickerDialog.show(
                    this@CombinedVoicesActivity, "Dodaj glas", labels, null,
                    onSelectionPreview = { index -> TtsEngineUtil.previewVoice(this@CombinedVoicesActivity, candidates[index]) },
                    autoConfirm = true
                ) { index -> addVoice(index) }
            }
        }
    }

    private fun showRemoveVoiceDialog() {
        lifecycleScope.launch {
            val voices = db.combinedVoiceDao().getVoices(scopeId)
            if (voices.isEmpty()) {
                AlertDialog.Builder(this@CombinedVoicesActivity)
                    .setTitle("Ukloni glas")
                    .setMessage("Nema dodatih glasova.")
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }
            val labels = voices.map { entry ->
                val match = allVoices.firstOrNull { it.voice.name == entry.voiceName }
                match?.displayLabel ?: "${langLabel(entry.languageTag)} — ${entry.voiceName}"
            }

            fun confirmRemove(voice: CombinedVoiceEntryEntity, label: String) {
                AlertDialog.Builder(this@CombinedVoicesActivity)
                    .setTitle("Ukloni glas")
                    .setMessage("Ukloniti \"$label\"?")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        lifecycleScope.launch {
                            db.combinedVoiceDao().deleteVoice(voice.id)
                            refreshStatusTexts()
                        }
                    }
                    .show()
            }

            // Kad postoji samo jedan dodat glas, nema potrebe da se prvo bira sa liste -
            // odmah se pita samo za potvrdu brisanja.
            if (voices.size == 1) {
                confirmRemove(voices[0], labels[0])
                return@launch
            }
            AlertDialog.Builder(this@CombinedVoicesActivity)
                .setTitle("Ukloni glas")
                .setItems(labels.toTypedArray()) { _, which -> confirmRemove(voices[which], labels[which]) }
                .show()
        }
    }

    private fun showSentenceCountDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "Broj rečenica po glasu"
        input.contentDescription = "Broj rečenica koje svaki glas pročita pre nego što se smeni sledeći"
        AlertDialog.Builder(this)
            .setTitle("Broj rečenica po glasu")
            .setMessage("Svaki glas čita podjednak broj rečenica pre smene. Ako ostaviš prazno, svaki glas čita po jednu rečenicu.")
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val count = input.text.toString().trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
                lifecycleScope.launch {
                    db.combinedVoiceDao().setSettings(CombinedVoiceSettingsEntity(scopeId = scopeId, sentencesPerVoice = count))
                    refreshStatusTexts()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshStatusTexts() {
        lifecycleScope.launch {
            val languages = db.combinedVoiceDao().getLanguages(scopeId)
            val voices = db.combinedVoiceDao().getVoices(scopeId)
            val settingsEntity = db.combinedVoiceDao().getSettings(scopeId)
            val enginesInUse = (voices.map { it.voiceEngine } + listOfNotNull(defaultVoiceEngine)).distinct()
            val engineLabels = enginesInUse.map { pkg -> allVoices.firstOrNull { it.enginePackage == pkg }?.engineLabel ?: pkg }
            binding.textEngineStatus.text = if (engineLabels.isEmpty()) {
                "Motori u upotrebi: još nije određeno"
            } else {
                "Motori u upotrebi: " + engineLabels.joinToString(", ")
            }

            binding.textLanguagesStatus.text = if (languages.isEmpty()) {
                "Dodati jezici: nema"
            } else {
                "Dodati jezici: " + languages.joinToString(", ") { langLabel(it.languageTag) }
            }

            binding.textVoicesStatus.text = if (voices.isEmpty()) {
                "Dodati glasovi: nema"
            } else {
                val names = voices.map { entry ->
                    allVoices.firstOrNull { it.voice.name == entry.voiceName }?.displayLabel ?: entry.voiceName
                }
                val regularLabel = defaultVoiceName
                    ?.takeIf { name -> voices.none { it.voiceName == name } }
                    ?.let { name -> allVoices.firstOrNull { it.voice.name == name }?.displayLabel ?: name }
                if (regularLabel != null) {
                    "Dodati glasovi (${voices.size + 1}, uz tvoj obični glas): $regularLabel, " + names.joinToString(", ")
                } else {
                    "Dodati glasovi (${voices.size}): " + names.joinToString(", ")
                }
            }

            binding.textCountStatus.text = "Broj rečenica po glasu: ${settingsEntity?.sentencesPerVoice ?: 1}"
        }
    }

    companion object {
        const val EXTRA_SCOPE_ID = "extra_scope_id"
        const val EXTRA_DEFAULT_LANGUAGE_TAG = "extra_default_language_tag"
        const val EXTRA_DEFAULT_VOICE_NAME = "extra_default_voice_name"
        const val EXTRA_DEFAULT_VOICE_ENGINE = "extra_default_voice_engine"
    }
}
