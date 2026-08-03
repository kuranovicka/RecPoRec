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
import com.recporec.app.util.requestAccessibilityFocusNow
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

    // Pamti se PO DOKUMENTU u AppSettings (vidi applyControlsVisibility/toggleControlsVisibility) -
    // stvarna pocetna vrednost se ucitava u onCreate, cim je documentId poznat.
    private var controlsHidden = false

    private var allVoices: List<com.recporec.app.tts.VoiceOption> = emptyList()
    private var voicesLoadJob: kotlinx.coroutines.Job? = null

    /** Lenjo ucitavanje liste glasova - pravi PRIVREMENU instancu TTS motora za SVAKI
     * instalirani motor na telefonu (Google, Samsung...), sto je primetno "tesko" i suvisno
     * da se radi svaki put kad se otvori ekran za citanje (ranije je bilo u onCreate) - dok
     * se pri tom istovremeno i GLAVNI motor za citanje priprema, oteze mu se start. Ucitava se
     * samo kad korisnica STVARNO otvori meni za jezik/glas, jednom, pa se rezultat pamti. */
    private fun loadVoicesIfNeeded(onReady: () -> Unit) {
        if (allVoices.isNotEmpty()) {
            onReady()
            return
        }
        if (voicesLoadJob == null) {
            voicesLoadJob = lifecycleScope.launch {
                allVoices = try {
                    com.recporec.app.tts.TtsEngineUtil.listAllVoices(this@ReaderActivity)
                } catch (e: Exception) {
                    emptyList()
                }
                onReady()
            }
        } else {
            android.widget.Toast.makeText(this, "Učitavanje glasova, sačekaj trenutak", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        documentId = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1)
        if (intent.getBooleanExtra(EXTRA_AUTOPLAY, false)) {
            pendingPlayAfterReady = true
        }
        PlaybackController.ensureInitialized(applicationContext)

        setupButtons()
        // Sacuvano stanje "sakrij kontrole" ZA OVAJ dokument - primenjuje se odmah, PRE
        // loadDocument()/prve prikazane sekunde, da korisnica ne vidi kratak "trep" svih
        // dugmica pre nego sto se sakriju.
        if (documentId != -1L) {
            controlsHidden = settings.isControlsHiddenForDocument(documentId)
        }
        applyControlsVisibility()
        loadDocument()
        startTicker()
        // Sve dozvole (obavestenja, stanje telefona, baterija, pun ekran za budjenje) se sad
        // traze odmah pri POKRETANJU APLIKACIJE (DocumentListActivity), ne ovde - vidi tamo.
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
        btnBookmarks.setOnLongClickListener { quickAddBookmark(); true }
        btnGoTo.setOnClickListener(clickSound { showGoToMenu() })
        btnGoTo.setOnLongClickListener { goToDocumentStart(); true }
        btnWakeUp.setOnClickListener(clickSound { showWakeUpDialog() })
        btnWakeUp.setOnLongClickListener { cancelWakeUp(); true }
        btnSearchText.setOnClickListener(clickSound { showSearchTextDialog() })
        btnSearchText.setOnLongClickListener { repeatLastSearch(); true }

        // Van gornje tastature od 20 dugmica - samo zatvara ovaj ekran (kao sistemsko Nazad),
        // NE zaustavlja citanje ako je u toku u pozadini.
        btnBack.setOnClickListener(clickSound { finish() })
        // Dug pritisak: SVODI CEO ZADATAK (ne samo ovaj ekran) u pozadinu - kao pritisak na
        // Home. Aktivnosti se NE gase (za razliku od finish() iznad), pa ponovno pokretanje
        // ikonice aplikacije vraca korisnicu TACNO na ovaj isti ekran, sa svim dugmadima,
        // umesto da krene ispocetka od liste dokumenata.
        btnBack.setOnLongClickListener { playClickSound(); moveTaskToBack(true); true }

        btnToggleControls.setOnClickListener(clickSound { toggleControlsVisibility() })
        btnToggleControls.setOnLongClickListener { quickShortBreak(); true }

        btnPitchDown.setOnClickListener(clickSound { adjustPitch(-0.1f) })
        btnPitchDown.setOnLongClickListener { resetToGlobal("visina"); true }
        btnPrevChapter.setOnClickListener(clickSound { jumpChapter(-1) })
        btnPrevChapter.setOnLongClickListener { repeatCurrentChapter(); true }
        btnNextChapter.setOnClickListener(clickSound { jumpChapter(1) })
        btnNextChapter.setOnLongClickListener { showChapterList(); true }
        btnPitchUp.setOnClickListener(clickSound { adjustPitch(0.1f) })
        btnPitchUp.setOnLongClickListener { resetToGlobal("visina"); true }

        btnTimer.setOnClickListener(clickSound { showTimerMenu() })
        btnTimer.setOnLongClickListener { extendTimer(); true }

        btnDocLanguage.setOnClickListener(clickSound { showDocLanguagePicker() })
        btnDocLanguage.setOnLongClickListener { undoAllJumps(); true }
        btnCombinedVoices.setOnClickListener(clickSound {
            startActivity(
                android.content.Intent(this@ReaderActivity, CombinedVoicesActivity::class.java)
                    .putExtra(CombinedVoicesActivity.EXTRA_SCOPE_ID, documentId)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_LANGUAGE_TAG, doc?.languageTag ?: settings.globalLanguageTag)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_VOICE_NAME, doc?.voiceName ?: settings.globalVoiceName)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_VOICE_ENGINE, doc?.voiceEngine ?: settings.globalVoiceEngine)
            )
        })
        btnCombinedVoices.setOnLongClickListener { turnOffTimer(); true }
        btnVolDown.setOnClickListener(clickSound { adjustVolume(-1) })
        btnVolDown.setOnLongClickListener { resetToGlobal("jačina"); true }
        btnVolUp.setOnClickListener(clickSound { adjustVolume(1) })
        btnVolUp.setOnLongClickListener { resetToGlobal("jačina"); true }
        btnVoice.setOnClickListener(clickSound { showVoiceDialog() })
        btnVoice.setOnLongClickListener { showScheduleReadingDialog(); true }

        btnSpeedDown.setOnClickListener(clickSound { adjustSpeed(-0.05f) })
        btnSpeedDown.setOnLongClickListener { resetToGlobal("brzina"); true }
        btnSpeedUp.setOnClickListener(clickSound { adjustSpeed(0.05f) })
        btnSpeedUp.setOnLongClickListener { resetToGlobal("brzina"); true }
        btnPlayPause.setOnClickListener(clickSound { togglePlayPause() })
        btnPlayPause.setOnLongClickListener { announceStatus(); true }
        btnRemindMe.setOnClickListener(clickSound { showRemindMeMenu() })
        btnRemindMe.setOnLongClickListener { reactivateLastReminder(); true }

        btnStepBack.setOnClickListener(clickSound { stepNavigate(forward = false) })
        btnStepBack.setOnLongClickListener { repeatCurrentPage(); true }
        btnStepForward.setOnClickListener(clickSound { stepNavigate(forward = true) })
        btnStepForward.setOnLongClickListener { undoLastJump(); true }

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

    /** Predlog korisnika: "cist" ekran za citanje ocima (za videce, ili kad kontrole smetaju) -
     * sakriva SVE osim ovog dugmeta i Pokreni/Pauziraj citanje. Pamti se PO DOKUMENTU (ne
     * globalno) - prezivljava zatvaranje i ponovno otvaranje i dokumenta i cele aplikacije. */
    private fun toggleControlsVisibility() {
        controlsHidden = !controlsHidden
        if (documentId != -1L) settings.setControlsHiddenForDocument(documentId, controlsHidden)
        applyControlsVisibility()
    }

    /** Postavlja vidljivost kontrola prema TRENUTNOJ vrednosti controlsHidden - koristi se i
     * pri prebacivanju (toggleControlsVisibility) i pri otvaranju dokumenta (da odmah prikaze
     * sacuvano stanje za taj dokument, bez potrebe da korisnica prvo sama pritisne dugme). */
    private fun applyControlsVisibility() = with(binding) {
        val visibility = if (controlsHidden) android.view.View.GONE else android.view.View.VISIBLE
        layoutStatus.visibility = visibility
        layoutRow1.visibility = visibility
        layoutRow2.visibility = visibility
        layoutRow3.visibility = visibility
        layoutRow5.visibility = visibility
        btnSpeedDown.visibility = visibility
        btnSpeedUp.visibility = visibility
        seekProgress.visibility = visibility
        btnBack.visibility = visibility
        btnToggleControls.contentDescription = if (controlsHidden) {
            "Prikaži kontrole. Dug pritisak: Kratak predah, pauzira čitanje na 15 minuta."
        } else {
            "Sakrij kontrole. Dug pritisak: Kratak predah, pauzira čitanje na 15 minuta."
        }
    }

    /** "Kratak predah" - dug pritisak na Kontrole. Trenutna radnja (kao brza oznaka), BEZ
     * dijaloga - fiksnih 15 minuta, dovoljno npr. da se skuva kafa. Iskorišćava POSTOJEĆI,
     * već proveren mehanizam "Zakaži čitanje" (tiho pauzira, tiho nastavlja u zakazano vreme,
     * bez alarma) - isti pouzdan AlarmManager, ista zaštita od gašenja procesa i restarta
     * telefona koju smo već sredile za buđenje.
     * PRODUŽAVANJE: kao kod Tajmera (extendTimer) - ako se dugo pritisne OPET dok predah VEĆ
     * traje, SABIRA 15 novih minuta na PREOSTALO vreme (ne resetuje na svežih 15). */
    private fun quickShortBreak() {
        if (PlaybackController.isScheduledReadingActive()) {
            PlaybackController.extendScheduledReadingMinutes(15)
            android.widget.Toast.makeText(this, "Predah produžen za 15 minuta.", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            PlaybackController.startScheduledReading(15 * 60, isQuickBreak = true)
            android.widget.Toast.makeText(this, "Čitanje pauzirano na 15 minuta.", android.widget.Toast.LENGTH_SHORT).show()
        }
        updateRestStatusText()
    }

    private fun loadDocument() {
        // Zabelezi OVAJ zahtev za otvaranje kao najnoviji - ako neki DRUGI ekran (npr. za
        // dokument koji je otvoren POSLE ovog) zavrsi svoje asinhrono ucitavanje pre nas, mi
        // cemo to prepoznati ispod i tiho odustati, umesto da "pregazimo" njegovo, novije
        // stanje.
        val loadToken = PlaybackController.beginLoadRequest()
        // Da li je NESTO DRUGO vec aktivno citalo kad smo poceli da otvaramo OVAJ dokument -
        // ako jeste, citanje treba da se "prenese" na ovaj dokument automatski (nastavlja se
        // tok), umesto da stane i ceka rucni pritisak na Play, cak i ako "Automatski citaj
        // pri otvaranju dokumenta" nije ukljuceno.
        val wasSpeakingBeforeSwitch =
            PlaybackController.currentDocument?.id != documentId && PlaybackController.ttsManager?.isSpeaking == true
        // Ako TRENUTNO neki DRUGI dokument aktivno cita (ne ovaj koji upravo otvaramo),
        // eksplicitno ga zaustavi PRE nego sto pocnemo bilo sta drugo - jasnije i pouzdanije
        // nego osloniti se da ce novo ucitavanje samo nekako "preklopiti" staro.
        if (wasSpeakingBeforeSwitch) {
            PlaybackController.ttsManager?.pause()
        }
        lifecycleScope.launch {
            val entity = db.documentDao().getById(documentId) ?: return@launch
            binding.textDocTitle.text = entity.title

            val cachedParsed = PlaybackController.parsedDocument
            // VAZNO: ovo se mora izracunati OVDE, PRE nego sto bilo sta nize promeni
            // PlaybackController.currentDocument - u suprotnom bi provera kasnije (posle
            // vec izvrsenog PlaybackController.currentDocument = finalEntity) bila GOTOVO
            // UVEK tacna za NOVI dokument (jer bismo poredili currentDocument sa samim sobom,
            // netom postavljenim), sto je i BIO pravi uzrok "cita pogresnu (prethodnu)
            // knjigu" - setupTts() bi se pogresno PRESKOCIO za potpuno nov dokument, cija
            // sadrzina zapravo NIKAD nije ucitana u TTS motor.
            val usingCachedParse = cachedParsed != null && PlaybackController.currentDocument?.id == entity.id
            val parsedDoc = if (usingCachedParse) {
                cachedParsed!!
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

            // KLJUCNA PROVERA: ako je u medjuvremenu neki DRUGI, NOVIJI dokument pocet da se
            // otvara (npr. korisnica je vec presla na sledecu knjigu dok je ovo asinhrono
            // ucitavanje jos trajalo), tiho odustajemo OVDE - PRE nego sto bilo sta upisemo u
            // deljeno PlaybackController stanje. Bez ovoga, "sporiji" (ali stariji) zahtev bi
            // mogao da zavrsi POSLE novijeg i pogresno preuzme kontrolu.
            if (!PlaybackController.isLoadRequestCurrent(loadToken)) return@launch

            parsed = parsedDoc
            PlaybackController.parsedDocument = parsedDoc

            // Sve dopune (npr. broj stranica) se izracunaju PRE nego sto doc postane vidljiv/dostupan
            // ostatku ekrana - da ne postoji prozor u kom je doc "napola gotov" i neko dugme
            // (idi na, itd) radi sa nepotpunim podacima (npr. brojem stranica 0).
            val totalPages = max(1, (parsedDoc.length + charsPerPage - 1) / charsPerPage)
            val finalEntity = if (entity.totalPages != totalPages || entity.totalCharacters != parsedDoc.length) {
                val updated = entity.copy(totalPages = totalPages, totalCharacters = parsedDoc.length)
                db.documentDao().update(updated)
                updated
            } else entity

            // Druga provera, na slucaj da je NOVIJI zahtev stigao BAS dok smo cekali gornji
            // upis u bazu.
            if (!PlaybackController.isLoadRequestCurrent(loadToken)) return@launch

            // VAZNO: doc/currentDocument moraju odmah da nose OVO, sveze upisano vreme, ne
            // ono staro sa kojim je entity ucitan iz baze - u suprotnom bi SVAKO kasnije
            // periodicno cuvanje pozicije (persistCurrentDocument, koje upisuje CEO entitet)
            // tiho VRATILO lastOpenedTimestamp nazad na staru vrednost, i "aktivni dokument"
            // bi se pogresno birao po davno zastareloj, "zamrznutoj" vrednosti umesto stvarno
            // poslednjeg otvaranja.
            val now = System.currentTimeMillis()
            val finalEntityWithTimestamp = finalEntity.copy(lastOpenedTimestamp = now)
            doc = finalEntityWithTimestamp
            PlaybackController.currentDocument = finalEntityWithTimestamp
            PlaybackController.elapsedSeconds = finalEntityWithTimestamp.elapsedSeconds
            withContext(Dispatchers.IO) {
                db.documentDao().updateLastOpenedTimestamp(finalEntityWithTimestamp.id, now)
            }

            // Ako je PlaybackController VEĆ imao ovaj TAČAN dokument spreman i motor već
            // radi (npr. čitanje je pokrenuto automatski - buđenje, odmor, ili prelazak na
            // sledeći dokument dok ovaj ekran nije bio otvoren), NE ponavljamo celu pripremu
            // glasa - to bi prekinulo VEĆ AKTIVNO čitanje usred rečenice (setupTts ponovo
            // učitava tekst i glas), i privremeno bi ostavilo ttsReady=false dok se ne
            // završi, što bi u tom prozoru pogrešno prikazalo "Glas se priprema" na svaki
            // dodir dugmadi, čak i dok se knjiga već čuje.
            val sessionAlreadyActive = usingCachedParse && PlaybackController.ttsManager?.isEngineReady == true
            val alreadySpeaking = PlaybackController.ttsManager?.isSpeaking == true
            // "Automatski čitaj aktivni dokument" - "Pri otvaranju dokumenta": čim se OVAJ
            // dokument otvori, samo krene da čita, bez potrebe da se pritisne Play. Koristi
            // isti mehanizam kao "nastavi čim glas bude spreman" (pendingPlayAfterReady).
            // ISTO se desava i ako je NESTO DRUGO vec citalo kad smo poceli da otvaramo ovaj
            // dokument (bez obzira na ovo podesavanje) - citanje se "prenosi" na novi
            // dokument, ne prekida se tok koji je vec bio u toku.
            if ((settings.autoReadEnabled && settings.autoReadTrigger == "document" || wasSpeakingBeforeSwitch) && !alreadySpeaking) {
                pendingPlayAfterReady = true
                PlaybackController.playTransitionSound(this@ReaderActivity)
                // Odbrambeno: pokreni pozadinski servis (drzi drmanje aktivnim) VEC OVDE, cim
                // znamo da ce citanje samo pocenti - ne cekamo da to uradi tek kasniji
                // togglePlayPause() poziv, da ne bi ostao ni najmanji razmak u kom servis jos
                // ne radi.
                if (settings.backgroundEnabled) {
                    ReadingService.start(this@ReaderActivity, settings.uninterruptedEnabled)
                }
            }
            if (sessionAlreadyActive) {
                ttsReady = true
                if (pendingPlayAfterReady) {
                    pendingPlayAfterReady = false
                    // VAZNO: ako je sesija VEC aktivna I VEC cita (npr. automatski prelazak
                    // na sledeci dokument, koji je vec pokrenuo citanje pre nego sto se ovaj
                    // ekran uopste otvorio), togglePlayPause() bi je POGRESNO pauzirao umesto
                    // pokrenuo (otud "procita rec dve i stane"). Pokrecemo SAMO ako stvarno
                    // JOS UVEK nista ne cita.
                    if (PlaybackController.ttsManager?.isSpeaking != true) {
                        togglePlayPause(manual = false)
                    }
                }
            } else {
                setupTts(
                    parsedDoc,
                    finalEntityWithTimestamp,
                    resolveCombinedVoiceConfig(
                        finalEntityWithTimestamp.id,
                        finalEntityWithTimestamp.voiceName ?: settings.globalVoiceName,
                        finalEntityWithTimestamp.voiceEngine ?: settings.globalVoiceEngine
                    )
                )
            }
            updateStatusTexts()
            updateSeekBar()
            updateDocLanguageButtonText()
            updateNavigationButtonLabels()
            updateTimerStatusText()
            updateRestStatusText()
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

    /** POZIVA SE kad je tekst/glas pripremljen (setupTts zavrsen) - NE znaci da je i sam TTS
     * MOTOR zavrsio svoju (asinhronu) inicijalizaciju! Na HLADNOM pokretanju (app se dugo nije
     * pokretala, proces bas sad kreiran), motor moze da javi "spreman" tek nekoliko stotina ms
     * POSLE ovoga - poziv startFromOffset() pre toga bi TIHO ne izgovorio nista (isti "tihi
     * neuspeh" koji smo vec resile za budjenje posle gasenja procesa), iako se ton prelaska
     * VEC cuo (playTransitionSound, pozvan ranije, pre ovoga) - otud utisak "cula sam zvuk da
     * je pusteno, ali se nista ne cuje". Zato SACEKAMO isEngineReady pre auto-pustanja. */
    private fun markTtsReady() {
        ttsReady = true
        if (pendingPlayAfterReady) {
            val tts = PlaybackController.ttsManager
            if (tts != null && !tts.isEngineReady) {
                tts.onReady = {
                    if (pendingPlayAfterReady) {
                        pendingPlayAfterReady = false
                        togglePlayPause(manual = false)
                    }
                }
            } else {
                pendingPlayAfterReady = false
                togglePlayPause(manual = false)
            }
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
        // Isti obrazac kao glas: dokumentova sopstvena brzina/visina ako je ikad eksplicitno
        // postavljena, inače opšta - da izmena opštih podešavanja stvarno utiče na dokumente
        // koji nemaju svoju (ranije je brzina/visina uvek bila "zamrznuta" od trenutka dodavanja).
        val effRate = effectiveRate(entity)
        val effPitch = effectivePitch(entity)
        val effVolume = effectiveVolume(entity)

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
                tts.setSpeechRate(effRate)
                tts.setPitch(effPitch)
                tts.setVolume(effVolume / 100f)
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
            tts.switchEngine(effectiveEngine, effectiveVoiceName, effRate) {
                lifecycleScope.launch {
                    withContext(Dispatchers.Default) {
                        tts.loadText(parsedDoc.fullText)
                    }
                    tts.setPitch(effPitch)
                    tts.setVolume(effVolume / 100f)
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

    private fun togglePlayPause(manual: Boolean = true) {
        val tts = PlaybackController.ttsManager ?: return
        if (!ttsReady) {
            pendingPlayAfterReady = true
            android.widget.Toast.makeText(this, "Glas se priprema, kreće za trenutak.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (tts.isSpeaking) {
            tts.pause()
            if (manual) settings.userManuallyPaused = true
        } else {
            if (manual) settings.userManuallyPaused = false
            if (PlaybackController.isRestAlarmRinging()) {
                // Alarm VEC zvoni (probudila se) - dugme Play ovde znaci "probudjena sam",
                // isto kao "Prekini buđenje" - tek SAD se ceo niz stvarno prekida.
                PlaybackController.cancelRest()
                updateRestStatusText()
                android.widget.Toast.makeText(this, "Buđenje isključeno.", android.widget.Toast.LENGTH_SHORT).show()
            } else if (PlaybackController.isWakeUpActive()) {
                // JEDINO "Probudi me u" JOS NIJE zazvonilo (npr. za 6 ujutru, ali želiš da
                // slušaš VEĆ sada, pre spavanja) - NE otkazuje se, ostaje aktivno za svoje
                // pravo vreme, bez obzira što je knjiga u međuvremenu već ručno puštena.
                android.widget.Toast.makeText(this, "Čitaš sada - buđenje ostaje zakazano.", android.widget.Toast.LENGTH_SHORT).show()
            } else if (PlaybackController.restIsQuickBreak) {
                // "Kratak predah" - rucno nastavljanje OVDE znaci "gotova sam sa predahom" -
                // to je i poenta kratke pauze, prekida se odmah.
                PlaybackController.cancelRest()
                updateRestStatusText()
            } else if (PlaybackController.restRemainingSeconds > 0) {
                // "Zakaži čitanje" (ne Probudi, ne Kratak predah) - PRETPOSTAVKA te funkcije
                // je da neko vreme nećeš biti tu da rucno pustis (zato se i zakazuje unapred);
                // ako ipak rucno pustis ranije, to znaci da ta pretpostavka vise ne vazi, pa
                // se zakazivanje prekida - kao i pre.
                PlaybackController.cancelRest()
                updateRestStatusText()
                android.widget.Toast.makeText(this, "Zakazano čitanje prekinuto.", android.widget.Toast.LENGTH_SHORT).show()
            }
            // Servis (drzi drmanje aktivnim) se pokrece PRE pocetka citanja, ne posle - isti
            // razlog kao kod "Pri otvaranju dokumenta" iznad: pokretanje servisa nije trenutno,
            // pa bi drmanje odmah po pritisku Play moglo tiho da ne stigne da se registruje.
            if (settings.backgroundEnabled) {
                ReadingService.start(this, settings.uninterruptedEnabled)
            }
            val startOffset = doc?.currentCharacterOffset ?: 0
            tts.startFromOffset(startOffset)
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
        val rate = effectiveRate(doc)
        return (minutes * baseCharsPerMinute * rate.coerceAtLeast(0.3f)).toInt()
    }

    /** Brzina koju STVARNO treba koristiti - dokumentova sopstvena, ako je ikad eksplicitno
     * postavljena (vrednost > 0), inače opšta (globalna). Isti obrazac kao kod glasa/jezika -
     * bez ovoga, promena Brzine u Opštim podešavanjima ne bi uticala ni na jedan dokument
     * koji je ikad otvoren (jer bi već imao "snimljenu" staru vrednost od trenutka dodavanja). */
    private fun effectiveRate(entity: DocumentEntity?): Float {
        val stored = entity?.speechRate ?: return settings.globalSpeechRate
        return if (stored > 0f) stored else settings.globalSpeechRate
    }

    /** Isto kao [effectiveRate], samo za visinu glasa. */
    private fun effectivePitch(entity: DocumentEntity?): Float {
        val stored = entity?.pitch ?: return settings.globalPitch
        return if (stored > 0f) stored else settings.globalPitch
    }

    /** Isto kao [effectiveRate], samo za jačinu (0-100%). Jačina je vezana za TTS (ovu knjigu),
     * NE za sistemsku jačinu telefona. */
    private fun effectiveVolume(entity: DocumentEntity?): Int {
        val stored = entity?.volumePercent ?: return settings.globalVolumePercent
        return if (stored >= 0) stored else settings.globalVolumePercent
    }

    private fun updateNavigationButtonLabels() {
        val mode = settings.navigationMode
        val backHint = " Dug pritisak: ponovi trenutnu stranicu."
        val forwardHint = " Dug pritisak: poništi poslednju radnju."
        when (mode) {
            "min1" -> {
                binding.btnStepBack.text = "◀ 1 min"
                binding.btnStepBack.contentDescription = "1 minut unazad.$backHint"
                binding.btnStepForward.text = "1 min ▶"
                binding.btnStepForward.contentDescription = "1 minut unapred.$forwardHint"
            }
            "min5" -> {
                binding.btnStepBack.text = "◀ 5 min"
                binding.btnStepBack.contentDescription = "5 minuta unazad.$backHint"
                binding.btnStepForward.text = "5 min ▶"
                binding.btnStepForward.contentDescription = "5 minuta unapred.$forwardHint"
            }
            "min10" -> {
                binding.btnStepBack.text = "◀ 10 min"
                binding.btnStepBack.contentDescription = "10 minuta unazad.$backHint"
                binding.btnStepForward.text = "10 min ▶"
                binding.btnStepForward.contentDescription = "10 minuta unapred.$forwardHint"
            }
            "bookmark" -> {
                binding.btnStepBack.text = "◀ Ozn."
                binding.btnStepBack.contentDescription = "Prethodna oznaka.$backHint"
                binding.btnStepForward.text = "Ozn. ▶"
                binding.btnStepForward.contentDescription = "Sledeća oznaka.$forwardHint"
            }
            else -> {
                binding.btnStepBack.text = "◀ Str."
                binding.btnStepBack.contentDescription = "Prethodna stranica.$backHint"
                binding.btnStepForward.text = "Str. ▶"
                binding.btnStepForward.contentDescription = "Sledeća stranica.$forwardHint"
            }
        }
    }

    /** Pamti istoriju pozicija PRE svakog skoka (bilo koje dugme za navigaciju - korak,
     * poglavlje, oznaka, Podseti me, pretraga, Idi na...) - da "Sledeći element" može da
     * poništi POSLEDNJU radnju, a "Jezik" SVE radnje odjednom, bez računanja koliko unazad. */
    private val jumpHistory = mutableListOf<Int>()

    /** Dug pritisak na "Idi na" - vraća na sam početak dokumenta. */
    private fun goToDocumentStart() {
        moveTo(0)
        android.widget.Toast.makeText(this, "Vraćeno na početak dokumenta.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** recordHistory=false koriste SAMO funkcije za poništavanje - da ne bi same sebe
     * "zapisivale" u istoriju i pravile beskonačnu petlju napred-nazad umesto pravog
     * povratka kroz stvarne, ranije radnje. */
    private fun moveTo(offset: Int, recordHistory: Boolean = true) {
        if (doc == null || parsed == null) {
            android.widget.Toast.makeText(this, "Dokument se još učitava, sačekaj trenutak.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (recordHistory) {
            doc?.currentCharacterOffset?.let { jumpHistory.add(it) }
            if (jumpHistory.size > 50) jumpHistory.removeAt(0) // ogranici velicinu
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

    /** Dug pritisak na "Sledeći element" (korak napred) - poništava POSLEDNJU radnju (bilo
     * koji skok), vraćajući tačno na mesto pre nje. */
    private fun undoLastJump() {
        if (jumpHistory.isEmpty()) {
            android.widget.Toast.makeText(this, "Nema prethodne radnje.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val target = jumpHistory.removeAt(jumpHistory.size - 1)
        moveTo(target, recordHistory = false)
        android.widget.Toast.makeText(this, "Poništena poslednja radnja.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Dug pritisak na "Jezik" - poništava SVE prethodne radnje odjednom, vraćajući na mesto
     * od PRE prve od njih (npr. skočila si 2 sata napred, pa nazad, pa na oznaku, pa na
     * nasumičnu stranicu - ovo te vraća tačno tamo gde si bila pre svega toga). */
    private fun undoAllJumps() {
        if (jumpHistory.isEmpty()) {
            android.widget.Toast.makeText(this, "Nema prethodnih radnji.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val target = jumpHistory.first()
        jumpHistory.clear()
        moveTo(target, recordHistory = false)
        android.widget.Toast.makeText(this, "Poništene sve prethodne radnje.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Dug pritisak na "Sledeće poglavlje" - spisak SVIH poglavlja, dodirneš da odeš direktno
     * na bilo koje, umesto da klikćeš jedno po jedno kroz dugačku knjigu. */
    private fun showChapterList() {
        val chapters = parsed?.chapters ?: emptyList()
        if (chapters.isEmpty()) {
            android.widget.Toast.makeText(this, "Ovaj dokument nema prepoznata poglavlja.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = chapters.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Sadržaj")
            .setItems(labels) { _, which -> moveTo(chapters[which].startOffset) }
            .show()
    }

    /** Pretraga teksta ti omogućava da pronađeš neki pojam u dokumentu. */
    private var lastSearchQuery: String? = null

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
                if (query.isNotEmpty()) {
                    lastSearchQuery = query
                    performTextSearch(query)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Dug pritisak na "Pretraga" - ponovo pokreće POSLEDNJU pretragu, sa istim spiskom
     * rezultata kao i pre, bez ponovnog kucanja već traženog teksta. */
    private fun repeatLastSearch() {
        val query = lastSearchQuery
        if (query == null) {
            android.widget.Toast.makeText(this, "Nema prethodne pretrage.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.widget.Toast.makeText(this, "Ponovo pretražujem.", android.widget.Toast.LENGTH_SHORT).show()
        performTextSearch(query)
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

    /** Dug pritisak na "Oznake" - odmah dodaje oznaku na trenutnu poziciju, bez otvaranja
     * menija/dijaloga (uvek dobija broj, kao kad se ostavi prazno ime u običnom dodavanju). */
    private fun quickAddBookmark() {
        val currentDocId = documentId
        val offset = doc?.currentCharacterOffset ?: 0
        lifecycleScope.launch {
            val count = db.bookmarkDao().countForDocument(currentDocId)
            val name = (count + 1).toString()
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
        if (chapters.isEmpty()) {
            android.widget.Toast.makeText(this, "Ovaj dokument nema prepoznata poglavlja.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val current = doc?.currentCharacterOffset ?: 0
        val idx = chapters.indexOfLast { it.startOffset <= current }.coerceAtLeast(0)
        val targetIdx = (idx + direction).coerceIn(0, chapters.size - 1)
        moveTo(chapters[targetIdx].startOffset)
    }

    /** Dug pritisak na "Prethodna" (korak nazad) - ponavlja poslednju (trenutnu) stranicu
     * od početka, bez obzira na izabranu Navigaciju (uvek stranica, ne minut/oznaka). */
    private fun repeatCurrentPage() {
        val current = doc?.currentCharacterOffset ?: 0
        val pageStart = (current / charsPerPage) * charsPerPage
        moveTo(pageStart)
        android.widget.Toast.makeText(this, "Ponavljam stranicu.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Dug pritisak na "Prethodno poglavlje" - ponavlja poslednje (trenutno) poglavlje od
     * početka, umesto da ide na poglavlje PRE njega. */
    private fun repeatCurrentChapter() {
        val chapters = parsed?.chapters ?: emptyList()
        if (chapters.isEmpty()) {
            android.widget.Toast.makeText(this, "Ovaj dokument nema prepoznata poglavlja.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val current = doc?.currentCharacterOffset ?: 0
        val idx = chapters.indexOfLast { it.startOffset <= current }.coerceAtLeast(0)
        moveTo(chapters[idx].startOffset)
        android.widget.Toast.makeText(this, "Ponavljam poglavlje.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Dug pritisak na dugmad za brzinu/visinu/jačinu vraća TU vrednost za OVAJ dokument
     * na "prati opšte" (isto kao "Koristi opšti glas" za glas) - korisno ako si nešto slučajno
     * prilagodila i želiš da se to poništi bez ručnog vraćanja na tačnu staru vrednost. */
    private fun resetToGlobal(what: String) {
        val entity = doc ?: return
        val tts = PlaybackController.ttsManager
        doc = when (what) {
            "brzina" -> {
                val newRate = settings.globalSpeechRate
                tts?.setSpeechRate(newRate)
                entity.copy(speechRate = -1f)
            }
            "visina" -> {
                val newPitch = settings.globalPitch
                tts?.setPitch(newPitch)
                entity.copy(pitch = -1f)
            }
            else -> {
                val newVolume = settings.globalVolumePercent
                tts?.setVolume(newVolume / 100f)
                entity.copy(volumePercent = -1)
            }
        }
        persistState()
        android.widget.Toast.makeText(
            this, "${what.replaceFirstChar { it.uppercase() }}: vraćeno na opšte.", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun adjustVolume(direction: Int) {
        val entity = doc ?: return
        val newPercent = (effectiveVolume(entity) + direction * 5).coerceIn(0, 100)
        doc = entity.copy(volumePercent = newPercent)
        PlaybackController.ttsManager?.setVolume(newPercent / 100f)
        persistState()
        android.widget.Toast.makeText(this, "Jačina zvuka: $newPercent%", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun adjustSpeed(delta: Float) {
        val entity = doc ?: return
        val newRate = (effectiveRate(entity) + delta).coerceIn(0.3f, 3.0f)
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
        val newPitch = (effectivePitch(entity) + delta).coerceIn(0.5f, 2.0f)
        doc = entity.copy(pitch = newPitch)
        PlaybackController.ttsManager?.setPitch(newPitch)
        persistState()
        val roundedPitch = (newPitch * 100).roundToInt() / 100f
        android.widget.Toast.makeText(
            this, "Visina glasa: ${String.format(Locale.US, "%.2f", roundedPitch)}x", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun showDocLanguagePicker() {
        loadVoicesIfNeeded { showDocLanguagePickerWithVoices() }
    }

    private fun showDocLanguagePickerWithVoices() {
        val voices = allVoices.ifEmpty { return }
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
        loadVoicesIfNeeded { showVoiceDialogWithVoices() }
    }

    private fun showVoiceDialogWithVoices() {
        val voices = allVoices.ifEmpty { return }
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
                tts.switchEngine(chosen.enginePackage, chosen.voice.name, effectiveRate(doc)) {
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
        val view = layoutInflater.inflate(R.layout.dialog_remind_me, null)
        val textStatus = view.findViewById<android.widget.TextView>(R.id.textRemindStatus)
        val seek = view.findViewById<android.widget.SeekBar>(R.id.seekRemindMinutes)

        fun minutesFor(progress: Int) = 5 + progress * 5

        seek.max = 23 // (120 - 5) / 5
        val currentMinutes = doc?.timerMinutes ?: 0
        seek.progress = if (currentMinutes in 5..120) (currentMinutes - 5) / 5 else 2 // 15 min podrazumevano
        textStatus.text = "${minutesFor(seek.progress)} minuta"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                textStatus.text = "${minutesFor(progress)} minuta"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Tajmer")
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton("Isključi") { _, _ -> setTimer(0) }
            .setPositiveButton("Postavi") { _, _ -> setTimer(minutesFor(seek.progress)) }
            .show()
        seek.requestAccessibilityFocusNow()
    }

    /** Dug pritisak na "Glas" - "Odmori": SUPROTNO od Tajmera. Pauzira čitanje ODMAH, i
     * SAMO NASTAVLJA posle izabranog broja minuta, bez ikakve dalje akcije. Korisno kad
     * radiš nešto drugo (npr. kućne poslove) i ne želiš da se zamaraš ručnim pauziranjem i
     * nastavljanjem čitanja. Ako u međuvremenu sama pustiš knjigu, odmor se prekida. */
    /** Parsira uneto vreme u formi "sat:minut" (npr. "5:20") i vraća broj sekundi od SADA do
     * sledećeg nastupanja tog vremena (danas ako još nije prošlo, inače sutra). Vraća null
     * ako format nije ispravan. */
    private fun parseWakeTimeToSecondsFromNow(input: String): Int? {
        val parts = input.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance()
        target.set(java.util.Calendar.HOUR_OF_DAY, hour)
        target.set(java.util.Calendar.MINUTE, minute)
        target.set(java.util.Calendar.SECOND, 0)
        target.set(java.util.Calendar.MILLISECOND, 0)
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return ((target.timeInMillis - now.timeInMillis) / 1000).toInt()
    }

    /** Dug pritisak na "Glas" - "Zakaži čitanje": upišeš tačno vreme, npr. 5:20, i čitanje
     * TIHO krene tada, bez ikakvog alarma ili punog ekrana - kao da si sama pritisla Play.
     * Za buđenje SA alarmom, koristi dugme "Probudi". */
    private fun showScheduleReadingDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "npr. 5:20"
        input.contentDescription = "Zakaži čitanje u - upiši vreme u formi sat:minut, na primer 5:20"
        AlertDialog.Builder(this)
            .setTitle("Zakaži čitanje")
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton("Isključi") { _, _ ->
                PlaybackController.cancelRest()
                updateRestStatusText()
                android.widget.Toast.makeText(this, "Zakazano čitanje isključeno.", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Postavi") { _, _ ->
                val timeInput = input.text.toString().trim()
                val seconds = parseWakeTimeToSecondsFromNow(timeInput)
                if (seconds == null) {
                    android.widget.Toast.makeText(
                        this, "Neispravno vreme. Upiši u formi sat:minut, na primer 5:20.", android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    PlaybackController.startScheduledReading(seconds)
                    updateRestStatusText()
                    android.widget.Toast.makeText(this, "Čitanje je zakazano.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    /** Dug pritisak na "Probudi" - "Probudi me u": upišeš tačno vreme, npr. 5:20. Potpuno
     * odvojeno od "Odmori" (samo trajanje), da ne bi bilo pomešano u istom meniju. */
    private fun showWakeUpDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "npr. 5:20"
        input.contentDescription = "Probudi me u - upiši vreme u formi sat:minut, na primer 5:20"
        AlertDialog.Builder(this)
            .setTitle("Probudi me")
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val wakeInput = input.text.toString().trim()
                val seconds = parseWakeTimeToSecondsFromNow(wakeInput)
                if (seconds == null) {
                    android.widget.Toast.makeText(
                        this, "Neispravno vreme. Upiši u formi sat:minut, na primer 5:20.", android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    PlaybackController.startRestUntil(seconds)
                    updateRestStatusText()
                    android.widget.Toast.makeText(this, "Buđenje je postavljeno.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    /** Dug pritisak na "Probudi" - "Isključi buđenje": prekida SAMO buđenje ("Probudi me u"),
     * ne dira klasičan odmor (klizač) ako je taj aktivan umesto njega. */
    private fun cancelWakeUp() {
        if (!PlaybackController.isWakeUpActive()) {
            android.widget.Toast.makeText(this, "Nema aktivnog buđenja.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (PlaybackController.isRestAlarmRinging()) {
            // Alarm VEC zvoni - iskljuciti ga znaci "probudila sam se", pa knjiga krece,
            // bas kao pravi budilnik uz knjigu.
            PlaybackController.resumeCancelingRestIfNeeded()
        } else {
            // Jos samo odbrojava, alarm jos nije ni zazvonio - obicno otkazivanje, bez
            // pokretanja knjige (kao da nikad nisi ni postavila buđenje).
            PlaybackController.cancelRest()
        }
        updateRestStatusText()
        android.widget.Toast.makeText(this, "Buđenje isključeno.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Dug pritisak na "Kombinovani glasovi" - isključuje tajmer, bez potrebe da se otvara
     * ceo meni za Tajmer. */
    private fun turnOffTimer() {
        setTimer(0)
    }

    private fun setTimer(minutes: Int) {
        doc = doc?.copy(timerMinutes = minutes)
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

    /** Dug pritisak na "Tajmer" - produžava VEĆ AKTIVAN tajmer za tačno onoliko minuta
     * koliko je poslednji put postavljen, bez ponovnog otvaranja klizača. Radi sve dok
     * tajmer ne istekne, čak i u poslednjem minutu. */
    private fun extendTimer() {
        val minutes = doc?.timerMinutes ?: 0
        if (PlaybackController.timerRemainingSeconds <= 0 || minutes <= 0) {
            android.widget.Toast.makeText(this, "Nema tajmera.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        PlaybackController.extendTimerMinutes(minutes)
        updateTimerStatusText()
        android.widget.Toast.makeText(this, "Tajmer produžen za $minutes minuta.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** "Podseti me": vraća čitanje UNAZAD za izabrani broj minuta, odmah čim potvrdiš -
     * korisno kad se probudiš i ne znaš tačno dokle si čula dok si zaspala. */
    /** Pamti poslednji korišćen broj minuta za "Podseti me" (samo za ovu sesiju čitanja) -
     * da dug pritisak na dugme može ponovo da ga aktivira bez otvaranja klizača. */
    private var lastRemindMinutes: Int? = null

    /** Sama radnja premotavanja unazad za dati broj minuta - deli je i klizač i dug pritisak. */
    private fun applyRemind(minutes: Int) {
        val current = doc?.currentCharacterOffset ?: 0
        val target = (current - minutesToChars(minutes)).coerceAtLeast(0)
        moveTo(target)
        lastRemindMinutes = minutes
        android.widget.Toast.makeText(
            this, "Vraćeno $minutes minuta unazad.", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /** Dug pritisak na "Podseti me" - ponovo aktivira POSLEDNJI korišćeni broj minuta, bez
     * otvaranja klizača. Korisno kad prespavaš i deo posle prvog "Podseti me". */
    private fun reactivateLastReminder() {
        val minutes = lastRemindMinutes
        if (minutes == null) {
            android.widget.Toast.makeText(this, "Nema prethodnog podsetnika.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        applyRemind(minutes)
    }

    private fun showRemindMeMenu() {
        val view = layoutInflater.inflate(R.layout.dialog_remind_me, null)
        val textStatus = view.findViewById<android.widget.TextView>(R.id.textRemindStatus)
        val seek = view.findViewById<android.widget.SeekBar>(R.id.seekRemindMinutes)

        fun minutesFor(progress: Int) = 5 + progress * 5

        seek.max = 23 // (120 - 5) / 5
        seek.progress = 2 // 15 minuta podrazumevano
        textStatus.text = "${minutesFor(seek.progress)} minuta"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                textStatus.text = "${minutesFor(progress)} minuta"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Podseti me")
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> applyRemind(minutesFor(seek.progress)) }
            .show()
        seek.requestAccessibilityFocusNow()
    }

    private fun updateTimerStatusText() {
        val remaining = PlaybackController.timerRemainingSeconds
        if (remaining <= 0) {
            binding.textTimerStatus.text = "Tajmer nije aktivan."
        } else {
            binding.textTimerStatus.text = "Tajmer: preostalo ${formatTime(remaining.toLong())}."
        }
    }

    private fun updateRestStatusText() {
        val remaining = PlaybackController.restRemainingSeconds
        binding.textRestStatus.text = when {
            PlaybackController.isRestAlarmRinging() -> "Buđenje: alarm zvoni."
            PlaybackController.isScheduledReadingActive() -> "Predah ističe za ${formatTime(remaining.toLong())}."
            remaining > 0 -> "Do buđenja ostalo još ${formatHoursMinutes(remaining)}."
            else -> "Nije ništa zakazano."
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
                updateRestStatusText()
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(tickerRunnable!!, 1000)
    }

    /** Dug pritisak na Play/Pauza - naglas (Toast koji TalkBack pročita) kaže trenutni status:
     * stranicu, poglavlje (ako postoji), proteklo/preostalo vreme, i tajmer (ako je aktivan).
     * Isti podaci koji već stoje pri vrhu ekrana, samo bez potrebe da ih prstom tražiš. */
    private fun announceStatus() {
        val entity = doc ?: return
        val chapters = parsed?.chapters ?: emptyList()
        val currentChapterTitle = chapters.lastOrNull { it.startOffset <= entity.currentCharacterOffset }?.title

        val parts = mutableListOf<String>()
        parts.add(binding.textPages.text.toString())
        if (currentChapterTitle != null) parts.add("Poglavlje: $currentChapterTitle.")
        parts.add(binding.textElapsed.text.toString())
        parts.add(binding.textRemaining.text.toString())
        val timerRemaining = PlaybackController.timerRemainingSeconds
        if (timerRemaining > 0) {
            parts.add("Tajmer: preostalo ${formatTime(timerRemaining.toLong())}.")
        }
        val restRemaining = PlaybackController.restRemainingSeconds
        if (PlaybackController.isRestAlarmRinging()) {
            parts.add("Buđenje: alarm zvoni.")
        } else if (PlaybackController.isScheduledReadingActive()) {
            parts.add("Predah ističe za ${formatTime(restRemaining.toLong())}.")
        } else if (restRemaining > 0) {
            parts.add("Do buđenja ostalo još ${formatHoursMinutes(restRemaining)}.")
        }
        android.widget.Toast.makeText(this, parts.joinToString(" "), android.widget.Toast.LENGTH_LONG).show()
    }

    private fun updateStatusTexts() {
        val entity = doc ?: return
        val length = parsed?.length ?: 1
        val currentPage = min(entity.totalPages, (entity.currentCharacterOffset / charsPerPage) + 1)

        binding.textPages.text = getString(R.string.status_pages, entity.totalPages).let {
            "$it  ($currentPage/${entity.totalPages})"
        }

        val readingRate = max(0.3f, effectiveRate(entity))
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

        val elapsedEstimateSeconds = (consumedChars / (baseCharsPerMinute * readingRate) * 60).toLong() + elapsedPauseMs / 1000
        binding.textElapsed.text = getString(R.string.status_elapsed, formatTime(elapsedEstimateSeconds))

        val remainingChars = max(0, length - entity.currentCharacterOffset)
        val remainingSeconds = (remainingChars / (baseCharsPerMinute * readingRate) * 60).toLong() + remainingPauseMs / 1000
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

    /** Bira ispravan oblik srpske reči prema broju, po standardnom pravilu: 1 -> jednina,
     * 2-4 -> "malo" množina, ostalo -> "veliko" množina - osim 11-14, koji su uvek "veliko"
     * množina bez obzira na poslednju cifru (npr. "jedanaest sati", ne "jedanaest sat"). */
    private fun serbianPlural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    /** "Do buđenja ostalo još..." / "Čitanje zakazano za...": X sati i Y minuta, po
     * pravilima srpske gramatike. Bez sekundi - nepotrebna preciznost za ovaj kontekst. */
    private fun formatHoursMinutes(totalSeconds: Int): String {
        val totalMinutes = (totalSeconds + 59) / 60 // zaokruzi NAGORE - "jos malo" ne sme da pokaze 0
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val hourWord = serbianPlural(hours, "sat", "sata", "sati")
        val minuteWord = serbianPlural(minutes, "minut", "minuta", "minuta")
        return if (hours > 0) {
            "$hours $hourWord i $minutes $minuteWord"
        } else {
            "$minutes $minuteWord"
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
        updateRestStatusText()
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
            // Isto - ako je Brzina/Visina/Jačina promenjena u Opštim podešavanjima dok je ovaj
            // dokument bio otvoren (nije imao svoju posebnu vrednost), primeni odmah.
            tts.setSpeechRate(effectiveRate(doc))
            tts.setPitch(effectivePitch(doc))
            tts.setVolume(effectiveVolume(doc) / 100f)
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
                // VAZNO: bez ovoga, lokalno "doc" na ovom ekranu ostaje na STAROJ poziciji (od
                // pre kraja) - ako se posle toga bilo sta ovde sacuva (npr. izlazak sa ekrana
                // pokrece persistState()), to bi PREPISALO tacnu, "zavrseno" poziciju nazad na
                // stariju vrednost, i knjiga bi delovala kao da se "vratila unazad" umesto da
                // ostane oznacena kao procitana.
                val totalLen = parsed?.length
                if (totalLen != null && totalLen > 0) {
                    doc = doc?.copy(currentCharacterOffset = totalLen)
                    updateStatusTexts()
                    updateSeekBar()
                }
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
