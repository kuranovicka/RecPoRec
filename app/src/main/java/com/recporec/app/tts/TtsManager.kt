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
class TtsManager(context: Context) {

    var onPositionChanged: ((Int) -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var ready = false

    private var chunks: List<String> = emptyList()
    private var chunkOffsets: List<Int> = emptyList()
    private var currentChunkIndex = 0
    private var baseOffset = 0

    var isSpeaking = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
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
                onReady?.invoke()
            }
        }
    }

    fun availableVoices(): List<Voice> {
        return tts?.voices?.toList()?.sortedBy { it.name } ?: emptyList()
    }

    fun setVoiceByName(name: String) {
        val voice = tts?.voices?.firstOrNull { it.name == name } ?: return
        tts?.voice = voice
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
        currentChunkIndex = chunkOffsets.indexOfFirst { it >= offset }.let {
            if (it == -1) chunks.size - 1 else it
        }.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
        if (chunks.isEmpty()) return
        isSpeaking = true
        speakCurrentChunk()
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
