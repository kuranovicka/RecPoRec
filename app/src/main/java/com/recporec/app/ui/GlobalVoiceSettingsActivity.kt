package com.recporec.app.ui

import android.media.AudioManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.recporec.app.data.AppSettings
import com.recporec.app.databinding.ActivityGlobalVoiceSettingsBinding
import com.recporec.app.tts.TtsEngineUtil
import com.recporec.app.tts.VoiceOption
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class GlobalVoiceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGlobalVoiceSettingsBinding
    private val settings by lazy { AppSettings(this) }
    private var allVoices: List<VoiceOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGlobalVoiceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        refreshStatusTexts()
        loadVoices()

        binding.btnLanguage.setOnClickListener { showLanguagePicker() }
        binding.btnVoice.setOnClickListener { showVoicePicker() }

        binding.btnSpeedDown.setOnClickListener { changeSpeed(-0.05f) }
        binding.btnSpeedUp.setOnClickListener { changeSpeed(0.05f) }
        binding.btnVolumeDown.setOnClickListener { changeVolume(-5) }
        binding.btnVolumeUp.setOnClickListener { changeVolume(5) }
    }

    private fun loadVoices() {
        lifecycleScope.launch {
            allVoices = TtsEngineUtil.listAllVoices(this@GlobalVoiceSettingsActivity)
        }
    }

    private fun showLanguagePicker() {
        val voices = allVoices.ifEmpty {
            binding.textLanguageStatus.text = "Učitavanje glasova, sačekaj trenutak i probaj ponovo"
            return
        }
        val languages = TtsEngineUtil.distinctLanguages(voices)
        val labels = languages.map { "${it.displayLanguage.replaceFirstChar { c -> c.uppercase() }}" +
            if (it.displayCountry.isNotBlank() && it.displayCountry != it.displayLanguage) " — ${it.displayCountry}" else "" }
        val current = settings.globalLanguageTag?.let { tag ->
            languages.firstOrNull { it.toLanguageTag() == tag }?.displayLanguage
        }
        PickerDialog.show(this, "Izaberi jezik", labels, current) { index ->
            val chosen = languages[index]
            settings.globalLanguageTag = chosen.toLanguageTag()
            refreshStatusTexts()
        }
    }

    private fun showVoicePicker() {
        val voices = allVoices.ifEmpty {
            binding.textVoiceStatus.text = "Učitavanje glasova, sačekaj trenutak i probaj ponovo"
            return
        }
        val languageFilter = settings.globalLanguageTag
        val filtered = if (languageFilter != null) {
            voices.filter { it.voice.locale.toLanguageTag() == languageFilter }.ifEmpty { voices }
        } else voices

        val labels = filtered.map { it.displayLabel }
        val current = settings.globalVoiceName?.let { name ->
            filtered.firstOrNull { it.voice.name == name }?.displayLabel
        }
        PickerDialog.show(this, "Izaberi glas", labels, current) { index ->
            val chosen = filtered[index]
            settings.globalVoiceName = chosen.voice.name
            settings.globalVoiceEngine = chosen.enginePackage
            refreshStatusTexts()
        }
    }

    private fun changeSpeed(delta: Float) {
        val newRate = (settings.globalSpeechRate + delta).coerceIn(0.3f, 3.0f)
        settings.globalSpeechRate = newRate
        refreshStatusTexts()
    }

    private fun changeVolume(deltaPercent: Int) {
        val newVol = (settings.globalVolumePercent + deltaPercent).coerceIn(0, 100)
        settings.globalVolumePercent = newVol
        refreshStatusTexts()
    }

    private fun refreshStatusTexts() {
        val langTag = settings.globalLanguageTag
        binding.textLanguageStatus.text = if (langTag != null) {
            "Potvrđeno: ${Locale.forLanguageTag(langTag).displayLanguage.replaceFirstChar { it.uppercase() }}"
        } else "Nije izabrano (koristi se podrazumevani jezik telefona)"

        val voiceName = settings.globalVoiceName
        binding.textVoiceStatus.text = if (voiceName != null) {
            "Potvrđeno: ${allVoices.firstOrNull { it.voice.name == voiceName }?.displayLabel ?: voiceName}"
        } else "Nije izabran (koristi se podrazumevani glas)"

        binding.textSpeedStatus.text = "Brzina: ${(settings.globalSpeechRate * 100).roundToInt()}%"
        binding.textVolumeStatus.text = "Jačina: ${settings.globalVolumePercent}%"
    }
}
