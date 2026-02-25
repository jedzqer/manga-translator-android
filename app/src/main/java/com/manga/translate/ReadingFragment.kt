package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.manga.translate.databinding.FragmentReadingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ReadingFragment : Fragment() {
    private fun formatInt(value: Int): String = String.format(Locale.getDefault(), "%d", value)

    private var _binding: FragmentReadingBinding? = null
    private val binding get() = _binding!!
    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()
    private val translationStore = TranslationStore()
    private lateinit var settingsStore: SettingsStore
    private lateinit var readingProgressStore: ReadingProgressStore
    private var currentImageFile: java.io.File? = null
    private var currentTranslation: TranslationResult? = null
    private var translationWatchJob: Job? = null
    private var currentBitmap: Bitmap? = null
    private lateinit var imageTransformController: ReadingImageTransformController
    private var readingDisplayMode = ReadingDisplayMode.FIT_WIDTH
    private var isEditMode = false
    private var isEmbeddedMode = false
    private var resizeTargetId: Int? = null
    private var resizeBaseRect: RectF? = null
    private var resizeUpdatingWidthInput = false
    private var resizeUpdatingHeightInput = false
    private var resizeUpdatingWidthSlider = false
    private var resizeUpdatingHeightSlider = false
    private var resizeWidthPercent = 100
    private var resizeHeightPercent = 100
    private val resizeMinPercent = 50
    private val resizeMaxPercent = 500
    private val glossaryStore = GlossaryStore()
    private lateinit var emptyBubbleCoordinator: ReadingEmptyBubbleCoordinator
    private var emptyBubbleJob: Job? = null

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
        settingsStore = SettingsStore(requireContext())
        readingProgressStore = ReadingProgressStore(requireContext())
        emptyBubbleCoordinator = ReadingEmptyBubbleCoordinator(
            context = requireContext(),
            translationStore = translationStore,
            glossaryStore = glossaryStore,
            llmClient = LlmClient(requireContext()),
            libraryPrefs = requireContext().getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE)
        )
        readingDisplayMode = settingsStore.loadReadingDisplayMode()
        imageTransformController = ReadingImageTransformController(
            context = requireContext(),
            imageView = binding.readingImage,
            hasBubbleAt = { x, y -> binding.translationOverlay.hasBubbleAt(x, y) },
            onMatrixUpdated = { updateOverlayDisplayRect() }
        )
        binding.translationOverlay.onTap = { x ->
            handleTap(x)
        }
        binding.translationOverlay.onSwipe = { direction ->
            handleSwipe(direction)
        }
        binding.translationOverlay.onTransformTouch = { event ->
            imageTransformController.handleTouch(event)
        }
        binding.translationOverlay.onBubbleRemove = { bubbleId ->
            handleBubbleRemove(bubbleId)
        }
        binding.translationOverlay.onBubbleTap = { bubbleId ->
            handleBubbleEdit(bubbleId)
        }
        binding.translationOverlay.onBubbleResizeTap = { bubbleId ->
            showResizePanel(bubbleId)
        }
        binding.translationOverlay.onBubbleLongPress = { bubbleId ->
            showBubbleActionDialog(bubbleId)
        }
        binding.readingEditButton.setOnClickListener {
            toggleEditMode()
        }
        binding.readingAddButton.setOnClickListener {
            addNewBubble()
        }
        binding.readingResizeWidthSlider.max = resizeMaxPercent - resizeMinPercent
        binding.readingResizeHeightSlider.max = resizeMaxPercent - resizeMinPercent
        binding.readingResizeWidthSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (resizeUpdatingWidthSlider) return
                val percent = (progress + resizeMinPercent).coerceIn(resizeMinPercent, resizeMaxPercent)
                resizeUpdatingWidthInput = true
                binding.readingResizeWidthInput.setText(formatInt(percent))
                binding.readingResizeWidthInput.setSelection(binding.readingResizeWidthInput.text?.length ?: 0)
                resizeUpdatingWidthInput = false
                resizeWidthPercent = percent
                applyResizePercent(resizeWidthPercent, resizeHeightPercent)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                saveCurrentTranslation()
            }
        })
        binding.readingResizeHeightSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (resizeUpdatingHeightSlider) return
                val percent = (progress + resizeMinPercent).coerceIn(resizeMinPercent, resizeMaxPercent)
                resizeUpdatingHeightInput = true
                binding.readingResizeHeightInput.setText(formatInt(percent))
                binding.readingResizeHeightInput.setSelection(binding.readingResizeHeightInput.text?.length ?: 0)
                resizeUpdatingHeightInput = false
                resizeHeightPercent = percent
                applyResizePercent(resizeWidthPercent, resizeHeightPercent)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                saveCurrentTranslation()
            }
        })
        binding.readingResizeWidthInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (resizeUpdatingWidthInput) return
                val raw = s?.toString().orEmpty()
                val value = raw.toIntOrNull() ?: return
                val clamped = value.coerceIn(resizeMinPercent, resizeMaxPercent)
                if (clamped.toString() != raw) {
                    resizeUpdatingWidthInput = true
                    binding.readingResizeWidthInput.setText(formatInt(clamped))
                    binding.readingResizeWidthInput.setSelection(binding.readingResizeWidthInput.text?.length ?: 0)
                    resizeUpdatingWidthInput = false
                }
                val progress = clamped - resizeMinPercent
                resizeUpdatingWidthSlider = true
                binding.readingResizeWidthSlider.progress = progress
                resizeUpdatingWidthSlider = false
                resizeWidthPercent = clamped
                applyResizePercent(resizeWidthPercent, resizeHeightPercent)
                saveCurrentTranslation()
            }
        })
        binding.readingResizeHeightInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (resizeUpdatingHeightInput) return
                val raw = s?.toString().orEmpty()
                val value = raw.toIntOrNull() ?: return
                val clamped = value.coerceIn(resizeMinPercent, resizeMaxPercent)
                if (clamped.toString() != raw) {
                    resizeUpdatingHeightInput = true
                    binding.readingResizeHeightInput.setText(formatInt(clamped))
                    binding.readingResizeHeightInput.setSelection(binding.readingResizeHeightInput.text?.length ?: 0)
                    resizeUpdatingHeightInput = false
                }
                val progress = clamped - resizeMinPercent
                resizeUpdatingHeightSlider = true
                binding.readingResizeHeightSlider.progress = progress
                resizeUpdatingHeightSlider = false
                resizeHeightPercent = clamped
                applyResizePercent(resizeWidthPercent, resizeHeightPercent)
                saveCurrentTranslation()
            }
        })
        binding.readingResizeWidthInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveCurrentTranslation()
            }
        }
        binding.readingResizeHeightInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveCurrentTranslation()
            }
        }
        binding.readingResizeConfirm.setOnClickListener {
            saveCurrentTranslation()
            hideResizePanel()
        }
        updateEditButtonState()
        applyTextLayoutSetting()
        readingSessionViewModel.images.observe(viewLifecycleOwner) {
            loadCurrentImage()
        }
        readingSessionViewModel.index.observe(viewLifecycleOwner) {
            loadCurrentImage()
            persistReadingProgress()
        }
        readingSessionViewModel.isEmbedded.observe(viewLifecycleOwner) { embedded ->
            isEmbeddedMode = embedded
            if (embedded) {
                setEditMode(false)
            }
            loadCurrentImage()
        }
        binding.readingImage.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (currentBitmap == null) return@addOnLayoutChangeListener
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                imageTransformController.reset(currentBitmap!!, readingDisplayMode)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyTextLayoutSetting()
        applyReadingDisplayMode()
        (activity as? MainActivity)?.setPagerSwipeEnabled(false)
    }

    override fun onPause() {
        super.onPause()
        (activity as? MainActivity)?.setPagerSwipeEnabled(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        translationWatchJob?.cancel()
        emptyBubbleJob?.cancel()
        _binding = null
    }

    private fun loadCurrentImage() {
        val images = readingSessionViewModel.images.value.orEmpty()
        val folder = readingSessionViewModel.currentFolder.value
        isEmbeddedMode = readingSessionViewModel.isEmbedded.value == true
        if (images.isEmpty() || folder == null) {
            binding.readingEmptyHint.visibility = View.VISIBLE
            binding.readingPageInfo.visibility = View.GONE
            binding.translationOverlay.visibility = View.GONE
            binding.readingEditControls.visibility = View.GONE
            hideResizePanel()
            binding.readingImage.setImageDrawable(null)
            currentBitmap = null
            imageTransformController.setCurrentBitmap(null)
            return
        }
        val index = (readingSessionViewModel.index.value ?: 0).coerceIn(0, images.lastIndex)
        val imageFile = images[index]
        currentImageFile = imageFile
        binding.readingEmptyHint.visibility = View.GONE
        binding.readingPageInfo.visibility = View.VISIBLE
        binding.readingEditControls.visibility = if (isEmbeddedMode) View.GONE else View.VISIBLE
        if (isEmbeddedMode && isEditMode) {
            setEditMode(false)
        }
        updateEditButtonState()
        hideResizePanel()
        binding.readingPageInfo.text = getString(
            R.string.reading_page_info,
            folder.name,
            index + 1,
            images.size
        )
        val targetPath = imageFile.absolutePath
        val targetIndex = index
        val targetEmbeddedMode = isEmbeddedMode
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = loadBitmap(imageFile.absolutePath)
            val translation = if (targetEmbeddedMode) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    translationStore.load(imageFile)
                }
            }
            val currentImages = readingSessionViewModel.images.value.orEmpty()
            val currentIndex = readingSessionViewModel.index.value ?: 0
            val currentEmbeddedMode = readingSessionViewModel.isEmbedded.value == true
            if (
                currentIndex != targetIndex ||
                currentImages.getOrNull(currentIndex)?.absolutePath != targetPath ||
                currentEmbeddedMode != targetEmbeddedMode
            ) {
                return@launch
            }
            if (bitmap != null) {
                binding.readingImage.setImageBitmap(bitmap)
                currentBitmap = bitmap
                imageTransformController.setCurrentBitmap(bitmap)
            } else {
                binding.readingImage.setImageDrawable(null)
                currentBitmap = null
                imageTransformController.setCurrentBitmap(null)
            }
            binding.readingImage.post {
                if (bitmap != null) {
                    readingDisplayMode = settingsStore.loadReadingDisplayMode()
                    imageTransformController.reset(bitmap, readingDisplayMode)
                }
                updateOverlay(translation, bitmap)
            }
            if (!targetEmbeddedMode && translation == null && bitmap != null) {
                startTranslationWatcher(imageFile)
            } else {
                translationWatchJob?.cancel()
            }
        }
    }

    private fun updateOverlay(translation: TranslationResult?, bitmap: Bitmap?) {
        val rect = imageTransformController.computeImageDisplayRect() ?: run {
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
        binding.translationOverlay.setEditMode(isEditMode && !isEmbeddedMode)
        binding.translationOverlay.visibility = View.VISIBLE
    }

    private fun updateOverlayDisplayRect() {
        if (binding.translationOverlay.visibility != View.VISIBLE) return
        val rect = imageTransformController.computeImageDisplayRect() ?: return
        binding.translationOverlay.setDisplayRect(rect)
    }

    private fun applyReadingDisplayMode() {
        val mode = settingsStore.loadReadingDisplayMode()
        if (mode == readingDisplayMode) return
        readingDisplayMode = mode
        val bitmap = currentBitmap ?: return
        imageTransformController.reset(bitmap, readingDisplayMode)
        updateOverlay(currentTranslation, bitmap)
    }

    private suspend fun loadBitmap(path: String): Bitmap? = withContext(Dispatchers.IO) {
        android.graphics.BitmapFactory.decodeFile(path)
    }

    private fun handleTap(x: Float) {
        if (isEditMode) return
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

    private fun handleSwipe(direction: Int) {
        if (isEditMode) return
        if (direction == 0) return
        persistCurrentTranslation()
        if (direction > 0) {
            readingSessionViewModel.prev()
        } else {
            readingSessionViewModel.next()
        }
    }

    private fun applyTextLayoutSetting() {
        val useHorizontal = settingsStore.loadUseHorizontalText()
        binding.translationOverlay.setVerticalLayoutEnabled(!useHorizontal)
    }

    private fun toggleEditMode() {
        if (isEmbeddedMode) return
        if (isEditMode) {
            persistCurrentTranslation(forceSave = true)
            setEditMode(false)
            processEmptyBubbles()
        } else {
            setEditMode(true)
        }
    }

    private fun setEditMode(enabled: Boolean) {
        val nextEnabled = enabled && !isEmbeddedMode
        if (isEditMode == nextEnabled) return
        isEditMode = nextEnabled
        binding.translationOverlay.setEditMode(nextEnabled)
        if (!nextEnabled) {
            hideResizePanel()
        }
        updateEditButtonState()
    }

    private fun updateEditButtonState() {
        if (isEmbeddedMode) {
            binding.readingEditControls.visibility = View.GONE
            binding.readingAddButton.visibility = View.GONE
            return
        }
        binding.readingEditControls.visibility = View.VISIBLE
        val button = binding.readingEditButton
        if (isEditMode) {
            button.setImageResource(R.drawable.ic_check)
            button.setColorFilter(0xFF22C55E.toInt())
            button.contentDescription = getString(R.string.reading_confirm_edit)
            binding.readingAddButton.visibility = View.VISIBLE
        } else {
            button.setImageResource(android.R.drawable.ic_menu_edit)
            button.setColorFilter(Color.WHITE)
            button.contentDescription = getString(R.string.reading_edit_bubbles)
            binding.readingAddButton.visibility = View.GONE
        }
    }

    private fun startTranslationWatcher(imageFile: java.io.File) {
        if (isEmbeddedMode) return
        translationWatchJob?.cancel()
        translationWatchJob = viewLifecycleOwner.lifecycleScope.launch {
            val jsonFile = translationStore.translationFileFor(imageFile)
            while (isActive) {
                if (currentImageFile?.absolutePath != imageFile.absolutePath) return@launch
                if (jsonFile.exists()) {
                    val translation = withContext(Dispatchers.IO) {
                        translationStore.load(imageFile)
                    }
                    if (currentImageFile?.absolutePath != imageFile.absolutePath) return@launch
                    val bitmap = binding.readingImage.drawable?.let { _ ->
                        loadBitmap(imageFile.absolutePath)
                    }
                    if (bitmap != null) {
                        binding.readingImage.setImageBitmap(bitmap)
                        currentBitmap = bitmap
                        imageTransformController.setCurrentBitmap(bitmap)
                    }
                    binding.readingImage.post {
                        updateOverlay(translation, bitmap)
                    }
                    return@launch
                }
                delay(800)
            }
        }
    }

    private fun persistReadingProgress() {
        val folder = readingSessionViewModel.currentFolder.value ?: return
        val index = readingSessionViewModel.index.value ?: return
        readingProgressStore.save(folder, index)
    }

    private fun persistCurrentTranslation(forceSave: Boolean = false) {
        if (isEmbeddedMode) return
        val imageFile = currentImageFile ?: return
        val translation = currentTranslation ?: return
        val offsets = binding.translationOverlay.getOffsets()
        if (offsets.isEmpty() && !forceSave) return
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

    private fun handleBubbleRemove(bubbleId: Int) {
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        val remaining = translation.bubbles.filterNot { it.id == bubbleId }
        if (remaining.size == translation.bubbles.size) return
        if (resizeTargetId == bubbleId) {
            hideResizePanel()
        }
        currentTranslation = translation.copy(bubbles = remaining)
        val offsets = binding.translationOverlay.getOffsets().toMutableMap()
        offsets.remove(bubbleId)
        binding.translationOverlay.setOffsets(offsets)
        binding.translationOverlay.setTranslations(currentTranslation)
    }

    private fun handleBubbleEdit(bubbleId: Int) {
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        val bubble = translation.bubbles.firstOrNull { it.id == bubbleId } ?: return
        val input = EditText(requireContext()).apply {
            setText(bubble.text)
            setSelection(text?.length ?: 0)
            minLines = 2
            if (bubble.text.isBlank()) {
                hint = getString(R.string.reading_empty_bubble_hint)
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reading_edit_bubble_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updatedText = input.text?.toString().orEmpty()
                persistCurrentTranslation(forceSave = true)
                val refreshed = currentTranslation ?: return@setPositiveButton
                val updatedBubbles = refreshed.bubbles.map { current ->
                    if (current.id == bubbleId) {
                        current.copy(text = updatedText)
                    } else {
                        current
                    }
                }
                val updated = refreshed.copy(bubbles = updatedBubbles)
                currentTranslation = updated
                binding.translationOverlay.setTranslations(updated)
                currentImageFile?.let { imageFile ->
                    translationStore.save(imageFile, updated)
                }
            }
            .show()
    }

    private fun showBubbleActionDialog(bubbleId: Int) {
        if (!isEditMode) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_bubble_actions, null)
        val resizeButton = dialogView.findViewById<android.widget.Button>(R.id.bubbleActionResize)
        val deleteButton = dialogView.findViewById<android.widget.Button>(R.id.bubbleActionDelete)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        resizeButton.setOnClickListener {
            dialog.dismiss()
            showResizePanel(bubbleId)
        }
        deleteButton.setOnClickListener {
            dialog.dismiss()
            handleBubbleRemove(bubbleId)
        }
        dialog.show()
    }

    private fun showResizePanel(bubbleId: Int) {
        if (!isEditMode) return
        persistCurrentTranslation(forceSave = true)
        val translation = currentTranslation ?: return
        val bubble = translation.bubbles.firstOrNull { it.id == bubbleId } ?: return
        resizeTargetId = bubbleId
        resizeBaseRect = RectF(bubble.rect)
        val percent = 100
        resizeWidthPercent = percent
        resizeHeightPercent = percent
        resizeUpdatingWidthSlider = true
        binding.readingResizeWidthSlider.progress = percent - resizeMinPercent
        resizeUpdatingWidthSlider = false
        resizeUpdatingHeightSlider = true
        binding.readingResizeHeightSlider.progress = percent - resizeMinPercent
        resizeUpdatingHeightSlider = false
        resizeUpdatingWidthInput = true
        binding.readingResizeWidthInput.setText(formatInt(percent))
        binding.readingResizeWidthInput.setSelection(binding.readingResizeWidthInput.text?.length ?: 0)
        resizeUpdatingWidthInput = false
        resizeUpdatingHeightInput = true
        binding.readingResizeHeightInput.setText(formatInt(percent))
        binding.readingResizeHeightInput.setSelection(binding.readingResizeHeightInput.text?.length ?: 0)
        resizeUpdatingHeightInput = false
        binding.readingResizePanel.visibility = View.VISIBLE
    }

    private fun hideResizePanel() {
        resizeTargetId = null
        resizeBaseRect = null
        binding.readingResizePanel.visibility = View.GONE
    }

    private fun applyResizePercent(widthPercent: Int?, heightPercent: Int?) {
        val id = resizeTargetId ?: return
        val base = resizeBaseRect ?: return
        val translation = currentTranslation ?: return
        val widthScale = (widthPercent ?: 100) / 100f
        val heightScale = (heightPercent ?: 100) / 100f
        val width = base.width() * widthScale
        val height = base.height() * heightScale
        if (width <= 1f || height <= 1f) return
        val centerX = base.centerX()
        val centerY = base.centerY()
        var left = centerX - width / 2f
        var top = centerY - height / 2f
        var right = centerX + width / 2f
        var bottom = centerY + height / 2f
        val imageWidth = translation.width.toFloat()
        val imageHeight = translation.height.toFloat()
        if (left < 0f) {
            right -= left
            left = 0f
        }
        if (top < 0f) {
            bottom -= top
            top = 0f
        }
        if (right > imageWidth) {
            left -= right - imageWidth
            right = imageWidth
        }
        if (bottom > imageHeight) {
            top -= bottom - imageHeight
            bottom = imageHeight
        }
        val updatedRect = RectF(left, top, right, bottom)
        val updatedBubbles = translation.bubbles.map { bubble ->
            if (bubble.id == id) {
                bubble.copy(rect = updatedRect)
            } else {
                bubble
            }
        }
        val updated = translation.copy(bubbles = updatedBubbles)
        currentTranslation = updated
        binding.translationOverlay.setTranslations(updated)
    }

    private fun saveCurrentTranslation() {
        if (isEmbeddedMode) return
        val imageFile = currentImageFile ?: return
        val translation = currentTranslation ?: return
        translationStore.save(imageFile, translation)
    }

    private fun addNewBubble() {
        if (isEmbeddedMode) return
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        val width = translation.width.toFloat()
        val height = translation.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val baseSize = minOf(width, height) * 0.18f
        val bubbleWidth = baseSize.coerceIn(80f, width * 0.6f)
        val bubbleHeight = (baseSize * 0.7f).coerceIn(60f, height * 0.6f)
        val left = (width - bubbleWidth) / 2f
        val top = (height - bubbleHeight) / 2f
        val rect = RectF(left, top, left + bubbleWidth, top + bubbleHeight)
        val nextId = (translation.bubbles.maxOfOrNull { it.id } ?: -1) + 1
        val newBubble = BubbleTranslation(nextId, rect, "", BubbleSource.MANUAL)
        val updated = translation.copy(bubbles = translation.bubbles + newBubble)
        currentTranslation = updated
        binding.translationOverlay.setTranslations(updated)
        saveCurrentTranslation()
        showResizePanel(nextId)
    }

    private fun processEmptyBubbles() {
        if (isEmbeddedMode) return
        val imageFile = currentImageFile ?: return
        val translation = currentTranslation ?: return
        val folder = readingSessionViewModel.currentFolder.value ?: return
        if (translation.bubbles.none { it.text.isBlank() }) return
        Toast.makeText(requireContext(), R.string.reading_empty_bubble_translating, Toast.LENGTH_SHORT).show()
        emptyBubbleJob?.cancel()
        emptyBubbleJob = viewLifecycleOwner.lifecycleScope.launch {
            val outcome = emptyBubbleCoordinator.process(
                imageFile,
                folder,
                translation
            ) ?: return@launch
            if (currentImageFile?.absolutePath == imageFile.absolutePath) {
                currentTranslation = outcome.updatedTranslation
                binding.translationOverlay.setTranslations(outcome.updatedTranslation)
                if (outcome.translatedByLlm) {
                    Toast.makeText(
                        requireContext(),
                        R.string.reading_empty_bubble_translated,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
