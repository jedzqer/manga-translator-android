package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.EditText
import android.content.Context
import android.content.SharedPreferences
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

class ReadingFragment : Fragment() {
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
    private val baseMatrix = Matrix()
    private val imageMatrix = Matrix()
    private val imageRect = RectF()
    private var imageUserScale = 1f
    private var minScale = 1f
    private var maxScale = 3f
    private var isScaling = false
    private var isPanning = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var panTouchSlop = 0f
    private lateinit var scaleDetector: ScaleGestureDetector
    private var readingDisplayMode = ReadingDisplayMode.FIT_WIDTH
    private var isEditMode = false
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
    private var emptyBubbleJob: Job? = null
    private var mangaOcr: MangaOcr? = null
    private var englishOcr: EnglishOcr? = null
    private var englishLineDetector: EnglishLineDetector? = null
    private lateinit var llmClient: LlmClient
    private lateinit var libraryPrefs: SharedPreferences
    private val languageKeyPrefix = "translation_language_"

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
        llmClient = LlmClient(requireContext())
        libraryPrefs = requireContext().getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
        readingDisplayMode = settingsStore.loadReadingDisplayMode()
        panTouchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop.toFloat()
        binding.readingImage.scaleType = ImageView.ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val bitmap = currentBitmap ?: return false
                val newScale = (imageUserScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = newScale / imageUserScale
                imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                imageUserScale = newScale
                fixTranslation(bitmap)
                applyImageMatrix()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })
        binding.translationOverlay.onTap = { x ->
            handleTap(x)
        }
        binding.translationOverlay.onSwipe = { direction ->
            handleSwipe(direction)
        }
        binding.translationOverlay.onTransformTouch = { event ->
            handleTransformTouch(event)
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
                binding.readingResizeWidthInput.setText(percent.toString())
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
                binding.readingResizeHeightInput.setText(percent.toString())
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
                    binding.readingResizeWidthInput.setText(clamped.toString())
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
                    binding.readingResizeHeightInput.setText(clamped.toString())
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
        binding.readingImage.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (currentBitmap == null) return@addOnLayoutChangeListener
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                resetImageMatrix(currentBitmap!!)
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
        if (images.isEmpty() || folder == null) {
            binding.readingEmptyHint.visibility = View.VISIBLE
            binding.readingPageInfo.visibility = View.GONE
            binding.translationOverlay.visibility = View.GONE
            binding.readingEditControls.visibility = View.GONE
            hideResizePanel()
            binding.readingImage.setImageDrawable(null)
            currentBitmap = null
            return
        }
        val index = (readingSessionViewModel.index.value ?: 0).coerceIn(0, images.lastIndex)
        val imageFile = images[index]
        currentImageFile = imageFile
        binding.readingEmptyHint.visibility = View.GONE
        binding.readingPageInfo.visibility = View.VISIBLE
        binding.readingEditControls.visibility = View.VISIBLE
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
                currentBitmap = bitmap
            } else {
                binding.readingImage.setImageDrawable(null)
                currentBitmap = null
            }
            binding.readingImage.post {
                if (bitmap != null) {
                    readingDisplayMode = settingsStore.loadReadingDisplayMode()
                    resetImageMatrix(bitmap)
                }
                updateOverlay(translation, bitmap)
            }
            if (translation == null && bitmap != null) {
                startTranslationWatcher(imageFile)
            } else {
                translationWatchJob?.cancel()
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
        binding.translationOverlay.setEditMode(isEditMode)
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

    private fun applyImageMatrix() {
        binding.readingImage.imageMatrix = imageMatrix
        updateOverlayDisplayRect()
    }

    private fun updateOverlayDisplayRect() {
        if (binding.translationOverlay.visibility != View.VISIBLE) return
        val rect = computeImageDisplayRect() ?: return
        binding.translationOverlay.setDisplayRect(rect)
    }

    private fun resetImageMatrix(bitmap: Bitmap) {
        val viewWidth = binding.readingImage.width.toFloat()
        val viewHeight = binding.readingImage.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val drawableWidth = bitmap.width.toFloat()
        val drawableHeight = bitmap.height.toFloat()
        val scale = when (readingDisplayMode) {
            ReadingDisplayMode.FIT_WIDTH -> viewWidth / drawableWidth
            ReadingDisplayMode.FIT_HEIGHT -> viewHeight / drawableHeight
        }
        val dx = (viewWidth - drawableWidth * scale) / 2f
        val dy = (viewHeight - drawableHeight * scale) / 2f
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        imageMatrix.set(baseMatrix)
        imageUserScale = 1f
        minScale = 1f
        maxScale = 3f
        applyImageMatrix()
    }

    private fun fixTranslation(bitmap: Bitmap) {
        val viewWidth = binding.readingImage.width.toFloat()
        val viewHeight = binding.readingImage.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        imageRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        imageMatrix.mapRect(imageRect)
        var dx = 0f
        var dy = 0f
        if (imageRect.width() <= viewWidth) {
            dx = (viewWidth - imageRect.width()) / 2f - imageRect.left
        } else {
            if (imageRect.left > 0f) dx = -imageRect.left
            if (imageRect.right < viewWidth) dx = viewWidth - imageRect.right
        }
        if (imageRect.height() <= viewHeight) {
            dy = (viewHeight - imageRect.height()) / 2f - imageRect.top
        } else {
            if (imageRect.top > 0f) dy = -imageRect.top
            if (imageRect.bottom < viewHeight) dy = viewHeight - imageRect.bottom
        }
        imageMatrix.postTranslate(dx, dy)
    }

    private fun handleTransformTouch(event: android.view.MotionEvent): Boolean {
        val bitmap = currentBitmap ?: return false
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            return true
        }
        val zoomed = imageUserScale > minScale + 0.01f
        val overflow = isImageOverflowing(bitmap)
        val allowPan = (zoomed || overflow) && !binding.translationOverlay.hasBubbleAt(event.x, event.y)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                startTouchX = event.x
                startTouchY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                isPanning = false
                return false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (isScaling) return true
                if (allowPan) {
                    val movedX = event.x - startTouchX
                    val movedY = event.y - startTouchY
                    if (!isPanning && (kotlin.math.abs(movedX) > panTouchSlop || kotlin.math.abs(movedY) > panTouchSlop)) {
                        isPanning = true
                    }
                    if (isPanning) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        imageMatrix.postTranslate(dx, dy)
                        fixTranslation(bitmap)
                        applyImageMatrix()
                        lastTouchX = event.x
                        lastTouchY = event.y
                        return true
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                val handled = isPanning || isScaling
                isPanning = false
                return handled
            }
        }
        return isScaling || isPanning
    }

    private fun isImageOverflowing(bitmap: Bitmap): Boolean {
        val viewWidth = binding.readingImage.width.toFloat()
        val viewHeight = binding.readingImage.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return false
        imageRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        imageMatrix.mapRect(imageRect)
        return imageRect.width() > viewWidth + 0.5f || imageRect.height() > viewHeight + 0.5f
    }

    private fun applyReadingDisplayMode() {
        val mode = settingsStore.loadReadingDisplayMode()
        if (mode == readingDisplayMode) return
        readingDisplayMode = mode
        val bitmap = currentBitmap ?: return
        resetImageMatrix(bitmap)
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
        if (isEditMode) {
            persistCurrentTranslation(forceSave = true)
            setEditMode(false)
            processEmptyBubbles()
        } else {
            setEditMode(true)
        }
    }

    private fun setEditMode(enabled: Boolean) {
        if (isEditMode == enabled) return
        isEditMode = enabled
        binding.translationOverlay.setEditMode(enabled)
        if (!enabled) {
            hideResizePanel()
        }
        updateEditButtonState()
    }

    private fun updateEditButtonState() {
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
        binding.readingResizeWidthInput.setText(percent.toString())
        binding.readingResizeWidthInput.setSelection(binding.readingResizeWidthInput.text?.length ?: 0)
        resizeUpdatingWidthInput = false
        resizeUpdatingHeightInput = true
        binding.readingResizeHeightInput.setText(percent.toString())
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
        val imageFile = currentImageFile ?: return
        val translation = currentTranslation ?: return
        translationStore.save(imageFile, translation)
    }

    private fun addNewBubble() {
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
        val newBubble = BubbleTranslation(nextId, rect, "")
        val updated = translation.copy(bubbles = translation.bubbles + newBubble)
        currentTranslation = updated
        binding.translationOverlay.setTranslations(updated)
        saveCurrentTranslation()
        showResizePanel(nextId)
    }

    private fun processEmptyBubbles() {
        val imageFile = currentImageFile ?: return
        val translation = currentTranslation ?: return
        val folder = readingSessionViewModel.currentFolder.value ?: return
        val targets = translation.bubbles.filter { it.text.isBlank() }
        if (targets.isEmpty()) return
        Toast.makeText(requireContext(), R.string.reading_empty_bubble_translating, Toast.LENGTH_SHORT).show()
        emptyBubbleJob?.cancel()
        val baseTranslation = translation
        emptyBubbleJob = viewLifecycleOwner.lifecycleScope.launch {
            val language = getTranslationLanguage(folder)
            val glossary = glossaryStore.load(folder)
            val result = withContext(Dispatchers.Default) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext null
                val candidates = ArrayList<OcrBubble>(targets.size)
                val removedIds = HashSet<Int>()
                for (bubble in targets) {
                    val text = ocrBubble(bitmap, bubble.rect, language).trim()
                    if (text.length <= 2) {
                        removedIds.add(bubble.id)
                    } else {
                        candidates.add(OcrBubble(bubble.id, bubble.rect, text))
                    }
                }
                ProcessingResult(removedIds, candidates)
            } ?: return@launch
            val remainingBubbles = baseTranslation.bubbles.filterNot { result.removedIds.contains(it.id) }
            if (result.ocrCandidates.isEmpty()) {
                val updated = baseTranslation.copy(bubbles = remainingBubbles)
                withContext(Dispatchers.IO) {
                    translationStore.save(imageFile, updated)
                }
                if (currentImageFile?.absolutePath == imageFile.absolutePath) {
                    currentTranslation = updated
                    binding.translationOverlay.setTranslations(updated)
                }
                return@launch
            }
            val translated = translateOcrBubbles(
                imageFile,
                result.ocrCandidates,
                glossary,
                language
            )
            if (translated != null) {
                if (translated.glossaryUsed.isNotEmpty()) {
                    glossary.putAll(translated.glossaryUsed)
                    glossaryStore.save(folder, glossary)
                }
                val translatedSegments = extractTaggedSegments(
                    translated.translation,
                    result.ocrCandidates.map { it.text }
                )
                val translationMap = HashMap<Int, String>(result.ocrCandidates.size)
                for (i in result.ocrCandidates.indices) {
                    translationMap[result.ocrCandidates[i].id] = translatedSegments[i]
                }
                val merged = remainingBubbles.mapNotNull { bubble ->
                    when {
                        result.removedIds.contains(bubble.id) -> null
                        translationMap.containsKey(bubble.id) ->
                            bubble.copy(text = translationMap[bubble.id].orEmpty())
                        else -> bubble
                    }
                }
                val updated = baseTranslation.copy(bubbles = merged)
                withContext(Dispatchers.IO) {
                    translationStore.save(imageFile, updated)
                }
                if (currentImageFile?.absolutePath == imageFile.absolutePath) {
                    currentTranslation = updated
                    binding.translationOverlay.setTranslations(updated)
                    Toast.makeText(
                        requireContext(),
                        R.string.reading_empty_bubble_translated,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                val fallbackMap = result.ocrCandidates.associate { it.id to it.text }
                val merged = remainingBubbles.mapNotNull { bubble ->
                    when {
                        result.removedIds.contains(bubble.id) -> null
                        fallbackMap.containsKey(bubble.id) ->
                            bubble.copy(text = fallbackMap[bubble.id].orEmpty())
                        else -> bubble
                    }
                }
                val updated = baseTranslation.copy(bubbles = merged)
                withContext(Dispatchers.IO) {
                    translationStore.save(imageFile, updated)
                }
                if (currentImageFile?.absolutePath == imageFile.absolutePath) {
                    currentTranslation = updated
                    binding.translationOverlay.setTranslations(updated)
                }
            }
        }
    }

    private fun getTranslationLanguage(folder: java.io.File): TranslationLanguage {
        val value = libraryPrefs.getString(languageKeyPrefix + folder.absolutePath, null)
        return TranslationLanguage.fromString(value)
    }

    private suspend fun translateOcrBubbles(
        imageFile: java.io.File,
        bubbles: List<OcrBubble>,
        glossary: Map<String, String>,
        language: TranslationLanguage
    ): LlmTranslationResult? = withContext(Dispatchers.IO) {
        if (!llmClient.isConfigured()) {
            AppLogger.log("Reading", "Missing API settings for OCR translation")
            return@withContext null
        }
        val pageText = bubbles.joinToString("\n") { bubble ->
            val text = normalizeOcrText(bubble.text, language)
            "<b>$text</b>"
        }
        val promptAsset = when (language) {
            TranslationLanguage.EN_TO_ZH -> "en-zh-llm_prompts.json"
            TranslationLanguage.JA_TO_ZH -> "llm_prompts.json"
        }
        try {
            llmClient.translate(pageText, glossary, promptAsset)
        } catch (e: Exception) {
            AppLogger.log("Reading", "Translate OCR bubbles failed for ${imageFile.name}", e)
            null
        }
    }

    private fun normalizeOcrText(text: String, language: TranslationLanguage): String {
        if (language != TranslationLanguage.EN_TO_ZH) return text
        return text.replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractTaggedSegments(text: String, fallback: List<String>): List<String> {
        val expected = fallback.size
        val regex = Regex("<b>(.*?)</b>", RegexOption.DOT_MATCHES_ALL)
        val matches = regex.findAll(text).map { it.groupValues[1].trim() }.toList()
        if (matches.isEmpty()) {
            if (expected == 1) return listOf(text.trim())
            AppLogger.log("Reading", "Missing <b> tags in OCR translation")
            return List(expected) { "" }
        }
        if (matches.size != expected) {
            AppLogger.log(
                "Reading",
                "OCR translation count mismatch: expected $expected, got ${matches.size}"
            )
        }
        val result = MutableList(expected) { "" }
        val limit = minOf(expected, matches.size)
        for (i in 0 until limit) {
            result[i] = matches[i]
        }
        return result
    }

    private fun ocrBubble(
        bitmap: android.graphics.Bitmap,
        rect: RectF,
        language: TranslationLanguage
    ): String {
        val crop = cropBitmap(bitmap, rect) ?: return ""
        return when (language) {
            TranslationLanguage.JA_TO_ZH -> {
                val engine = getMangaOcr() ?: return ""
                engine.recognize(crop).trim()
            }
            TranslationLanguage.EN_TO_ZH -> {
                val engine = getEnglishOcr() ?: return ""
                val lineDetector = getEnglishLineDetector()
                val lineRects = lineDetector?.detectLines(crop).orEmpty()
                val lines = recognizeEnglishLines(crop, lineRects, engine)
                if (lines.isEmpty()) {
                    engine.recognize(crop).trim()
                } else {
                    lines.joinToString("\n") { it.text }
                }
            }
        }
    }

    private fun recognizeEnglishLines(
        source: android.graphics.Bitmap,
        lineRects: List<RectF>,
        ocrEngine: EnglishOcr
    ): List<EnglishLine> {
        if (lineRects.isEmpty()) return emptyList()
        val results = ArrayList<EnglishLine>(lineRects.size)
        for (rect in lineRects) {
            val crop = cropBitmap(source, rect) ?: continue
            val decoded = ocrEngine.recognizeWithScore(crop)
            val text = decoded.text.trim()
            if (decoded.score < EN_MIN_LINE_SCORE || text.isBlank()) continue
            results.add(EnglishLine(rect, text))
        }
        return results
    }

    private fun cropBitmap(source: android.graphics.Bitmap, rect: RectF): android.graphics.Bitmap? {
        val left = rect.left.toInt().coerceIn(0, source.width - 1)
        val top = rect.top.toInt().coerceIn(0, source.height - 1)
        val right = rect.right.toInt().coerceIn(1, source.width)
        val bottom = rect.bottom.toInt().coerceIn(1, source.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        return android.graphics.Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun getMangaOcr(): MangaOcr? {
        if (mangaOcr != null) return mangaOcr
        return try {
            mangaOcr = MangaOcr(requireContext())
            mangaOcr
        } catch (e: Exception) {
            AppLogger.log("Reading", "Failed to init OCR", e)
            null
        }
    }

    private fun getEnglishOcr(): EnglishOcr? {
        if (englishOcr != null) return englishOcr
        return try {
            englishOcr = EnglishOcr(requireContext())
            englishOcr
        } catch (e: Exception) {
            AppLogger.log("Reading", "Failed to init English OCR", e)
            null
        }
    }

    private fun getEnglishLineDetector(): EnglishLineDetector? {
        if (englishLineDetector != null) return englishLineDetector
        return try {
            englishLineDetector = EnglishLineDetector(requireContext())
            englishLineDetector
        } catch (e: Exception) {
            AppLogger.log("Reading", "Failed to init English line detector", e)
            null
        }
    }

    private data class ProcessingResult(
        val removedIds: Set<Int>,
        val ocrCandidates: List<OcrBubble>
    )

    private data class EnglishLine(
        val rect: RectF,
        val text: String
    )

    companion object {
        private const val EN_MIN_LINE_SCORE = 0.5f
    }
}
