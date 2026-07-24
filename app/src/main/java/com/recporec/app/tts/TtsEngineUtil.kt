package com.recporec.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** Jedan glas zajedno sa informacijom iz kog TTS motora (aplikacije) dolazi. */
data class VoiceOption(
    val enginePackage: String,
    val engineLabel: String,
    val voice: Voice
) {
    val displayLanguage: String
        get() = voice.locale.displayLanguage.replaceFirstChar { it.uppercase() }

    val displayCountry: String get() = voice.locale.displayCountry

    val displayLabel: String
        get() {
            val country = if (displayCountry.isNotBlank() && displayCountry != displayLanguage) " — $displayCountry" else ""
            return "$displayLanguage$country ($engineLabel)"
        }
}

/**
 * Prikuplja glasove iz SVIH instaliranih TTS motora (Google, Samsung, i dr.),
 * ne samo iz trenutno podrazumevanog. Android ograničava jedan TextToSpeech
 * na jedan motor, pa se za svaki instalirani motor pravi privremena instanca.
 */
object TtsEngineUtil {

    suspend fun listAllVoices(context: Context): List<VoiceOption> {
        val engines = listEngines(context)
        val result = mutableListOf<VoiceOption>()
        for (engine in engines) {
            try {
                val voices = queryVoicesForEngine(context, engine.name)
                voices.forEach { result.add(VoiceOption(engine.name, engine.label, it)) }
            } catch (_: Exception) {
                // Ako neki motor ne uspe da se učita, samo ga preskačemo
            }
        }
        return result
    }

    data class EngineInfo(val name: String, val label: String)

    private suspend fun listEngines(context: Context): List<EngineInfo> {
        return suspendCancellableCoroutine { cont ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context) { status ->
                val engines = if (status == TextToSpeech.SUCCESS) {
                    tts?.engines?.map { EngineInfo(it.name, it.label) } ?: emptyList()
                } else emptyList()
                tts?.shutdown()
                if (cont.isActive) cont.resume(engines)
            }
        }
    }

    private suspend fun queryVoicesForEngine(context: Context, enginePackage: String): List<Voice> {
        return suspendCancellableCoroutine { cont ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context, { status ->
                val voices = if (status == TextToSpeech.SUCCESS) {
                    tts?.voices?.toList() ?: emptyList()
                } else emptyList()
                tts?.shutdown()
                if (cont.isActive) cont.resume(voices)
            }, enginePackage)
        }
    }

    fun distinctLanguages(voices: List<VoiceOption>): List<Locale> {
        return voices.map { it.voice.locale }
            .distinctBy { it.language }
            .sortedBy { it.displayLanguage }
    }
}
