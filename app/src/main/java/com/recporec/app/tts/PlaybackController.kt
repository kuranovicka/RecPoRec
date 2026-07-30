package com.recporec.app.tts

import android.content.Context
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.DocumentEntity
import com.recporec.app.parser.ParsedDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drži jedinu instancu TtsManager-a i trenutno otvoren dokument,
 * dostupno i Activity-ju i pozadinskom servisu.
 *
 * VAŽNO: ovaj objekat SAM čuva poziciju u dokumentu (i vreme čitanja) u bazu,
 * nezavisno od toga da li je ReaderActivity trenutno otvorena. Ranije je to
 * radila sama Activity, pa bi se čuvanje pozicije prekinulo čim korisnik
 * ode na listu dokumenata ili potpuno izađe dok se čita u pozadini.
 */
object PlaybackController {

    var ttsManager: TtsManager? = null
        private set

    var currentDocument: DocumentEntity? = null
    var parsedDocument: ParsedDocument? = null
    var elapsedSeconds: Long = 0

    /** Preostale sekunde tajmera za automatsku pauzu (0 = nije aktivan). Živi ovde (ne u
     * ReaderActivity) da bi preživeo ako Android uništi i ponovo napravi ekran za čitanje
     * dok app radi u pozadini - inače bi se tajmer "restartovao" na isključeno. */
    var timerRemainingSeconds: Int = 0
        private set

    var uiTimerExpiredListener: (() -> Unit)? = null

    /** ReadingService se prijavi ovde dok radi u pozadini, da bi osvežio notifikaciju kad
     * se stanje čitanja promeni bilo odakle (dugme, tajmer, automatska pauza pri pozivu...). */
    var playbackStateListener: (() -> Unit)? = null

    fun notifyPlaybackStateChanged() {
        playbackStateListener?.invoke()
    }

    fun setTimerMinutes(minutes: Int) {
        timerRemainingSeconds = if (minutes <= 0) 0 else minutes * 60
    }

    /** UI (ReaderActivity) se ovde prikači dok je vidljiva, da dobija živo ažuriranje. */
    var uiPositionListener: ((Int) -> Unit)? = null
    var uiFinishedListener: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null
    private var tickerStarted = false

    // Iste vrednosti kao u ReaderActivity - drzi korak navigacije dosledan bez obzira da li
    // je pokrenut sa dugmeta u citacu ili sa medijskog tastera.
    private const val CHARS_PER_PAGE = 1800
    private const val BASE_CHARS_PER_MINUTE = 800f

    fun ensureInitialized(context: Context) {
        appContext = context.applicationContext
        if (ttsManager == null) {
            ttsManager = TtsManager(context.applicationContext)
            wireCallbacks()
        }
        startTickerIfNeeded()
    }

    private fun wireCallbacks() {
        val tts = ttsManager ?: return
        tts.onPositionChanged = { offset ->
            scope.launch {
                currentDocument = currentDocument?.copy(currentCharacterOffset = offset)
                persistCurrentDocument()
                uiPositionListener?.invoke(offset)
            }
        }
        tts.onFinished = {
            scope.launch { uiFinishedListener?.invoke() }
            handleAutoAdvance()
        }
        tts.onAutoPaused = { notifyPlaybackStateChanged() }
        tts.onAutoResumed = { notifyPlaybackStateChanged() }
    }

    /** Kad se dokument do kraja pročita i uključeno je "Pređi automatski na sledeći",
     * nakon kratke pauze (i zvučnog signala) prelazi na sledeći dokument u listi. Živi ovde
     * (ne u ReaderActivity) da bi radilo i kad je čitanje u pozadini, van otvorenog ekrana. */
    private fun handleAutoAdvance() {
        val ctx = appContext ?: return
        val settings = com.recporec.app.data.AppSettings(ctx)
        if (!settings.autoNextDocumentEnabled) return
        val currentDocId = currentDocument?.id ?: return
        scope.launch {
            delay(1000)
            val list = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(ctx).documentDao().observeAllOnce()
            }
            val currentIndex = list.indexOfFirst { it.id == currentDocId }
            val next = if (currentIndex in 0 until list.size - 1) list[currentIndex + 1] else null
            if (next == null) {
                android.widget.Toast.makeText(
                    ctx, "Ovo je poslednji dokument u listi.", android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 200)
            val intent = android.content.Intent(ctx, com.recporec.app.ui.ReaderActivity::class.java).apply {
                putExtra(com.recporec.app.ui.ReaderActivity.EXTRA_DOCUMENT_ID, next.id)
                putExtra(com.recporec.app.ui.ReaderActivity.EXTRA_AUTOPLAY, true)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            ctx.startActivity(intent)
            tone.release()
        }
    }

    private fun startTickerIfNeeded() {
        if (tickerStarted) return
        tickerStarted = true
        scope.launch {
            var tick = 0
            while (true) {
                delay(1000)

                if (ttsManager?.isSpeaking == true) {
                    elapsedSeconds += 1
                    currentDocument = currentDocument?.copy(elapsedSeconds = elapsedSeconds)
                    tick++
                    if (tick % 5 == 0) persistCurrentDocument()

                    if (timerRemainingSeconds > 0) {
                        timerRemainingSeconds -= 1
                        if (timerRemainingSeconds <= 0) {
                            timerRemainingSeconds = 0
                            ttsManager?.pause()
                            notifyPlaybackStateChanged()
                            // Kad istekne, tajmer se prosto iskljuci - jednostavno, kao pre.
                            currentDocument = currentDocument?.copy(timerMinutes = 0)
                            persistCurrentDocument()
                            uiTimerExpiredListener?.invoke()
                        }
                    }
                }
            }
        }
    }

    private fun persistCurrentDocument() {
        val ctx = appContext ?: return
        val doc = currentDocument ?: return
        scope.launch(Dispatchers.IO) {
            try {
                AppDatabase.getInstance(ctx).documentDao().update(doc)
            } catch (_: Exception) { }
        }
    }

    /** Ručno čuvanje (npr. posle navigacije dugmadima/klizačem) kroz isti nezavisan mehanizam,
     * da upis ne zavisi od toga da li je ReaderActivity još živa u tom trenutku. */
    fun persistDocumentNow(entity: DocumentEntity) {
        currentDocument = entity
        persistCurrentDocument()
    }

    fun isActive(): Boolean = ttsManager?.isSpeaking == true

    /** Isto što i dugmad "Prethodna/Sledeća" u čitaču (zavisno od podešavanja Navigacije) -
     * ovde postoji posebno da bi radilo i pozvano iz servisa (npr. sa tastera za premotavanje
     * na slušalicama/spoljnoj tastaturi), nezavisno od toga da li je ekran otvoren. */
    fun stepNavigate(forward: Boolean, context: Context) {
        val settings = com.recporec.app.data.AppSettings(context)
        val mode = settings.navigationMode
        val entity = currentDocument ?: return
        val length = parsedDocument?.length ?: return

        if (mode == "bookmark") {
            scope.launch {
                val bookmarks = withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(context).bookmarkDao().getForDocument(entity.id)
                }.sortedBy { it.characterOffset }
                if (bookmarks.isEmpty()) return@launch
                val current = currentDocument?.currentCharacterOffset ?: 0
                val target = if (forward) {
                    bookmarks.firstOrNull { it.characterOffset > current } ?: bookmarks.first()
                } else {
                    bookmarks.lastOrNull { it.characterOffset < current } ?: bookmarks.last()
                }
                applyOffset(target.characterOffset)
            }
            return
        }

        val delta = when (mode) {
            "min1" -> minutesToChars(1, entity.speechRate)
            "min5" -> minutesToChars(5, entity.speechRate)
            "min10" -> minutesToChars(10, entity.speechRate)
            else -> CHARS_PER_PAGE
        }
        val signedDelta = if (forward) delta else -delta
        val current = entity.currentCharacterOffset
        val newOffset = (current + signedDelta).coerceIn(0, (length - 1).coerceAtLeast(0))
        applyOffset(newOffset)
    }

    private fun minutesToChars(minutes: Int, rate: Float): Int =
        (minutes * BASE_CHARS_PER_MINUTE * rate.coerceAtLeast(0.3f)).toInt()

    private fun applyOffset(offset: Int) {
        currentDocument = currentDocument?.copy(currentCharacterOffset = offset)
        persistCurrentDocument()
        val tts = ttsManager
        if (tts?.isSpeaking == true) tts.startFromOffset(offset) else tts?.syncPositionOnly(offset)
        uiPositionListener?.invoke(offset)
    }

    fun release() {
        ttsManager?.shutdown()
        ttsManager = null
        currentDocument = null
        parsedDocument = null
        elapsedSeconds = 0
        timerRemainingSeconds = 0
        uiPositionListener = null
        uiFinishedListener = null
        uiTimerExpiredListener = null
        playbackStateListener = null
        scope.coroutineContext.cancelChildren()
        tickerStarted = false
    }
}
