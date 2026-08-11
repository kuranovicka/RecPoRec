package com.recporec.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.recporec.app.databinding.ItemPronunciationBinding

/** Lista spojenog rečnika (ugrađeni + korisnikov) - dodir na bilo koji unos otvara radnje
 * (za ugrađen: Pusti izgovor / Dodaj svoju zamenu; za korisnikov: Pusti izgovor / Izmeni /
 * Obriši) - isti "dodir umesto dugog pritiska" princip kao u prethodnoj verziji, jednostavnije
 * za TalkBack. */
class PronunciationAdapter(
    private val onTap: (PronunciationListItem) -> Unit
) : ListAdapter<PronunciationListItem, PronunciationAdapter.VH>(DIFF_CALLBACK) {

    inner class VH(val binding: ItemPronunciationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPronunciationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val suffix = if (entry.isBuiltIn) " (ugrađeno)" else ""
        holder.binding.textEntry.text = "${entry.originalWord} → ${entry.replacement}$suffix"
        holder.binding.textEntry.contentDescription =
            if (entry.isBuiltIn) "${entry.originalWord}, izgovara se kao ${entry.replacement}, ugrađeno u aplikaciju"
            else "${entry.originalWord}, izgovara se kao ${entry.replacement}, tvoj unos"
        holder.binding.root.setOnClickListener { onTap(entry) }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PronunciationListItem>() {
            override fun areItemsTheSame(oldItem: PronunciationListItem, newItem: PronunciationListItem): Boolean =
                oldItem.originalWord.lowercase() == newItem.originalWord.lowercase() && oldItem.isBuiltIn == newItem.isBuiltIn

            override fun areContentsTheSame(oldItem: PronunciationListItem, newItem: PronunciationListItem): Boolean =
                oldItem == newItem
        }
    }
}
