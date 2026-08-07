package com.recporec.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.recporec.app.data.AppDatabase
import com.recporec.app.databinding.ActivityStatsBinding
import com.recporec.app.util.StatsFormatter
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
            binding.textStatsBody.text = StatsFormatter.buildStatsText(docs)
        }
    }
}
