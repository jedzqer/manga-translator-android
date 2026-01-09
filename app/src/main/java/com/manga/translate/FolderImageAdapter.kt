package com.manga.translate

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.ItemFolderImageBinding

class FolderImageAdapter : RecyclerView.Adapter<FolderImageAdapter.ImageViewHolder>() {
    private val items = ArrayList<ImageItem>()

    fun submit(list: List<ImageItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemFolderImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ImageViewHolder(
        private val binding: ItemFolderImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ImageItem) {
            binding.imageName.text = item.file.name
            val statusRes = if (item.translated) {
                R.string.image_translated
            } else {
                R.string.image_not_translated
            }
            binding.imageStatus.setText(statusRes)
        }
    }
}
