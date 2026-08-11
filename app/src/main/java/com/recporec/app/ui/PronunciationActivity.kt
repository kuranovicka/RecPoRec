package com.recporec.app.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.AppSettings
import com.recporec.app.data.PronunciationEntity
import com.recporec.app.databinding.ActivityPronunciationBinding
import com.recporec.app.tts.PronunciationDictionary
import com.recporec.app.util.requestAccessibilityFocusNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ekran "Rečnik izgovora" - spojen prikaz UGRAĐENOG rečnika (iz resursa, samo za čitanje i
 * probni izgovor) i KORISNIKOVOG sopstvenog rečnika (baza, potpuno menjivo). Dodir na
 * ugrađen unos nudi "Dodaj svoju zamenu" (pravi novi red u bazi koji ga prepisuje); dodir
 * na sopstveni unos nudi puno Izmeni/Obriši, uz Pusti izgovor za oba tipa. */
class PronunciationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPronunciationBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private lateinit var adapter: PronunciationAdapter
    private var fullList: List<PronunciationListItem> = emptyList()
    private var previewTts: TextToSpeech? = null

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        importFromFile(uri)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        exportToFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPronunciationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = PronunciationAdapter(onTap = { showEntryActionsDialog(it) })
        binding.recyclerPronunciation.layoutManager = LinearLayoutManager(this)
        binding.recyclerPronunciation.adapter = adapter

        binding.btnAddEntry.setOnClickListener { showAddOrEditDialog(prefillWord = null, prefillReplacement = null, editEntityId = null) }
        binding.btnImportDictionary.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        binding.btnExportDictionary.setOnClickListener { exportLauncher.launch("recnik-izgovora.txt") }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnWordList.setOnClickListener {
            Toast.makeText(this, "${adapter.currentList.size} unosa u rečniku.", Toast.LENGTH_SHORT).show()
            binding.recyclerPronunciation.requestAccessibilityFocusNow()
        }
        binding.inputSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applyFilter(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        refreshList()
    }

    override fun onDestroy() {
        previewTts?.shutdown()
        super.onDestroy()
    }

    /** Spaja ugrađeni rečnik (iz resursa) i korisnikov (iz baze) - korisnikov unos
     * PREKRIVA ugrađeni ako je ista reč (prikazuje se kao "tvoj unos", potpuno menjiv). */
    private fun refreshList() {
        lifecycleScope.launch {
            val merged = withContext(Dispatchers.Default) {
                val builtIn = PronunciationDictionary.loadBuiltInRaw(this@PronunciationActivity)
                val userEntries = db.pronunciationDao().getAll()
                val userByLower = userEntries.associateBy { it.originalWord.lowercase() }
                val result = mutableListOf<PronunciationListItem>()
                for ((word, replacement) in builtIn) {
                    val override = userByLower[word.lowercase()]
                    if (override == null) {
                        result.add(PronunciationListItem(word, replacement, isBuiltIn = true, entityId = null))
                    }
                }
                for (entry in userEntries) {
                    result.add(PronunciationListItem(entry.originalWord, entry.replacement, isBuiltIn = false, entityId = entry.id))
                }
                result.sortBy { it.originalWord.lowercase() }
                result
            }
            fullList = merged
            applyFilter(binding.inputSearch.text?.toString().orEmpty())
        }
    }

    private fun applyFilter(query: String) {
        val trimmed = query.trim().lowercase()
        val filtered = if (trimmed.isEmpty()) fullList else fullList.filter { it.originalWord.lowercase().contains(trimmed) }
        adapter.submitList(filtered)
    }

    /** Dva polja jedno ispod drugog (originalna reč / zamena), isti "skraćeni dijalog"
     * princip kao svuda - samo polja i Otkaži, tastatura potvrđuje. editEntityId != null
     * znači da se menja POSTOJEĆI red u bazi (ne ugrađen unos - taj se samo "prepisuje"
     * novim redom, originalni resurs se nikad ne dira). */
    private fun showAddOrEditDialog(prefillWord: String?, prefillReplacement: String?, editEntityId: Long?) {
        val inputWord = EditText(this).apply {
            setText(prefillWord.orEmpty())
            hint = "Originalna reč (npr. John)"
        }
        val inputReplacement = EditText(this).apply {
            setText(prefillReplacement.orEmpty())
            hint = "Kako da se izgovori (npr. Džon)"
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(inputWord)
            addView(inputReplacement)
        }

        fun confirm() {
            val word = inputWord.text?.toString()?.trim().orEmpty()
            val replacement = inputReplacement.text?.toString()?.trim().orEmpty()
            if (word.isEmpty() || replacement.isEmpty()) {
                Toast.makeText(this, "Oba polja moraju biti popunjena.", Toast.LENGTH_SHORT).show()
                return
            }
            lifecycleScope.launch {
                // Ista reč (bez razlike velikih/malih slova) se zamenjuje, ne dodaje duplo -
                // dogovoreno pravilo: poslednji unos pobeđuje, bez upozorenja. Poređenje se
                // radi ovde (ne u SQL upitu) da se izbegne COLLATE u @Query, koji zna da
                // zbuni Room-ov prevodilac upita u vreme kompajliranja.
                val all = db.pronunciationDao().getAll()
                val toRemove = all.filter { it.originalWord.equals(word, ignoreCase = true) || it.id == editEntityId }
                toRemove.forEach { db.pronunciationDao().deleteById(it.id) }
                db.pronunciationDao().insert(PronunciationEntity(originalWord = word, replacement = replacement))
                refreshList()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setNegativeButton(com.recporec.app.R.string.cancel, null)
            .create()
        inputReplacement.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                confirm()
                dialog.dismiss()
                true
            } else false
        }
        dialog.show()
    }

    private fun showEntryActionsDialog(entry: PronunciationListItem) {
        val options = if (entry.isBuiltIn) {
            arrayOf("Pusti izgovor", "Dodaj svoju zamenu")
        } else {
            arrayOf("Pusti izgovor", "Izmeni", "Obriši")
        }
        val titleSuffix = if (entry.isBuiltIn) " (ugrađeno)" else ""
        AlertDialog.Builder(this)
            .setTitle("${entry.originalWord} → ${entry.replacement}$titleSuffix")
            .setItems(options) { _, which ->
                when {
                    which == 0 -> previewPronunciation(entry.replacement)
                    entry.isBuiltIn && which == 1 -> showAddOrEditDialog(entry.originalWord, entry.replacement, editEntityId = null)
                    !entry.isBuiltIn && which == 1 -> showAddOrEditDialog(entry.originalWord, entry.replacement, editEntityId = entry.entityId)
                    !entry.isBuiltIn && which == 2 -> lifecycleScope.launch {
                        entry.entityId?.let { db.pronunciationDao().deleteById(it) }
                        refreshList()
                    }
                }
            }
            .setNegativeButton(com.recporec.app.R.string.cancel, null)
            .show()
    }

    /** Izgovara zamenu KORIŠĆENJEM ISTOG GLASA/MOTORA/JEZIKA koji je podešen u programu za
     * čitanje (Opšta podešavanja glasa) - ne generičkog sistemskog glasa, jer bi to bio
     * pogrešan utisak o tome kako će zaista zvučati tokom čitanja knjige. */
    private fun previewPronunciation(text: String) {
        previewTts?.shutdown()
        val settings = AppSettings(this)
        val voiceName = settings.globalVoiceName
        val enginePackage = settings.globalVoiceEngine
        previewTts = TextToSpeech(this, { status ->
            val tts = previewTts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                if (voiceName != null) {
                    tts.voices?.firstOrNull { it.name == voiceName }?.let { tts.voice = it }
                }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pronunciation_preview")
            }
        }, enginePackage)
    }

    /** Uvoz iz obicnog tekst fajla: jedan par po redu, "originalna_rec=zamena". Otporan na
     * "prljave" fajlove (visak navodnika, zvezdica, praznih redova) - iz istih razloga
     * kao svaki drugi uvoz u ovoj aplikaciji (npr. import dokumenata) ne sme da padne na
     * necem sto korisnica nije ni napravila rucno, vec je preneto/konvertovano odnekud. */
    private fun importFromFile(uri: Uri) {
        lifecycleScope.launch {
            Toast.makeText(this@PronunciationActivity, "Uvoz rečnika...", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) {
                try {
                    val text = contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: return@withContext -1
                    val cleanText = text.removePrefix("\uFEFF")
                    val seen = LinkedHashMap<String, Pair<String, String>>()
                    for (rawLine in cleanText.lines()) {
                        var line = rawLine.trim()
                        if (line.isEmpty()) continue
                        line = line.trim('"', '*', ' ')
                        if ('=' !in line) continue
                        val idx = line.indexOf('=')
                        val word = line.substring(0, idx).trim().trim('"', '*', ' ')
                        val replacement = line.substring(idx + 1).trim().trim('"', '*', ' ')
                        if (word.isEmpty() || replacement.isEmpty()) continue
                        seen[word.lowercase()] = word to replacement
                    }
                    if (seen.isEmpty()) return@withContext 0
                    val dao = AppDatabase.getInstance(this@PronunciationActivity).pronunciationDao()
                    val existing = dao.getAll()
                    val existingByLower = existing.associateBy { it.originalWord.lowercase() }
                    for ((lower, pair) in seen) {
                        val (word, replacement) = pair
                        existingByLower[lower]?.let { dao.deleteById(it.id) }
                        dao.insert(PronunciationEntity(originalWord = word, replacement = replacement))
                    }
                    seen.size
                } catch (_: Exception) {
                    -1
                }
            }
            refreshList()
            val msg = when {
                result < 0 -> "Uvoz nije uspeo - proveri da li je fajl ispravan."
                result == 0 -> "Fajl ne sadrži nijedan ispravan unos."
                else -> "Uvezeno unosa: $result."
            }
            Toast.makeText(this@PronunciationActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Izvoz u ISTI, cist format ("rec=zamena", bez navodnika/zvezdica) - izveze SAMO
     * korisnikove sopstvene unose (ne ugrađeni rečnik - taj je vec u samoj aplikaciji). */
    private fun exportToFile(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val entries = AppDatabase.getInstance(this@PronunciationActivity).pronunciationDao().getAll()
                    val text = entries.joinToString("\n") { "${it.originalWord}=${it.replacement}" }
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            Toast.makeText(
                this@PronunciationActivity,
                if (ok) "Rečnik izvezen." else "Izvoz nije uspeo.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
