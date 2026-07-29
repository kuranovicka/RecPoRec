package com.recporec.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

/** Jedan glas u kombinovanom nizu - motor + ime glasa. Motor može biti primarni ili
 * neki od dodatnih (drugi TTS proizvod nego onaj kojim dokument inače čita). */
data class CombinedVoiceRef(val enginePackage: String, val voiceName: String)

/**
 * Obmotava Android TextToSpeech. Čita tekst u delovima (rečenicama) da bi
 * mogli precizno da pratimo poziciju (offset) radi pamćenja napretka i
 * pomeranja po stranicama/procentima.
 *
 * Za kombinovane glasove iz RAZLIČITIH TTS motora (ne samo različitih glasova istog
 * motora), umesto da se jedna veza gasi i pali (sporo, nepouzdano usred čitanja), drže se
 * DVE (ili više) već upaljene, spremne veze istovremeno - jedna po motoru - i samo se bira
 * koja od njih izgovara sledeću rečenicu. Nema gašenja/paljenja usred čitanja.
 */
class TtsManager(private val appContext: Context) {

    var onPositionChanged: ((Int) -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null
    /** Pozvano kad čitanje automatski pauzira zbog telefonskog poziva ili nekog drugog
     * zvuka koji preuzme prednost (audio fokus) - ne zbog korisnikovog dodira na dugme. */
    var onAutoPaused: (() -> Unit)? = null
    /** Pozvano kad se čitanje automatski nastavi posle takvog prekida. */
    var onAutoResumed: (() -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var ready = false
    var currentEnginePackage: String? = null
        private set

    /** Da li je trenutni TTS motor zaista zavrsio inicijalizaciju (spreman za setVoice/speak). */
    val isEngineReady: Boolean get() = ready

    // Dodatni (sekundarni) motori za kombinovane glasove van primarnog motora - drže se
    // upaljeni i spremni unapred, da ne bude čekanja usred čitanja.
    private val secondaryEngines: MutableMap<String, TextToSpeech> = mutableMapOf()
    private val secondaryEnginesReady: MutableMap<String, Boolean> = mutableMapOf()

    // Veza koja TRENUTNO izgovara tekst (primarna ili neka od sekundarnih).
    private var activeInstance: TextToSpeech? = null

    private var lastSpeechRate: Float = 1.0f
    private var lastPitch: Float = 1.0f

    // Automatska pauza kad neko drugi (npr. telefonski poziv) preuzme audio - standardan
    // Android mehanizam, ne zahteva nikakvu posebnu dozvolu. Kad poziv zavrsi, citanje se
    // samo nastavi - ali SAMO ako je pauzirano zbog ovoga, ne ako je korisnica sama pauzirala.
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var pausedDueToFocusLoss = false

    private val focusChangeListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isSpeaking) {
                    pausedDueToFocusLoss = true
                    pause()
                    onAutoPaused?.invoke()
                }
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedDueToFocusLoss) {
                    pausedDueToFocusLoss = false
                    resume()
                    onAutoResumed?.invoke()
                }
            }
        }
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (audioFocusRequest == null) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
        }
        try {
            am.requestAudioFocus(audioFocusRequest!!)
        } catch (_: Exception) {
            // Bezopasno ako ne uspe - citanje samo nastavlja bez ove zastite.
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        audioFocusRequest?.let {
            try {
                am.abandonAudioFocusRequest(it)
            } catch (_: Exception) {
            }
        }
    }

    private var chunks: List<String> = emptyList()
    private var chunkOffsets: List<Int> = emptyList()
    private var currentChunkIndex = 0

    var isSpeaking = false
        private set

    /** Pauza između rečenica u milisekundama (0 = isključeno, čita se odmah dalje). */
    var sentencePauseMs: Long = 0

    private val pauseHandler = Handler(Looper.getMainLooper())
    private var pendingNextChunk: Runnable? = null

    // Kombinovani glasovi (2+) koji se smenjuju na svakih N rečenica. Prazna/jednočlana lista
    // isključuje kombinovane glasove (čita se normalno, jednim glasom).
    private var combinedVoices: List<CombinedVoiceRef> = emptyList()
    private var combinedSentencesPerVoice: Int = 1
    private var combinedVoiceIndex = 0
    private var sentencesReadInCurrentVoice = 0

    /** Postavlja listu glasova (svaki sa svojim motorom) koji se smenjuju svakih
     * [sentencesPerVoice] rečenica. Unapred priprema (pali) svaki motor koji nije primarni,
     * da bude spreman pre nego što čitanje uopšte stigne do njega. */
    fun setCombinedVoices(voices: List<CombinedVoiceRef>, sentencesPerVoice: Int) {
        combinedVoices = voices
        combinedSentencesPerVoice = sentencesPerVoice.coerceAtLeast(1)
        combinedVoiceIndex = 0
        sentencesReadInCurrentVoice = 0

        val distinctEngines = voices.map { it.enginePackage }.toSet()
        for (engine in distinctEngines) {
            if (engine != currentEnginePackage && !secondaryEngines.containsKey(engine)) {
                prepareSecondaryEngine(engine)
            }
        }

        if (voices.size >= 2) {
            applyCombinedVoice(voices[0])
        } else {
            activeInstance = tts
        }
    }

    private fun prepareSecondaryEngine(enginePackage: String) {
        secondaryEnginesReady[enginePackage] = false
        val instance = TextToSpeech(appContext, { status ->
            val success = status == TextToSpeech.SUCCESS
            secondaryEnginesReady[enginePackage] = success
            if (success) {
                secondaryEngines[enginePackage]?.setOnUtteranceProgressListener(makeSharedListener())
                secondaryEngines[enginePackage]?.setSpeechRate(lastSpeechRate)
                secondaryEngines[enginePackage]?.setPitch(lastPitch)
            }
        }, enginePackage)
        secondaryEngines[enginePackage] = instance
    }

    private fun instanceForEngine(enginePackage: String): TextToSpeech? {
        if (enginePackage == currentEnginePackage) return tts
        if (secondaryEnginesReady[enginePackage] == true) return secondaryEngines[enginePackage]
        return null
    }

    private fun applyCombinedVoice(ref: CombinedVoiceRef) {
        val instance = instanceForEngine(ref.enginePackage) ?: run {
            // Sekundarni motor još nije stigao da se upali (retko - samo ako se čitanje
            // pokrene odmah nakon što su kombinovani glasovi upravo postavljeni). Ostaje se
            // na trenutnom glasu za ovaj krug, probaće se ponovo na sledećoj smeni.
            return
        }
        activeInstance = instance
        val voice = instance.voices?.firstOrNull { it.name == ref.voiceName } ?: return
        // setLanguage() se poziva SAMO kad se jezik stvarno menja - kod nekih motora, poziv
        // sa VEĆ AKTIVNIM jezikom (npr. prelazak sa jednog na drugi srpski glas) zna tiho da
        // vrati podrazumevani glas tog jezika, poništavajući baš izabrani glas.
        val languageChanged = instance.voice?.locale?.language != voice.locale.language
        instance.voice = voice
        if (languageChanged) {
            try {
                instance.setLanguage(voice.locale)
            } catch (_: Exception) {
                // Neki motori/lokali mogu odbiti setLanguage - glas je već postavljen gore.
            }
        }
    }

    private fun advanceCombinedVoiceIfNeeded() {
        if (combinedVoices.size < 2) return
        sentencesReadInCurrentVoice++
        if (sentencesReadInCurrentVoice >= combinedSentencesPerVoice) {
            sentencesReadInCurrentVoice = 0
            combinedVoiceIndex = (combinedVoiceIndex + 1) % combinedVoices.size
            applyCombinedVoice(combinedVoices[combinedVoiceIndex])
        }
    }

    init {
        initEngine(null)
    }

    private fun makeSharedListener(): UtteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            handleUtteranceDone()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            isSpeaking = false
        }
    }

    private fun handleUtteranceDone() {
        currentChunkIndex++
        if (currentChunkIndex < chunks.size) {
            advanceCombinedVoiceIfNeeded()
            if (sentencePauseMs > 0) {
                val runnable = Runnable { speakCurrentChunk() }
                pendingNextChunk = runnable
                pauseHandler.postDelayed(runnable, sentencePauseMs)
            } else {
                speakCurrentChunk()
            }
        } else {
            isSpeaking = false
            onFinished?.invoke()
        }
    }

    private fun attachListener() {
        tts?.setOnUtteranceProgressListener(makeSharedListener())
    }

    private fun initEngine(enginePackage: String?) {
        val listener = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            currentEnginePackage = enginePackage
            if (ready) {
                attachListener()
                if (activeInstance == null) activeInstance = tts
                onReady?.invoke()
            }
        }
        tts = if (enginePackage != null) {
            TextToSpeech(appContext, listener, enginePackage)
        } else {
            TextToSpeech(appContext, listener)
        }
    }

    /** Prebacuje PRIMARNI motor (npr. sa Google-ovog na Samsung-ov) i ponovo primenjuje glas.
     * Ovo je i dalje sporije/asinhrono - koristi se samo za običan (nekombinovan) glas
     * dokumenta, ne za smenjivanje unutar kombinovanih glasova (za to postoje sekundarne,
     * unapred upaljene veze). */
    fun switchEngine(enginePackage: String?, voiceName: String?, rate: Float, onSwitched: () -> Unit) {
        tts?.stop()
        tts?.shutdown()
        val previousOnReady = onReady
        onReady = {
            voiceName?.let { setVoiceByName(it) }
            setSpeechRate(rate)
            onReady = previousOnReady
            onSwitched()
        }
        initEngine(enginePackage)
    }

    fun availableVoices(): List<Voice> {
        return tts?.voices?.toList()?.sortedBy { it.name } ?: emptyList()
    }

    fun setVoiceByName(name: String) {
        val voice = tts?.voices?.firstOrNull { it.name == name } ?: return
        // setLanguage() se poziva SAMO kad se jezik stvarno menja - kod nekih motora, poziv
        // sa VEĆ AKTIVNIM jezikom (npr. prelazak sa jednog na drugi glas istog jezika) zna
        // tiho da vrati podrazumevani glas tog jezika, poništavajući baš izabrani glas.
        val languageChanged = tts?.voice?.locale?.language != voice.locale.language
        tts?.voice = voice
        if (languageChanged) {
            try {
                tts?.setLanguage(voice.locale)
            } catch (_: Exception) {
                // Neki motori/lokali mogu odbiti setLanguage - glas je vec postavljen gore,
                // pa nastavljamo bez prekida.
            }
        }
    }

    fun currentVoiceName(): String? = tts?.voice?.name

    /**
     * Kada nije eksplicitno izabran nijedan glas (ni za dokument ni globalno), TextToSpeech
     * bi inače mogao da koristi glas koji se poklapa sa onim kojim čita ekranski čitač
     * (jer oba mogu da koriste isti podrazumevani sistemski glas). Da bi čitanje knjiga bilo
     * potpuno nezavisno, ovde SAMI biramo određen, dosledan podrazumevani glas.
     */
    fun applyIndependentDefaultVoice(): Voice? {
        val allEngineVoices = tts?.voices?.toList() ?: return null
        if (allEngineVoices.isEmpty()) return null
        val deviceLanguage = Locale.getDefault().language
        val candidates = allEngineVoices.filter { it.locale.language == deviceLanguage }
            .ifEmpty { allEngineVoices }
        val chosen = candidates.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            .firstOrNull()
        chosen?.let { tts?.voice = it }
        return chosen
    }

    fun setSpeechRate(rate: Float) {
        lastSpeechRate = rate
        tts?.setSpeechRate(rate)
        secondaryEngines.values.forEach { it.setSpeechRate(rate) }
    }

    fun setPitch(pitch: Float) {
        lastPitch = pitch
        tts?.setPitch(pitch)
        secondaryEngines.values.forEach { it.setPitch(pitch) }
    }

    /** Učitava puni tekst i deli ga na rečenice radi praćenja pozicije. */
    fun loadText(fullText: String) {
        val splitter = Regex("(?<=[.!?\\n])\\s+")
        val parts = fullText.split(splitter).filter { it.isNotBlank() }
        val offsets = mutableListOf<Int>()
        var pos = 0
        val cleaned = mutableListOf<String>()
        for (part in parts) {
            val idx = fullText.indexOf(part, pos)
            val safeIdx = if (idx >= 0) idx else pos
            offsets.add(safeIdx)
            cleaned.add(part)
            pos = safeIdx + part.length
        }
        chunks = cleaned
        chunkOffsets = offsets
    }

    /** Počni čitanje od zadatog offseta u tekstu (karakter). */
    fun startFromOffset(offset: Int) {
        val idx = java.util.Collections.binarySearch(chunkOffsets, offset)
        currentChunkIndex = (if (idx >= 0) idx else -idx - 1)
            .coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
        if (chunks.isEmpty()) return
        requestAudioFocus()
        pausedDueToFocusLoss = false
        if (combinedVoices.size >= 2) {
            // Svaki novi početak čitanja (posle skoka na stranicu/oznaku/pretragu) počinje
            // od prvog glasa u nizu - dosledno i lako za očekivati, umesto nagađanja koji bi
            // glas "trebalo" da bude na tom mestu.
            combinedVoiceIndex = 0
            sentencesReadInCurrentVoice = 0
            applyCombinedVoice(combinedVoices[0])
        } else {
            activeInstance = tts
        }
        isSpeaking = true
        speakCurrentChunk()
    }

    /** Uskladi internu poziciju sa datim offsetom BEZ pokretanja govora (npr. dok je pauzirano). */
    fun syncPositionOnly(offset: Int) {
        val idx = java.util.Collections.binarySearch(chunkOffsets, offset)
        currentChunkIndex = (if (idx >= 0) idx else -idx - 1)
            .coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
    }

    fun pause() {
        cancelPendingChunk()
        tts?.stop()
        secondaryEngines.values.forEach { it.stop() }
        isSpeaking = false
    }

    fun resume() {
        if (chunks.isEmpty()) return
        requestAudioFocus()
        isSpeaking = true
        speakCurrentChunk()
    }

    fun stop() {
        cancelPendingChunk()
        tts?.stop()
        secondaryEngines.values.forEach { it.stop() }
        isSpeaking = false
        pausedDueToFocusLoss = false
        abandonAudioFocus()
    }

    private fun cancelPendingChunk() {
        pendingNextChunk?.let { pauseHandler.removeCallbacks(it) }
        pendingNextChunk = null
    }

    fun currentOffset(): Int {
        if (chunkOffsets.isEmpty()) return 0
        val idx = currentChunkIndex.coerceIn(0, chunkOffsets.size - 1)
        return chunkOffsets[idx]
    }

    private fun speakCurrentChunk() {
        val chunk = chunks.getOrNull(currentChunkIndex) ?: run {
            isSpeaking = false
            onFinished?.invoke()
            return
        }
        onPositionChanged?.invoke(chunkOffsets.getOrElse(currentChunkIndex) { 0 })
        val speaker = activeInstance ?: tts
        speaker?.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun shutdown() {
        cancelPendingChunk()
        abandonAudioFocus()
        tts?.stop()
        tts?.shutdown()
        secondaryEngines.values.forEach {
            it.stop()
            it.shutdown()
        }
        secondaryEngines.clear()
        secondaryEnginesReady.clear()
    }
}
