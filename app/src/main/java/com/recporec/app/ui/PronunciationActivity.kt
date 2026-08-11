package com.recporec.app.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.PronunciationEntity
import com.recporec.app.databinding.ActivityPronunciationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ekran "Rečnik izgovora" - korisnikova SOPSTVENA lista zamena (odvojeno od ugrađenog
 * rečnika koji je resurs u aplikaciji i ne dira se odavde). Dodir ili dug pritisak na
 * unos otvara Izmeni/Obriši - isti obrazac kao svuda drugde u aplikaciji. */
class PronunciationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPronunciationBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private lateinit var adapter: PronunciationAdapter

    // Isti obrazac kao rezervna kopija/izvoz podesavanja u DocumentListActivity - obican
    // SAF (Storage Access Framework) birac fajlova, bez ijedne nove zavisnosti.
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

        adapter = PronunciationAdapter(onLongPress = { showEntryActionsDialog(it) })
        binding.recyclerPronunciation.layoutManager = LinearLayoutManager(this)
        binding.recyclerPronunciation.adapter = adapter

        binding.btnAddEntry.setOnClickListener { showAddOrEditDialog(existing = null) }
        binding.btnImportDictionary.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        binding.btnExportDictionary.setOnClickListener { exportLauncher.launch("recnik-izgovora.txt") }
        binding.btnBack.setOnClickListener { finish() }

        refreshList()
    }

    private fun refreshList() {
        lifecycleScope.launch {
            val entries = db.pronunciationDao().getAll()
            adapter.submitList(entries)
        }
    }

    /** Dva polja jedno ispod drugog (originalna reč / zamena), isti "skraćeni dijalog"
     * princip kao svuda - samo polja i Otkaži, tastatura potvrđuje. */
    private fun showAddOrEditDialog(existing: PronunciationEntity?) {
        val inputWord = EditText(this).apply {
            setText(existing?.originalWord.orEmpty())
            hint = "Originalna reč (npr. John)"
        }
        val inputReplacement = EditText(this).apply {
            setText(existing?.replacement.orEmpty())
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
                val toRemove = all.filter { it.originalWord.equals(word, ignoreCase = true) || it.id == existing?.id }
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

    private fun showEntryActionsDialog(entry: PronunciationEntity) {
        val options = arrayOf("Pusti izgovor", "Izmeni", "Obriši")
        AlertDialog.Builder(this)
            .setTitle("${entry.originalWord} → ${entry.replacement}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> previewPronunciation(entry.replacement)
                    1 -> showAddOrEditDialog(existing = entry)
                    2 -> lifecycleScope.launch {
                        db.pronunciationDao().deleteById(entry.id)
                        refreshList()
                    }
                }
            }
            .setNegativeButton(com.recporec.app.R.string.cancel, null)
            .show()
    }

    /** Izgovara samu zamenu (ne originalnu reč) - korisnica ovako čuje TAČNO ono što će
     * čuti i tokom čitanja knjige, bez čekanja da naiđe na tu reč u tekstu. Isti obrazac
     * kao TtsEngineUtil.previewVoice - kratkotrajan TTS koji se sam gasi kad završi. */
    private fun previewPronunciation(text: String) {
        var tts: android.speech.tts.TextToSpeech? = null
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { tts?.shutdown() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { tts?.shutdown() }
                })
                tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "pronunciation_preview")
            } else {
                tts?.shutdown()
            }
        }
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
                    // Ukloni BOM ako postoji (Windows Notepad ga cesto dodaje).
                    val cleanText = text.removePrefix("\uFEFF")
                    val seen = LinkedHashMap<String, Pair<String, String>>()
                    var skipped = 0
                    for (rawLine in cleanText.lines()) {
                        var line = rawLine.trim()
                        if (line.isEmpty()) continue
                        // Skini slucajne navodnike i zvezdice sa ivica reda - ostaci iz
                        // razlicitih izvora odakle je fajl mogao doci.
                        line = line.trim('"', '*', ' ')
                        if ('=' !in line) { skipped++; continue }
                        val idx = line.indexOf('=')
                        var word = line.substring(0, idx).trim().trim('"', '*', ' ')
                        var replacement = line.substring(idx + 1).trim().trim('"', '*', ' ')
                        if (word.isEmpty() || replacement.isEmpty()) { skipped++; continue }
                        // Poslednji unos u fajlu pobedjuje kod sudara - dogovoreno pravilo,
                        // bez upozorenja (isto kao pri rucnom dodavanju).
                        seen[word.lowercase()] = word to replacement
                    }
                    if (seen.isEmpty()) return@withContext 0
                    val db = AppDatabase.getInstance(this@PronunciationActivity).pronunciationDao()
                    val existing = db.getAll()
                    val existingByLower = existing.associateBy { it.originalWord.lowercase() }
                    for ((lower, pair) in seen) {
                        val (word, replacement) = pair
                        existingByLower[lower]?.let { db.deleteById(it.id) }
                        db.insert(PronunciationEntity(originalWord = word, replacement = replacement))
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

    /** Izvoz u ISTI, cist format ("rec=zamena", bez navodnika/zvezdica) - cak i ako je
     * uvezen "prljav" fajl, ono sto se izveze je uvek uredno. */
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
