package com.recporec.app.tts

import android.content.Context
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

    init {
        initEngine(null)
    }

    private fun attachListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                currentChunkIndex++
                if (currentChunkIndex < chunks.size) {
                    speakCurrentChunk()
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
        tts?.stop()
        isSpeaking = false
    }

    fun resume() {
        if (chunks.isEmpty()) return
        isSpeaking = true
        speakCurrentChunk()
    }

    fun stop() {
        tts?.stop()
        isSpeaking = false
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
        tts?.stop()
        tts?.shutdown()
    }
}
