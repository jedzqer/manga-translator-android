package com.manga.translate

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.ItemFolderBinding

class LibraryFolderAdapter(
    private val onClick: (FolderItem) -> Unit
) : RecyclerView.Adapter<LibraryFolderAdapter.FolderViewHolder>() {
    private val items = ArrayList<FolderItem>()

    fun submit(list: List<FolderItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class FolderViewHolder(
        private val binding: ItemFolderBinding,
        private val onClick: (FolderItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FolderItem) {
            binding.folderName.text = item.folder.name
            binding.folderMeta.text = binding.root.context.getString(
                R.string.folder_image_count,
                item.imageCount
            )
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
