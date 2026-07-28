package com.recporec.app.ui

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.recporec.app.R

/**
 * Prikazuje dijalog sa pretragom i dvostepenom potvrdom (izbor pa potvrda),
 * da bi slep korisnik imao jasnu povratnu informaciju šta je izabrano
 * pre nego što se izbor primeni.
 */
object PickerDialog {

    fun show(
        context: Context,
        title: String,
        items: List<String>,
        currentLabel: String?,
        onSelectionPreview: ((Int) -> Unit)? = null,
        autoConfirm: Boolean = false,
        onConfirmed: (Int) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_picker, null)
        val editSearch = view.findViewById<EditText>(R.id.editSearch)
        val listView = view.findViewById<ListView>(R.id.listChoices)
        val textStatus = view.findViewById<TextView>(R.id.textConfirmStatus)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirm)

        // originalni indeksi za mapiranje filtrirane liste nazad na pravi izbor
        var visibleIndices = items.indices.toList()
        var selectedOriginalIndex: Int? = null

        textStatus.text = if (currentLabel != null) "Trenutno: $currentLabel" else "Nije izabrano"
        if (autoConfirm) {
            btnConfirm.visibility = android.view.View.GONE
        }

        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_single_choice, items.toMutableList())
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE

        fun applyFilter(query: String) {
            val filtered = items.withIndex().filter { (_, label) ->
                label.contains(query, ignoreCase = true)
            }
            visibleIndices = filtered.map { it.index }
            adapter.clear()
            adapter.addAll(filtered.map { it.value })
            adapter.notifyDataSetChanged()
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val idx = visibleIndices.getOrNull(position) ?: return@setOnItemClickListener
            selectedOriginalIndex = idx
            selectedOriginalIndex?.let { onSelectionPreview?.invoke(it) }
            if (autoConfirm) {
                // Dodir odmah bira i potvrđuje - jedan korak umesto dva.
                onConfirmed(idx)
                dialog.dismiss()
            } else {
                textStatus.text = "Izabrano: ${items[idx]} — dodirni Potvrdi da sačuvaš (nije potvrđeno)"
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val idx = selectedOriginalIndex
            if (idx == null) {
                textStatus.text = "Prvo izaberi stavku sa liste, pa potvrdi"
            } else {
                textStatus.text = "Potvrđeno: ${items[idx]}"
                onConfirmed(idx)
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
