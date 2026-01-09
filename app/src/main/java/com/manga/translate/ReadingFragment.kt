package com.manga.translate

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.manga.translate.databinding.FragmentReadingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadingFragment : Fragment() {
    private var _binding: FragmentReadingBinding? = null
    private val binding get() = _binding!!
    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()
    private val translationStore = TranslationStore()
    private var currentImageFile: java.io.File? = null
    private var currentTranslation: TranslationResult? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.translationOverlay.onTap = { x ->
            handleTap(x)
        }
        readingSessionViewModel.images.observe(viewLifecycleOwner) {
            loadCurrentImage()
        }
        readingSessionViewModel.index.observe(viewLifecycleOwner) {
            loadCurrentImage()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadCurrentImage() {
        val images = readingSessionViewModel.images.value.orEmpty()
        val folder = readingSessionViewModel.currentFolder.value
        if (images.isEmpty() || folder == null) {
            binding.readingEmptyHint.visibility = View.VISIBLE
            binding.readingPageInfo.visibility = View.GONE
            binding.translationOverlay.visibility = View.GONE
            binding.readingImage.setImageDrawable(null)
            return
        }
        val index = (readingSessionViewModel.index.value ?: 0).coerceIn(0, images.lastIndex)
        val imageFile = images[index]
        currentImageFile = imageFile
        binding.readingEmptyHint.visibility = View.GONE
        binding.readingPageInfo.visibility = View.VISIBLE
        binding.readingPageInfo.text = getString(
            R.string.reading_page_info,
            folder.name,
            index + 1,
            images.size
        )
        val targetPath = imageFile.absolutePath
        val targetIndex = index
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = loadBitmap(imageFile.absolutePath)
            val translation = withContext(Dispatchers.IO) {
                translationStore.load(imageFile)
            }
            val currentImages = readingSessionViewModel.images.value.orEmpty()
            val currentIndex = readingSessionViewModel.index.value ?: 0
            if (currentIndex != targetIndex || currentImages.getOrNull(currentIndex)?.absolutePath != targetPath) {
                return@launch
            }
            if (bitmap != null) {
                binding.readingImage.setImageBitmap(bitmap)
            } else {
                binding.readingImage.setImageDrawable(null)
            }
            binding.readingImage.post {
                updateOverlay(translation, bitmap)
            }
        }
    }

    private fun updateOverlay(translation: TranslationResult?, bitmap: Bitmap?) {
        val rect = computeImageDisplayRect() ?: run {
            binding.translationOverlay.visibility = View.GONE
            return
        }
        val width = translation?.width ?: bitmap?.width ?: 0
        val height = translation?.height ?: bitmap?.height ?: 0
        if (width <= 0 || height <= 0) {
            binding.translationOverlay.visibility = View.GONE
            return
        }
        val normalized = when {
            translation == null -> TranslationResult("", width, height, emptyList())
            translation.width == width && translation.height == height -> translation
            else -> translation.copy(width = width, height = height)
        }
        currentTranslation = normalized
        binding.translationOverlay.setDisplayRect(rect)
        binding.translationOverlay.setTranslations(normalized)
        binding.translationOverlay.setOffsets(emptyMap())
        binding.translationOverlay.visibility = View.VISIBLE
    }

    private fun computeImageDisplayRect(): RectF? {
        val drawable = binding.readingImage.drawable ?: return null
        val rect = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )
        binding.readingImage.imageMatrix.mapRect(rect)
        rect.offset(binding.readingImage.left.toFloat(), binding.readingImage.top.toFloat())
        return rect
    }

    private suspend fun loadBitmap(path: String): Bitmap? = withContext(Dispatchers.IO) {
        android.graphics.BitmapFactory.decodeFile(path)
    }

    private fun handleTap(x: Float) {
        val width = binding.readingRoot.width
        if (width <= 0) return
        val ratio = x / width
        when {
            ratio < 0.33f -> {
                persistCurrentTranslation()
                readingSessionViewModel.prev()
            }
            ratio > 0.67f -> {
                persistCurrentTranslation()
                readingSessionViewModel.next()
            }
        }
    }

    private fun persistCurrentTranslation() {
        val imageFile = currentImageFile ?: return
        val translation = currentTranslation ?: return
        val offsets = binding.translationOverlay.getOffsets()
        if (offsets.isEmpty()) return
        val updatedBubbles = translation.bubbles.map { bubble ->
            val offset = offsets[bubble.id] ?: (0f to 0f)
            bubble.copy(
                rect = RectF(
                    bubble.rect.left + offset.first,
                    bubble.rect.top + offset.second,
                    bubble.rect.right + offset.first,
                    bubble.rect.bottom + offset.second
                )
            )
        }
        val updated = translation.copy(bubbles = updatedBubbles)
        translationStore.save(imageFile, updated)
        currentTranslation = updated
        binding.translationOverlay.setTranslations(updated)
        binding.translationOverlay.setOffsets(emptyMap())
    }
}
