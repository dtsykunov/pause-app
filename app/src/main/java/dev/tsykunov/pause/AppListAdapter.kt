package dev.tsykunov.pause

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.tsykunov.pause.databinding.ItemAppBinding

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    var blocked: Boolean,
)

class AppListAdapter(
    private val items: List<AppEntry>,
    private val onToggle: (AppEntry, Boolean) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    inner class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.appIcon.setImageDrawable(item.icon)
        holder.binding.appLabel.text = item.label
        holder.binding.appSwitch.isChecked = item.blocked
        holder.binding.root.setOnClickListener {
            val newValue = !item.blocked
            item.blocked = newValue
            holder.binding.appSwitch.isChecked = newValue
            onToggle(item, newValue)
        }
    }

    override fun getItemCount(): Int = items.size
}
