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
        binding.btnCombinedVoices.setOnClickListener {
            startActivity(
                android.content.Intent(this, CombinedVoicesActivity::class.java)
                    .putExtra(CombinedVoicesActivity.EXTRA_SCOPE_ID, 0L)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_LANGUAGE_TAG, settings.globalLanguageTag)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_VOICE_NAME, settings.globalVoiceName)
                    .putExtra(CombinedVoicesActivity.EXTRA_DEFAULT_VOICE_ENGINE, settings.globalVoiceEngine)
            )
        }

        // Brzina: klizač 0..270 predstavlja stopu 0.30x .. 3.00x (korak 0.01)
        binding.seekSpeed.max = 270
        binding.seekSpeed.progress = ((settings.globalSpeechRate * 100).roundToInt() - 30).coerceIn(0, 270)
        binding.seekSpeed.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings.globalSpeechRate = (progress + 30) / 100f
                refreshStatusTexts()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.seekVolume.max = 100
        binding.seekVolume.progress = settings.globalVolumePercent
        binding.seekVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings.globalVolumePercent = progress
                refreshStatusTexts()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.btnSpeedDown.setOnClickListener { changeSpeed(-0.05f) }
        binding.btnSpeedUp.setOnClickListener { changeSpeed(0.05f) }
        binding.btnVolumeDown.setOnClickListener { changeVolume(-5) }
        binding.btnVolumeUp.setOnClickListener { changeVolume(5) }
    }

    private fun changeSpeed(delta: Float) {
        val newRate = (settings.globalSpeechRate + delta).coerceIn(0.3f, 3.0f)
        settings.globalSpeechRate = newRate
        binding.seekSpeed.progress = ((newRate * 100).roundToInt() - 30).coerceIn(0, 270)
        refreshStatusTexts()
    }

    private fun changeVolume(deltaPercent: Int) {
        val newVol = (settings.globalVolumePercent + deltaPercent).coerceIn(0, 100)
        settings.globalVolumePercent = newVol
        binding.seekVolume.progress = newVol
        refreshStatusTexts()
    }

    private fun loadVoices() {
        lifecycleScope.launch {
            allVoices = try {
                TtsEngineUtil.listAllVoices(this@GlobalVoiceSettingsActivity)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun showLanguagePicker() {
        val voices = allVoices.ifEmpty {
            binding.textLanguageStatus.text = "Učitavanje glasova, sačekaj trenutak i probaj ponovo"
            return
        }
        val languages = TtsEngineUtil.distinctLanguages(voices)
        val labels = languages.map { it.displayLanguage.replaceFirstChar { c -> c.uppercase() } }
        val current = settings.globalLanguageTag?.let { code ->
            languages.firstOrNull { it.language == code }?.displayLanguage
        }
        PickerDialog.show(this, "Izaberi jezik", labels, current, autoConfirm = true) { index ->
            val chosen = languages[index]
            settings.globalLanguageTag = chosen.language
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
            voices.filter { it.voice.locale.language == languageFilter }.ifEmpty { voices }
        } else voices

        val labels = TtsEngineUtil.disambiguatedLabels(filtered)
        val current = settings.globalVoiceName?.let { name ->
            filtered.firstOrNull { it.voice.name == name }?.displayLabel
        }
        PickerDialog.show(
            this, "Izaberi glas", labels, current,
            onSelectionPreview = { index -> TtsEngineUtil.previewVoice(this, filtered[index]) },
            autoConfirm = true
        ) { index ->
            val chosen = filtered[index]
            settings.globalVoiceName = chosen.voice.name
            settings.globalVoiceEngine = chosen.enginePackage
            refreshStatusTexts()
        }
    }

    private fun refreshStatusTexts() {
        val langTag = settings.globalLanguageTag
        binding.textLanguageStatus.text = if (langTag != null) {
            "Potvrđeno: ${Locale(langTag).displayLanguage.replaceFirstChar { it.uppercase() }}"
        } else "Nije izabrano (koristi se podrazumevani jezik telefona)"

        val voiceName = settings.globalVoiceName
        binding.textVoiceStatus.text = if (voiceName != null) {
            "Potvrđeno: ${allVoices.firstOrNull { it.voice.name == voiceName }?.displayLabel ?: voiceName}"
        } else "Nije izabran (koristi se podrazumevani glas)"

        binding.textSpeedStatus.text = "Brzina: ${(settings.globalSpeechRate * 100).roundToInt()}%"
        binding.textVolumeStatus.text = "Jačina: ${settings.globalVolumePercent}%"
    }
}
