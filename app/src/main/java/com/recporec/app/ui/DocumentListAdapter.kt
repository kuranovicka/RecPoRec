package com.recporec.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.recporec.app.data.DocumentEntity
import com.recporec.app.databinding.ItemDocumentBinding

class DocumentListAdapter(
    private val onOpen: (DocumentEntity) -> Unit,
    private val onLongPress: (DocumentEntity) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<DocumentEntity, DocumentListAdapter.VH>(DIFF_CALLBACK) {

    private var selectionMode = false
    private val selectedIds = mutableSetOf<Long>()

    override fun submitList(list: List<DocumentEntity>?) {
        // Ako je nesto obrisano dok je bilo odabrano, ukloni ga i iz odabira.
        selectedIds.retainAll((list ?: emptyList()).map { it.id }.toSet())
        super.submitList(list)
    }

    /** "Odaberi sve" - ulazi u rezim biranja sa SVIM dokumentima odmah odabranim (korisnica
     * onda rucno iskljuci one koje NE zeli da obrise). Eksplicitna, korisnicka akcija - puno
     * osvezavanje ovde je ocekivano i bezbedno (ne desava se samo od sebe u pozadini). */
    fun selectAll() {
        selectionMode = true
        selectedIds.clear()
        selectedIds.addAll(currentList.map { it.id })
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selectedIds.size)
    }

    /** Izlazi iz rezima biranja, bez brisanja. */
    fun cancelSelection() {
        selectionMode = false
        selectedIds.clear()
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(0)
    }

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    fun isSelectionMode(): Boolean = selectionMode

    inner class VH(val binding: ItemDocumentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val doc = getItem(position)
        holder.binding.textTitle.text = "${doc.title}.${doc.format}"
        holder.binding.checkboxSelect.visibility = if (selectionMode) android.view.View.VISIBLE else android.view.View.GONE
        holder.binding.checkboxSelect.setOnCheckedChangeListener(null)
        holder.binding.checkboxSelect.isChecked = selectedIds.contains(doc.id)
        holder.binding.checkboxSelect.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedIds.add(doc.id) else selectedIds.remove(doc.id)
            onSelectionChanged(selectedIds.size)
        }
        holder.binding.root.setOnClickListener {
            if (selectionMode) {
                holder.binding.checkboxSelect.isChecked = !holder.binding.checkboxSelect.isChecked
            } else {
                onOpen(doc)
            }
        }
        holder.binding.root.setOnLongClickListener {
            if (!selectionMode) onLongPress(doc)
            true
        }
    }

    companion object {
        // KRITICNO za pristupacnost: poredi SAMO ono sto se stvarno prikazuje u redu (naslov,
        // format) - NE ceo DocumentEntity. Dok se dokument cita, pozicija i proteklo vreme se
        // cuvaju u bazu na svakih par sekundi - to menja entitet, ali se NIGDE ne prikazuje u
        // ovoj listi. Sa punim poredjenjem (ili sa starim notifyDataSetChanged() pristupom) bi
        // se ceo red i dalje smatrao "izmenjenim" svakih par sekundi, sto je TalkBack fokus
        // vracalo na pocetak liste tokom aktivnog citanja (prijavljeno od korisnika).
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DocumentEntity>() {
            override fun areItemsTheSame(oldItem: DocumentEntity, newItem: DocumentEntity): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DocumentEntity, newItem: DocumentEntity): Boolean =
                oldItem.title == newItem.title && oldItem.format == newItem.format
        }
    }
}
