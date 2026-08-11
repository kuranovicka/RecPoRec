package com.recporec.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.recporec.app.data.AppDatabase
import com.recporec.app.data.PronunciationEntity
import com.recporec.app.databinding.ActivityPronunciationBinding
import kotlinx.coroutines.launch

/** Ekran "Rečnik izgovora" - korisnikova SOPSTVENA lista zamena (odvojeno od ugrađenog
 * rečnika koji je resurs u aplikaciji i ne dira se odavde). Dodir ili dug pritisak na
 * unos otvara Izmeni/Obriši - isti obrazac kao svuda drugde u aplikaciji. */
class PronunciationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPronunciationBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private lateinit var adapter: PronunciationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPronunciationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = PronunciationAdapter(onLongPress = { showEntryActionsDialog(it) })
        binding.recyclerPronunciation.layoutManager = LinearLayoutManager(this)
        binding.recyclerPronunciation.adapter = adapter

        binding.btnAddEntry.setOnClickListener { showAddOrEditDialog(existing = null) }
        binding.btnBack.setOnClickListener { finish() }

        refreshList()
    }

    private fun refreshList() {
        lifecycleScope.launch {
            val entries = db.pronunciationDao().getAll()
            adapter.submitList(entries)
        }
    }

    /** Dva polja jedno ispod drugog (originalna reč / zamena), isti "skraćeni dijalog"
     * princip kao svuda - samo polja i Otkaži, tastatura potvrđuje. */
    private fun showAddOrEditDialog(existing: PronunciationEntity?) {
        val inputWord = EditText(this).apply {
            setText(existing?.originalWord.orEmpty())
            hint = "Originalna reč (npr. John)"
        }
        val inputReplacement = EditText(this).apply {
            setText(existing?.replacement.orEmpty())
            hint = "Kako da se izgovori (npr. Džon)"
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(inputWord)
            addView(inputReplacement)
        }

        fun confirm() {
            val word = inputWord.text?.toString()?.trim().orEmpty()
            val replacement = inputReplacement.text?.toString()?.trim().orEmpty()
            if (word.isEmpty() || replacement.isEmpty()) {
                Toast.makeText(this, "Oba polja moraju biti popunjena.", Toast.LENGTH_SHORT).show()
                return
            }
            lifecycleScope.launch {
                // Ista reč (bez razlike velikih/malih slova) se zamenjuje, ne dodaje duplo -
                // dogovoreno pravilo: poslednji unos pobeđuje, bez upozorenja. Poređenje se
                // radi ovde (ne u SQL upitu) da se izbegne COLLATE u @Query, koji zna da
                // zbuni Room-ov prevodilac upita u vreme kompajliranja.
                val all = db.pronunciationDao().getAll()
                val toRemove = all.filter { it.originalWord.equals(word, ignoreCase = true) || it.id == existing?.id }
                toRemove.forEach { db.pronunciationDao().deleteById(it.id) }
                db.pronunciationDao().insert(PronunciationEntity(originalWord = word, replacement = replacement))
                refreshList()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setNegativeButton(com.recporec.app.R.string.cancel, null)
            .create()
        inputReplacement.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                confirm()
                dialog.dismiss()
                true
            } else false
        }
        dialog.show()
    }

    private fun showEntryActionsDialog(entry: PronunciationEntity) {
        val options = arrayOf("Izmeni", "Obriši")
        AlertDialog.Builder(this)
            .setTitle("${entry.originalWord} → ${entry.replacement}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddOrEditDialog(existing = entry)
                    1 -> lifecycleScope.launch {
                        db.pronunciationDao().deleteById(entry.id)
                        refreshList()
                    }
                }
            }
            .setNegativeButton(com.recporec.app.R.string.cancel, null)
            .show()
    }
}
