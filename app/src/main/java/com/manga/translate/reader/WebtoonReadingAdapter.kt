package com.manga.translate.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.ItemReadingWebtoonPageBinding
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.model.TranslationResult
import com.manga.translate.settings.NormalBubbleRenderSettings
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Projects a previous-page union bubble into the current page's coordinate system so both
 * overlays can draw the same top-origin layout. Each overlay clips to its own page bounds.
 */
internal fun projectPreviousSpillBubbles(
    previous: TranslationResult?,
    previousImageName: String,
    currentWidth: Int,
    currentHeight: Int
): List<BubbleTranslation> {
    val previousTranslation = previous ?: return emptyList()
    if (previousTranslation.height <= 0 || currentHeight <= 0) return emptyList()
    val previousWidth = previousTranslation.width.toFloat()
    val targetWidth = currentWidth.toFloat()
    if (previousWidth <= 0f || targetWidth <= 0f) return emptyList()
    val fitWidthScale = targetWidth / previousWidth
    val previousHeight = previousTranslation.height.toFloat()
    return previousTranslation.bubbles.mapNotNull { bubble ->
        if (bubble.resolvedOwnerImageName(previousImageName) != previousImageName) {
            return@mapNotNull null
        }
        if (bubble.rect.bottom <= previousHeight) return@mapNotNull null
        val projected = RectF(
            bubble.rect.left * fitWidthScale,
            (bubble.rect.top - previousHeight) * fitWidthScale,
            bubble.rect.right * fitWidthScale,
            (bubble.rect.bottom - previousHeight) * fitWidthScale
        )
        if (projected.bottom <= 0f || projected.top >= currentHeight.toFloat()) {
            return@mapNotNull null
        }
        bubble.copy(
            rect = projected,
            ownerImageName = previousImageName
        )
    }
}

class WebtoonReadingAdapter(
    private val scope: CoroutineScope,
    private val loadTranslation: (File) -> TranslationResult?,
    private val bitmapCache: ReadingBitmapCache
) : RecyclerView.Adapter<WebtoonReadingAdapter.WebtoonPageViewHolder>() {
    data class BoundPageSnapshot(
        val imageFile: File,
        val translation: TranslationResult?,
        val sourceWidth: Int,
        val sourceHeight: Int
    )

    private data class PresentationConfig(
        val verticalLayoutEnabled: Boolean,
        val bubbleRenderSettings: NormalBubbleRenderSettings
    )

    data class WebtoonDisplayItem(
        val imageFile: File,
        val imageIndex: Int
    ) {
        val path: String
            get() = imageFile.absolutePath

        val stableKey: String
            get() = path
    }

    private companion object {
        const val PAYLOAD_PRESENTATION_ONLY = "presentation_only"
        const val PAYLOAD_TRANSLATION_ONLY = "translation_only"
        const val PAYLOAD_PLACEHOLDER_ONLY = "placeholder_only"
        const val DEFAULT_PLACEHOLDER_HEIGHT_RATIO = 1.4f
        const val SOURCE_SIZE_BATCH_SIZE = 12
    }

    private var items: List<File> = emptyList()
    private var displayItems: List<WebtoonDisplayItem> = emptyList()
    private var verticalLayoutEnabled: Boolean = true
    private var bubbleRenderSettings = NormalBubbleRenderSettings(
        shrinkPercent = 0,
        opacityPercent = 100,
        freeBubbleShrinkPercent = 0,
        freeBubbleOpacityPercent = 100,
        useHorizontalText = true
    )
    private val runtimeCacheLimit = computeRuntimeCacheLimit()
    private val rememberedPageHeights = LruMap<String, Int>(runtimeCacheLimit)
    private val sourceSizeCache = LruMap<String, Size>(runtimeCacheLimit)
    private val translationCache = LruMap<String, TranslationResult?>(runtimeCacheLimit)
    private val boundHolders = mutableMapOf<String, MutableSet<WebtoonPageViewHolder>>()
    private val translationLoadLock = Any()
    private val translationLoadJobs = mutableMapOf<String, Deferred<TranslationResult?>>()

    private class LruMap<K, V>(private val maxSize: Int) :
        LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }

    private var editModeEnabled = false
    private var lockedPageImageIndex: Int? = null
    private var lockedPagePath: String? = null
    private var lockedPageTranslation: TranslationResult? = null
    private var lockedPageOffsets: Map<Int, Pair<Float, Float>> = emptyMap()
    private var sourceSizePrefetchJob: Job? = null

    var onLockedBubbleOffsetChanged: ((Int, Float, Float) -> Unit)? = null
    var onLockedBubbleRemove: ((Int) -> Unit)? = null
    var onLockedBubbleTap: ((Int) -> Unit)? = null
    var onLockedBubbleResizeTap: ((Int) -> Unit)? = null
    var onLockedBubbleResized: ((Int, RectF) -> Unit)? = null
    var onLockedBubbleResizeModeChanged: ((Int?) -> Unit)? = null
    var onLockedBubbleLongPress: ((Int) -> Unit)? = null
    var onDisplayStructureChanging: (() -> Unit)? = null
    var onDisplayStructureChanged: (() -> Unit)? = null

    fun submit(
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        bubbleRenderSettings: NormalBubbleRenderSettings
    ) {
        val previousDisplayItems = displayItems
        val newDisplayItems = buildDisplayItems(images)
        val previousConfig = PresentationConfig(
            verticalLayoutEnabled = this.verticalLayoutEnabled,
            bubbleRenderSettings = this.bubbleRenderSettings
        )
        val newConfig = PresentationConfig(
            verticalLayoutEnabled = verticalLayoutEnabled,
            bubbleRenderSettings = bubbleRenderSettings
        )
        val diffResult = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previousDisplayItems.size

                override fun getNewListSize(): Int = newDisplayItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return previousDisplayItems[oldItemPosition].stableKey ==
                        newDisplayItems[newItemPosition].stableKey
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return areItemsTheSame(oldItemPosition, newItemPosition) && previousConfig == newConfig
                }

                override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
                    if (!areItemsTheSame(oldItemPosition, newItemPosition)) return null
                    return if (
                        previousConfig.verticalLayoutEnabled != newConfig.verticalLayoutEnabled ||
                        previousConfig.bubbleRenderSettings != newConfig.bubbleRenderSettings
                    ) {
                        PAYLOAD_PRESENTATION_ONLY
                    } else {
                        null
                    }
                }
            }
        )
        items = images
        displayItems = newDisplayItems
        bitmapCache.retainPaths(images.mapTo(hashSetOf()) { it.absolutePath })
        pruneTranslationCache(images)
        pruneSourceSizeCache(images)
        this.verticalLayoutEnabled = verticalLayoutEnabled
        this.bubbleRenderSettings = bubbleRenderSettings
        diffResult.dispatchUpdatesTo(this)
        prefetchSourceSizes(images)
    }

    fun updateEditSession(
        enabled: Boolean,
        lockedImageIndex: Int?,
        lockedImagePath: String?,
        translation: TranslationResult?,
        offsets: Map<Int, Pair<Float, Float>>
    ) {
        val affectedPaths = linkedSetOf<String>()
        lockedPagePath?.let(affectedPaths::add)
        pathForImageIndex(lockedPageImageIndex?.plus(1))?.let(affectedPaths::add)
        lockedImagePath?.let(affectedPaths::add)
        pathForImageIndex(lockedImageIndex?.plus(1))?.let(affectedPaths::add)
        editModeEnabled = enabled
        lockedPageImageIndex = lockedImageIndex
        lockedPagePath = lockedImagePath
        lockedPageTranslation = translation
        lockedPageOffsets = offsets.toMap()
        for (path in affectedPaths) {
            refreshPath(path)
        }
    }

    fun findBoundPageSnapshot(imagePath: String): BoundPageSnapshot? {
        return boundHolders[imagePath]?.firstOrNull()?.buildSnapshot()
    }

    fun setEditSessionGestureInteracting(active: Boolean) {
        val path = lockedPagePath ?: return
        boundHolders[path]?.forEach { it.applyGestureInteracting(active) }
    }

    fun enterLockedBubbleResizeMode(bubbleId: Int) {
        val path = lockedPagePath ?: return
        boundHolders[path]?.forEach { it.enterResizeMode(bubbleId) }
    }

    fun exitLockedBubbleResizeMode() {
        val path = lockedPagePath ?: return
        boundHolders[path]?.forEach { it.exitResizeMode() }
    }

    fun getLockedBubbleResizeModeId(): Int? {
        val path = lockedPagePath ?: return null
        return boundHolders[path]
            ?.asSequence()
            ?.mapNotNull { it.getResizeModeBubbleId() }
            ?.firstOrNull()
    }

    fun adapterPositionForImageIndex(imageIndex: Int): Int {
        if (imageIndex < 0) return RecyclerView.NO_POSITION
        return displayItems.indexOfFirst { it.imageIndex == imageIndex }
    }

    fun adapterPositionRangeForImageIndex(imageIndex: Int): IntRange? {
        if (imageIndex < 0) return null
        var first = RecyclerView.NO_POSITION
        var last = RecyclerView.NO_POSITION
        displayItems.forEachIndexed { index, item ->
            if (item.imageIndex != imageIndex) return@forEachIndexed
            if (first == RecyclerView.NO_POSITION) first = index
            last = index
        }
        return if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            null
        } else {
            first..last
        }
    }

    fun imageIndexForAdapterPosition(adapterPosition: Int): Int {
        return displayItems.getOrNull(adapterPosition)?.imageIndex ?: RecyclerView.NO_POSITION
    }

    fun imagePathsForAdapterRange(startPosition: Int, endPosition: Int): Set<String> {
        if (displayItems.isEmpty()) return emptySet()
        val start = startPosition.coerceAtLeast(0)
        val end = endPosition.coerceAtMost(displayItems.lastIndex)
        if (start > end) return emptySet()
        return (start..end).mapTo(linkedSetOf()) { displayItems[it].path }
    }

    fun clearRuntimeCaches() {
        sourceSizePrefetchJob?.cancel()
        synchronized(translationLoadLock) {
            translationLoadJobs.values.forEach { it.cancel() }
            translationLoadJobs.clear()
        }
        boundHolders.values.flatten().forEach { it.releaseForCacheClear() }
        boundHolders.clear()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WebtoonPageViewHolder {
        val binding = ItemReadingWebtoonPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return WebtoonPageViewHolder(binding)
    }

    override fun getItemCount(): Int = displayItems.size

    override fun onBindViewHolder(holder: WebtoonPageViewHolder, position: Int) {
        holder.bind(
            item = displayItems[position],
            verticalLayoutEnabled = verticalLayoutEnabled,
            bubbleRenderSettings = bubbleRenderSettings
        )
    }

    override fun onBindViewHolder(
        holder: WebtoonPageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_TRANSLATION_ONLY)) {
            holder.reloadTranslationOverlay()
            return
        }
        if (payloads.contains(PAYLOAD_PLACEHOLDER_ONLY)) {
            holder.refreshPlaceholderHeight()
            return
        }
        if (payloads.contains(PAYLOAD_PRESENTATION_ONLY)) {
            holder.updatePresentation(
                verticalLayoutEnabled = verticalLayoutEnabled,
                bubbleRenderSettings = bubbleRenderSettings
            )
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: WebtoonPageViewHolder) {
        holder.recycle()
    }

    fun notifyTranslationChanged(imagePath: String) {
        val impactedPaths = linkedSetOf(imagePath)
        nextPathFor(imagePath)?.let(impactedPaths::add)
        impactedPaths.forEach { path ->
            translationCache.remove(path)
            synchronized(translationLoadLock) {
                translationLoadJobs.remove(path)?.cancel()
            }
        }
        displayItems.forEachIndexed { index, item ->
            if (item.path !in impactedPaths) return@forEachIndexed
            notifyItemChanged(index, PAYLOAD_TRANSLATION_ONLY)
        }
    }

    private fun pruneTranslationCache(images: List<File>) {
        val activePaths = images.mapTo(hashSetOf()) { it.absolutePath }
        if (translationCache.isNotEmpty()) {
            translationCache.keys.retainAll(activePaths)
        }
        synchronized(translationLoadLock) {
            val iterator = translationLoadJobs.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in activePaths) {
                    entry.value.cancel()
                    iterator.remove()
                }
            }
        }
    }

    private fun pruneSourceSizeCache(images: List<File>) {
        if (sourceSizeCache.isEmpty()) return
        val activePaths = images.mapTo(hashSetOf()) { it.absolutePath }
        val iterator = sourceSizeCache.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() !in activePaths) {
                iterator.remove()
            }
        }
    }

    private suspend fun loadTranslationShared(imageFile: File): TranslationResult? {
        val imagePath = imageFile.absolutePath
        if (translationCache.containsKey(imagePath)) {
            return translationCache[imagePath]
        }
        val deferred = synchronized(translationLoadLock) {
            if (translationCache.containsKey(imagePath)) {
                return translationCache[imagePath]
            }
            translationLoadJobs[imagePath] ?: scope.async(Dispatchers.IO) {
                loadTranslation(imageFile)
            }.also { translationLoadJobs[imagePath] = it }
        }
        return try {
            deferred.await().also { translation ->
                translationCache[imagePath] = translation
            }
        } finally {
            synchronized(translationLoadLock) {
                if (translationLoadJobs[imagePath] === deferred) {
                    translationLoadJobs.remove(imagePath)
                }
            }
        }
    }

    private fun prefetchSourceSizes(images: List<File>) {
        sourceSizePrefetchJob?.cancel()
        val uncached = images.filterNot { sourceSizeCache.containsKey(it.absolutePath) }
        if (uncached.isEmpty()) return
        sourceSizePrefetchJob = scope.launch {
            val activePathsSnapshot = items.mapTo(hashSetOf()) { it.absolutePath }
            for (batch in uncached.chunked(SOURCE_SIZE_BATCH_SIZE)) {
                ensureActive()
                val sizes = withContext(Dispatchers.IO) {
                    batch.mapNotNull { imageFile ->
                        if (imageFile.absolutePath !in activePathsSnapshot) {
                            null
                        } else {
                            readImageSize(imageFile)?.let { imageFile.absolutePath to it }
                        }
                    }
                }
                if (sizes.isEmpty()) continue
                val updatedPositions = ArrayList<Int>(sizes.size)
                sizes.forEach { (path, size) ->
                    sourceSizeCache.put(path, size)
                    val position = displayItems.indexOfFirst { it.path == path }
                    if (position >= 0) {
                        updatedPositions.add(position)
                    }
                }
                if (updatedPositions.isNotEmpty()) {
                    val minPos = updatedPositions.min()
                    val maxPos = updatedPositions.max()
                    notifyItemRangeChanged(minPos, maxPos - minPos + 1, PAYLOAD_PLACEHOLDER_ONLY)
                }
            }
        }
    }

    private fun readImageSize(imageFile: File): Size? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            Size(options.outWidth, options.outHeight)
        } else {
            null
        }
    }

    private fun refreshPath(path: String) {
        val holders = boundHolders[path]
        if (!holders.isNullOrEmpty()) {
            holders.forEach { it.refreshOverlayPresentation() }
            return
        }
        displayItems.forEachIndexed { index, item ->
            if (item.path != path) return@forEachIndexed
            notifyItemChanged(index, PAYLOAD_PRESENTATION_ONLY)
        }
    }

    private fun buildDisplayItems(images: List<File>): List<WebtoonDisplayItem> {
        val result = ArrayList<WebtoonDisplayItem>(images.size)
        images.forEachIndexed { index, imageFile ->
            result += WebtoonDisplayItem(imageFile, index)
        }
        return result
    }

    private fun computeRuntimeCacheLimit(): Int {
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        return when {
            maxMemoryMb >= 768 -> 80
            maxMemoryMb >= 512 -> 60
            maxMemoryMb >= 256 -> 40
            else -> 25
        }
    }

    private fun pathForImageIndex(imageIndex: Int?): String? {
        val resolvedIndex = imageIndex ?: return null
        return items.getOrNull(resolvedIndex)?.absolutePath
    }

    private fun nextPathFor(imagePath: String): String? {
        val currentIndex = displayItems.firstOrNull { it.path == imagePath }?.imageIndex ?: return null
        return pathForImageIndex(currentIndex + 1)
    }

    inner class WebtoonPageViewHolder(
        private val binding: ItemReadingWebtoonPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val imageTransformController = ReadingImageTransformController(
            context = binding.root.context,
            imageView = binding.readingPageImage,
            hasBubbleAt = { x, y -> binding.readingPageOverlay.hasBubbleAt(x, y) },
            onMatrixUpdated = { updateOverlayDisplayRect() },
            allowPanWhenOverflowing = false
        )
        private val touchSlop = ViewConfiguration.get(binding.root.context).scaledTouchSlop.toFloat()
        private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        private val doubleTapSlop = ViewConfiguration.get(binding.root.context).scaledDoubleTapSlop.toFloat()
        private var bindJob: Job? = null
        private var overlayReloadJob: Job? = null
        private var boundPath: String? = null
        private var boundFile: File? = null
        private var boundItem: WebtoonDisplayItem? = null
        private var currentDecodedImage: DecodedReadingBitmap? = null
        private var currentBitmap: Bitmap? = null
        private var currentBitmapLease: ReadingBitmapCache.Lease? = null
        private var currentImageWidth: Int = 0
        private var currentImageHeight: Int = 0
        private var currentBaseTranslation: TranslationResult? = null
        private var previousPageTranslation: TranslationResult? = null
        private var downX = 0f
        private var downY = 0f
        private var touchMoved = false
        private var lastTapTime = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f

        /** Public accessor for the currently bound image path. */
        val boundImagePath: String?
            get() = boundPath

        private fun releaseCurrentHolderBitmap() {
            binding.readingPageOverlay.setSourceBitmap(null)
            binding.readingPageOverlay.setSourceImageFile(null)
            binding.readingPageImage.setRegionSource(null)
            binding.readingPageImage.setImageDrawable(null)
            imageTransformController.setCurrentBitmap(null)
            currentBitmapLease?.close()
            currentBitmapLease = null
            currentDecodedImage = null
            currentBitmap = null
        }

        fun releaseForCacheClear() {
            releaseCurrentHolderBitmap()
        }

        /**
         * Computes the image-local point that corresponds to the current visible center
         * of this page within the RecyclerView.
         * Returns null if the view is not laid out or no image content is available.
         */
        fun computeVisibleCenterImagePoint(): Pair<Float, Float>? {
            if (!hasCurrentContent() || currentImageWidth <= 0 || currentImageHeight <= 0) return null
            val recyclerView = binding.root.parent as? RecyclerView ?: return null
            val recyclerLeft = recyclerView.paddingLeft.toFloat()
            val recyclerRight = (recyclerView.width - recyclerView.paddingRight).toFloat()
            val recyclerTop = recyclerView.paddingTop.toFloat()
            val recyclerBottom = (recyclerView.height - recyclerView.paddingBottom).toFloat()
            if (recyclerRight <= recyclerLeft || recyclerBottom <= recyclerTop) return null
            val recyclerCenterX = (recyclerLeft + recyclerRight) / 2f
            val recyclerCenterY = (recyclerTop + recyclerBottom) / 2f
            return recyclerPointToImagePoint(recyclerCenterX, recyclerCenterY)
                ?: computeVisibleCenterY()?.let { currentImageWidth / 2f to it }
        }

        /**
         * Computes the image-local Y coordinate that corresponds to the current visible
         * vertical center of this page within the RecyclerView.
         * Returns null if the view is not laid out or no image content is available.
         */
        fun computeVisibleCenterY(): Float? {
            if (!hasCurrentContent() || currentImageHeight <= 0) return null
            val recyclerView = binding.root.parent as? RecyclerView ?: return null
            val recyclerTop = recyclerView.paddingTop
            val recyclerBottom = recyclerView.height - recyclerView.paddingBottom
            val itemTopInRecycler = binding.root.top
            val itemBottomInRecycler = binding.root.bottom
            if (itemTopInRecycler == itemBottomInRecycler) return null
            val visibleTop = itemTopInRecycler.coerceAtLeast(recyclerTop)
            val visibleBottom = itemBottomInRecycler.coerceAtMost(recyclerBottom)
            if (visibleTop >= visibleBottom) return null
            val itemHeight = (itemBottomInRecycler - itemTopInRecycler).toFloat()
            if (itemHeight <= 0f) return null
            val visibleTopFraction = (visibleTop - itemTopInRecycler) / itemHeight
            val visibleBottomFraction = (visibleBottom - itemTopInRecycler) / itemHeight
            val imageHeight = currentImageHeight.toFloat()
            val centerY = ((visibleTopFraction + visibleBottomFraction) / 2f) * imageHeight
            return centerY.coerceIn(0f, imageHeight)
        }

        /**
         * Converts a point in the RecyclerView's coordinate space to this page's image-local
         * coordinates, taking the current zoom/pan matrix into account.
         * Returns null if the view is not laid out or no image content is available.
         */
        fun recyclerPointToImagePoint(recyclerX: Float, recyclerY: Float): Pair<Float, Float>? {
            if (!hasCurrentContent() || currentImageWidth <= 0 || currentImageHeight <= 0) return null
            val localX = recyclerX - binding.root.left
            val localY = recyclerY - binding.root.top
            val matrix = binding.readingPageImage.imageMatrix
            val inverse = Matrix()
            if (!matrix.invert(inverse)) return null
            val point = floatArrayOf(localX, localY)
            inverse.mapPoints(point)
            val imageX = point[0].coerceIn(0f, currentImageWidth.toFloat())
            val imageY = point[1].coerceIn(0f, currentImageHeight.toFloat())
            return imageX to imageY
        }

        fun bind(
            item: WebtoonDisplayItem,
            verticalLayoutEnabled: Boolean,
            bubbleRenderSettings: NormalBubbleRenderSettings
        ) {
            val imageFile = item.imageFile
            val previousPath = boundPath
            bindJob?.cancel()
            overlayReloadJob?.cancel()
            if (previousPath != null && previousPath != imageFile.absolutePath) {
                unregisterBoundHolder(previousPath)
            }
            releaseCurrentHolderBitmap()
            boundPath = imageFile.absolutePath
            boundFile = imageFile
            boundItem = item
            currentImageWidth = 0
            currentImageHeight = 0
            currentBaseTranslation = null
            previousPageTranslation = null
            downX = 0f
            downY = 0f
            touchMoved = false
            lastTapTime = 0L
            lastTapX = 0f
            lastTapY = 0f
            registerBoundHolder(imageFile.absolutePath)
            binding.readingPageOverlay.setEditMode(false)
            binding.readingPageOverlay.setTouchPassthroughEnabled(true)
            binding.readingPageOverlay.setEditScrollThroughEnabled(false)
            binding.readingPageOverlay.setVerticalLayoutEnabled(verticalLayoutEnabled)
            binding.readingPageOverlay.setNormalBubbleRenderSettings(bubbleRenderSettings)
            binding.readingPageOverlay.onOffsetChanged = null
            binding.readingPageOverlay.onBubbleRemove = null
            binding.readingPageOverlay.onBubbleTap = null
            binding.readingPageOverlay.onBubbleResizeTap = null
            binding.readingPageOverlay.onBubbleResized = null
            binding.readingPageOverlay.onResizeModeChanged = null
            binding.readingPageOverlay.onBubbleLongPress = null
            binding.readingPageOverlay.visibility = View.GONE
            applyPlaceholder(item)
            primeLayoutFromKnownSize(item)
            loadPage(item)
        }

        fun updatePresentation(
            verticalLayoutEnabled: Boolean,
            bubbleRenderSettings: NormalBubbleRenderSettings
        ) {
            binding.readingPageOverlay.setVerticalLayoutEnabled(verticalLayoutEnabled)
            binding.readingPageOverlay.setNormalBubbleRenderSettings(bubbleRenderSettings)
            refreshOverlayPresentation()
        }

        fun refreshOverlayPresentation() {
            if (!hasCurrentContent()) return
            bindOverlay(currentBaseTranslation)
        }

        fun buildSnapshot(): BoundPageSnapshot? {
            val imageFile = boundFile ?: return null
            return BoundPageSnapshot(
                imageFile = imageFile,
                translation = currentBaseTranslation,
                sourceWidth = currentImageWidth,
                sourceHeight = currentImageHeight
            )
        }

        fun isZoomed(): Boolean = imageTransformController.isZoomed()

        fun resetZoom() {
            imageTransformController.resetZoom()
        }

        fun applyGestureInteracting(active: Boolean) {
            if (binding.readingPageOverlay.isGone) return
            binding.readingPageOverlay.setGestureInteracting(active)
        }

        fun enterResizeMode(bubbleId: Int) {
            if (binding.readingPageOverlay.isGone) return
            binding.readingPageOverlay.enterResizeMode(bubbleId)
        }

        fun exitResizeMode() {
            binding.readingPageOverlay.exitResizeMode()
        }

        fun getResizeModeBubbleId(): Int? = binding.readingPageOverlay.getResizeModeBubbleId()

        fun handleTouchEvent(event: MotionEvent): Boolean {
            val transformHandled = imageTransformController.handleTouch(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    touchMoved = false
                    return transformHandled || isZoomed()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!touchMoved &&
                        (kotlin.math.abs(event.x - downX) > touchSlop ||
                            kotlin.math.abs(event.y - downY) > touchSlop)
                    ) {
                        touchMoved = true
                    }
                    return transformHandled || isZoomed()
                }

                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> {
                    return transformHandled || isZoomed()
                }

                MotionEvent.ACTION_UP -> {
                    if (!touchMoved && !isLockedEditPage()) {
                        val now = event.eventTime
                        val isDoubleTap = now - lastTapTime <= doubleTapTimeout &&
                            kotlin.math.abs(event.x - lastTapX) <= doubleTapSlop &&
                            kotlin.math.abs(event.y - lastTapY) <= doubleTapSlop
                        if (isDoubleTap) {
                            lastTapTime = 0L
                            touchMoved = false
                            return toggleDoubleTapZoom(event.x, event.y)
                        }
                        lastTapTime = now
                        lastTapX = event.x
                        lastTapY = event.y
                    }
                    touchMoved = false
                    return transformHandled || isZoomed()
                }

                MotionEvent.ACTION_CANCEL -> {
                    touchMoved = false
                    return transformHandled || isZoomed()
                }
            }
            return transformHandled || isZoomed()
        }

        private fun loadPage(item: WebtoonDisplayItem) {
            bindJob?.cancel()
            bindJob = scope.launch {
                val imageFile = item.imageFile
                val imagePath = imageFile.absolutePath
                val targetWidth = resolveTargetWidth()
                val targetHeight = resolveTargetHeight()
                val decodedDeferred = async(Dispatchers.IO) {
                    var lease: ReadingBitmapCache.Lease? = null
                    try {
                        lease = bitmapCache.acquire(imageFile) {
                            ReadingBitmapDecoder.decode(imageFile, targetWidth, targetHeight)
                        }
                        lease
                    } finally {
                        if (!isActive) {
                            lease?.close()
                        }
                    }
                }
                val earlyTranslationJob = launch {
                    val translation = loadTranslationShared(imageFile)
                    val previousTranslation = item.imageIndex
                        .takeIf { it > 0 }
                        ?.let { items.getOrNull(it - 1) }
                        ?.let { loadTranslationShared(it) }
                    if (boundItem?.stableKey != item.stableKey) return@launch
                    currentBaseTranslation = translation
                    previousPageTranslation = previousTranslation
                    if (currentImageWidth > 0 && currentImageHeight > 0) {
                        currentBaseTranslation = normalizeTranslation(translation)
                        bindOverlay(currentBaseTranslation)
                    }
                }
                val decodedLease = decodedDeferred.await()
                val decoded = decodedLease?.decoded
                if (boundItem?.stableKey != item.stableKey) {
                    decodedLease?.close()
                    return@launch
                }
                if (decoded == null) {
                    earlyTranslationJob.cancel()
                    releaseCurrentHolderBitmap()
                    binding.readingPageOverlay.visibility = View.GONE
                    showPlaceholder(item)
                    return@launch
                }
                val previousBitmapLease = currentBitmapLease
                currentDecodedImage = decoded
                currentBitmapLease = decodedLease
                currentBitmap = decoded.bitmap
                currentImageWidth = decoded.sourceWidth
                currentImageHeight = decoded.sourceHeight
                currentBaseTranslation = normalizeTranslation(currentBaseTranslation)
                updatePageHeightForImage(decoded.sourceWidth, decoded.sourceHeight)
                binding.readingPageImage.setRegionSource(decoded.regionSource)
                binding.readingPageImage.setImageDrawable(decoded.drawable)
                if (previousBitmapLease !== decodedLease) {
                    binding.readingPageOverlay.setSourceBitmap(null)
                    binding.readingPageOverlay.setSourceImageFile(null)
                    previousBitmapLease?.close()
                }
                binding.root.post {
                    if (boundItem?.stableKey != item.stableKey) return@post
                    imageTransformController.resetContent(
                        decoded.displayWidth,
                        decoded.displayHeight,
                        ReadingDisplayMode.FIT_WIDTH
                    )
                    rememberedPageHeights[item.stableKey] = binding.readingPageImage.height
                    binding.readingPagePlaceholder.visibility = View.GONE
                    bindOverlay(currentBaseTranslation)
                }
            }
        }

        fun recycle() {
            bindJob?.cancel()
            overlayReloadJob?.cancel()
            boundPath?.let(::unregisterBoundHolder)
            boundPath = null
            boundFile = null
            boundItem = null
            releaseCurrentHolderBitmap()
            currentImageWidth = 0
            currentImageHeight = 0
            currentBaseTranslation = null
            previousPageTranslation = null
            binding.readingPageOverlay.onOffsetChanged = null
            binding.readingPageOverlay.onBubbleRemove = null
            binding.readingPageOverlay.onBubbleTap = null
            binding.readingPageOverlay.onBubbleResizeTap = null
            binding.readingPageOverlay.onBubbleResized = null
            binding.readingPageOverlay.onResizeModeChanged = null
            binding.readingPageOverlay.onBubbleLongPress = null
            binding.readingPageOverlay.visibility = View.GONE
            binding.readingPageOverlay.setSourceBitmap(null)
            binding.readingPageOverlay.setSourceImageFile(null)
            binding.readingPagePlaceholder.visibility = View.VISIBLE
            updateViewHeight(binding.root, ViewGroup.LayoutParams.WRAP_CONTENT)
            updateViewHeight(binding.readingPageImage, ViewGroup.LayoutParams.WRAP_CONTENT)
            updateViewHeight(binding.readingPageOverlay, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        private fun applyPlaceholder(item: WebtoonDisplayItem) {
            showPlaceholder(item)
        }

        fun refreshPlaceholderHeight() {
            val item = boundItem ?: return
            if (currentDecodedImage != null) return
            showPlaceholder(item)
        }

        private fun showPlaceholder(item: WebtoonDisplayItem) {
            val targetHeight = rememberedPageHeights[item.stableKey]
                ?: estimatePlaceholderHeight(item)
            updatePlaceholderHeight(targetHeight)
            binding.readingPagePlaceholder.visibility = View.VISIBLE
        }

        private fun estimatePlaceholderHeight(item: WebtoonDisplayItem): Int {
            val metrics = binding.root.resources.displayMetrics
            val width = binding.root.width.takeIf { it > 0 } ?: metrics.widthPixels
            val size = sourceSizeCache[item.path]
            val displaySourceHeight = size?.height ?: 0
            val estimated = size
                ?.takeIf { it.width > 0 && it.height > 0 }
                ?.takeIf { displaySourceHeight > 0 }
                ?.let {
                    (width.toFloat() * displaySourceHeight / it.width).roundToInt()
                }
                ?: (width * DEFAULT_PLACEHOLDER_HEIGHT_RATIO).toInt()
            val minHeight = (metrics.density * 240f).toInt()
            return estimated.coerceAtLeast(minHeight)
        }

        private fun updatePlaceholderHeight(height: Int) {
            val params = binding.readingPagePlaceholder.layoutParams
            if (params.height == height) return
            params.height = height
            binding.readingPagePlaceholder.layoutParams = params
        }

        private fun primeLayoutFromKnownSize(item: WebtoonDisplayItem) {
            val size = sourceSizeCache[item.path] ?: return
            if (size.width <= 0 || size.height <= 0) return
            currentImageWidth = size.width
            currentImageHeight = size.height
            updatePageHeightForImage(size.width, size.height)
        }

        private fun updatePageHeightForImage(sourceWidth: Int, sourceHeight: Int) {
            if (sourceWidth <= 0 || sourceHeight <= 0) return
            val targetWidth = resolveTargetWidth()
            val targetHeight = (targetWidth.toFloat() * sourceHeight / sourceWidth)
                .roundToInt()
                .coerceAtLeast(1)
            updateViewHeight(binding.root, targetHeight)
            updateViewHeight(binding.readingPageImage, targetHeight)
            updateViewHeight(binding.readingPageOverlay, targetHeight)
            updatePlaceholderHeight(targetHeight)
        }

        private fun updateViewHeight(view: View, height: Int) {
            val params = view.layoutParams ?: return
            if (params.height == height) return
            params.height = height
            view.layoutParams = params
        }

        private fun bindOverlay(translation: TranslationResult?) {
            val width = binding.readingPageImage.width.toFloat()
            val height = binding.readingPageImage.height.toFloat()
            if (width <= 0f || height <= 0f) {
                binding.readingPageOverlay.visibility = View.GONE
                binding.readingPageOverlay.setSourceBitmap(null)
                binding.readingPageOverlay.setSourceImageFile(null)
                return
            }
            val resolved = resolveOverlayTranslation(translation)
            val lockedForEdit = isLockedEditPage()
            updateOverlayDisplayRect(width, height)
            binding.readingPageOverlay.setContentZoomScale(imageTransformController.currentContentZoomScale())
            binding.readingPageOverlay.setSourceBitmap(currentBitmap)
            binding.readingPageOverlay.setSourceImageFile(boundFile)
            binding.readingPageOverlay.setCurrentImageName(boundFile?.name ?: resolved.imageName)
            binding.readingPageOverlay.setTranslations(resolved)
            binding.readingPageOverlay.setOffsets(if (lockedForEdit) lockedPageOffsets else emptyMap())
            binding.readingPageOverlay.setTouchPassthroughEnabled(!lockedForEdit)
            binding.readingPageOverlay.setEditScrollThroughEnabled(lockedForEdit)
            binding.readingPageOverlay.setEditOverflowBounds(
                top = 0f,
                bottom = if (lockedForEdit) currentImageHeight.toFloat() else 0f
            )
            binding.readingPageOverlay.onOffsetChanged = if (lockedForEdit) { bubbleId, offsetX, offsetY ->
                onLockedBubbleOffsetChanged?.invoke(bubbleId, offsetX, offsetY)
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleRemove = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleRemove?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleTap = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleTap?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleResizeTap = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleResizeTap?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleResized = if (lockedForEdit) {
                { bubbleId, newRect -> onLockedBubbleResized?.invoke(bubbleId, newRect) }
            } else {
                null
            }
            binding.readingPageOverlay.onResizeModeChanged = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleResizeModeChanged?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleLongPress = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleLongPress?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.setEditMode(lockedForEdit)
            binding.readingPageOverlay.visibility = if (resolved.bubbles.isEmpty()) View.GONE else View.VISIBLE
            if (binding.readingPageOverlay.isVisible) {
                val displayedPath = boundPath
                binding.readingPageOverlay.postOnAnimation {
                    if (boundPath != displayedPath) return@postOnAnimation
                    if (binding.readingPageOverlay.visibility != View.VISIBLE) return@postOnAnimation
                    updateOverlayDisplayRect()
                    binding.readingPageOverlay.setContentZoomScale(
                        imageTransformController.currentContentZoomScale()
                    )
                    binding.readingPageOverlay.postInvalidateOnAnimation()
                }
            }
        }

        private fun toggleDoubleTapZoom(x: Float, y: Float): Boolean {
            return imageTransformController.toggleDoubleTapZoom(x, y)
        }

        private fun updateOverlayDisplayRect(
            fallbackWidth: Float = binding.readingPageImage.width.toFloat(),
            fallbackHeight: Float = binding.readingPageImage.height.toFloat()
        ) {
            val rect = imageTransformController.computeImageDisplayRect()
                ?: RectF(0f, 0f, fallbackWidth, fallbackHeight)
            binding.readingPageOverlay.setDisplayRect(rect)
        }

        fun reloadTranslationOverlay() {
            val imageFile = boundFile ?: return
            if (!hasCurrentContent()) return
            overlayReloadJob?.cancel()
            overlayReloadJob = scope.launch {
                val imagePath = imageFile.absolutePath
                val translation = loadTranslationShared(imageFile)
                if (boundPath != imagePath) return@launch
                currentBaseTranslation = normalizeTranslation(translation)
                previousPageTranslation = boundItem
                    ?.imageIndex
                    ?.takeIf { it > 0 }
                    ?.let { items.getOrNull(it - 1) }
                    ?.let { loadTranslationShared(it) }
                bindOverlay(currentBaseTranslation)
            }
        }

        private fun resolveOverlayTranslation(base: TranslationResult?): TranslationResult {
            val preferred = if (isLockedEditPage()) lockedPageTranslation ?: base else base
            val normalized = normalizeTranslation(preferred)
            val spillSource = resolveSpillSourceTranslation()
            val spillBubbles = projectPreviousSpillBubbles(
                previous = spillSource,
                previousImageName = previousImageNameForSpill(spillSource),
                currentWidth = currentImageWidth,
                currentHeight = currentImageHeight
            )
            if (normalized == null) {
                return TranslationResult(
                    imageName = boundFile?.name.orEmpty(),
                    width = currentImageWidth,
                    height = currentImageHeight,
                    bubbles = spillBubbles
                )
            }
            if (spillBubbles.isEmpty()) return normalized
            return normalized.copy(bubbles = normalized.bubbles + spillBubbles)
        }

        private fun normalizeTranslation(translation: TranslationResult?): TranslationResult? {
            if (translation == null) return null
            return if (translation.width == currentImageWidth && translation.height == currentImageHeight) {
                translation
            } else {
                translation.copy(width = currentImageWidth, height = currentImageHeight)
            }
        }

        private fun isLockedEditPage(): Boolean {
            return editModeEnabled &&
                boundPath != null &&
                boundPath == lockedPagePath
        }

        private fun resolveSpillSourceTranslation(): TranslationResult? {
            val item = boundItem ?: return null
            val previousIndex = item.imageIndex - 1
            if (previousIndex < 0) return null
            return if (editModeEnabled && lockedPageImageIndex == previousIndex) {
                lockedPageTranslation
            } else {
                previousPageTranslation
            }
        }

        private fun previousImageNameForSpill(previousTranslation: TranslationResult?): String {
            return previousTranslation?.imageName?.ifBlank {
                items.getOrNull((boundItem?.imageIndex ?: 0) - 1)?.name.orEmpty()
            } ?: ""
        }

        private fun resolveTargetWidth(): Int {
            return binding.readingPageImage.width
                .takeIf { it > 0 }
                ?: binding.root.width.takeIf { it > 0 }
                ?: binding.root.resources.displayMetrics.widthPixels
        }

        private fun resolveTargetHeight(): Int {
            return binding.readingPageImage.height
                .takeIf { it > 0 }
                ?: binding.root.height.takeIf { it > 0 }
                ?: binding.root.resources.displayMetrics.heightPixels
        }

        private fun hasCurrentContent(): Boolean {
            return currentDecodedImage != null || currentBitmap != null
        }

        private fun registerBoundHolder(path: String) {
            boundHolders.getOrPut(path) { linkedSetOf() }.add(this)
        }

        private fun unregisterBoundHolder(path: String) {
            val holders = boundHolders[path] ?: return
            holders.remove(this)
            if (holders.isEmpty()) {
                boundHolders.remove(path)
            }
        }
    }
}
