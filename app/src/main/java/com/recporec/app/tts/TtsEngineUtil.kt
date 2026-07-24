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
            val qualityTag = when (voice.quality) {
                android.speech.tts.Voice.QUALITY_VERY_HIGH -> " • vrlo visok kvalitet"
                android.speech.tts.Voice.QUALITY_HIGH -> " • visok kvalitet"
                android.speech.tts.Voice.QUALITY_LOW -> " • nizak kvalitet"
                android.speech.tts.Voice.QUALITY_VERY_LOW -> " • vrlo nizak kvalitet"
                else -> ""
            }
            val networkTag = if (voice.isNetworkConnectionRequired) " • zahteva internet" else ""
            return "$displayLanguage$country ($engineLabel)$qualityTag$networkTag"
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

    /** Ako više glasova ima potpuno isti prikazani naziv, doda kratak stabilan kod iz internog imena glasa
     * (umesto proizvoljnog brojanja koje bi se moglo promeniti od sesije do sesije). */
    fun disambiguatedLabels(voices: List<VoiceOption>): List<String> {
        val counts = mutableMapOf<String, Int>()
        voices.forEach { counts[it.displayLabel] = (counts[it.displayLabel] ?: 0) + 1 }
        return voices.map { voice ->
            val base = voice.displayLabel
            if ((counts[base] ?: 0) > 1) {
                "$base — kod ${shortVoiceCode(voice.voice.name)}"
            } else base
        }
    }

    private fun shortVoiceCode(name: String): String {
        val afterX = name.substringAfter("-x-", "")
        val code = if (afterX.isNotEmpty()) afterX.substringBefore("-") else name.substringAfterLast("-")
        return code.ifBlank { name.takeLast(6) }
    }

    /** Izgovara kratak primer datim glasom, da bi korisnik čuo razliku umesto da nagađa iz naziva. */
    fun previewVoice(context: Context, option: VoiceOption) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.voice = option.voice
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { tts?.shutdown() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { tts?.shutdown() }
                })
                tts?.speak("Ovo je primer ovog glasa.", TextToSpeech.QUEUE_FLUSH, null, "preview")
            } else {
                tts?.shutdown()
            }
        }, option.enginePackage)
    }
}
