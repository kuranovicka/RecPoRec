package com.recporec.app.ui

import android.app.AlertDialog
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.recporec.app.R
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.AppSettings
import com.recporec.app.data.DocumentEntity
import com.recporec.app.databinding.ActivityReaderBinding
import com.recporec.app.parser.DocumentParser
import com.recporec.app.parser.ParsedDocument
import com.recporec.app.service.ReadingService
import com.recporec.app.tts.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import java.util.Locale
import kotlin.math.roundToInt

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private val settings by lazy { AppSettings(this) }

    private var documentId: Long = -1
    private var doc: DocumentEntity? = null
    private var parsed: ParsedDocument? = null

    private val charsPerPage = 1800
    private val baseCharsPerMinute = 800f // procenjena brzina čitanja pri rate=1.0

    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null

    private var audioManager: AudioManager? = null
    private var allVoices: List<com.recporec.app.tts.VoiceOption> = emptyList()
    private var toneGenerator: android.media.ToneGenerator? = null
    private var seekBarTouchTracking = false

    // Da li je TTS spreman za govor (tekst ucitan, glas primenjen). Dok se motor prebacuje
    // (npr. zbog izabranog glasa), ovo je false, i komande se cuvaju da se izvrse cim bude spremno.
    private var ttsReady = false
    private var pendingPlayAfterReady = false

    // Detekcija dodira sa dva prsta (pauza/nastavak)
    private var twoFingerActive = false
    private var twoFingerStartTime = 0L
    private var twoFingerStartX0 = 0f
    private var twoFingerStartY0 = 0f
    private var twoFingerStartX1 = 0f
    private var twoFingerStartY1 = 0f

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* ako korisnik odbije, servis i dalje radi, samo bez vidljive notifikacije */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        documentId = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1)
        if (intent.getBooleanExtra(EXTRA_AUTOPLAY, false)) {
            pendingPlayAfterReady = true
        }
        PlaybackController.ensureInitialized(applicationContext)

        setupButtons()
        loadDocument()
        startTicker()
        lifecycleScope.launch {
            allVoices = try {
                com.recporec.app.tts.TtsEngineUtil.listAllVoices(this@ReaderActivity)
            } catch (e: Exception) {
                emptyList()
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    twoFingerActive = true
                    twoFingerStartTime = System.currentTimeMillis()
                    twoFingerStartX0 = ev.getX(0); twoFingerStartY0 = ev.getY(0)
                    twoFingerStartX1 = ev.getX(1); twoFingerStartY1 = ev.getY(1)
                } else if (ev.pointerCount > 2) {
                    twoFingerActive = false
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (twoFingerActive && ev.pointerCount >= 2) {
                    val moved0 = kotlin.math.hypot((ev.getX(0) - twoFingerStartX0), (ev.getY(0) - twoFingerStartY0))
                    val moved1 = kotlin.math.hypot((ev.getX(1) - twoFingerStartX1), (ev.getY(1) - twoFingerStartY1))
                    if (moved0 > 40f || moved1 > 40f) {
                        twoFingerActive = false // previše pomereno, nije tap - verovatno skrol
                    }
                }
            }
            android.view.MotionEvent.ACTION_POINTER_UP, android.view.MotionEvent.ACTION_UP -> {
                if (twoFingerActive) {
                    val elapsed = System.currentTimeMillis() - twoFingerStartTime
                    if (elapsed in 0..500) {
                        togglePlayPause()
                    }
                    twoFingerActive = false
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                twoFingerActive = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun playClickSound() {
        if (!settings.soundFeedbackEnabled) return
        try {
            if (toneGenerator == null) {
                toneGenerator = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            }
            toneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 60)
        } catch (_: Exception) { }
    }

    private fun setupButtons() = with(binding) {
        var lastClickAt = 0L
        val clickSound: (() -> Unit) -> (android.view.View) -> Unit = { action ->
            { _ ->
                // Bezbednosna mera za uređaje kod kojih TalkBack ponekad duplo registruje
                // dvostruki dodir (primećeno na nekim MIUI/Xiaomi telefonima) - ignoriše se
                // drugi dodir ako stigne u vrlo kratkom razmaku od prvog, za SVA dugmad.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastClickAt >= 400L) {
                    lastClickAt = now
                    playClickSound()
                    action()
                }
            }
        }

        btnBookmarks.setOnClickListener(clickSound { showBookmarksMenu() })
        btnGoTo.setOnClickListener(clickSound { showGoToMenu() })
        btnSearchText.setOnClickListener(clickSound { showSearchTextDialog() })

        btnPitchDown.setOnClickListener(clickSound { adjustPitch(-0.1f) })
        btnPrevChapter.setOnClickListener(clickSound { jumpChapter(-1) })
        btnNextChapter.setOnClickListener(clickSound { jumpChapter(1) })
        btnPitchUp.setOnClickListener(clickSound { adjustPitch(0.1f) })

        btnTimer.setOnClickListener(clickSound { showTimerMenu() })

        btnDocLanguage.setOnClickListener(clickSound { showDocLanguagePicker() })
        btnCombinedVoices.setOnClickListener(clickSound {
            startActivity(
                android.content.Intent(this@ReaderActivity, CombinedVoicesActivity::class.java)
                    .putExtra(CombinedVoicesActivity.EXTRA_SCOPE_ID, documentId)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_LANGUAGE_TAG, doc?.languageTag ?: settings.globalLanguageTag)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_VOICE_NAME, doc?.voiceName ?: settings.globalVoiceName)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_VOICE_ENGINE, doc?.voiceEngine ?: settings.globalVoiceEngine)
            )
        })
        btnVolDown.setOnClickListener(clickSound { adjustVolume(-1) })
        btnVolUp.setOnClickListener(clickSound { adjustVolume(1) })
        btnVoice.setOnClickListener(clickSound { showVoiceDialog() })

        btnSpeedDown.setOnClickListener(clickSound { adjustSpeed(-0.05f) })
        btnSpeedUp.setOnClickListener(clickSound { adjustSpeed(0.05f) })
        btnPlayPause.setOnClickListener(clickSound { togglePlayPause() })

        btnStepBack.setOnClickListener(clickSound { stepNavigate(forward = false) })
        btnStepForward.setOnClickListener(clickSound { stepNavigate(forward = true) })

        seekProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                // Ako promena dolazi od korisnika ali NE u sklopu obicnog prevlacenja prstom
                // (npr. čitač ekrana pomeri vrednost jednim prstom gore/dole), to ne prolazi
                // kroz onStartTrackingTouch/onStopTrackingTouch, pa je primenjujemo odmah ovde.
                if (fromUser && !seekBarTouchTracking) {
                    goToPercent(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekBarTouchTracking = true
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekBarTouchTracking = false
                seekBar ?: return
                goToPercent(seekBar.progress)
            }
        })
        // Klizač zadrži dodir isključivo za sebe tokom celog pokreta prsta (gore-dole
        // uključeno) - bez ovoga, deo tog pokreta može da "iscuri" i sistem ga
        // pogrešno protumači kao komandu za jačinu medija umesto pomeranja u knjizi.
        seekProgress.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun loadDocument() {
        lifecycleScope.launch {
            val entity = db.documentDao().getById(documentId) ?: return@launch
            binding.textDocTitle.text = entity.title

            val cachedParsed = PlaybackController.parsedDocument
            val parsedDoc = if (cachedParsed != null && PlaybackController.currentDocument?.id == entity.id) {
                cachedParsed
            } else {
                try {
                    withContext(Dispatchers.IO) {
                        DocumentParser.parse(this@ReaderActivity, android.net.Uri.parse(entity.uri), entity.format)
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this@ReaderActivity,
                        "Nije moguće pročitati ovaj dokument. Fajl je možda oštećen ili nepodržan.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@launch
                }
            }
            parsed = parsedDoc
            PlaybackController.parsedDocument = parsedDoc

            // Sve dopune (npr. broj stranica) se izracunaju PRE nego sto doc postane vidljiv/dostupan
            // ostatku ekrana - da ne postoji prozor u kom je doc "napola gotov" i neko dugme
            // (idi na, itd) radi sa nepotpunim podacima (npr. brojem stranica 0).
            val totalPages = max(1, (parsedDoc.length + charsPerPage - 1) / charsPerPage)
            val finalEntity = if (entity.totalPages != totalPages) {
                val updated = entity.copy(totalPages = totalPages)
                db.documentDao().update(updated)
                updated
            } else entity

            doc = finalEntity
            PlaybackController.currentDocument = finalEntity
            PlaybackController.elapsedSeconds = finalEntity.elapsedSeconds

            setupTts(
                parsedDoc,
                finalEntity,
                resolveCombinedVoiceConfig(
                    finalEntity.id,
                    finalEntity.voiceName ?: settings.globalVoiceName,
                    finalEntity.voiceEngine ?: settings.globalVoiceEngine
                )
            )
            updateStatusTexts()
            updateSeekBar()
            updateDocLanguageButtonText()
            updateNavigationButtonLabels()
            updateTimerStatusText()
        }
    }

    private data class CombinedVoiceConfig(val voices: List<com.recporec.app.tts.CombinedVoiceRef>, val sentencesPerVoice: Int)

    /** Kombinovani glasovi za dokument imaju prednost nad opštim; ako dokument nema
     * validnu kombinaciju, koriste se opšti (globalni) kombinovani glasovi, ako postoje.
     * Obican, vec izabran glas (regularVoiceName) automatski ulazi kao prvi u smeni ako
     * je bar jedan glas eksplicitno dodat - korisnica ne mora da ga posebno "doda", pošto
     * je već njen izbor. Glasovi mogu biti iz RAZLIČITIH TTS motora - TtsManager drži
     * odvojenu, unapred upaljenu vezu po motoru. */
    private suspend fun resolveCombinedVoiceConfig(
        docId: Long,
        regularVoiceName: String?,
        regularEngine: String?
    ): CombinedVoiceConfig? {
        val dao = db.combinedVoiceDao()

        suspend fun resolveForScope(scopeId: Long): CombinedVoiceConfig? {
            val explicit = dao.getVoices(scopeId)
            if (explicit.isEmpty()) return null

            val refs = mutableListOf<com.recporec.app.tts.CombinedVoiceRef>()
            if (regularVoiceName != null && regularEngine != null &&
                explicit.none { it.voiceName == regularVoiceName }
            ) {
                refs.add(com.recporec.app.tts.CombinedVoiceRef(regularEngine, regularVoiceName))
            }
            refs.addAll(explicit.map { com.recporec.app.tts.CombinedVoiceRef(it.voiceEngine, it.voiceName) })
            if (refs.size < 2) return null

            val count = dao.getSettings(scopeId)?.sentencesPerVoice ?: 1
            return CombinedVoiceConfig(refs, count)
        }

        return resolveForScope(docId) ?: resolveForScope(0L)
    }

    private fun markTtsReady() {
        ttsReady = true
        if (pendingPlayAfterReady) {
            pendingPlayAfterReady = false
            togglePlayPause()
        }
    }

    private fun setupTts(parsedDoc: ParsedDocument, entity: DocumentEntity, combined: CombinedVoiceConfig?) {
        val tts = PlaybackController.ttsManager ?: return
        ttsReady = false

        // Lanac: glas ovog dokumenta -> opšti (globalni) glas -> nezavisan podrazumevani glas.
        // Ne upisujemo rešenje trajno u dokument, da naknadna izmena opštih podešavanja
        // i dalje važi za dokumente koji nemaju sopstveni izbor.
        // Ako postoje kombinovani glasovi (za dokument ili opšte), oni imaju prednost.
        val effectiveVoiceName = combined?.voices?.first()?.voiceName ?: (entity.voiceName ?: settings.globalVoiceName)
        val effectiveEngine = combined?.voices?.first()?.enginePackage ?: (entity.voiceEngine ?: settings.globalVoiceEngine)

        fun applyCombinedVoicesIfAny() {
            if (combined != null) {
                tts.setCombinedVoices(combined.voices, combined.sentencesPerVoice)
            } else {
                tts.setCombinedVoices(emptyList(), 1)
            }
        }

        fun applyVoiceAndText() {
            // Deljenje na rečenice/pasuse je sad malo zahtevnije (prepoznavanje godina,
            // navodnika, pasusa) - radi se u pozadini da otvaranje dokumenta ne "zamrzne"
            // ekran na velikim knjigama.
            lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    tts.loadText(parsedDoc.fullText)
                }
                tts.setSpeechRate(entity.speechRate)
                tts.setPitch(entity.pitch)
                tts.sentencePauseMs = if (settings.sentencePauseEnabled) settings.sentencePauseMs.toLong() else 0L
                tts.paragraphPauseMs = if (settings.paragraphPauseEnabled) settings.paragraphPauseMs.toLong() else 0L
                if (effectiveVoiceName != null) {
                    tts.setVoiceByName(effectiveVoiceName)
                } else {
                    // Ni dokument ni opšta podešavanja nemaju izabran glas - biramo nezavisan
                    // podrazumevani glas umesto da TTS slučajno preuzme glas ekranskog čitača.
                    tts.applyIndependentDefaultVoice()
                }
                applyCombinedVoicesIfAny()
                markTtsReady()
            }
        }

        val needsEngineSwitch = effectiveEngine != null && effectiveEngine != tts.currentEnginePackage
        if (needsEngineSwitch) {
            tts.switchEngine(effectiveEngine, effectiveVoiceName, entity.speechRate) {
                lifecycleScope.launch {
                    withContext(Dispatchers.Default) {
                        tts.loadText(parsedDoc.fullText)
                    }
                    tts.setPitch(entity.pitch)
                    tts.sentencePauseMs = if (settings.sentencePauseEnabled) settings.sentencePauseMs.toLong() else 0L
                    tts.paragraphPauseMs = if (settings.paragraphPauseEnabled) settings.paragraphPauseMs.toLong() else 0L
                    if (effectiveVoiceName == null) tts.applyIndependentDefaultVoice()
                    applyCombinedVoicesIfAny()
                    markTtsReady()
                }
            }
        } else {
            tts.onReady = { applyVoiceAndText() }
            if (tts.isEngineReady) {
                // Motor je vec spreman (npr. nastavak iz iste sesije) - primeni odmah.
                applyVoiceAndText()
            }
            // Ako motor još nije spreman, čekamo legitiman onReady poziv iznad -
            // pokušaj "na silu" ovde bi tiho promašio postavljanje glasa (motor još
            // nema učitanu listu glasova), a lažno bi označio da je sve spremno.
        }
    }

    private fun togglePlayPause() {
        val tts = PlaybackController.ttsManager ?: return
        if (!ttsReady) {
            pendingPlayAfterReady = true
            android.widget.Toast.makeText(this, "Glas se priprema, kreće za trenutak.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (tts.isSpeaking) {
            tts.pause()
        } else {
            val startOffset = doc?.currentCharacterOffset ?: 0
            tts.startFromOffset(startOffset)
            if (settings.backgroundEnabled) {
                ReadingService.start(this, settings.uninterruptedEnabled)
            }
        }
    }

    private fun showGotoMinuteDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "Broj minuta od početka"
        input.contentDescription = "Broj minuta od početka dokumenta"
        AlertDialog.Builder(this)
            .setTitle("Unesi broj minuta od početka")
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val minute = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                val length = parsed?.length ?: return@setPositiveButton
                val offset = minutesToChars(minute).coerceIn(0, max(0, length - 1))
                moveTo(offset)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun goToPercent(percent: Int) {
        val length = parsed?.length ?: return
        val offset = (length * percent / 100).coerceIn(0, max(0, length - 1))
        moveTo(offset)
    }

    private fun stepNavigate(forward: Boolean) {
        val mode = settings.navigationMode
        if (mode == "bookmark") {
            jumpBookmark(forward)
            return
        }
        val length = parsed?.length ?: return
        val current = doc?.currentCharacterOffset ?: 0
        val delta: Int = when (mode) {
            "min1" -> minutesToChars(1)
            "min5" -> minutesToChars(5)
            "min10" -> minutesToChars(10)
            else -> charsPerPage // "page"
        }
        val signedDelta = if (forward) delta else -delta
        val newOffset = (current + signedDelta).coerceIn(0, max(0, length - 1))
        moveTo(newOffset)
    }

    /** Prelazi na prethodnu/sledeću oznaku (po poziciji u dokumentu, ne po redosledu dodavanja). */
    private fun jumpBookmark(forward: Boolean) {
        val currentDocId = documentId
        val current = doc?.currentCharacterOffset ?: 0
        lifecycleScope.launch {
            val bookmarks = db.bookmarkDao().getForDocument(currentDocId).sortedBy { it.characterOffset }
            if (bookmarks.isEmpty()) {
                android.widget.Toast.makeText(this@ReaderActivity, "Nema oznaka.", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val target = if (forward) {
                bookmarks.firstOrNull { it.characterOffset > current }
            } else {
                bookmarks.lastOrNull { it.characterOffset < current }
            }
            if (target == null) {
                val msg = if (forward) "Ovo je poslednja oznaka." else "Ovo je prva oznaka."
                android.widget.Toast.makeText(this@ReaderActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            moveTo(target.characterOffset)
        }
    }

    private fun minutesToChars(minutes: Int): Int {
        val rate = doc?.speechRate ?: 1.0f
        return (minutes * baseCharsPerMinute * rate.coerceAtLeast(0.3f)).toInt()
    }

    private fun updateNavigationButtonLabels() {
        val mode = settings.navigationMode
        when (mode) {
            "min1" -> {
                binding.btnStepBack.text = "◀ 1 min"
                binding.btnStepBack.contentDescription = "1 minut unazad"
                binding.btnStepForward.text = "1 min ▶"
                binding.btnStepForward.contentDescription = "1 minut unapred"
            }
            "min5" -> {
                binding.btnStepBack.text = "◀ 5 min"
                binding.btnStepBack.contentDescription = "5 minuta unazad"
                binding.btnStepForward.text = "5 min ▶"
                binding.btnStepForward.contentDescription = "5 minuta unapred"
            }
            "min10" -> {
                binding.btnStepBack.text = "◀ 10 min"
                binding.btnStepBack.contentDescription = "10 minuta unazad"
                binding.btnStepForward.text = "10 min ▶"
                binding.btnStepForward.contentDescription = "10 minuta unapred"
            }
            "bookmark" -> {
                binding.btnStepBack.text = "◀ Ozn."
                binding.btnStepBack.contentDescription = "Prethodna oznaka"
                binding.btnStepForward.text = "Ozn. ▶"
                binding.btnStepForward.contentDescription = "Sledeća oznaka"
            }
            else -> {
                binding.btnStepBack.text = "◀ Str."
                binding.btnStepBack.contentDescription = "Prethodna stranica"
                binding.btnStepForward.text = "Str. ▶"
                binding.btnStepForward.contentDescription = "Sledeća stranica"
            }
        }
    }

    private fun moveTo(offset: Int) {
        if (doc == null || parsed == null) {
            android.widget.Toast.makeText(this, "Dokument se još učitava, sačekaj trenutak.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        doc = doc?.copy(currentCharacterOffset = offset)
        updateStatusTexts()
        updateSeekBar()
        persistState()
        val tts = PlaybackController.ttsManager ?: return
        if (tts.isSpeaking) {
            tts.startFromOffset(offset)
        } else {
            tts.syncPositionOnly(offset)
        }
    }

    /** Pretraga teksta ti omogućava da pronađeš neki pojam u dokumentu. */
    private fun showSearchTextDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "Pojam za pretragu"
        input.contentDescription = "Pojam koji tražiš u dokumentu"
        AlertDialog.Builder(this)
            .setTitle("Pretraži tekst")
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) performTextSearch(query)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performTextSearch(query: String) {
        val fullText = parsed?.fullText ?: return
        lifecycleScope.launch {
            val results = withContext(Dispatchers.Default) {
                val matches = mutableListOf<Pair<Int, String>>()
                var idx = fullText.indexOf(query, 0, ignoreCase = true)
                while (idx >= 0 && matches.size < 200) {
                    val start = max(0, idx - 30)
                    val end = min(fullText.length, idx + query.length + 30)
                    val snippet = fullText.substring(start, end)
                        .replace("\n", " ")
                        .trim()
                    matches.add(idx to snippet)
                    idx = fullText.indexOf(query, idx + query.length, ignoreCase = true)
                }
                matches
            }
            if (results.isEmpty()) {
                AlertDialog.Builder(this@ReaderActivity)
                    .setTitle("Pretraži tekst")
                    .setMessage("Nema rezultata.")
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }
            val labels = results.mapIndexed { i, pair -> "${i + 1}. …${pair.second}…" }.toTypedArray()
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("Rezultati pretrage (${results.size})")
                .setItems(labels) { _, which ->
                    moveTo(results[which].first)
                }
                .show()
        }
    }

    /** Meni "Idi na": stranica, minut ili oznaka. */
    private fun showGoToMenu() {
        AlertDialog.Builder(this)
            .setTitle("Idi na")
            .setItems(arrayOf("Idi na stranicu", "Idi na minut", "Idi na oznaku")) { _, which ->
                when (which) {
                    0 -> showGotoPageDialog()
                    1 -> showGotoMinuteDialog()
                    2 -> showGoToBookmarkDialog()
                }
            }
            .show()
    }

    /** Meni "Oznake": dodaj, ukloni jednu ili ukloni sve. */
    private fun showBookmarksMenu() {
        AlertDialog.Builder(this)
            .setTitle("Oznake")
            .setItems(arrayOf("Dodaj oznaku", "Ukloni oznaku", "Ukloni sve oznake")) { _, which ->
                when (which) {
                    0 -> showAddBookmarkDialog()
                    1 -> showRemoveBookmarkDialog()
                    2 -> confirmRemoveAllBookmarks()
                }
            }
            .show()
    }

    private fun showAddBookmarkDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "Naziv oznake (nije obavezno)"
        input.contentDescription = "Naziv nove oznake, nije obavezno - ako ostane prazno, dobija broj"
        AlertDialog.Builder(this)
            .setTitle("Dodaj oznaku")
            .setMessage("Postavlja se oznaka na mesto na kome se trenutno nalaziš.")
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val currentDocId = documentId
                val offset = doc?.currentCharacterOffset ?: 0
                val typedName = input.text.toString().trim()
                lifecycleScope.launch {
                    val name = if (typedName.isNotEmpty()) {
                        typedName
                    } else {
                        val count = db.bookmarkDao().countForDocument(currentDocId)
                        (count + 1).toString()
                    }
                    db.bookmarkDao().insert(
                        com.recporec.app.data.BookmarkEntity(
                            documentId = currentDocId,
                            name = name,
                            characterOffset = offset
                        )
                    )
                    android.widget.Toast.makeText(
                        this@ReaderActivity, "Oznaka \"$name\" je dodata.", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRemoveBookmarkDialog() {
        val currentDocId = documentId
        lifecycleScope.launch {
            val bookmarks = db.bookmarkDao().getForDocument(currentDocId)
            if (bookmarks.isEmpty()) {
                AlertDialog.Builder(this@ReaderActivity)
                    .setTitle("Ukloni oznaku")
                    .setMessage("Nema oznaka.")
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }
            val names = bookmarks.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("Ukloni oznaku")
                .setItems(names) { _, which ->
                    val bookmark = bookmarks[which]
                    AlertDialog.Builder(this@ReaderActivity)
                        .setTitle("Ukloni oznaku")
                        .setMessage("Da li sigurno želiš da ukloniš oznaku \"${bookmark.name}\"?")
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(getString(R.string.delete)) { _, _ ->
                            lifecycleScope.launch {
                                db.bookmarkDao().deleteById(bookmark.id)
                                android.widget.Toast.makeText(
                                    this@ReaderActivity, "Oznaka \"${bookmark.name}\" je uklonjena.", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .show()
                }
                .show()
        }
    }

    private fun confirmRemoveAllBookmarks() {
        AlertDialog.Builder(this)
            .setTitle("Ukloni sve oznake")
            .setMessage("Da li sigurno želiš da obrišeš sve oznake u ovom dokumentu?")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                val currentDocId = documentId
                lifecycleScope.launch {
                    db.bookmarkDao().deleteAllForDocument(currentDocId)
                    android.widget.Toast.makeText(
                        this@ReaderActivity, "Sve oznake su uklonjene.", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun showGoToBookmarkDialog() {
        val currentDocId = documentId
        lifecycleScope.launch {
            val bookmarks = db.bookmarkDao().getForDocument(currentDocId)
            if (bookmarks.isEmpty()) {
                AlertDialog.Builder(this@ReaderActivity)
                    .setTitle("Idi na oznaku")
                    .setMessage("Nema oznaka.")
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }
            val names = bookmarks.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("Idi na oznaku")
                .setItems(names) { _, which ->
                    moveTo(bookmarks[which].characterOffset)
                }
                .show()
        }
    }

    private fun showGotoPageDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "Broj stranice"
        input.contentDescription = "Broj stranice na koju treba preći"
        AlertDialog.Builder(this)
            .setTitle(R.string.goto_page_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val page = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                val totalPages = doc?.totalPages ?: 1
                val safePage = page.coerceIn(1, max(1, totalPages))
                val offset = (safePage - 1) * charsPerPage
                moveTo(offset.coerceIn(0, max(0, (parsed?.length ?: 1) - 1)))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun jumpChapter(direction: Int) {
        val chapters = parsed?.chapters ?: emptyList()
        if (chapters.isEmpty()) return
        val current = doc?.currentCharacterOffset ?: 0
        val idx = chapters.indexOfLast { it.startOffset <= current }.coerceAtLeast(0)
        val targetIdx = (idx + direction).coerceIn(0, chapters.size - 1)
        moveTo(chapters[targetIdx].startOffset)
    }

    private fun adjustVolume(direction: Int) {
        val am = audioManager ?: return
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val step = max(1, (maxVol * 0.05f).roundToInt())
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val newVol = (current + direction * step).coerceIn(0, maxVol)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        val percent = if (maxVol > 0) (newVol * 100 / maxVol) else 0
        android.widget.Toast.makeText(this, "Jačina zvuka: $percent%", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun adjustSpeed(delta: Float) {
        val entity = doc ?: return
        val newRate = (entity.speechRate + delta).coerceIn(0.3f, 3.0f)
        doc = entity.copy(speechRate = newRate)
        PlaybackController.ttsManager?.setSpeechRate(newRate)
        persistState()
        val roundedRate = (newRate * 100).roundToInt() / 100f
        android.widget.Toast.makeText(
            this, "Brzina čitanja: ${String.format(Locale.US, "%.2f", roundedRate)}x", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun adjustPitch(delta: Float) {
        val entity = doc ?: return
        val newPitch = (entity.pitch + delta).coerceIn(0.5f, 2.0f)
        doc = entity.copy(pitch = newPitch)
        PlaybackController.ttsManager?.setPitch(newPitch)
        persistState()
        val roundedPitch = (newPitch * 100).roundToInt() / 100f
        android.widget.Toast.makeText(
            this, "Visina glasa: ${String.format(Locale.US, "%.2f", roundedPitch)}x", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun showDocLanguagePicker() {
        val voices = allVoices.ifEmpty {
            android.widget.Toast.makeText(this, "Učitavanje glasova, sačekaj trenutak", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val languages = com.recporec.app.tts.TtsEngineUtil.distinctLanguages(voices)
        val resetLabel = "Koristi opšti jezik (ukloni poseban izbor za ovaj dokument)"
        val labels = listOf(resetLabel) + languages.map { it.displayLanguage.replaceFirstChar { c -> c.uppercase() } }
        val current = if (doc?.languageTag == null) {
            resetLabel
        } else {
            doc?.languageTag?.let { code -> languages.firstOrNull { it.language == code }?.displayLanguage }
        }
        PickerDialog.show(this, "Jezik za ovaj dokument", labels, current, autoConfirm = true) { index ->
            if (index == 0) {
                doc = doc?.copy(languageTag = null)
                persistState()
                updateDocLanguageButtonText()
                return@show
            }
            val chosen = languages[index - 1]
            doc = doc?.copy(languageTag = chosen.language)
            persistState()
            updateDocLanguageButtonText()
        }
    }

    private fun updateDocLanguageButtonText() {
        val tag = doc?.languageTag
        binding.btnDocLanguage.text = if (tag != null) {
            "Jezik: ${java.util.Locale(tag).displayLanguage.replaceFirstChar { it.uppercase() }} ✓"
        } else {
            "Jezik"
        }
    }

    private fun showVoiceDialog() {
        val voices = allVoices.ifEmpty {
            android.widget.Toast.makeText(this, "Učitavanje glasova, sačekaj trenutak", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val languageFilter = doc?.languageTag ?: settings.globalLanguageTag
        val filtered = if (languageFilter != null) {
            voices.filter { it.voice.locale.language == languageFilter }.ifEmpty { voices }
        } else voices

        val resetLabel = "Koristi opšti glas (ukloni poseban izbor za ovaj dokument)"
        val labels = listOf(resetLabel) + com.recporec.app.tts.TtsEngineUtil.disambiguatedLabels(filtered)
        val effectiveVoiceName = doc?.voiceName ?: settings.globalVoiceName ?: PlaybackController.ttsManager?.currentVoiceName()
        val current = if (doc?.voiceName == null) {
            resetLabel
        } else {
            effectiveVoiceName?.let { name -> filtered.firstOrNull { it.voice.name == name }?.displayLabel }
        }
        PickerDialog.show(
            this, getString(R.string.voice_dialog_title), labels, current,
            onSelectionPreview = { index -> if (index > 0) com.recporec.app.tts.TtsEngineUtil.previewVoice(this, filtered[index - 1]) },
            autoConfirm = true
        ) { index ->
            if (index == 0) {
                // Ukloni poseban glas ovog dokumenta I sve njegove kombinovane glasove/jezike -
                // dokument se u potpunosti oslanja na opšta podešavanja, kombinovana ili ne.
                doc = doc?.copy(voiceName = null, voiceEngine = null)
                persistState()
                lifecycleScope.launch {
                    db.combinedVoiceDao().clearScope(documentId)
                    loadDocument()
                }
                return@show
            }
            val chosen = filtered[index - 1]
            val tts = PlaybackController.ttsManager
            if (tts != null && tts.currentEnginePackage != chosen.enginePackage) {
                ttsReady = false
                tts.switchEngine(chosen.enginePackage, chosen.voice.name, doc?.speechRate ?: 1.0f) {
                    parsed?.let { tts.loadText(it.fullText) }
                    markTtsReady()
                }
            } else {
                tts?.setVoiceByName(chosen.voice.name)
            }
            doc = doc?.copy(voiceName = chosen.voice.name, voiceEngine = chosen.enginePackage)
            persistState()
        }
    }

    private fun showTimerMenu() {
        val minuteOptions = intArrayOf(15, 30, 45, 60, 75, 90)
        val labels = minuteOptions.map { "$it minuta" } +
            listOf("Vrati se na poslednji tajmer", "Zaboravi tajmer", "Isključeno")
        AlertDialog.Builder(this)
            .setTitle("Tajmer")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < minuteOptions.size -> setTimer(minuteOptions[which])
                    which == minuteOptions.size -> showReturnToLastTimerDialog()
                    which == minuteOptions.size + 1 -> forgetLastTimer()
                    else -> setTimer(0)
                }
            }
            .show()
    }

    private fun setTimer(minutes: Int) {
        doc = if (minutes > 0) {
            // Novi tajmer - pamti gde je pocelo OVO odbrojavanje i na koliko minuta je
            // postavljeno, brise prethodno pamcenje.
            doc?.copy(timerMinutes = minutes, lastTimerStartOffset = doc?.currentCharacterOffset, lastTimerMinutes = minutes)
        } else {
            // Iskljuceno - zaustavlja odbrojavanje, ali NE brise pamcenje poslednjeg tajmera
            // (za slucaj da korisnica zaspi i posle zeli da se vrati na tu poziciju).
            doc?.copy(timerMinutes = 0)
        }
        persistState()
        PlaybackController.setTimerMinutes(minutes)

        if (minutes == 0) {
            android.widget.Toast.makeText(this, R.string.timer_off, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(
                this, getString(R.string.timer_set, minutes), android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        updateTimerStatusText()
    }

    /** Vraća na mesto gde je počeo poslednji tajmer - korisno kad korisnica zaspi uz knjigu
     * i ne zna tačno dokle je stigla dok je tajmer odbrojavao. */
    private fun showReturnToLastTimerDialog() {
        val startOffset = doc?.lastTimerStartOffset
        if (startOffset == null) {
            android.widget.Toast.makeText(this, "Nema prethodnog tajmera.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val lastMinutes = doc?.lastTimerMinutes
        val message = if (lastMinutes != null) {
            "Poslednji tajmer je odbrojavao $lastMinutes minuta."
        } else {
            "Postoji prethodni tajmer."
        }
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "Upiši minut na koji želiš da odeš"
        input.contentDescription = "Upiši minut na koji želiš da odeš"
        AlertDialog.Builder(this)
            .setTitle("Vrati se na poslednji tajmer")
            .setMessage(message)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val extraMinutes = input.text.toString().trim().toIntOrNull() ?: 0
                val target = startOffset + (if (extraMinutes > 0) minutesToChars(extraMinutes) else 0)
                moveTo(target)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Briše SVE pamćenje poslednjeg tajmera, bez potvrde - vraća se odmah u knjigu. */
    private fun forgetLastTimer() {
        doc = doc?.copy(lastTimerStartOffset = null, lastTimerMinutes = null)
        persistState()
        android.widget.Toast.makeText(this, "Tajmer zaboravljen.", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun updateTimerStatusText() {
        val remaining = PlaybackController.timerRemainingSeconds
        if (remaining <= 0) {
            binding.textTimerStatus.text = "Tajmer nije aktivan."
        } else {
            binding.textTimerStatus.text = "Tajmer: preostalo ${formatTime(remaining.toLong())}."
        }
    }

    private fun startTicker() {
        tickerRunnable = object : Runnable {
            override fun run() {
                val isSpeaking = PlaybackController.ttsManager?.isSpeaking == true
                if (isSpeaking) {
                    PlaybackController.currentDocument?.let { pcDoc ->
                        if (pcDoc.id == doc?.id) {
                            doc = doc?.copy(currentCharacterOffset = pcDoc.currentCharacterOffset)
                        }
                    }
                    updateStatusTexts()
                }
                if (PlaybackController.timerRemainingSeconds > 0) {
                    updateTimerStatusText()
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(tickerRunnable!!, 1000)
    }

    private fun updateStatusTexts() {
        val entity = doc ?: return
        val length = parsed?.length ?: 1
        val currentPage = min(entity.totalPages, (entity.currentCharacterOffset / charsPerPage) + 1)

        binding.textPages.text = getString(R.string.status_pages, entity.totalPages).let {
            "$it  ($currentPage/${entity.totalPages})"
        }

        val effectiveRate = max(0.3f, entity.speechRate)
        val consumedChars = entity.currentCharacterOffset.coerceIn(0, length)

        // Pauze između rečenica/pasusa dodaju stvarno vreme koje čisto računanje po broju
        // karaktera ne bi videlo - zato su dva dokumenta sličnog obima mogla da pokazuju
        // vrlo različito ukupno vreme (jedan ima mnogo više kratkih rečenica/pasusa od drugog).
        val tts = PlaybackController.ttsManager
        val sentenceMs = if (settings.sentencePauseEnabled) settings.sentencePauseMs.toLong() else 0L
        val paragraphMs = if (settings.paragraphPauseEnabled) settings.paragraphPauseMs.toLong() else 0L
        val currentChunkIdx = tts?.chunkIndexForOffset(entity.currentCharacterOffset) ?: 0
        val elapsedPauseMs = tts?.estimatedPauseMillis(0, currentChunkIdx, sentenceMs, paragraphMs) ?: 0L
        val remainingPauseMs = tts?.estimatedPauseMillis(currentChunkIdx, Int.MAX_VALUE, sentenceMs, paragraphMs) ?: 0L

        val elapsedEstimateSeconds = (consumedChars / (baseCharsPerMinute * effectiveRate) * 60).toLong() + elapsedPauseMs / 1000
        binding.textElapsed.text = getString(R.string.status_elapsed, formatTime(elapsedEstimateSeconds))

        val remainingChars = max(0, length - entity.currentCharacterOffset)
        val remainingSeconds = (remainingChars / (baseCharsPerMinute * effectiveRate) * 60).toLong() + remainingPauseMs / 1000
        binding.textRemaining.text = getString(R.string.status_remaining, formatTime(remainingSeconds))
    }

    private fun updateSeekBar() {
        val length = parsed?.length ?: return
        val current = doc?.currentCharacterOffset ?: 0
        val percent = if (length == 0) 0 else (current * 100 / length)
        binding.seekProgress.progress = percent.coerceIn(0, 100)
    }

    private fun formatTime(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "%d sati %d min".format(h, m)
            m > 0 -> "%d min %d sek".format(m, s)
            else -> "%d sek".format(s)
        }
    }

    private fun persistState() {
        val entity = doc ?: return
        PlaybackController.persistDocumentNow(entity)
    }

    override fun onResume() {
        super.onResume()

        // Uskladi lokalni prikaz sa stvarnim stanjem (moglo je da napreduje dok je citac
        // radio u pozadini, van ove Activity-je).
        val playbackDoc = PlaybackController.currentDocument
        if (playbackDoc != null && playbackDoc.id == documentId) {
            doc = playbackDoc
            updateStatusTexts()
            updateSeekBar()
        }
        updateTimerStatusText()
        updateNavigationButtonLabels()

        // Osveži kombinovane glasove - ako su dodati/uklonjeni dok si bila na ekranu
        // "Kombinovani glasovi" i vratila se ovde, bez ovoga bi citanje nastavilo da
        // koristi staro stanje sve dok se dokument ponovo ne otvori od pocetka. Namerno
        // BEZ uslova "ttsReady" - taj uslov je mogao da preskoci osvezavanje bas u
        // trenutku kad je najpotrebnije (odmah posle vracanja sa tog ekrana).
        lifecycleScope.launch {
            val tts = PlaybackController.ttsManager ?: return@launch
            val combined = resolveCombinedVoiceConfig(
                documentId,
                doc?.voiceName ?: settings.globalVoiceName,
                doc?.voiceEngine ?: settings.globalVoiceEngine
            )
            if (combined != null) {
                tts.setCombinedVoices(combined.voices, combined.sentencesPerVoice)
            } else {
                tts.setCombinedVoices(emptyList(), 1)
            }
        }

        PlaybackController.uiPositionListener = { offset ->
            runOnUiThread {
                doc = doc?.copy(currentCharacterOffset = offset)
                updateStatusTexts()
                updateSeekBar()
            }
        }
        PlaybackController.uiFinishedListener = {
            runOnUiThread {
                binding.btnPlayPause.text = "▶ / ⏸"
                android.widget.Toast.makeText(this, "Čitanje završeno.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        PlaybackController.uiTimerExpiredListener = {
            runOnUiThread {
                doc = doc?.copy(timerMinutes = 0)
                persistState()
                updateTimerStatusText()
            }
        }

        // Napomena: drmanje za pauzu/nastavak se sada osluškuje u ReadingService, ne ovde -
        // tako radi i dok je čitanje u pozadini, van ovog ekrana (vidi ReadingService.kt).
    }

    override fun onPause() {
        super.onPause()
        PlaybackController.uiPositionListener = null
        PlaybackController.uiFinishedListener = null
        PlaybackController.uiTimerExpiredListener = null
        persistState()
        if (!settings.backgroundEnabled) {
            PlaybackController.ttsManager?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tickerRunnable?.let { handler.removeCallbacks(it) }
        toneGenerator?.release()
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "extra_document_id"
        const val EXTRA_AUTOPLAY = "extra_autoplay"
    }
}
