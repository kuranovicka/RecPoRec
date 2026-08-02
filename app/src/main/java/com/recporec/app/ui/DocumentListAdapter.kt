package com.recporec.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.recporec.app.data.DocumentEntity
import com.recporec.app.databinding.ItemDocumentBinding

class DocumentListAdapter(
    private val onOpen: (DocumentEntity) -> Unit,
    private val onLongPress: (DocumentEntity) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<DocumentListAdapter.VH>() {

    private val items = mutableListOf<DocumentEntity>()
    private var selectionMode = false
    private val selectedIds = mutableSetOf<Long>()

    fun submitList(newItems: List<DocumentEntity>) {
        items.clear()
        items.addAll(newItems)
        // Ako je nesto obrisano dok je bilo odabrano, ukloni ga i iz odabira.
        selectedIds.retainAll(newItems.map { it.id }.toSet())
        notifyDataSetChanged()
    }

    /** "Odaberi sve" - ulazi u rezim biranja sa SVIM dokumentima odmah odabranim (korisnica
     * onda rucno iskljuci one koje NE zeli da obrise). */
    fun selectAll() {
        selectionMode = true
        selectedIds.clear()
        selectedIds.addAll(items.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    /** Izlazi iz rezima biranja, bez brisanja. */
    fun cancelSelection() {
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
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
        val doc = items[position]
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

    override fun getItemCount(): Int = items.size
}
