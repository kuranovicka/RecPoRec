package com.recporec.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

/**
 * Obmotava Android TextToSpeech. Čita tekst u delovima (rečenicama) da bi
 * mogli precizno da pratimo poziciju (offset) radi pamćenja napretka i
 * pomeranja po stranicama/procentima.
 */
class TtsManager(private val appContext: Context) {

    var onPositionChanged: ((Int) -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var ready = false
    var currentEnginePackage: String? = null
        private set

    /** Da li je trenutni TTS motor zaista zavrsio inicijalizaciju (spreman za setVoice/speak). */
    val isEngineReady: Boolean get() = ready

    private var chunks: List<String> = emptyList()
    private var chunkOffsets: List<Int> = emptyList()
    private var currentChunkIndex = 0

    var isSpeaking = false
        private set

    /** Pauza između rečenica u milisekundama (0 = isključeno, čita se odmah dalje). */
    var sentencePauseMs: Long = 0

    private val pauseHandler = Handler(Looper.getMainLooper())
    private var pendingNextChunk: Runnable? = null

    // Kombinovani glasovi (2+) koji se smenjuju na svakih N rečenica. Prazna lista = iskljuceno.
    private var combinedVoiceNames: List<String> = emptyList()
    private var combinedSentencesPerVoice: Int = 1
    private var combinedVoiceIndex = 0
    private var sentencesReadInCurrentVoice = 0

    /** Postavlja listu glasova koji se smenjuju svakih [sentencesPerVoice] rečenica.
     * Prazna ili jednočlana lista isključuje kombinovane glasove (čita se normalno, jednim glasom). */
    fun setCombinedVoices(voiceNames: List<String>, sentencesPerVoice: Int) {
        combinedVoiceNames = voiceNames
        combinedSentencesPerVoice = sentencesPerVoice.coerceAtLeast(1)
        combinedVoiceIndex = 0
        sentencesReadInCurrentVoice = 0
        if (combinedVoiceNames.size >= 2) {
            setVoiceByName(combinedVoiceNames[0])
        }
    }

    private fun advanceCombinedVoiceIfNeeded() {
        if (combinedVoiceNames.size < 2) return
        sentencesReadInCurrentVoice++
        if (sentencesReadInCurrentVoice >= combinedSentencesPerVoice) {
            sentencesReadInCurrentVoice = 0
            combinedVoiceIndex = (combinedVoiceIndex + 1) % combinedVoiceNames.size
            setVoiceByName(combinedVoiceNames[combinedVoiceIndex])
        }
    }

    init {
        initEngine(null)
    }

    private fun attachListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
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

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isSpeaking = false
            }
        })
    }

    private fun initEngine(enginePackage: String?) {
        val listener = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            currentEnginePackage = enginePackage
            if (ready) {
                attachListener()
                onReady?.invoke()
            }
        }
        tts = if (enginePackage != null) {
            TextToSpeech(appContext, listener, enginePackage)
        } else {
            TextToSpeech(appContext, listener)
        }
    }

    /** Prebacuje na drugi TTS motor (npr. sa Google-ovog na Samsung-ov) i ponovo primenjuje glas. */
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
        tts?.voice = voice
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
        tts?.setSpeechRate(rate)
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
        if (combinedVoiceNames.size >= 2) {
            // Svaki novi početak čitanja (posle skoka na stranicu/oznaku/pretragu) počinje
            // od prvog glasa u nizu - dosledno i lako za očekivati, umesto nagađanja koji bi
            // glas "trebalo" da bude na tom mestu.
            combinedVoiceIndex = 0
            sentencesReadInCurrentVoice = 0
            setVoiceByName(combinedVoiceNames[0])
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
        isSpeaking = false
    }

    fun resume() {
        if (chunks.isEmpty()) return
        isSpeaking = true
        speakCurrentChunk()
    }

    fun stop() {
        cancelPendingChunk()
        tts?.stop()
        isSpeaking = false
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
        tts?.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun shutdown() {
        cancelPendingChunk()
        tts?.stop()
        tts?.shutdown()
    }
}
