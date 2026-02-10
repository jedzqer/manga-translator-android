package com.manga.translate

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.ItemFolderImageBinding
import java.io.File

class FolderImageAdapter(
    private val onSelectionChanged: () -> Unit,
    private val onItemLongPress: (ImageItem) -> Unit,
    private val onItemClick: (ImageItem) -> Unit
) : RecyclerView.Adapter<FolderImageAdapter.ImageViewHolder>() {
    private val items = ArrayList<ImageItem>()
    private val selectedPaths = LinkedHashSet<String>()
    private var selectionMode = false

    fun submit(list: List<ImageItem>) {
        val oldSize = items.size
        items.clear()
        items.addAll(list)
        if (selectionMode) {
            val validPaths = items.map { it.file.absolutePath }.toHashSet()
            selectedPaths.retainAll(validPaths)
        }
        if (oldSize > 0) {
            notifyItemRangeRemoved(0, oldSize)
        }
        if (items.isNotEmpty()) {
            notifyItemRangeInserted(0, items.size)
        }
    }

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!selectionMode) {
            selectedPaths.clear()
        }
        if (items.isNotEmpty()) {
            notifyItemRangeChanged(0, items.size)
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
        val index = items.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    fun selectAll() {
        selectedPaths.clear()
        for (item in items) {
            selectedPaths.add(item.file.absolutePath)
        }
        if (items.isNotEmpty()) {
            notifyItemRangeChanged(0, items.size)
        }
    }

    fun clearSelection() {
        selectedPaths.clear()
        if (items.isNotEmpty()) {
            notifyItemRangeChanged(0, items.size)
        }
    }

    fun getSelectedFiles(): List<File> {
        return items.filter { selectedPaths.contains(it.file.absolutePath) }.map { it.file }
    }

    fun selectedCount(): Int = selectedPaths.size

    fun areAllSelected(): Boolean {
        return items.isNotEmpty() && selectedPaths.size == items.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemFolderImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ImageViewHolder(binding, ::onToggleSelection, onItemLongPress, onItemClick)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, selectionMode, selectedPaths.contains(item.file.absolutePath))
    }

    override fun getItemCount(): Int = items.size

    class ImageViewHolder(
        private val binding: ItemFolderImageBinding,
        private val onToggleSelection: (ImageItem) -> Unit,
        private val onItemLongPress: (ImageItem) -> Unit,
        private val onItemClick: (ImageItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ImageItem, selectionMode: Boolean, selected: Boolean) {
            binding.imageName.text = item.file.name
            val statusRes = if (item.embedded) {
                R.string.image_embedded
            } else if (item.translated) {
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
}
