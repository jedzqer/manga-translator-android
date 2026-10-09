package com.manga.translate.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.R
import com.manga.translate.databinding.ItemFolderImageBinding
import com.manga.translate.model.ImageItem
import java.io.File

class FolderImageAdapter(
    private val onSelectionChanged: () -> Unit,
    private val onItemLongPress: (ImageItem) -> Unit,
    private val onItemClick: (ImageItem) -> Unit
) : ListAdapter<ImageItem, FolderImageAdapter.ImageViewHolder>(DiffCallback) {
    private val selectedPaths = LinkedHashSet<String>()
    private var selectionMode = false

    fun submit(list: List<ImageItem>, onCommitted: (() -> Unit)? = null) {
        if (selectionMode) {
            val validPaths = list.map { it.file.absolutePath }.toHashSet()
            selectedPaths.retainAll(validPaths)
        }
        submitList(list) { onCommitted?.invoke() }
    }

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!selectionMode) {
            selectedPaths.clear()
        }
        if (currentList.isNotEmpty()) {
            notifyItemRangeChanged(0, currentList.size)
        }
    }

    fun toggleSelection(file: File) {
        val path = file.absolutePath
        if (!selectedPaths.add(path)) {
            selectedPaths.remove(path)
        }
    }

    fun toggleSelectionAndNotify(file: File) {
        toggleSelection(file)
        val index = currentList.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    fun selectAll() {
        selectedPaths.clear()
        for (item in currentList) {
            selectedPaths.add(item.file.absolutePath)
        }
        if (currentList.isNotEmpty()) {
            notifyItemRangeChanged(0, currentList.size)
        }
    }

    fun clearSelection() {
        selectedPaths.clear()
        if (currentList.isNotEmpty()) {
            notifyItemRangeChanged(0, currentList.size)
        }
    }

    fun getSelectedFiles(): List<File> {
        return currentList.filter { selectedPaths.contains(it.file.absolutePath) }.map { it.file }
    }

    fun selectedCount(): Int = selectedPaths.size

    fun areAllSelected(): Boolean {
        return currentList.isNotEmpty() && selectedPaths.size == currentList.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemFolderImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ImageViewHolder(binding, ::onToggleSelection, onItemLongPress, onItemClick)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, selectionMode, selectedPaths.contains(item.file.absolutePath))
    }

    class ImageViewHolder(
        private val binding: ItemFolderImageBinding,
        private val onToggleSelection: (ImageItem) -> Unit,
        private val onItemLongPress: (ImageItem) -> Unit,
        private val onItemClick: (ImageItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ImageItem, selectionMode: Boolean, selected: Boolean) {
            binding.imageName.text = item.file.name
            val statusRes = if (item.translated) {
                R.string.image_translated
            } else {
                R.string.image_not_translated
            }
            binding.imageStatus.setText(statusRes)
            binding.imageCheck.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.imageCheck.setOnCheckedChangeListener(null)
            binding.imageCheck.isChecked = selected
            binding.imageCheck.setOnCheckedChangeListener { _, _ ->
                onToggleSelection(item)
            }
            binding.root.setOnLongClickListener {
                onItemLongPress(item)
                true
            }
            binding.root.setOnClickListener {
                if (selectionMode) {
                    binding.imageCheck.toggle()
                } else {
                    onItemClick(item)
                }
            }
        }
    }

    private fun onToggleSelection(item: ImageItem) {
        toggleSelection(item.file)
        onSelectionChanged()
    }

    private companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<ImageItem>() {
            override fun areItemsTheSame(oldItem: ImageItem, newItem: ImageItem): Boolean {
                return oldItem.file.absolutePath == newItem.file.absolutePath
            }

            override fun areContentsTheSame(oldItem: ImageItem, newItem: ImageItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
