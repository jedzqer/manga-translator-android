package com.manga.translate.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.R
import com.manga.translate.app.MainActivity
import com.manga.translate.databinding.FragmentReadingBinding
import com.manga.translate.detection.shouldUseLongImageTiling
import com.manga.translate.di.appContainer
import com.manga.translate.library.LibraryDialogs
import com.manga.translate.library.LibraryPreferencesGateway
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.model.ReadingPageAnimationMode
import com.manga.translate.model.TranslationResult
import com.manga.translate.network.LlmRequestException
import com.manga.translate.network.LlmResponseException
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.LockedWebtoonLinearLayoutManager
import com.manga.translate.platform.showModelErrorDialog
import com.manga.translate.rendering.BubbleShapePaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadingFragment : Fragment() {
    private data class WebtoonScrollAnchor(
        val imageIndex: Int,
        val topOffset: Int
    )

    private fun resolveColorAttr(attrRes: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attrRes, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(requireContext(), typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private var _binding: FragmentReadingBinding? = null
    private val binding get() = _binding!!
    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()
    private val appContainer by lazy(LazyThreadSafetyMode.NONE) { requireContext().appContainer }
    private val dialogs = LibraryDialogs()
    private val translationPipeline by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.createTranslationPipeline()
    }
    private val translationStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.translationStore }
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.settingsStore }
    private val preferencesGateway by lazy(LazyThreadSafetyMode.NONE) {
        LibraryPreferencesGateway(
            context = requireContext().applicationContext,
            prefs = appContainer.libraryPrefs,
            repository = appContainer.libraryRepository
        )
    }
    private val readingProgressStore by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.readingProgressStore
    }
    private var currentImageFile: java.io.File? = null
    private var currentTranslation: TranslationResult? = null
    private var translationWatchJob: Job? = null
    private var webtoonTranslationWatchJob: Job? = null
    private var webtoonTranslationWarmJob: Job? = null
    private var currentDecodedImage: DecodedReadingBitmap? = null
    private var currentBitmap: Bitmap? = null
    private var currentBitmapLease: ReadingBitmapCache.Lease? = null
    private var currentImageWidth: Int = 0
    private var currentImageHeight: Int = 0
    private lateinit var imageTransformController: ReadingImageTransformController
    private var readingDisplayMode = ReadingDisplayMode.FIT_WIDTH
    private var folderReadingMode = FolderReadingMode.STANDARD
    private var isEditMode = false
    private val glossaryStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.glossaryStore }
    private lateinit var emptyBubbleCoordinator: ReadingEmptyBubbleCoordinator
    private var emptyBubbleJob: Job? = null
    private var activeEmptyBubbleModelErrorDialog: AlertDialog? = null
    private lateinit var webtoonAdapter: WebtoonReadingAdapter
    private lateinit var webtoonLayoutManager: LockedWebtoonLinearLayoutManager
    private var webtoonProgrammaticScroll = false
    private var webtoonLockedPageIndex: Int? = null
    private var webtoonLockedPagePath: String? = null
    private var webtoonEditOffsets = mutableMapOf<Int, Pair<Float, Float>>()
    private var webtoonPreparingEdit = false
    private var activeWebtoonZoomHolder: WebtoonReadingAdapter.WebtoonPageViewHolder? = null
    private var webtoonTouchHolder: WebtoonReadingAdapter.WebtoonPageViewHolder? = null
    private lateinit var readingBitmapCache: ReadingBitmapCache
    private var pendingWebtoonScrollAnchor: WebtoonScrollAnchor? = null
    private var displayedPageIndex: Int? = null
    private var displayedImagePath: String? = null
    private var isCurrentImageLong: Boolean = false
    private var hasCurrentPageVerticalOverflow: Boolean = false
    private var pageTransitionGeneration: Int = 0
    private var editModeSnapshotBubbles: List<BubbleTranslation>? = null
    private var editModeSnapshotOffsets: Map<Int, Pair<Float, Float>> = emptyMap()
    private val pageTransitionInterpolator = FastOutSlowInInterpolator()
    private val incomingPageParallaxDp = 28f
    private val webtoonTranslationWarmRadius = 2

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
        emptyBubbleCoordinator = appContainer.createReadingEmptyBubbleCoordinator()
        readingBitmapCache = ReadingBitmapCache()
        webtoonLayoutManager = LockedWebtoonLinearLayoutManager(requireContext())
        webtoonLayoutManager.initialPrefetchItemCount = 6
        webtoonAdapter = WebtoonReadingAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            loadTranslation = ::loadValidTranslationForCurrentFolder,
            bitmapCache = readingBitmapCache
        )
        webtoonAdapter.onDisplayStructureChanging = {
            pendingWebtoonScrollAnchor = captureWebtoonScrollAnchor()
        }
        webtoonAdapter.onDisplayStructureChanged = {
            restorePendingWebtoonScrollAnchor()
            syncWebtoonEditSession()
            updateWebtoonPageInfo()
        }
        webtoonAdapter.onLockedBubbleOffsetChanged = offsetChanged@{ bubbleId, offsetX, offsetY ->
            if (!isWebtoonEditSessionActive()) return@offsetChanged
            webtoonEditOffsets[bubbleId] = offsetX to offsetY
        }
        webtoonAdapter.onLockedBubbleRemove = { bubbleId ->
            handleBubbleRemove(bubbleId)
        }
        webtoonAdapter.onLockedBubbleTap = { bubbleId ->
            handleBubbleEdit(bubbleId)
        }
        webtoonAdapter.onLockedBubbleResizeTap = { bubbleId ->
            enterBubbleResizeMode(bubbleId)
        }
        webtoonAdapter.onLockedBubbleResized = { bubbleId, newRect ->
            handleBubbleResized(bubbleId, newRect)
        }
        webtoonAdapter.onLockedBubbleResizeModeChanged = {
            updateBubbleSizeFloatingControls()
        }
        webtoonAdapter.onLockedBubbleLongPress = { bubbleId ->
            showBubbleActionDialog(bubbleId)
        }
        binding.readingWebtoonList.layoutManager = webtoonLayoutManager
        binding.readingWebtoonList.adapter = webtoonAdapter
        binding.readingWebtoonList.setItemViewCacheSize(resolveWebtoonItemViewCacheSize())
        binding.readingWebtoonList.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                if (folderReadingMode != FolderReadingMode.WEBTOON_SCROLL || isEditMode) return false
                val target = webtoonTouchHolder ?: findWebtoonTouchHolder(rv, event)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    webtoonTouchHolder = target
                }
                val handled = target?.let { dispatchWebtoonTouch(it, event) } == true
                if (handled) {
                    webtoonTouchHolder = target
                } else if (
                    event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    webtoonTouchHolder = null
                }
                syncActiveWebtoonZoomHolder(target)
                return handled
            }

            override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
                val target = webtoonTouchHolder ?: return
                dispatchWebtoonTouch(target, event)
                syncActiveWebtoonZoomHolder(target)
                if (
                    event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    webtoonTouchHolder = null
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        })
        binding.readingWebtoonList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) return
                updateWebtoonPageInfo()
                if (!webtoonProgrammaticScroll) {
                    persistWebtoonProgress()
                }
                warmNearbyWebtoonTranslations()
            }
        })
        readingDisplayMode = settingsStore.loadReadingDisplayMode()
        folderReadingMode = readingSessionViewModel.readingMode.value ?: FolderReadingMode.STANDARD
        imageTransformController = ReadingImageTransformController(
            context = requireContext(),
            imageView = binding.readingImage,
            hasBubbleAt = { x, y -> binding.translationOverlay.hasBubbleAt(x, y) },
            onMatrixUpdated = { updateOverlayDisplayRect() },
            onHorizontalEdgeSwipe = ::handleSwipe
        )
        binding.translationOverlay.onTap = { x ->
            handleTap(x)
        }
        binding.translationOverlay.onDoubleTap = { x, y ->
            handleDoubleTap(x, y)
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
            enterBubbleResizeMode(bubbleId)
        }
        binding.translationOverlay.onResizeModeChanged = {
            updateBubbleSizeFloatingControls()
        }
        binding.translationOverlay.onBubbleLongPress = { bubbleId ->
            showBubbleActionDialog(bubbleId)
        }
        binding.translationOverlay.onBubbleCreated = { rect ->
            handleBubbleCreatedFromDrag(rect)
        }
        binding.translationOverlay.onBubbleResized = { bubbleId, newRect ->
            handleBubbleResized(bubbleId, newRect)
        }
        binding.readingEditButton.setOnClickListener {
            toggleEditMode()
        }
        binding.readingCancelEditButton.setOnClickListener {
            cancelCurrentEdit()
        }
        binding.readingAddButton.setOnClickListener {
            enterCreateBubbleMode()
        }
        binding.readingClearButton.setOnClickListener {
            clearAllBubbles()
        }
        binding.readingBubbleSizeMinus.setOnClickListener {
            adjustSelectedBubbleSize(deltaPercent = -2)
        }
        binding.readingBubbleSizePlus.setOnClickListener {
            adjustSelectedBubbleSize(deltaPercent = 2)
        }
        updateEditButtonState()
        applyNormalBubbleRenderSettings()
        applyTextLayoutSetting()
        readingSessionViewModel.images.observe(viewLifecycleOwner) {
            reloadReadingContent()
        }
        readingSessionViewModel.index.observe(viewLifecycleOwner) {
            if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
                loadWebtoonReading()
            } else {
                loadCurrentImage()
                persistReadingProgress()
            }
        }
        readingSessionViewModel.readingMode.observe(viewLifecycleOwner) { mode ->
            val previousMode = folderReadingMode
            if (previousMode != mode && isEditMode) {
                setEditMode(false)
            }
            folderReadingMode = mode
            if (mode != FolderReadingMode.WEBTOON_SCROLL) {
                clearWebtoonEditSession()
            }
            applyFolderReadingMode()
            reloadReadingContent()
        }
        binding.readingImage.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val decoded = currentDecodedImage ?: return@addOnLayoutChangeListener
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
                    updateReadingContentLayout(decoded)
                    updateOverlay(currentTranslation, currentBitmap)
                } else {
                    readingDisplayMode = resolveReadingDisplayMode(decoded)
                    imageTransformController.resetContent(decoded.displayWidth, decoded.displayHeight, readingDisplayMode)
                }
            }
        }
        binding.readingScrollContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop) {
                return@addOnLayoutChangeListener
            }
            val decoded = currentDecodedImage ?: return@addOnLayoutChangeListener
            updateReadingContentLayout(decoded)
        }
        applyFolderReadingMode()
    }

    override fun onResume() {
        super.onResume()
        applyNormalBubbleRenderSettings()
        applyTextLayoutSetting()
        applyFolderReadingMode()
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
        webtoonTranslationWatchJob?.cancel()
        emptyBubbleJob?.cancel()
        activeEmptyBubbleModelErrorDialog?.dismiss()
        activeEmptyBubbleModelErrorDialog = null
        cancelPageTransition()
        clearWebtoonEditSession(resetCurrentPage = true)
        if (::webtoonAdapter.isInitialized) {
            webtoonAdapter.clearRuntimeCaches()
        }
        releaseCurrentStandardBitmap()
        if (::readingBitmapCache.isInitialized) {
            readingBitmapCache.clear()
        }
        binding.readingWebtoonList.adapter = null
        _binding = null
    }

    private fun releaseCurrentStandardBitmap() {
        binding.translationOverlay.setSourceBitmap(null)
        binding.translationOverlay.setSourceImageFile(null)
        binding.readingImage.setRegionSource(null)
        binding.readingImage.setImageDrawable(null)
        imageTransformController.setCurrentBitmap(null)
        currentBitmapLease?.close()
        currentBitmapLease = null
        currentDecodedImage = null
        currentBitmap = null
    }

    private fun reloadReadingContent() {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            loadWebtoonReading()
        } else {
            loadCurrentImage()
        }
    }

    private fun loadCurrentImage() {
        stopWebtoonTranslationWatcher()
        val images = readingSessionViewModel.images.value.orEmpty()
        val folder = readingSessionViewModel.currentFolder.value
        folderReadingMode = readingSessionViewModel.readingMode.value ?: FolderReadingMode.STANDARD
        readingBitmapCache.retainPaths(images.mapTo(hashSetOf()) { it.absolutePath })
        if (images.isEmpty() || folder == null) {
            binding.readingEmptyHint.visibility = View.VISIBLE
            binding.readingPageInfo.visibility = View.GONE
            binding.translationOverlay.visibility = View.GONE
            binding.readingEditControls.visibility = View.GONE
            exitBubbleResizeMode()
            displayedImagePath = null
            displayedPageIndex = null
            releaseCurrentStandardBitmap()
            currentImageWidth = 0
            currentImageHeight = 0
            isCurrentImageLong = false
            hasCurrentPageVerticalOverflow = false
            imageTransformController.setVerticalPanEnabled(true)
            updateReadingInteractionState()
            finishPageTransitionImmediately()
            binding.readingScrollContainer.scrollTo(0, 0)
            return
        }
        val index = (readingSessionViewModel.index.value ?: 0).coerceIn(0, images.lastIndex)
        val imageFile = images[index]
        currentImageFile = imageFile
        val previousDecoded = currentDecodedImage
        val previousDisplayedPath = displayedImagePath
        val previousDisplayedIndex = displayedPageIndex
        val previousPageSnapshot = captureCurrentPageSnapshot()
        binding.readingEmptyHint.visibility = View.GONE
        binding.readingPageInfo.visibility = View.VISIBLE
        updateEditButtonState()
        exitBubbleResizeMode()
        binding.readingPageInfo.text = getString(
            R.string.reading_page_info,
            folder.name,
            index + 1,
            images.size
        )
        val targetPath = imageFile.absolutePath
        val targetIndex = index
        viewLifecycleOwner.lifecycleScope.launch {
            val decodedDeferred = async(Dispatchers.IO) {
                loadBitmap(imageFile)
            }
            val translationDeferred = async(Dispatchers.IO) {
                loadValidTranslationForCurrentFolder(imageFile)
            }
            val decodedLease = decodedDeferred.await()
            val decoded = decodedLease?.decoded
            val bitmap = decoded?.bitmap
            val translation = translationDeferred.await()
            val currentImages = readingSessionViewModel.images.value.orEmpty()
            val currentIndex = readingSessionViewModel.index.value ?: 0
            if (
                currentIndex != targetIndex ||
                currentImages.getOrNull(currentIndex)?.absolutePath != targetPath
            ) {
                decodedLease?.close()
                return@launch
            }
            val isTargetLongImage = decoded != null && isLongImage(decoded.sourceWidth, decoded.sourceHeight)
            val shouldAnimate = decoded != null &&
                !decoded.isTiled &&
                previousDecoded != null &&
                !previousDecoded.isTiled &&
                previousPageSnapshot != null &&
                previousDisplayedPath != null &&
                previousDisplayedPath != targetPath &&
                folderReadingMode != FolderReadingMode.WEBTOON_SCROLL &&
                !isTargetLongImage
            val direction = if ((previousDisplayedIndex ?: targetIndex) < targetIndex) -1 else 1
            binding.readingImage.translationX = 0f
            if (decoded != null) {
                val previousBitmapLease = currentBitmapLease
                binding.readingImage.setRegionSource(decoded.regionSource)
                binding.readingImage.setImageDrawable(decoded.drawable)
                currentDecodedImage = decoded
                currentBitmapLease = decodedLease
                currentBitmap = bitmap
                if (previousBitmapLease !== decodedLease) {
                    binding.translationOverlay.setSourceBitmap(null)
                    binding.translationOverlay.setSourceImageFile(null)
                    previousBitmapLease?.close()
                }
                currentImageWidth = decoded.sourceWidth
                currentImageHeight = decoded.sourceHeight
                isCurrentImageLong = isTargetLongImage
                imageTransformController.setCurrentContent(decoded.displayWidth, decoded.displayHeight)
                imageTransformController.setVerticalPanEnabled(!isTargetLongImage)
                updateReadingInteractionState()
                applyReadingImageLayerMode(decoded)
                displayedImagePath = targetPath
                displayedPageIndex = targetIndex
            } else {
                releaseCurrentStandardBitmap()
                currentImageWidth = 0
                currentImageHeight = 0
                isCurrentImageLong = false
                hasCurrentPageVerticalOverflow = false
                imageTransformController.setCurrentBitmap(null)
                imageTransformController.setVerticalPanEnabled(true)
                updateReadingInteractionState()
                applyReadingImageLayerMode(null)
                displayedImagePath = null
                displayedPageIndex = null
            }
            binding.readingScrollContainer.scrollTo(0, 0)
            binding.readingImage.post {
                if (decoded != null) {
                    readingDisplayMode = resolveReadingDisplayMode(decoded)
                    updateReadingContentLayout(decoded)
                    if (folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) {
                        binding.readingContentContainer.doOnLayout {
                            if (!isAdded || _binding == null || currentDecodedImage !== decoded) return@doOnLayout
                            binding.readingScrollContainer.scrollTo(0, 0)
                            imageTransformController.resetContent(decoded.displayWidth, decoded.displayHeight, readingDisplayMode)
                            updateOverlay(translation, bitmap)
                            if (shouldAnimate) {
                                startPageTransition(previousPageSnapshot, direction)
                            } else {
                                finishPageTransitionImmediately()
                            }
                        }
                        return@post
                    }
                }
                updateOverlay(translation, bitmap)
                if (shouldAnimate) {
                    startPageTransition(previousPageSnapshot, direction)
                } else {
                    finishPageTransitionImmediately()
                }
            }
            if (translation == null && decoded != null) {
                startTranslationWatcher(imageFile)
            } else {
                translationWatchJob?.cancel()
            }
        }
    }

    private fun loadWebtoonReading() {
        val images = readingSessionViewModel.images.value.orEmpty()
        val folder = readingSessionViewModel.currentFolder.value
        folderReadingMode = readingSessionViewModel.readingMode.value ?: FolderReadingMode.STANDARD
        translationWatchJob?.cancel()
        stopWebtoonTranslationWatcher(clearCache = false)
        emptyBubbleJob?.cancel()
        exitBubbleResizeMode()
        currentImageFile = null
        currentTranslation = null
        releaseCurrentStandardBitmap()
        currentImageWidth = 0
        currentImageHeight = 0
        hasCurrentPageVerticalOverflow = false
        displayedPageIndex = null
        displayedImagePath = null
        finishPageTransitionImmediately()
        if (images.isEmpty() || folder == null) {
            binding.readingEmptyHint.visibility = View.VISIBLE
            binding.readingPageInfo.visibility = View.GONE
            updateEditButtonState()
            val bubbleRenderSettings = settingsStore.loadNormalBubbleRenderSettings()
            webtoonAdapter.submit(
                images = emptyList(),
                verticalLayoutEnabled = !bubbleRenderSettings.useHorizontalText,
                bubbleRenderSettings = bubbleRenderSettings
            )
            syncWebtoonEditSession()
            stopWebtoonTranslationWatcher()
            return
        }
        binding.readingEmptyHint.visibility = View.GONE
        binding.readingPageInfo.visibility = View.VISIBLE
        updateEditButtonState()
        val bubbleRenderSettings = settingsStore.loadNormalBubbleRenderSettings()
        webtoonAdapter.submit(
            images = images,
            verticalLayoutEnabled = !bubbleRenderSettings.useHorizontalText,
            bubbleRenderSettings = bubbleRenderSettings
        )
        syncWebtoonEditSession()
        val targetIndex = (readingSessionViewModel.index.value ?: 0).coerceIn(0, images.lastIndex)
        webtoonProgrammaticScroll = true
        binding.readingWebtoonList.post {
            if (!isAdded || _binding == null || folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) {
                webtoonProgrammaticScroll = false
                return@post
            }
            val adapterPosition = webtoonAdapter.adapterPositionForImageIndex(targetIndex)
                .takeIf { it != RecyclerView.NO_POSITION }
                ?: targetIndex
            webtoonLayoutManager.scrollToPositionWithOffset(adapterPosition, 0)
            updateWebtoonPageInfo()
            persistWebtoonProgress()
            startWebtoonTranslationWatcher()
            binding.readingWebtoonList.post {
                webtoonProgrammaticScroll = false
            }
        }
    }

    private fun isWebtoonEditSessionActive(): Boolean {
        return folderReadingMode == FolderReadingMode.WEBTOON_SCROLL &&
            isEditMode &&
            webtoonLockedPageIndex != null &&
            webtoonLockedPagePath != null
    }

    private fun syncWebtoonEditSession() {
        val active = isWebtoonEditSessionActive()
        val lockedAdapterRange = if (active) {
            webtoonAdapter.adapterPositionRangeForImageIndex(webtoonLockedPageIndex ?: RecyclerView.NO_POSITION)
        } else {
            null
        }
        webtoonLayoutManager.setLockedPositionRange(lockedAdapterRange)
        webtoonAdapter.updateEditSession(
            enabled = active,
            lockedImageIndex = if (active) webtoonLockedPageIndex else null,
            lockedImagePath = if (active) webtoonLockedPagePath else null,
            translation = if (active) currentTranslation else null,
            offsets = if (active) webtoonEditOffsets else emptyMap()
        )
    }

    private fun clearWebtoonEditSession(resetCurrentPage: Boolean = false) {
        webtoonPreparingEdit = false
        webtoonLockedPageIndex = null
        webtoonLockedPagePath = null
        webtoonEditOffsets.clear()
        syncWebtoonEditSession()
        if (resetCurrentPage) {
            currentImageFile = null
            currentTranslation = null
        }
    }

    private fun renderCurrentTranslation() {
        if (isWebtoonEditSessionActive()) {
            syncWebtoonEditSession()
        } else {
            binding.translationOverlay.setTranslations(currentTranslation)
        }
    }

    private fun currentOverlayOffsets(): MutableMap<Int, Pair<Float, Float>> {
        return if (isWebtoonEditSessionActive()) {
            webtoonEditOffsets.toMutableMap()
        } else {
            binding.translationOverlay.getOffsets().toMutableMap()
        }
    }

    private fun applyOverlayOffsets(offsets: Map<Int, Pair<Float, Float>>) {
        if (isWebtoonEditSessionActive()) {
            webtoonEditOffsets.clear()
            webtoonEditOffsets.putAll(offsets)
            syncWebtoonEditSession()
        } else {
            binding.translationOverlay.setOffsets(offsets)
        }
    }

    private fun resolveLockedWebtoonIndex(): Int? {
        val firstVisible = webtoonLayoutManager.findFirstVisibleItemPosition()
        if (firstVisible != RecyclerView.NO_POSITION) {
            val imageIndex = webtoonAdapter.imageIndexForAdapterPosition(firstVisible)
            if (imageIndex != RecyclerView.NO_POSITION) return imageIndex
        }
        val images = readingSessionViewModel.images.value.orEmpty()
        if (images.isEmpty()) return null
        return (readingSessionViewModel.index.value ?: 0).coerceIn(0, images.lastIndex)
    }

    private suspend fun prepareWebtoonEditSession(index: Int): Boolean {
        val images = readingSessionViewModel.images.value.orEmpty()
        val imageFile = images.getOrNull(index) ?: return false
        val snapshot = webtoonAdapter.findBoundPageSnapshot(imageFile.absolutePath)
        val translation = snapshot?.translation ?: withContext(Dispatchers.IO) {
            loadValidTranslationForCurrentFolder(imageFile)
        }
        val bounds = readImageBounds(imageFile)
        val width = when {
            translation != null && translation.width > 0 -> translation.width
            snapshot != null && snapshot.sourceWidth > 0 -> snapshot.sourceWidth
            else -> bounds.first
        }
        val height = when {
            translation != null && translation.height > 0 -> translation.height
            snapshot != null && snapshot.sourceHeight > 0 -> snapshot.sourceHeight
            else -> bounds.second
        }
        if (width <= 0 || height <= 0) return false
        currentImageFile = imageFile
        currentTranslation = when {
            translation == null -> TranslationResult(imageFile.name, width, height, emptyList())
            translation.width == width && translation.height == height -> translation
            else -> translation.copy(width = width, height = height)
        }
        webtoonLockedPageIndex = index
        webtoonLockedPagePath = imageFile.absolutePath
        webtoonEditOffsets.clear()
        return true
    }

    private fun readImageBounds(imageFile: java.io.File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, options)
        return options.outWidth to options.outHeight
    }

    private fun updateOverlay(translation: TranslationResult?, bitmap: Bitmap?) {
        val rect = computeOverlayDisplayRect() ?: run {
            binding.translationOverlay.visibility = View.GONE
            binding.translationOverlay.setSourceBitmap(null)
            binding.translationOverlay.setSourceImageFile(null)
            return
        }
        val resolvedWidth = when {
            translation != null && translation.width > 0 -> translation.width
            currentImageWidth > 0 -> currentImageWidth
            else -> bitmap?.width ?: 0
        }
        val resolvedHeight = when {
            translation != null && translation.height > 0 -> translation.height
            currentImageHeight > 0 -> currentImageHeight
            else -> bitmap?.height ?: 0
        }
        if (resolvedWidth <= 0 || resolvedHeight <= 0) {
            binding.translationOverlay.visibility = View.GONE
            binding.translationOverlay.setSourceBitmap(null)
            binding.translationOverlay.setSourceImageFile(null)
            return
        }
        val normalized = when {
            translation == null -> TranslationResult("", resolvedWidth, resolvedHeight, emptyList())
            translation.width == resolvedWidth && translation.height == resolvedHeight -> translation
            else -> translation.copy(width = resolvedWidth, height = resolvedHeight)
        }
        currentTranslation = normalized
        binding.translationOverlay.setDisplayRect(rect)
        binding.translationOverlay.setContentZoomScale(imageTransformController.currentContentZoomScale())
        binding.translationOverlay.setSourceBitmap(bitmap)
        binding.translationOverlay.setSourceImageFile(currentImageFile)
        binding.translationOverlay.setCurrentImageName(currentImageFile?.name ?: normalized.imageName)
        binding.translationOverlay.setTranslations(normalized)
        binding.translationOverlay.setOffsets(emptyMap())
        binding.translationOverlay.setEditOverflowBounds(0f, 0f)
        binding.translationOverlay.setEditMode(isEditMode)
        binding.translationOverlay.visibility = View.VISIBLE
        val displayedPath = currentImageFile?.absolutePath
        binding.translationOverlay.postOnAnimation {
            if (!isAdded || _binding == null) return@postOnAnimation
            if (currentImageFile?.absolutePath != displayedPath) return@postOnAnimation
            if (binding.translationOverlay.visibility != View.VISIBLE) return@postOnAnimation
            val refreshedRect = computeOverlayDisplayRect() ?: return@postOnAnimation
            binding.translationOverlay.setDisplayRect(refreshedRect)
            binding.translationOverlay.setContentZoomScale(imageTransformController.currentContentZoomScale())
            binding.translationOverlay.postInvalidateOnAnimation()
        }
    }

    private fun currentReadingPageAnimationMode(): ReadingPageAnimationMode {
        return settingsStore.loadReadingPageAnimationMode()
    }

    private fun startPageTransition(previousSnapshot: Bitmap, direction: Int) {
        if (currentReadingPageAnimationMode() != ReadingPageAnimationMode.HORIZONTAL_SLIDE) {
            finishPageTransitionImmediately()
            return
        }
        val width = binding.readingContentContainer.width
        if (width <= 0) {
            finishPageTransitionImmediately()
            return
        }
        cancelPageTransition()
        val generation = ++pageTransitionGeneration
        val parallaxOffset = resolveIncomingPageParallaxOffset(direction)
        binding.readingTransitionImage.setImageBitmap(previousSnapshot)
        binding.readingTransitionImage.scaleType = android.widget.ImageView.ScaleType.FIT_XY
        binding.readingTransitionImage.imageMatrix = Matrix()
        binding.readingTransitionImage.visibility = View.VISIBLE
        binding.readingTransitionImage.translationX = 0f
        binding.readingTransitionImage.alpha = 1f
        binding.translationOverlay.visibility = View.INVISIBLE
        binding.readingImage.translationX = parallaxOffset
        binding.readingImage.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.readingTransitionImage.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.readingImage.animate()
            .translationX(0f)
            .setDuration(260L)
            .setInterpolator(pageTransitionInterpolator)
            .setListener(null)
            .start()
        binding.readingTransitionImage.animate()
            .translationX(direction * width.toFloat())
            .setDuration(260L)
            .setInterpolator(pageTransitionInterpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    completePageTransition(generation)
                }

                override fun onAnimationCancel(animation: Animator) {
                    completePageTransition(generation)
                }
            })
            .start()
    }

    private fun completePageTransition(generation: Int) {
        if (_binding == null || generation != pageTransitionGeneration) return
        finishPageTransitionImmediately()
        if (currentDecodedImage != null) {
            updateOverlay(currentTranslation, currentBitmap)
        }
    }

    private fun cancelPageTransition() {
        pageTransitionGeneration += 1
        if (_binding == null) return
        binding.readingImage.animate().cancel()
        binding.readingTransitionImage.animate().cancel()
    }

    private fun finishPageTransitionImmediately() {
        if (_binding == null) return
        binding.readingImage.animate().setListener(null)
        binding.readingTransitionImage.animate().setListener(null)
        binding.readingImage.translationX = 0f
        binding.readingImage.alpha = 1f
        binding.readingTransitionImage.translationX = 0f
        binding.readingTransitionImage.alpha = 1f
        binding.readingTransitionImage.visibility = View.GONE
        applyReadingImageLayerMode(currentDecodedImage)
        binding.readingTransitionImage.setLayerType(View.LAYER_TYPE_NONE, null)
        binding.readingTransitionImage.post {
            if (_binding == null) return@post
            if (binding.readingTransitionImage.isGone) {
                binding.readingTransitionImage.setImageDrawable(null)
            }
        }
    }

    private fun resolveIncomingPageParallaxOffset(direction: Int): Float {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            incomingPageParallaxDp,
            resources.displayMetrics
        )
        return (-direction) * px
    }

    private fun captureReadingImageMatrix(): Matrix? {
        val drawable = binding.readingImage.drawable ?: return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null
        return Matrix(binding.readingImage.imageMatrix)
    }

    private fun captureCurrentPageSnapshot(): Bitmap? {
        val width = binding.readingContentContainer.width
        val height = binding.readingContentContainer.height
        if (width <= 0 || height <= 0) return null
        return try {
            createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                binding.readingImage.draw(canvas)
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun updateOverlayDisplayRect() {
        if (binding.translationOverlay.visibility != View.VISIBLE) return
        val rect = computeOverlayDisplayRect() ?: return
        binding.translationOverlay.setDisplayRect(rect)
        binding.translationOverlay.setContentZoomScale(imageTransformController.currentContentZoomScale())
    }

    private fun applyReadingDisplayMode() {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return
        val decoded = currentDecodedImage ?: return
        val mode = resolveReadingDisplayMode(decoded)
        if (mode == readingDisplayMode) return
        readingDisplayMode = mode
        imageTransformController.resetContent(decoded.displayWidth, decoded.displayHeight, readingDisplayMode)
        updateOverlay(currentTranslation, currentBitmap)
    }

    private suspend fun loadBitmap(imageFile: java.io.File): ReadingBitmapCache.Lease? = withContext(Dispatchers.IO) {
        val width = binding.readingImage.width
            .takeIf { it > 0 }
            ?: binding.readingRoot.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val height = binding.readingImage.height
            .takeIf { it > 0 }
            ?: binding.readingRoot.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        var lease: ReadingBitmapCache.Lease? = null
        try {
            lease = readingBitmapCache.acquire(imageFile) {
                ReadingBitmapDecoder.decode(imageFile, width, height)
            }
            lease
        } finally {
            if (!isActive) {
                lease?.close()
            }
        }
    }

    private fun handleTap(x: Float) {
        if (isEditMode) return
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return
        if (isCurrentImageLong) return
        if (imageTransformController.isZoomed()) return
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
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return
        if (isCurrentImageLong) return
        if (imageTransformController.isZoomed()) return
        if (direction == 0) return
        persistCurrentTranslation()
        if (direction > 0) {
            readingSessionViewModel.prev()
        } else {
            readingSessionViewModel.next()
        }
    }

    private fun handleDoubleTap(x: Float, y: Float) {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return
        imageTransformController.toggleDoubleTapZoom(x, y)
    }

    private fun resolveReadingDisplayMode(decoded: DecodedReadingBitmap?): ReadingDisplayMode {
        return resolveEffectiveReadingDisplayMode(
            readingMode = folderReadingMode,
            configuredMode = settingsStore.loadReadingDisplayMode(),
            isLongImage = decoded != null && isLongImage(decoded.sourceWidth, decoded.sourceHeight)
        )
    }

    private fun applyReadingImageLayerMode(decoded: DecodedReadingBitmap?) {
        // 长图通过区域瓦片解码，不再持有整张大 bitmap，无需软件层（软件层会导致撑高后 OOM）。
        binding.readingImage.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun isLongImage(width: Int, height: Int): Boolean {
        return shouldUseLongImageTiling(width, height)
    }

    private fun applyTextLayoutSetting() {
        val useHorizontal = settingsStore.loadNormalBubbleRenderSettings().useHorizontalText
        binding.translationOverlay.setVerticalLayoutEnabled(!useHorizontal)
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            refreshWebtoonAdapterPresentation()
        }
    }

    private fun applyNormalBubbleRenderSettings() {
        binding.translationOverlay.setNormalBubbleRenderSettings(
            settingsStore.loadNormalBubbleRenderSettings()
        )
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            refreshWebtoonAdapterPresentation()
        }
    }

    private fun toggleEditMode() {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            if (webtoonPreparingEdit) return
            if (isEditMode) {
                persistCurrentTranslation(forceSave = true)
                setEditMode(false)
                processEmptyBubbles()
                clearWebtoonEditSession()
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    webtoonPreparingEdit = true
                    try {
                        val targetIndex = resolveLockedWebtoonIndex() ?: return@launch
                        val entryAnchor = captureWebtoonScrollAnchor()
                        val prepared = prepareWebtoonEditSession(targetIndex)
                        if (!prepared || !isAdded || _binding == null) return@launch
                        setEditMode(true)
                        webtoonProgrammaticScroll = true
                        binding.readingWebtoonList.post {
                            if (!isAdded || _binding == null || !isWebtoonEditSessionActive()) {
                                webtoonProgrammaticScroll = false
                                return@post
                            }
                            val adapterPosition = webtoonAdapter.adapterPositionForImageIndex(targetIndex)
                                .takeIf { it != RecyclerView.NO_POSITION }
                                ?: targetIndex
                            val targetOffset = entryAnchor
                                ?.takeIf { it.imageIndex == targetIndex }
                                ?.topOffset
                                ?: 0
                            webtoonLayoutManager.scrollToPositionWithOffset(adapterPosition, targetOffset)
                            updateWebtoonPageInfo()
                            persistWebtoonProgress()
                            binding.readingWebtoonList.post {
                                webtoonProgrammaticScroll = false
                            }
                        }
                    } finally {
                        webtoonPreparingEdit = false
                    }
                }
            }
            return
        }
        if (isEditMode) {
            persistCurrentTranslation(forceSave = true)
            setEditMode(false)
            processEmptyBubbles()
        } else {
            setEditMode(true)
        }
    }

    private fun setEditMode(enabled: Boolean) {
        val nextEnabled = enabled
        if (isEditMode == nextEnabled) return
        if (nextEnabled) {
            editModeSnapshotBubbles = currentTranslation?.bubbles?.map {
                it.copy(rect = RectF(it.rect), maskContour = it.maskContour?.copyOf())
            }
            editModeSnapshotOffsets = if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
                webtoonEditOffsets.toMap()
            } else {
                binding.translationOverlay.getOffsets().toMap()
            }
        }
        isEditMode = nextEnabled
        binding.translationOverlay.setEditMode(nextEnabled && folderReadingMode != FolderReadingMode.WEBTOON_SCROLL)
        syncWebtoonEditSession()
        updateReadingInteractionState()
        if (!nextEnabled) {
            exitBubbleResizeMode()
            editModeSnapshotBubbles = null
            editModeSnapshotOffsets = emptyMap()
        }
        updateEditButtonState()
    }

    private fun cancelCurrentEdit() {
        if (!isEditMode) return
        val snapshotBubbles = editModeSnapshotBubbles
        val snapshotOffsets = editModeSnapshotOffsets
        if (snapshotBubbles != null && currentTranslation != null) {
            val restored = currentTranslation?.copy(bubbles = snapshotBubbles)
            currentTranslation = restored
            applyOverlayOffsets(snapshotOffsets)
            renderCurrentTranslation()
            restored?.let(::saveTranslationToDisk)
        }
        setEditMode(false)
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            clearWebtoonEditSession()
        }
    }

    private fun updateEditButtonState() {
        val hasImages = readingSessionViewModel.images.value.orEmpty().isNotEmpty()
        if (!hasImages) {
            binding.readingEditControls.visibility = View.GONE
            binding.readingAddButton.visibility = View.GONE
            binding.readingClearButton.visibility = View.GONE
            binding.readingBubbleSizeFloatingControls.visibility = View.GONE
            updateReadingInteractionState()
            return
        }
        binding.readingEditControls.visibility = View.VISIBLE
        val button = binding.readingEditButton
        val density = resources.displayMetrics.density
        if (isEditMode) {
            button.layoutParams = button.layoutParams.apply {
                width = (36f * density).toInt()
                height = (36f * density).toInt()
            }
            button.setPadding(
                (6f * density).toInt(),
                (6f * density).toInt(),
                (6f * density).toInt(),
                (6f * density).toInt()
            )
            button.setImageResource(R.drawable.ic_check)
            button.setColorFilter(0xFF22C55E.toInt())
            button.contentDescription = getString(R.string.reading_confirm_edit)
            binding.readingAddButton.visibility = View.VISIBLE
            binding.readingClearButton.visibility = View.VISIBLE
            binding.readingClearButton.setColorFilter(Color.WHITE)
            binding.readingCancelEditButton.visibility = View.VISIBLE
        } else {
            button.layoutParams = button.layoutParams.apply {
                width = (18f * density).toInt()
                height = (18f * density).toInt()
            }
            button.setPadding(
                (3f * density).toInt(),
                (3f * density).toInt(),
                (3f * density).toInt(),
                (3f * density).toInt()
            )
            button.setImageResource(android.R.drawable.ic_menu_edit)
            button.setColorFilter(Color.WHITE)
            button.contentDescription = getString(R.string.reading_edit_bubbles)
            binding.readingAddButton.visibility = View.GONE
            binding.readingClearButton.visibility = View.GONE
            binding.readingCancelEditButton.visibility = View.GONE
        }
        updateBubbleSizeFloatingControls()
        updateReadingInteractionState()
    }

    private fun applyFolderReadingMode() {
        val isWebtoon = folderReadingMode == FolderReadingMode.WEBTOON_SCROLL
        binding.readingWebtoonList.visibility = if (isWebtoon) View.VISIBLE else View.GONE
        binding.readingScrollContainer.visibility = if (isWebtoon) View.GONE else View.VISIBLE
        binding.readingScrollContainer.isFillViewport = !isWebtoon
        if (isWebtoon) {
            binding.readingWebtoonList.post {
                if (!isAdded || _binding == null || folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) return@post
                startWebtoonTranslationWatcher()
            }
        } else {
            stopWebtoonTranslationWatcher()
        }
        syncWebtoonEditSession()
        updateReadingContentLayout(currentDecodedImage)
        updateReadingInteractionState()
        updateEditButtonState()
    }

    private fun refreshWebtoonAdapterPresentation() {
        if (!::webtoonAdapter.isInitialized) return
        val images = readingSessionViewModel.images.value.orEmpty()
        webtoonAdapter.submit(
            images = images,
            verticalLayoutEnabled = !settingsStore.loadNormalBubbleRenderSettings().useHorizontalText,
            bubbleRenderSettings = settingsStore.loadNormalBubbleRenderSettings()
        )
        syncWebtoonEditSession()
    }

    private fun updateReadingInteractionState() {
        val isWebtoonScroll = folderReadingMode == FolderReadingMode.WEBTOON_SCROLL && !isEditMode
        binding.readingScrollContainer.scrollEnabled = shouldEnableReadingContainerScroll(
            readingMode = folderReadingMode,
            isEditMode = isEditMode,
            hasVerticalOverflow = hasCurrentPageVerticalOverflow
        )
        binding.translationOverlay.setTouchPassthroughEnabled(isWebtoonScroll)
    }

    private fun updateReadingContentLayout(decoded: DecodedReadingBitmap?) {
        val contentParams = binding.readingContentContainer.layoutParams as FrameLayout.LayoutParams
        val imageParams = binding.readingImage.layoutParams as FrameLayout.LayoutParams
        val transitionImageParams = binding.readingTransitionImage.layoutParams as FrameLayout.LayoutParams
        val overlayParams = binding.translationOverlay.layoutParams as FrameLayout.LayoutParams
        val scrollableContentHeight = decoded?.let {
            resolveFitWidthScrollableContentHeight(
                readingMode = folderReadingMode,
                displayMode = resolveReadingDisplayMode(it),
                contentWidth = it.displayWidth,
                contentHeight = it.displayHeight,
                viewportWidth = binding.readingScrollContainer.contentViewportWidth(),
                viewportHeight = binding.readingScrollContainer.contentViewportHeight()
            )
        }
        hasCurrentPageVerticalOverflow = scrollableContentHeight != null
        updateReadingInteractionState()
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL && decoded != null) {
            contentParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            imageParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            transitionImageParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            overlayParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.readingImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            binding.readingTransitionImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            binding.readingImage.adjustViewBounds = true
            binding.readingTransitionImage.adjustViewBounds = true
            binding.readingContentContainer.layoutParams = contentParams
            binding.readingImage.layoutParams = imageParams
            binding.readingTransitionImage.layoutParams = transitionImageParams
            binding.translationOverlay.layoutParams = overlayParams
            binding.readingImage.requestLayout()
            binding.readingImage.doOnLayout {
                val imageHeight = binding.readingImage.height
                if (imageHeight > 0) {
                    updateWebtoonChildHeight(binding.readingContentContainer, imageHeight)
                    updateWebtoonChildHeight(binding.readingTransitionImage, imageHeight)
                    updateWebtoonChildHeight(binding.translationOverlay, imageHeight)
                    updateOverlay(currentTranslation, currentBitmap)
                }
            }
        } else if (decoded != null && scrollableContentHeight != null) {
            // FIT_WIDTH overflow depends on both page and viewport aspect ratios.
            // A regular page can therefore need the same scroll layout as a long image.
            val fullHeight = scrollableContentHeight
            contentParams.height = fullHeight
            imageParams.height = fullHeight
            transitionImageParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            overlayParams.height = fullHeight
            binding.readingImage.scaleType = android.widget.ImageView.ScaleType.MATRIX
            binding.readingTransitionImage.scaleType = android.widget.ImageView.ScaleType.MATRIX
            binding.readingImage.adjustViewBounds = false
            binding.readingTransitionImage.adjustViewBounds = false
            binding.readingContentContainer.layoutParams = contentParams
            binding.readingImage.layoutParams = imageParams
            binding.readingTransitionImage.layoutParams = transitionImageParams
            binding.translationOverlay.layoutParams = overlayParams
            binding.readingContentContainer.doOnLayout {
                if (!isAdded || _binding == null || folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
                    return@doOnLayout
                }
                binding.readingScrollContainer.scrollTo(0, 0)
            }
        } else {
            // Keep the image view inside the actual visible viewport. On devices
            // where system-window insets are represented by parent padding,
            // MATCH_PARENT can include the inset area and make FIT_WIDTH center
            // the page below the visible bottom edge.
            val viewportHeight = binding.readingScrollContainer.contentViewportHeight()
            val pageHeight = viewportHeight.takeIf { it > 0 }
                ?: ViewGroup.LayoutParams.MATCH_PARENT
            contentParams.height = pageHeight
            imageParams.height = pageHeight
            transitionImageParams.height = pageHeight
            overlayParams.height = pageHeight
            binding.readingImage.scaleType = android.widget.ImageView.ScaleType.MATRIX
            binding.readingTransitionImage.scaleType = android.widget.ImageView.ScaleType.MATRIX
            binding.readingImage.adjustViewBounds = false
            binding.readingTransitionImage.adjustViewBounds = false
            binding.readingContentContainer.layoutParams = contentParams
            binding.readingImage.layoutParams = imageParams
            binding.readingTransitionImage.layoutParams = transitionImageParams
            binding.translationOverlay.layoutParams = overlayParams
            binding.readingContentContainer.doOnLayout {
                if (!isAdded || _binding == null || folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
                    return@doOnLayout
                }
                binding.readingScrollContainer.scrollTo(0, 0)
            }
        }
    }

    private fun updateWebtoonChildHeight(view: View, height: Int) {
        val params = view.layoutParams
        if (params.height == height) return
        params.height = height
        view.layoutParams = params
    }

    private fun computeOverlayDisplayRect(): RectF? {
        return if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            val width = binding.translationOverlay.width.toFloat()
            val height = binding.translationOverlay.height.toFloat()
            if (width <= 0f || height <= 0f) null else RectF(0f, 0f, width, height)
        } else {
            imageTransformController.computeImageDisplayRect()
        }
    }

    private fun startWebtoonTranslationWatcher() {
        if (folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) return
        if (webtoonTranslationWatchJob?.isActive == true) return
        refreshVisibleWebtoonTranslations()
        warmNearbyWebtoonTranslations()
        webtoonTranslationWatchJob = viewLifecycleOwner.lifecycleScope.launch {
            translationStore.updates.collect { path ->
                if (!isAdded || _binding == null || folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) {
                    return@collect
                }
                val nextPath = resolveNextImagePath(path)
                if (isVisibleWebtoonPath(path) || (nextPath != null && isVisibleWebtoonPath(nextPath))) {
                    webtoonAdapter.notifyTranslationChanged(path)
                }
            }
        }
    }

    private fun resolveNextImagePath(path: String): String? {
        val images = readingSessionViewModel.images.value.orEmpty()
        val index = images.indexOfFirst { it.absolutePath == path }
        if (index < 0) return null
        return images.getOrNull(index + 1)?.absolutePath
    }

    private fun refreshVisibleWebtoonTranslations() {
        val images = readingSessionViewModel.images.value.orEmpty()
        if (images.isEmpty()) return
        val firstVisible = webtoonLayoutManager.findFirstVisibleItemPosition()
        val lastVisible = webtoonLayoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return
        val paths = webtoonAdapter.imagePathsForAdapterRange(firstVisible, lastVisible)
        if (paths.isEmpty()) return
        for (path in paths) {
            webtoonAdapter.notifyTranslationChanged(path)
        }
    }

    private fun stopWebtoonTranslationWatcher(clearCache: Boolean = true) {
        webtoonTranslationWatchJob?.cancel()
        webtoonTranslationWatchJob = null
        webtoonTranslationWarmJob?.cancel()
        webtoonTranslationWarmJob = null
    }

    private fun findWebtoonTouchHolder(
        recyclerView: RecyclerView,
        event: MotionEvent
    ): WebtoonReadingAdapter.WebtoonPageViewHolder? {
        val zoomedHolder = activeWebtoonZoomHolder
        if (zoomedHolder != null && zoomedHolder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
            val itemView = zoomedHolder.itemView
            if (
                event.x >= itemView.x &&
                event.x <= itemView.x + itemView.width &&
                event.y >= itemView.y &&
                event.y <= itemView.y + itemView.height
            ) {
                return zoomedHolder
            }
        }
        val child = recyclerView.findChildViewUnder(event.x, event.y) ?: return null
        return recyclerView.getChildViewHolder(child) as? WebtoonReadingAdapter.WebtoonPageViewHolder
    }

    private fun dispatchWebtoonTouch(
        holder: WebtoonReadingAdapter.WebtoonPageViewHolder,
        event: MotionEvent
    ): Boolean {
        val localized = MotionEvent.obtain(event)
        localized.offsetLocation(-holder.itemView.x, -holder.itemView.y)
        return try {
            holder.handleTouchEvent(localized)
        } finally {
            localized.recycle()
        }
    }

    private fun syncActiveWebtoonZoomHolder(
        preferredHolder: WebtoonReadingAdapter.WebtoonPageViewHolder? = null
    ) {
        val preferredZoomed = preferredHolder?.takeIf {
            it.bindingAdapterPosition != RecyclerView.NO_POSITION && it.isZoomed()
        }
        if (preferredZoomed != null) {
            val previous = activeWebtoonZoomHolder
            if (previous != null && previous !== preferredZoomed && previous.isZoomed()) {
                previous.resetZoom()
            }
            activeWebtoonZoomHolder = preferredZoomed
            return
        }
        if (activeWebtoonZoomHolder?.isZoomed() != true) {
            activeWebtoonZoomHolder = null
        }
    }

    private fun isVisibleWebtoonPath(path: String): Boolean {
        val images = readingSessionViewModel.images.value.orEmpty()
        if (images.isEmpty()) return false
        val firstVisible = webtoonLayoutManager.findFirstVisibleItemPosition()
        val lastVisible = webtoonLayoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return false
        return webtoonAdapter.imagePathsForAdapterRange(firstVisible, lastVisible).contains(path)
    }

    private fun startTranslationWatcher(imageFile: java.io.File) {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return
        translationWatchJob?.cancel()
        translationWatchJob = viewLifecycleOwner.lifecycleScope.launch {
            reloadCurrentImageTranslation(imageFile)
            translationStore.updates.collect { path ->
                if (path == imageFile.absolutePath) {
                    reloadCurrentImageTranslation(imageFile)
                    return@collect
                }
            }
        }
    }

    private suspend fun reloadCurrentImageTranslation(imageFile: java.io.File) {
        if (currentImageFile?.absolutePath != imageFile.absolutePath) return
        val translation = withContext(Dispatchers.IO) {
            loadValidTranslationForCurrentFolder(imageFile)
        }
        if (currentImageFile?.absolutePath != imageFile.absolutePath) return
        currentTranslation = translation
        binding.readingImage.post {
            updateOverlay(translation, currentBitmap)
        }
    }

    private fun loadValidTranslationForCurrentFolder(imageFile: java.io.File): TranslationResult? {
        val folder = readingSessionViewModel.currentFolder.value ?: return translationStore.load(imageFile)
        return translationPipeline.loadValidTranslation(
            imageFile = imageFile,
            fullTranslate = preferencesGateway.isFullTranslateEnabled(folder),
            useVlDirectTranslate = preferencesGateway.isVlDirectTranslateEnabled(folder),
            language = preferencesGateway.getTranslationLanguage(folder),
            detectionSelection = preferencesGateway.getRegionDetectionSelection(folder),
            readingMode = preferencesGateway.getReadingMode(folder)
        )
    }

    private fun warmNearbyWebtoonTranslations() {
        if (folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) return
        val images = readingSessionViewModel.images.value.orEmpty()
        if (images.isEmpty()) return
        val firstVisible = webtoonLayoutManager.findFirstVisibleItemPosition()
        val lastVisible = webtoonLayoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return
        val firstImageIndex = webtoonAdapter.imageIndexForAdapterPosition(firstVisible)
        val lastImageIndex = webtoonAdapter.imageIndexForAdapterPosition(lastVisible)
        if (firstImageIndex == RecyclerView.NO_POSITION || lastImageIndex == RecyclerView.NO_POSITION) return
        val visibleStart = minOf(firstImageIndex, lastImageIndex).coerceAtLeast(0)
        val visibleEnd = maxOf(firstImageIndex, lastImageIndex).coerceAtMost(images.lastIndex)
        val start = (visibleStart - webtoonTranslationWarmRadius).coerceAtLeast(0)
        val end = (visibleEnd + webtoonTranslationWarmRadius).coerceAtMost(images.lastIndex)
        val warmTargets = ArrayList<java.io.File>(end - start + 1)
        for (index in start..end) {
            if (index in visibleStart..visibleEnd) continue
            warmTargets += images[index]
        }
        if (warmTargets.isEmpty()) return
        webtoonTranslationWarmJob?.cancel()
        webtoonTranslationWarmJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            for (imageFile in warmTargets) {
                if (!isActive || folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) {
                    break
                }
                loadValidTranslationForCurrentFolder(imageFile)
            }
        }
    }

    private fun persistReadingProgress() {
        val folder = readingSessionViewModel.currentFolder.value ?: return
        val index = readingSessionViewModel.index.value ?: return
        readingProgressStore.save(folder, index)
    }

    private fun persistWebtoonProgress() {
        val folder = readingSessionViewModel.currentFolder.value ?: return
        val adapterPosition = webtoonLayoutManager.findFirstVisibleItemPosition()
        if (adapterPosition == RecyclerView.NO_POSITION) return
        val imageIndex = webtoonAdapter.imageIndexForAdapterPosition(adapterPosition)
        if (imageIndex == RecyclerView.NO_POSITION) return
        readingProgressStore.save(folder, imageIndex)
    }

    private fun captureWebtoonScrollAnchor(): WebtoonScrollAnchor? {
        if (!::webtoonLayoutManager.isInitialized || !::webtoonAdapter.isInitialized) return null
        val adapterPosition = webtoonLayoutManager.findFirstVisibleItemPosition()
        if (adapterPosition == RecyclerView.NO_POSITION) return null
        val imageIndex = webtoonAdapter.imageIndexForAdapterPosition(adapterPosition)
        if (imageIndex == RecyclerView.NO_POSITION) return null
        val view = webtoonLayoutManager.findViewByPosition(adapterPosition)
        val topOffset = view?.top?.minus(binding.readingWebtoonList.paddingTop) ?: 0
        return WebtoonScrollAnchor(imageIndex, topOffset)
    }

    private fun restorePendingWebtoonScrollAnchor() {
        val anchor = pendingWebtoonScrollAnchor ?: return
        pendingWebtoonScrollAnchor = null
        if (!isAdded || _binding == null || folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) return
        val adapterPosition = webtoonAdapter.adapterPositionForImageIndex(anchor.imageIndex)
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: return
        binding.readingWebtoonList.post {
            if (!isAdded || _binding == null || folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) return@post
            webtoonLayoutManager.scrollToPositionWithOffset(adapterPosition, anchor.topOffset)
        }
    }

    private fun resolveWebtoonItemViewCacheSize(): Int {
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        return when {
            maxMemoryMb >= 768L -> 10
            maxMemoryMb >= 384L -> 8
            else -> 6
        }
    }

    private fun updateWebtoonPageInfo() {
        val folder = readingSessionViewModel.currentFolder.value ?: return
        val total = readingSessionViewModel.images.value.orEmpty().size
        if (total == 0) {
            binding.readingPageInfo.visibility = View.GONE
            return
        }
        val firstVisible = webtoonLayoutManager.findFirstVisibleItemPosition()
        val displayIndex = if (firstVisible == RecyclerView.NO_POSITION) {
            (readingSessionViewModel.index.value ?: 0).coerceIn(0, total - 1)
        } else {
            val imageIndex = webtoonAdapter.imageIndexForAdapterPosition(firstVisible)
            if (imageIndex == RecyclerView.NO_POSITION) {
                (readingSessionViewModel.index.value ?: 0).coerceIn(0, total - 1)
            } else {
                imageIndex.coerceIn(0, total - 1)
            }
        }
        binding.readingPageInfo.visibility = View.VISIBLE
        binding.readingPageInfo.text = getString(
            R.string.reading_page_info,
            folder.name,
            displayIndex + 1,
            total
        )
    }

    private fun persistCurrentTranslation(forceSave: Boolean = false) {
        val imageFile = currentImageFile ?: return
        val translation = currentTranslation ?: return
        val offsets = currentOverlayOffsets()
        if (offsets.isEmpty() && !forceSave) return
        val updatedBubbles = translation.bubbles.map { bubble ->
            val offset = offsets[bubble.id] ?: (0f to 0f)
            bubble.copy(
                rect = RectF(
                    bubble.rect.left + offset.first,
                    bubble.rect.top + offset.second,
                    bubble.rect.right + offset.first,
                    bubble.rect.bottom + offset.second
                ),
                maskContour = BubbleShapePaths.translateMaskContour(
                    contour = bubble.maskContour,
                    deltaX = offset.first,
                    deltaY = offset.second,
                    sourceWidth = translation.width,
                    sourceHeight = translation.height
                )
            )
        }
        val updated = translation.copy(bubbles = updatedBubbles)
        translationStore.save(imageFile, updated)
        currentTranslation = updated
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            webtoonAdapter.notifyTranslationChanged(imageFile.absolutePath)
        }
        applyOverlayOffsets(emptyMap())
        renderCurrentTranslation()
    }

    private fun handleBubbleRemove(bubbleId: Int) {
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        val remaining = translation.bubbles.filterNot { it.id == bubbleId }
        if (remaining.size == translation.bubbles.size) return
        if (currentResizeModeBubbleId() == bubbleId) {
            exitBubbleResizeMode()
        }
        currentTranslation = translation.copy(bubbles = remaining)
        val offsets = currentOverlayOffsets()
        offsets.remove(bubbleId)
        applyOverlayOffsets(offsets)
        renderCurrentTranslation()
    }

    private fun clearAllBubbles() {
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        if (translation.bubbles.isEmpty()) return
        exitBubbleResizeMode()
        currentTranslation = translation.copy(bubbles = emptyList())
        applyOverlayOffsets(emptyMap())
        renderCurrentTranslation()
        Toast.makeText(requireContext(), R.string.reading_clear_bubbles_done, Toast.LENGTH_SHORT).show()
    }

    private fun handleBubbleEdit(bubbleId: Int) {
        if (blockBubbleEditingWhileZoomed()) return
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        val bubble = translation.bubbles.firstOrNull { it.id == bubbleId } ?: return
        val input = EditText(requireContext()).apply {
            setText(bubble.text)
            setSelection(text?.length ?: 0)
            minLines = 2
            if (!bubble.hasDisplayText()) {
                hint = getString(R.string.reading_empty_bubble_hint)
            }
            setTextColor(resolveColorAttr(R.attr.dialogTextColor))
            setHintTextColor(resolveColorAttr(R.attr.dialogHintTextColor))
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
                        current.withManualText(updatedText)
                    } else {
                        current
                    }
                }
                val updated = refreshed.copy(bubbles = updatedBubbles)
                currentTranslation = updated
                renderCurrentTranslation()
                saveCurrentTranslation()
            }
            .show()
    }

    private fun showBubbleActionDialog(bubbleId: Int) {
        if (blockBubbleEditingWhileZoomed()) return
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        val bubble = translation.bubbles.firstOrNull { it.id == bubbleId } ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_bubble_actions, null)
        val resizeButton = dialogView.findViewById<android.widget.Button>(R.id.bubbleActionResize)
        val deleteButton = dialogView.findViewById<android.widget.Button>(R.id.bubbleActionDelete)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        if (bubble.supportsResizeEditing()) {
            resizeButton.setOnClickListener {
                dialog.dismiss()
                enterBubbleResizeMode(bubbleId)
            }
        } else {
            resizeButton.visibility = View.GONE
        }
        deleteButton.setOnClickListener {
            dialog.dismiss()
            handleBubbleRemove(bubbleId)
        }
        dialog.show()
    }

    private fun enterBubbleResizeMode(bubbleId: Int) {
        if (blockBubbleEditingWhileZoomed()) return
        if (!isEditMode) return
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            webtoonAdapter.enterLockedBubbleResizeMode(bubbleId)
        } else {
            binding.translationOverlay.enterResizeMode(bubbleId)
        }
    }

    private fun exitBubbleResizeMode() {
        if (!isAdded || _binding == null) return
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            webtoonAdapter.exitLockedBubbleResizeMode()
        } else {
            binding.translationOverlay.exitResizeMode()
        }
        updateBubbleSizeFloatingControls()
    }

    private fun currentResizeModeBubbleId(): Int? {
        return if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            webtoonAdapter.getLockedBubbleResizeModeId()
        } else {
            binding.translationOverlay.getResizeModeBubbleId()
        }
    }

    private fun updateBubbleSizeFloatingControls() {
        if (!isAdded || _binding == null) return
        val visible = isEditMode && currentResizeModeBubbleId() != null
        binding.readingBubbleSizeFloatingControls.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun blockBubbleEditingWhileZoomed(): Boolean {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return false
        if (!imageTransformController.isZoomed()) return false
        Toast.makeText(
            requireContext(),
            R.string.reading_exit_zoom_before_edit,
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    private fun saveCurrentTranslation() {
        val translation = currentTranslation ?: return
        saveTranslationToDisk(translation)
    }

    private fun shouldDeferEditPersistence(): Boolean {
        return isEditMode
    }

    private fun renderAndMaybePersistCurrentTranslation() {
        renderCurrentTranslation()
        if (shouldDeferEditPersistence()) return
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            pendingWebtoonScrollAnchor = captureWebtoonScrollAnchor()
        }
        saveCurrentTranslation()
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            restorePendingWebtoonScrollAnchor()
        }
    }

    private fun saveTranslationToDisk(translation: TranslationResult) {
        val imageFile = currentImageFile ?: return
        translationStore.save(imageFile, translation)
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            webtoonAdapter.notifyTranslationChanged(imageFile.absolutePath)
        }
    }

    private fun enterCreateBubbleMode() {
        if (!isEditMode) return
        if (blockBubbleEditingWhileZoomed()) return
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            addNewBubble()
            return
        }
        binding.readingEditControls.visibility = View.GONE
        exitBubbleResizeMode()
        binding.translationOverlay.setCreateBubbleMode(true)
        Toast.makeText(
            requireContext(),
            R.string.reading_create_bubble_hint,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun handleBubbleCreatedFromDrag(rect: RectF) {
        if (!isEditMode) return
        binding.readingEditControls.visibility = View.VISIBLE
        binding.translationOverlay.setCreateBubbleMode(false)
        val translation = currentTranslation ?: return
        val nextId = (translation.bubbles.maxOfOrNull { it.id } ?: -1) + 1
        val newBubble = BubbleTranslation.pending(
            nextId,
            RectF(rect),
            "",
            BubbleSource.MANUAL,
            ownerImageName = currentImageFile?.name
        )
        val updated = translation.copy(bubbles = translation.bubbles + newBubble)
        currentTranslation = updated
        renderAndMaybePersistCurrentTranslation()
    }

    private fun handleBubbleResized(bubbleId: Int, newRect: RectF) {
        val translation = currentTranslation ?: return
        val offsets = currentOverlayOffsets()
        val offset = offsets[bubbleId] ?: (0f to 0f)
        val materializedRect = RectF(newRect).apply {
            offset(offset.first, offset.second)
        }
        val updatedBubbles = translation.bubbles.map { bubble ->
            if (bubble.id == bubbleId) {
                bubble.copy(rect = RectF(materializedRect))
            } else {
                bubble
            }
        }
        val updated = translation.copy(bubbles = updatedBubbles)
        currentTranslation = updated
        if (offset != (0f to 0f)) {
            offsets.remove(bubbleId)
            applyOverlayOffsets(offsets)
        }
        renderAndMaybePersistCurrentTranslation()
    }

    private fun adjustSelectedBubbleSize(deltaPercent: Int) {
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        val bubbleId = currentResizeModeBubbleId() ?: return

        val bubble = translation.bubbles.firstOrNull { it.id == bubbleId } ?: return
        val offsets = currentOverlayOffsets()
        val offset = offsets[bubbleId] ?: (0f to 0f)
        val visualRect = RectF(bubble.rect).apply {
            offset(offset.first, offset.second)
        }
        val scale = 1f + deltaPercent / 100f
        if (scale <= 0f) return

        val centerX = visualRect.centerX()
        val centerY = visualRect.centerY()
        val newWidth = (visualRect.width() * scale).coerceAtLeast(8f)
        val newHeight = (visualRect.height() * scale).coerceAtLeast(8f)

        var left = centerX - newWidth / 2f
        var top = centerY - newHeight / 2f
        var right = left + newWidth
        var bottom = top + newHeight

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
        val maxBottom = if (isWebtoonEditSessionActive()) {
            imageHeight * 2f
        } else {
            imageHeight
        }
        if (bottom > maxBottom) {
            top -= bottom - maxBottom
            bottom = maxBottom
        }
        if (left < 0f) left = 0f
        if (top < 0f) top = 0f

        val updatedRect = RectF(left, top, right, bottom)
        val updatedBubbles = translation.bubbles.map { current ->
            if (current.id == bubbleId) current.copy(rect = updatedRect) else current
        }
        currentTranslation = translation.copy(bubbles = updatedBubbles)
        if (offset != (0f to 0f)) {
            offsets.remove(bubbleId)
            applyOverlayOffsets(offsets)
        }
        renderAndMaybePersistCurrentTranslation()
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
        val left: Float
        val top: Float
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            val lockedHolder = findLockedWebtoonHolder()
            val imageCenter = lockedHolder?.computeVisibleCenterImagePoint()
            if (imageCenter != null) {
                left = (imageCenter.first - bubbleWidth / 2f).coerceIn(0f, width - bubbleWidth)
                top = (imageCenter.second - bubbleHeight / 2f).coerceIn(0f, height * 2f - bubbleHeight)
            } else {
                left = (width - bubbleWidth) / 2f
                top = (height - bubbleHeight) / 2f
            }
        } else {
            left = (width - bubbleWidth) / 2f
            top = (height - bubbleHeight) / 2f
        }
        val rect = RectF(left, top, left + bubbleWidth, top + bubbleHeight)
        val nextId = (translation.bubbles.maxOfOrNull { it.id } ?: -1) + 1
        val newBubble = BubbleTranslation.pending(
            nextId,
            rect,
            "",
            BubbleSource.MANUAL,
            ownerImageName = currentImageFile?.name
        )
        val updated = translation.copy(bubbles = translation.bubbles + newBubble)
        currentTranslation = updated
        renderAndMaybePersistCurrentTranslation()
        enterBubbleResizeMode(nextId)
    }

    private fun findLockedWebtoonHolder(): WebtoonReadingAdapter.WebtoonPageViewHolder? {
        val lockedIndex = webtoonLockedPageIndex ?: return null
        val range = webtoonAdapter.adapterPositionRangeForImageIndex(lockedIndex) ?: return null
        for (position in range) {
            val holder = binding.readingWebtoonList.findViewHolderForAdapterPosition(position)
                as? WebtoonReadingAdapter.WebtoonPageViewHolder ?: continue
            if (holder.boundImagePath == webtoonLockedPagePath) return holder
        }
        return null
    }

    private fun processEmptyBubbles() {
        val imageFile = currentImageFile ?: return
        val folder = readingSessionViewModel.currentFolder.value ?: return
        val translation = currentTranslation ?: return
        if (translation.bubbles.none { it.needsTranslationRetry() }) return
        Toast.makeText(requireContext(), R.string.reading_empty_bubble_translating, Toast.LENGTH_SHORT).show()
        emptyBubbleJob?.cancel()
        emptyBubbleJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                if (!isAdded) return@launch
                if (currentImageFile?.absolutePath != imageFile.absolutePath) return@launch
                val current = currentTranslation ?: return@launch
                if (current.bubbles.none { it.needsTranslationRetry() }) return@launch
                try {
                    val outcome = emptyBubbleCoordinator.process(
                        imageFile,
                        folder,
                        current
                    )
                    if (outcome == null) {
                        if (isAdded) {
                            Toast.makeText(
                                requireContext(),
                                R.string.reading_empty_bubble_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@launch
                    }
                    if (currentImageFile?.absolutePath == imageFile.absolutePath) {
                        currentTranslation = outcome.updatedTranslation
                        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
                            webtoonAdapter.notifyTranslationChanged(imageFile.absolutePath)
                        } else {
                            binding.translationOverlay.setTranslations(outcome.updatedTranslation)
                        }
                        if (outcome.translatedByLlm) {
                            Toast.makeText(
                                requireContext(),
                                R.string.reading_empty_bubble_translated,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (outcome.ocrFailedCount > 0) {
                            Toast.makeText(
                                requireContext(),
                                R.string.reading_empty_bubble_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    return@launch
                } catch (e: LlmResponseException) {
                    AppLogger.log("Reading", "Reading empty bubble model response invalid", e)
                    if (!awaitEmptyBubbleModelErrorRetry(e.responseContent)) {
                        return@launch
                    }
                } catch (e: LlmRequestException) {
                    AppLogger.log("Reading", "Reading empty bubble request failed", e)
                    if (!isAdded) return@launch
                    dialogs.showApiErrorDialog(requireContext(), e.errorCode, e.responseBody)
                    return@launch
                }
            }
        }
    }

    private suspend fun awaitEmptyBubbleModelErrorRetry(responseContent: String): Boolean {
        if (!isAdded) return false
        activeEmptyBubbleModelErrorDialog?.dismiss()
        val decision = CompletableDeferred<Boolean>()
        val dialog = dialogs.showModelErrorDialog(
            context = requireContext(),
            responseContent = responseContent,
            onRetry = { decision.complete(true) },
            onSkip = { decision.complete(false) },
            negativeButtonResId = R.string.close_action
        )
        dialog.setOnDismissListener {
            activeEmptyBubbleModelErrorDialog = null
            if (!decision.isCompleted) {
                decision.complete(false)
            }
        }
        activeEmptyBubbleModelErrorDialog = dialog
        return decision.await()
    }
}
