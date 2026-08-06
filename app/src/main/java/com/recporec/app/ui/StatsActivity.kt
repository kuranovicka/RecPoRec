package com.recporec.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.recporec.app.data.AppDatabase
import com.recporec.app.databinding.ActivityStatsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Statistika čitanja - potpuno "read-only" ekran (samo čita već postojeće podatke iz baze,
 * ništa ne piše niti menja) - koliko je ukupno slušano, koliko knjiga započeto/pročitano.
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnBack.setOnClickListener { finish() }

        loadStats()
    }

    private fun loadStats() {
        val db = AppDatabase.getInstance(applicationContext)
        lifecycleScope.launch {
            val docs = withContext(Dispatchers.IO) { db.documentDao().observeAllOnce() }
            val totalSeconds = docs.sumOf { it.elapsedSeconds }
            val totalBooks = docs.size
            val finished = docs.count { it.totalCharacters > 0 && it.currentCharacterOffset >= it.totalCharacters }
            val started = docs.count {
                it.totalCharacters > 0 && it.currentCharacterOffset in 1 until it.totalCharacters
            }
            val notStarted = totalBooks - finished - started

            val lines = StringBuilder()
            lines.append("Ukupno vreme slušanja: ${formatDuration(totalSeconds)}.\n\n")
            lines.append("Ukupno dokumenata u listi: $totalBooks.\n")
            lines.append("Pročitano do kraja: $finished.\n")
            lines.append("Započeto, u toku: $started.\n")
            lines.append("Nije ni započeto: $notStarted.\n")
            if (docs.isNotEmpty()) {
                val longest = docs.maxByOrNull { it.elapsedSeconds }
                if (longest != null && longest.elapsedSeconds > 0) {
                    lines.append("\nNajviše vremena provedeno na: \"${longest.title}\" (${formatDuration(longest.elapsedSeconds)}).")
                }
            }
            binding.textStatsBody.text = lines.toString()
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val totalMinutes = totalSeconds / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val hourWord = serbianPlural(hours.toInt(), "sat", "sata", "sati")
        val minuteWord = serbianPlural(minutes.toInt(), "minut", "minuta", "minuta")
        return if (hours > 0) "$hours $hourWord i $minutes $minuteWord" else "$minutes $minuteWord"
    }

    private fun serbianPlural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
