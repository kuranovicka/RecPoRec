package com.recporec.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.recporec.app.data.PronunciationEntity
import com.recporec.app.databinding.ItemPronunciationBinding

/** Lista korisnikovog rečnika izgovora - isti obrazac kao DocumentListAdapter (dodir =
 * otvori/izmeni, dug pritisak = radnje), samo bez rezima za višestruko biranje jer ovde
 * nije bilo potrebe za tim. */
class PronunciationAdapter(
    private val onLongPress: (PronunciationEntity) -> Unit
) : ListAdapter<PronunciationEntity, PronunciationAdapter.VH>(DIFF_CALLBACK) {

    inner class VH(val binding: ItemPronunciationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPronunciationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val text = "${entry.originalWord} → ${entry.replacement}"
        holder.binding.textEntry.text = text
        holder.binding.textEntry.contentDescription = "${entry.originalWord}, izgovara se kao ${entry.replacement}"
        holder.binding.root.setOnLongClickListener {
            onLongPress(entry)
            true
        }
        // Dodir isto otvara radnje kao dug pritisak - jednostavnije za TalkBack (nema dva
        // razlicita nacina da se dodje do iste akcije, jedan je dovoljan i jasan).
        holder.binding.root.setOnClickListener {
            onLongPress(entry)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PronunciationEntity>() {
            override fun areItemsTheSame(oldItem: PronunciationEntity, newItem: PronunciationEntity): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: PronunciationEntity, newItem: PronunciationEntity): Boolean =
                oldItem.originalWord == newItem.originalWord && oldItem.replacement == newItem.replacement
        }
    }
}
