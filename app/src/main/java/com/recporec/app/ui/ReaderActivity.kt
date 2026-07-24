package com.recporec.app.ui

import android.app.AlertDialog
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.PopupMenu
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
import com.recporec.app.util.ShakeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
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

    private var timerMinutesCycle = intArrayOf(0, 15, 30, 45, 60, 90)
    private var timerIndex = 0
    private var timerRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null

    private var audioManager: AudioManager? = null
    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null
    private var allVoices: List<com.recporec.app.tts.VoiceOption> = emptyList()

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
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        documentId = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1)
        PlaybackController.ensureInitialized(applicationContext)

        setupButtons()
        loadDocument()
        startTicker()
        lifecycleScope.launch {
            allVoices = com.recporec.app.tts.TtsEngineUtil.listAllVoices(this@ReaderActivity)
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

    private fun setupButtons() = with(binding) {
        btnOverflow.setOnClickListener { showOverflowMenu(it) }

        btnPrevChapter.setOnClickListener { jumpChapter(-1) }
        btnNextChapter.setOnClickListener { jumpChapter(1) }

        btnTimer.setOnClickListener { cycleTimer() }

        btnDocLanguage.setOnClickListener { showDocLanguagePicker() }
        btnVolDown.setOnClickListener { adjustVolume(-1) }
        btnVolUp.setOnClickListener { adjustVolume(1) }
        btnVoice.setOnClickListener { showVoiceDialog() }

        btnSpeedDown.setOnClickListener { adjustSpeed(-0.05f) }
        btnSpeedUp.setOnClickListener { adjustSpeed(0.05f) }
        btnPlayPause.setOnClickListener { togglePlayPause() }

        btnStepBack.setOnClickListener { stepNavigate(forward = false) }
        btnStepForward.setOnClickListener { stepNavigate(forward = true) }
        btnGotoPage.setOnClickListener { showGotoPageDialog() }

        seekProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekBar ?: return
                goToPercent(seekBar.progress)
            }
        })
    }

    private fun loadDocument() {
        lifecycleScope.launch {
            val entity = db.documentDao().getById(documentId) ?: return@launch
            doc = entity
            binding.textDocTitle.text = entity.title

            PlaybackController.currentDocument = entity
            PlaybackController.elapsedSeconds = entity.elapsedSeconds

            val cachedParsed = PlaybackController.parsedDocument
            val parsedDoc = if (cachedParsed != null && PlaybackController.currentDocument?.id == entity.id) {
                cachedParsed
            } else {
                withContext(Dispatchers.IO) {
                    DocumentParser.parse(this@ReaderActivity, android.net.Uri.parse(entity.uri), entity.format)
                }
            }
            parsed = parsedDoc
            PlaybackController.parsedDocument = parsedDoc

            val totalPages = max(1, (parsedDoc.length + charsPerPage - 1) / charsPerPage)
            if (entity.totalPages != totalPages) {
                doc = entity.copy(totalPages = totalPages)
                db.documentDao().update(doc!!)
            }

            setupTts(parsedDoc, entity)
            updateStatusTexts()
            updateSeekBar()
            updateDocLanguageButtonText()
            updateNavigationButtonLabels()
        }
    }

    private fun setupTts(parsedDoc: ParsedDocument, entity: DocumentEntity) {
        val tts = PlaybackController.ttsManager ?: return

        fun applyVoiceAndText() {
            tts.loadText(parsedDoc.fullText)
            tts.setSpeechRate(entity.speechRate)
            if (entity.voiceName != null) {
                tts.setVoiceByName(entity.voiceName)
            } else {
                // Nijedan glas nije izabran - biramo nezavisan podrazumevani glas
                // umesto da TTS slučajno preuzme isti glas kojim čita ekranski čitač.
                val chosen = tts.applyIndependentDefaultVoice()
                if (chosen != null) {
                    doc = doc?.copy(voiceName = chosen.name, voiceEngine = tts.currentEnginePackage)
                    persistState()
                    runOnUiThread { updateStatusTexts() }
                }
            }
        }

        val needsEngineSwitch = entity.voiceEngine != null && entity.voiceEngine != tts.currentEnginePackage
        if (needsEngineSwitch) {
            tts.switchEngine(entity.voiceEngine, entity.voiceName, entity.speechRate) {
                tts.loadText(parsedDoc.fullText)
            }
        } else {
            tts.onReady = { applyVoiceAndText() }
            applyVoiceAndText()
        }

        tts.onPositionChanged = { offset ->
            runOnUiThread {
                doc = doc?.copy(currentCharacterOffset = offset)
                updateStatusTexts()
                updateSeekBar()
                persistState()
            }
        }
        tts.onFinished = {
            runOnUiThread { binding.btnPlayPause.text = "▶ / ⏸" }
        }
    }

    private fun togglePlayPause() {
        val tts = PlaybackController.ttsManager ?: return
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

    private fun goToPercent(percent: Int) {
        val length = parsed?.length ?: return
        val offset = (length * percent / 100).coerceIn(0, max(0, length - 1))
        moveTo(offset)
    }

    private fun stepNavigate(forward: Boolean) {
        val length = parsed?.length ?: return
        val current = doc?.currentCharacterOffset ?: 0
        val mode = settings.navigationMode
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

    private fun minutesToChars(minutes: Int): Int {
        val rate = doc?.speechRate ?: 1.0f
        return (minutes * baseCharsPerMinute * rate.coerceAtLeast(0.3f)).toInt()
    }

    private fun updateNavigationButtonLabels() {
        val mode = settings.navigationMode
        val (label, description) = when (mode) {
            "min1" -> "1 min" to "1 minut"
            "min5" -> "5 min" to "5 minuta"
            "min10" -> "10 min" to "10 minuta"
            else -> "Str." to "Stranica"
        }
        binding.btnStepBack.text = "◀ $label"
        binding.btnStepBack.contentDescription = "$description unazad"
        binding.btnStepForward.text = "$label ▶"
        binding.btnStepForward.contentDescription = "$description unapred"
    }

    private fun moveTo(offset: Int) {
        doc = doc?.copy(currentCharacterOffset = offset)
        updateStatusTexts()
        updateSeekBar()
        persistState()
        val tts = PlaybackController.ttsManager ?: return
        val wasSpeaking = tts.isSpeaking
        tts.pause()
        if (wasSpeaking) {
            tts.startFromOffset(offset)
        }
    }

    private fun showGotoPageDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
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
    }

    private fun adjustSpeed(delta: Float) {
        val entity = doc ?: return
        val newRate = (entity.speechRate + delta).coerceIn(0.3f, 3.0f)
        doc = entity.copy(speechRate = newRate)
        PlaybackController.ttsManager?.setSpeechRate(newRate)
        persistState()
    }

    private fun showDocLanguagePicker() {
        val voices = allVoices.ifEmpty {
            android.widget.Toast.makeText(this, "Učitavanje glasova, sačekaj trenutak", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val languages = com.recporec.app.tts.TtsEngineUtil.distinctLanguages(voices)
        val labels = languages.map { it.displayLanguage.replaceFirstChar { c -> c.uppercase() } }
        val current = doc?.languageTag?.let { code ->
            languages.firstOrNull { it.language == code }?.displayLanguage
        }
        PickerDialog.show(this, "Jezik za ovaj dokument", labels, current) { index ->
            val chosen = languages[index]
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

        val labels = com.recporec.app.tts.TtsEngineUtil.disambiguatedLabels(filtered)
        val current = doc?.voiceName?.let { name ->
            filtered.firstOrNull { it.voice.name == name }?.displayLabel
        }
        PickerDialog.show(this, getString(R.string.voice_dialog_title), labels, current) { index ->
            val chosen = filtered[index]
            val tts = PlaybackController.ttsManager
            if (tts != null && tts.currentEnginePackage != chosen.enginePackage) {
                tts.switchEngine(chosen.enginePackage, chosen.voice.name, doc?.speechRate ?: 1.0f) {
                    parsed?.let { tts.loadText(it.fullText) }
                }
            } else {
                tts?.setVoiceByName(chosen.voice.name)
            }
            doc = doc?.copy(voiceName = chosen.voice.name, voiceEngine = chosen.enginePackage)
            persistState()
        }
    }

    private fun cycleTimer() {
        timerIndex = (timerIndex + 1) % timerMinutesCycle.size
        val minutes = timerMinutesCycle[timerIndex]
        timerRunnable?.let { handler.removeCallbacks(it) }
        doc = doc?.copy(timerMinutes = minutes)
        persistState()

        if (minutes == 0) {
            android.widget.Toast.makeText(this, R.string.timer_off, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.widget.Toast.makeText(
            this, getString(R.string.timer_set, minutes), android.widget.Toast.LENGTH_SHORT
        ).show()
        val runnable = Runnable { PlaybackController.ttsManager?.pause() }
        timerRunnable = runnable
        handler.postDelayed(runnable, minutes * 60_000L)
    }

    private fun startTicker() {
        tickerRunnable = object : Runnable {
            override fun run() {
                if (PlaybackController.ttsManager?.isSpeaking == true) {
                    PlaybackController.elapsedSeconds += 1
                    doc = doc?.copy(elapsedSeconds = PlaybackController.elapsedSeconds)
                    updateStatusTexts()
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
        binding.textElapsed.text = getString(R.string.status_elapsed, formatTime(PlaybackController.elapsedSeconds))

        val remainingChars = max(0, length - entity.currentCharacterOffset)
        val effectiveRate = max(0.3f, entity.speechRate)
        val remainingSeconds = (remainingChars / (baseCharsPerMinute * effectiveRate) * 60).toLong()
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
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun persistState() {
        val entity = doc ?: return
        lifecycleScope.launch { db.documentDao().update(entity) }
    }

    private fun showOverflowMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_options, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(android.content.Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_help -> {
                    startActivity(android.content.Intent(this, HelpActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onResume() {
        super.onResume()
        if (settings.shakeEnabled) {
            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                shakeDetector = ShakeDetector { togglePlayPause() }
                sensorManager?.registerListener(shakeDetector, accel, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        shakeDetector?.let { sensorManager?.unregisterListener(it) }
        persistState()
        if (!settings.backgroundEnabled) {
            PlaybackController.ttsManager?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerRunnable?.let { handler.removeCallbacks(it) }
        tickerRunnable?.let { handler.removeCallbacks(it) }
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "extra_document_id"
    }
}
