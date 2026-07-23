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
        }
        binding.switchShake.setOnCheckedChangeListener { _, checked ->
            settings.shakeEnabled = checked
        }
    }
}
