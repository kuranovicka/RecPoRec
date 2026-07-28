package com.recporec.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.recporec.app.data.AppSettings
import com.recporec.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { AppSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.switchBackground.isChecked = settings.backgroundEnabled
        binding.switchUninterrupted.isChecked = settings.uninterruptedEnabled
        binding.switchShake.isChecked = settings.shakeEnabled
        binding.switchSound.isChecked = settings.soundFeedbackEnabled
        binding.switchSentencePause.isChecked = settings.sentencePauseEnabled
        binding.switchAutoNext.isChecked = settings.autoNextDocumentEnabled

        binding.groupSentencePauseMs.visibility =
            if (settings.sentencePauseEnabled) android.view.View.VISIBLE else android.view.View.GONE
        if (settings.sentencePauseMs == 500) {
            binding.radioPause500.isChecked = true
        } else {
            binding.radioPause300.isChecked = true
        }

        binding.switchBackground.setOnCheckedChangeListener { _, checked ->
            settings.backgroundEnabled = checked
            if (!checked) binding.switchUninterrupted.isChecked = false
        }
        binding.switchUninterrupted.setOnCheckedChangeListener { _, checked ->
            if (checked && !settings.backgroundEnabled) {
                binding.switchBackground.isChecked = true
                settings.backgroundEnabled = true
            }
            settings.uninterruptedEnabled = checked
            if (checked) requestIgnoreBatteryOptimizations()
        }
        binding.switchShake.setOnCheckedChangeListener { _, checked ->
            settings.shakeEnabled = checked
        }
        binding.switchSound.setOnCheckedChangeListener { _, checked ->
            settings.soundFeedbackEnabled = checked
        }
        binding.switchSentencePause.setOnCheckedChangeListener { _, checked ->
            settings.sentencePauseEnabled = checked
            binding.groupSentencePauseMs.visibility =
                if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }
        binding.groupSentencePauseMs.setOnCheckedChangeListener { _, checkedId ->
            settings.sentencePauseMs = if (checkedId == binding.radioPause500.id) 500 else 300
        }
        binding.switchAutoNext.setOnCheckedChangeListener { _, checked ->
            settings.autoNextDocumentEnabled = checked
        }

        val navLabels = listOf("Stranica", "1 minut", "5 minuta", "10 minuta")
        val navValues = listOf("page", "min1", "min5", "min10")
        fun refreshNavButton() {
            val idx = navValues.indexOf(settings.navigationMode).coerceAtLeast(0)
            binding.btnNavigationMode.text = navLabels[idx]
        }
        refreshNavButton()
        binding.btnNavigationMode.setOnClickListener {
            val currentLabel = navLabels[navValues.indexOf(settings.navigationMode).coerceAtLeast(0)]
            PickerDialog.show(this, "Izaberi način navigacije", navLabels, currentLabel) { index ->
                settings.navigationMode = navValues[index]
                refreshNavButton()
            }
        }
    }

    /** Traži od sistema da ne ograničava aplikaciju radi štednje baterije, da bi čitanje
     * moglo pouzdano da nastavi i kada se ekran zaključa. */
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                android.widget.Toast.makeText(
                    this,
                    "Na sledećem ekranu izaberi \"Dozvoli\" ili \"Bez ograničenja\", da bi čitanje pouzdano radilo i kad je ekran zaključan.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } catch (_: Exception) {
            // Neki telefoni (npr. pojedini Xiaomi, Huawei modeli) imaju svoja dodatna
            // podešavanja štednje baterije van standardnog Android sistema, koja aplikacija
            // ne može programski da otvori - tada korisnik mora ručno da ih pronađe u
            // podešavanjima telefona (obično "Baterija" -> ime aplikacije -> "Bez ograničenja").
        }
    }
}
