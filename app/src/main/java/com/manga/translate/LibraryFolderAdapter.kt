package com.manga.translate

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.ItemFolderBinding

class LibraryFolderAdapter(
    private val onClick: (FolderItem) -> Unit,
    private val onDelete: (FolderItem) -> Unit,
    private val onRename: (FolderItem) -> Unit
) : RecyclerView.Adapter<LibraryFolderAdapter.FolderViewHolder>() {
    private val items = ArrayList<FolderItem>()
    private var actionPosition: Int? = null

    fun submit(list: List<FolderItem>) {
        val oldSize = items.size
        items.clear()
        items.addAll(list)
        if (oldSize > 0) {
            notifyItemRangeRemoved(0, oldSize)
        }
        if (items.isNotEmpty()) {
            notifyItemRangeInserted(0, items.size)
        }
    }

    fun clearActionSelection() {
        val previous = actionPosition
        actionPosition = null
        if (previous != null) {
            notifyItemChanged(previous)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding, onClick, onDelete, onRename, ::toggleActionPosition)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(items[position], position == actionPosition)
    }

    override fun getItemCount(): Int = items.size

    class FolderViewHolder(
        private val binding: ItemFolderBinding,
        private val onClick: (FolderItem) -> Unit,
        private val onDelete: (FolderItem) -> Unit,
        private val onRename: (FolderItem) -> Unit,
        private val onToggleAction: (Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FolderItem, showActions: Boolean) {
            binding.folderName.text = item.folder.name
            binding.folderMeta.text = binding.root.context.getString(
                R.string.folder_image_count,
                item.imageCount
            )
            binding.folderActions.visibility = if (showActions) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onToggleAction(bindingAdapterPosition)
                true
            }
            binding.folderDelete.setOnClickListener { onDelete(item) }
            binding.folderRename.setOnClickListener { onRename(item) }
        }
    }

    private fun toggleActionPosition(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        val previous = actionPosition
        actionPosition = if (previous == position) null else position
        if (previous != null) {
            notifyItemChanged(previous)
        }
        notifyItemChanged(position)
    }
}
