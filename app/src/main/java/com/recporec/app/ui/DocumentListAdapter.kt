package com.recporec.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.recporec.app.data.DocumentEntity
import com.recporec.app.databinding.ItemDocumentBinding

class DocumentListAdapter(
    private val onOpen: (DocumentEntity) -> Unit,
    private val onDelete: (DocumentEntity) -> Unit
) : RecyclerView.Adapter<DocumentListAdapter.VH>() {

    private val items = mutableListOf<DocumentEntity>()

    fun submitList(newItems: List<DocumentEntity>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemDocumentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val doc = items[position]
        holder.binding.textTitle.text = doc.title
        holder.binding.root.setOnClickListener { onOpen(doc) }
        holder.binding.btnDelete.setOnClickListener { onDelete(doc) }
    }

    override fun getItemCount(): Int = items.size
}
