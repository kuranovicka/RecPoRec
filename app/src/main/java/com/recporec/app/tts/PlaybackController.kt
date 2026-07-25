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

    /** ReadingService se prijavi ovde dok radi, da bi osvežio notifikaciju i MediaSession
     * (bitno za dugmad na notifikaciji, Bluetooth/žičane komande i TalkBack-ov gest za
     * pauzu/nastavak medija) svaki put kad se stanje čitanja promeni bilo odakle - iz
     * ReaderActivity-je (dugme, gest), iz tajmera, ili iz samog servisa. */
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
            notifyPlaybackStateChanged()
            scope.launch { uiFinishedListener?.invoke() }
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
        scope.coroutineContext.cancelChildren()
        tickerStarted = false
    }
}
