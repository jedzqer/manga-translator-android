package com.manga.translate.library

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.manga.translate.R
import com.manga.translate.app.MainActivity
import com.manga.translate.app.MainPagerAdapter
import com.manga.translate.background.TranslationKeepAliveService
import com.manga.translate.databinding.FragmentLibraryBinding
import com.manga.translate.di.appContainer
import com.manga.translate.detection.RegionDetectionSelection
import com.manga.translate.floating.FloatingBallOverlayService
import com.manga.translate.model.FolderItem
import com.manga.translate.model.FolderStatus
import com.manga.translate.model.ImageItem
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.ResourceAssessment
import com.manga.translate.platform.ResourceWarningDialogs
import com.manga.translate.platform.showWithScrollableMessage
import com.manga.translate.reader.ReadingSessionViewModel
import com.manga.translate.translation.FolderTranslationTaskFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class LibraryFragment : Fragment() {

    private sealed interface FolderContent {
        data class Chapters(val items: List<FolderItem>) : FolderContent
        data class Images(val items: List<ImageItem>) : FolderContent
    }

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()
    private val appContainer by lazy(LazyThreadSafetyMode.NONE) { requireContext().appContainer }
    private val repository by lazy(LazyThreadSafetyMode.NONE) { appContainer.libraryRepository }
    private val translationStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.translationStore }
    private val glossaryStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.glossaryStore }
    private val extractStateStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.extractStateStore }
    private val ocrStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.ocrStore }
    private val readingProgressStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.readingProgressStore }
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.settingsStore }
    private val dialogs = LibraryDialogs()

    private val prefs by lazy(LazyThreadSafetyMode.NONE) { appContainer.libraryPrefs }

    private lateinit var preferencesGateway: LibraryPreferencesGateway
    private lateinit var importExportCoordinator: LibraryImportExportCoordinator
    private lateinit var selectionController: LibrarySelectionController
    private lateinit var selectionManager: LibrarySelectionManager
    private var imageConversionDialog: AlertDialog? = null

    private val taskFactory by lazy(LazyThreadSafetyMode.NONE) {
        FolderTranslationTaskFactory(repository, preferencesGateway, settingsStore)
    }

    private var currentFolder: File? = null
    private var currentParentFolder: File? = null
    private var pendingChapterImportParent: File? = null
    private var isFolderTransitionRunning: Boolean = false
    private var isFolderTopBarVisible: Boolean = true
    private var lastFolderDetailScrollY: Int = 0
    private var folderTopBarScrollAccumulated: Int = 0
    private var folderDetailContentBaseTopPadding: Int = 0
    private var activeFolderFilter: FolderFilter? = null
    private var folderLoadJob: Job? = null
    private var folderLoadGeneration: Long = 0L
    private var folderContentLoadJob: Job? = null
    private var folderContentLoadGeneration: Long = 0L
    private var pendingFloatingTranslateLanguage: TranslationLanguage? = null
    private var regionDetectionModeIndicator: RegionDetectionModeIndicator? = null
    private var suppressRegionDetectionIndicatorAnimation: Boolean = false
    private val modelErrorController by lazy(LazyThreadSafetyMode.NONE) {
        ModelErrorDialogController(this, dialogs)
    }

    private fun getTutorialUrls(): Pair<String, String> {
        val locale = resources.configuration.locales[0]
        val language = locale.language.lowercase()

        // Determine tutorial language based on UI language
        val tutorialFile = when {
            language == "zh" -> "简中教程.md"
            else -> "English Tutorial.md" // Default to English for all non-Chinese languages
        }

        return Pair(
            "https://github.com/jedzqer/manga-translator/blob/main/Tutorial/$tutorialFile",
            "https://gitee.com/jedzqer/manga-translator/blob/main/Tutorial/$tutorialFile"
        )
    }

    private fun isChineseLanguage(): Boolean {
        val locale = resources.configuration.locales[0]
        return locale.language.lowercase() == "zh"
    }

    private val folderAdapter = LibraryFolderAdapter(
        onClick = { openFolder(it.folder) },
        onDelete = { confirmDeleteFolder(it.folder) },
        onRename = { showRenameFolderDialog(it.folder) },
        onMove = { showMoveFolderPicker(it.folder) },
        onEditTags = { showEditFolderTagsDialog(it) },
        onTagClick = { applyFolderFilter(FolderFilter.CustomTag(it)) },
        onStatusClick = null,
        showOverflowMenu = true,
        onSelectionChanged = { updateLibrarySelectionActions() },
        onItemLongPress = { selectionManager.enterLibrarySelectionMode(it.folder) }
    )

    private val imageAdapter = FolderImageAdapter(
        onSelectionChanged = { selectionController.updateSelectionActions() },
        onItemLongPress = { selectionController.enterSelectionMode(it.file) },
        onItemClick = { openImageInReader(it.file) }
    )

    private val chapterAdapter = LibraryFolderAdapter(
        onClick = { openChildFolder(it.folder) },
        onDelete = { confirmDeleteFolder(it.folder) },
        onRename = { showRenameFolderDialog(it.folder) },
        onMove = { showMoveFolderPicker(it.folder) },
        onSelectionChanged = { updateChapterSelectionActions() },
        onItemLongPress = { selectionManager.enterChapterSelectionMode(it.folder) }
    )

    private val uiCallbacks = object : LibraryUiCallbacks {
        override fun setFolderStatus(left: String, right: String) {
            _binding?.let {
                it.folderProgressLeft.text = left
                it.folderProgressRight.text = right
            }
        }

        override fun clearFolderStatus() {
            setFolderStatus("")
        }

        override fun setTranslationActionsEnabled(enabled: Boolean) {
            applyTranslationActionsEnabled(enabled)
        }

        override fun setFolderExportEnabled(folder: File, enabled: Boolean) {
            if (currentFolder?.absolutePath != folder.absolutePath) return
            _binding?.folderExport?.isEnabled = enabled
            _binding?.folderExportCollection?.isEnabled = enabled
        }

        override fun showToast(resId: Int) {
            if (!isAdded) return
            Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
        }

        override fun showToastMessage(message: String) {
            if (!isAdded) return
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }

        override fun showImageConversionProgress() {
            if (!isAdded || imageConversionDialog?.isShowing == true) return
            imageConversionDialog = AlertDialog.Builder(requireContext())
                .setMessage(R.string.avif_conversion_progress)
                .setCancelable(false)
                .create()
                .also { it.show() }
        }

        override fun hideImageConversionProgress() {
            imageConversionDialog?.dismiss()
            imageConversionDialog = null
        }

        override fun showApiError(code: String, detail: String?) {
            if (!isAdded) return
            dialogs.showApiErrorDialog(requireContext(), code, detail)
        }

        override fun showModelError(
            content: String,
            useSystemOverlay: Boolean,
            onRetry: (() -> Unit)?,
            onSkip: (() -> Unit)?
        ) {
            modelErrorController.enqueue(content, useSystemOverlay, onRetry, onSkip)
        }

        override fun refreshFolders() {
            if (!isAdded || _binding == null) return
            loadFolders()
        }

        override fun refreshImages(folder: File) {
            if (!isAdded || _binding == null) return
            loadImages(folder)
        }

        override fun showExportSuccess(path: String) {
            if (!isAdded || _binding == null) return
            dialogs.showExportSuccessDialog(requireContext(), path)
        }

        override fun isUiAttached(): Boolean {
            return isAdded
        }

        override fun isFragmentActive(): Boolean {
            return isAdded && _binding != null
        }

        override fun isAppInForeground(): Boolean {
            val hostActivity = activity ?: return false
            return isAdded &&
                hostActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                !hostActivity.isFinishing &&
                !hostActivity.isDestroyed
        }

        override fun isLibraryInForeground(): Boolean {
            return isAdded && _binding != null && isResumed
        }

        override fun canShowSystemOverlay(): Boolean {
            return isAdded && canDrawOverlays()
        }
    }

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        importExportCoordinator.handleStoragePermissionResult(granted) {
            val folder = currentFolder ?: return@handleStoragePermissionResult
            if (importExportCoordinator.isPendingExportCollection()) {
                val chapterImages = buildChapterImagesForCollection(folder)
                importExportCoordinator.exportCollectionAfterPermission(
                    uiContext = requireContext(),
                    collectionFolder = folder,
                    chapterImages = chapterImages,
                    onExitSelectionMode = { selectionController.exitSelectionMode() },
                    onSetExportEnabled = { enabled -> _binding?.folderExportCollection?.isEnabled = enabled }
                )
            } else {
                importExportCoordinator.exportFolderAfterPermission(
                    uiContext = requireContext(),
                    folder = folder,
                    images = repository.listImages(folder),
                    onExitSelectionMode = { selectionController.exitSelectionMode() },
                    onSetExportEnabled = { enabled -> _binding?.folderExport?.isEnabled = enabled }
                )
            }
        }
    }

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            addImagesToFolder(uris)
        }
    }

    private val pickImportTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            importExportCoordinator.handleImportTreeSelection(
                uiContext = requireContext(),
                uri = uri,
                scope = viewLifecycleOwner.lifecycleScope,
                onShowFolderList = { showFolderList() }
            )
        }
    }

    private val pickChapterImportTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val parentFolder = pendingChapterImportParent
        pendingChapterImportParent = null
        if (uri != null && parentFolder != null) {
            importExportCoordinator.handleChapterImportTreeSelection(
                uiContext = requireContext(),
                parentFolder = parentFolder,
                uri = uri,
                scope = viewLifecycleOwner.lifecycleScope
            )
        }
    }

    private val pickArchiveOrPdfFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importFromArchiveOrPdf(uri)
        }
    }

    private val pickExportTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            importExportCoordinator.handleExportTreeSelection(uri) {
                val folder = currentFolder ?: return@handleExportTreeSelection
                if (importExportCoordinator.isPendingExportCollection()) {
                    val chapterImages = buildChapterImagesForCollection(folder)
                    importExportCoordinator.exportCollectionAfterPermission(
                        uiContext = requireContext(),
                        collectionFolder = folder,
                        chapterImages = chapterImages,
                        onExitSelectionMode = { selectionController.exitSelectionMode() },
                        onSetExportEnabled = { enabled -> _binding?.folderExportCollection?.isEnabled = enabled }
                    )
                } else {
                    importExportCoordinator.exportFolderAfterPermission(
                        uiContext = requireContext(),
                        folder = folder,
                        images = repository.listImages(folder),
                        onExitSelectionMode = { selectionController.exitSelectionMode() },
                        onSetExportEnabled = { enabled -> _binding?.folderExport?.isEnabled = enabled }
                    )
                }
            }
        } else {
            importExportCoordinator.handleExportTreeCanceled()
        }
    }

    private val requestOverlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!isAdded) return@registerForActivityResult
        if (canDrawOverlays()) {
            showFloatingTranslateLanguageDialog()
            return@registerForActivityResult
        }
        showOverlayPermissionFailedDialog()
    }

    private val requestScreenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded) return@registerForActivityResult
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            startFloatingTranslateEntry(
                resultCode = result.resultCode,
                resultData = data,
                language = pendingFloatingTranslateLanguage ?: defaultFloatingTranslateLanguage()
            )
            pendingFloatingTranslateLanguage = null
            return@registerForActivityResult
        }
        pendingFloatingTranslateLanguage = null
        showScreenCapturePermissionFailedDialog()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LibraryUiBridge.register(uiCallbacks)
        preferencesGateway = LibraryPreferencesGateway(requireContext(), prefs, repository)
        importExportCoordinator = LibraryImportExportCoordinator(
            context = requireContext(),
            repository = repository,
            translationStore = translationStore,
            settingsStore = settingsStore,
            prefs = prefs,
            preferencesGateway = preferencesGateway,
            dialogs = dialogs,
            ui = uiCallbacks,
            exportTaskHost = appContainer.exportTaskHost
        )
        selectionController = LibrarySelectionController(
            imageAdapter = imageAdapter,
            translationStore = translationStore,
            ocrStore = ocrStore,
            repository = repository,
            preferencesGateway = preferencesGateway,
            ui = uiCallbacks,
            dialogs = dialogs,
            bindingProvider = { _binding },
            contextProvider = { if (isAdded) requireContext() else null },
            onRetranslateRequested = { folder, selected, force ->
                runTranslation(folder, selected, force)
            }
        )
        selectionManager = LibrarySelectionManager(
            folderAdapter = folderAdapter,
            chapterAdapter = chapterAdapter,
            ui = uiCallbacks,
            bindingProvider = { _binding },
            onExitImageSelectionMode = { selectionController.exitSelectionMode() }
        )

        binding.folderList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderList.isNestedScrollingEnabled = true
        binding.folderList.adapter = folderAdapter
        (binding.folderList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.root.setOnClickListener { folderAdapter.clearActionSelection() }
        binding.folderList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                folderAdapter.clearActionSelectionIfTouchedOutside(rv, e)
                return false
            }
        })
        binding.folderImageList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderImageList.isNestedScrollingEnabled = false
        binding.folderImageList.adapter = imageAdapter
        binding.folderChapterList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderChapterList.isNestedScrollingEnabled = false
        binding.folderChapterList.adapter = chapterAdapter
        setupFolderTopBarOverlay()
        setupFolderDetailScrollBehavior()

        binding.addFolderFab.setOnClickListener { showCreateEntryDialog() }
        binding.importEhviewerButton.setOnClickListener { importFromEhViewer() }
        binding.floatingTranslateButton.setOnClickListener { handleFloatingTranslateClick() }
        binding.importCbzButton.setOnClickListener {
            pickArchiveOrPdfFile.launch(
                arrayOf(
                    "application/vnd.comicbook+zip",
                    "application/x-cbz",
                    "application/zip",
                    "application/pdf"
                )
            )
        }
        binding.tutorialButton.setOnClickListener { openTutorial() }
        if (!settingsStore.hasShownTutorialPrompt()) {
            settingsStore.markTutorialPromptShown()
            view.post {
                if (isAdded && _binding != null) openTutorial()
            }
        }
        binding.librarySelectAll.setOnClickListener { selectionManager.toggleSelectAllLibraryFolders() }
        binding.libraryTranslateSelected.setOnClickListener { translateSelectedLibraryFolders() }
        binding.libraryDeleteSelected.setOnClickListener { confirmDeleteSelectedLibraryFolders() }
        binding.libraryCancelSelection.setOnClickListener { selectionManager.exitLibrarySelectionMode() }
        binding.libraryActiveFilter.setOnClickListener { applyFolderFilter(null) }
        binding.librarySortField.setOnClickListener { toggleLibrarySortField() }
        binding.librarySortOrder.setOnClickListener { toggleLibrarySortOrder() }
        updateLibrarySortControl()
        binding.folderBackButton.setOnClickListener { navigateBackFromDetail() }
        binding.folderAddImages.setOnClickListener { handleAddContentClick() }
        binding.folderCollectionAddChapter.setOnClickListener { handleAddContentClick() }
        binding.folderImportChapters.setOnClickListener { importChildChapters() }
        binding.folderExportCollection.setOnClickListener { exportCollection() }
        binding.folderTranslateCollection.setOnClickListener { translateFolder() }
        binding.folderExport.setOnClickListener { exportFolder() }
        binding.folderTranslate.setOnClickListener { translateFolder() }
        binding.folderRead.setOnClickListener { startReading() }
        binding.folderCollectionRead.setOnClickListener { startReading() }
        binding.folderSelectAll.setOnClickListener { handleSelectAllClick() }
        binding.folderDeleteSelected.setOnClickListener { handleDeleteSelectedClick() }
        binding.folderRenameSelected.setOnClickListener { renameSelectedChapter() }
        binding.folderCancelSelection.setOnClickListener { exitActiveSelectionMode() }
        binding.folderRetranslateSelected.setOnClickListener {
            val folder = currentFolder
            selectionController.retranslateSelectedImages(folder)
        }
        binding.folderTranslationSettingsInfo.setOnClickListener {
            dialogs.showTranslationSettingsInfo(requireContext())
        }
        binding.folderFullTranslateInfo.setOnClickListener { showFullTranslateInfo() }
        binding.folderBubbleDetectionInfo.setOnClickListener {
            dialogs.showBubbleDetectionInfo(requireContext())
        }
        binding.folderGlossaryProcessingInfo.setOnClickListener {
            dialogs.showGlossaryProcessingInfo(requireContext())
        }
        binding.folderVlDirectTranslateInfo.setOnClickListener {
            dialogs.showVlDirectTranslateInfo(requireContext())
        }
        binding.folderLanguageSetting.setOnClickListener { showLanguageSettingDialog() }
        binding.folderReadingModeButton.setOnClickListener { showFolderReadingModeDialog() }
        binding.folderFullTranslateSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentFolder?.let { folder ->
                preferencesGateway.setFullTranslateEnabled(folder, isChecked)
                if (isChecked && preferencesGateway.isVlDirectTranslateEnabled(folder)) {
                    binding.folderVlDirectTranslateSwitch.isChecked = false
                }
                updateFolderTranslationSwitchStates(folder)
            }
        }
        binding.folderGlossaryProcessingSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentFolder?.let { preferencesGateway.setGlossaryProcessingEnabled(it, isChecked) }
        }
        setupRegionDetectionModeIndicator()
        binding.folderBubbleDetectionModeGroup.setOnCheckedChangeListener { _, checkedId ->
            updateRegionDetectionModeIndicator(checkedId)
            val selection = when (checkedId) {
                R.id.folder_detection_mode_bubbles -> RegionDetectionSelection.BUBBLES_ONLY
                R.id.folder_detection_mode_text -> RegionDetectionSelection.TEXT_ONLY
                R.id.folder_detection_mode_bubbles_and_text -> RegionDetectionSelection.BUBBLES_AND_TEXT
                else -> return@setOnCheckedChangeListener
            }
            currentFolder?.let { preferencesGateway.setRegionDetectionSelection(it, selection) }
        }
        binding.folderVlDirectTranslateSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentFolder?.let { folder ->
                preferencesGateway.setVlDirectTranslateEnabled(folder, isChecked)
                if (isChecked) {
                    Toast.makeText(
                        requireContext(),
                        R.string.folder_use_vl_direct_translate_warning,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectionController.isSelectionMode) {
                        selectionController.exitSelectionMode()
                        return
                    }
                    if (selectionManager.isLibrarySelectionMode) {
                        selectionManager.exitLibrarySelectionMode()
                        return
                    }
                    if (binding.folderDetailContainer.isVisible) {
                        navigateBackFromDetail()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        showFolderList()
    }

    private fun handleFloatingTranslateClick() {
        if (canDrawOverlays()) {
            showFloatingTranslateLanguageDialog()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.overlay_permission_required_title))
            .setMessage(getString(R.string.overlay_permission_required_message))
            .setPositiveButton(android.R.string.ok) { _, _ -> openOverlayPermissionSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithScrollableMessage()
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${requireContext().packageName}".toUri()
        )
        requestOverlayPermission.launch(intent)
    }

    private fun launchScreenCapturePermissionRequest(language: TranslationLanguage) {
        val manager = requireContext().getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            pendingFloatingTranslateLanguage = null
            showScreenCapturePermissionFailedDialog()
            return
        }
        pendingFloatingTranslateLanguage = language
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.screen_capture_permission_required_title))
            .setMessage(getString(R.string.screen_capture_permission_required_message))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                requestScreenCapturePermissionLauncher.launch(manager.createScreenCaptureIntent())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithScrollableMessage()
    }

    private fun showOverlayPermissionFailedDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.overlay_permission_failed_title))
            .setMessage(getString(R.string.overlay_permission_failed_message))
            .setPositiveButton(android.R.string.ok, null)
            .showWithScrollableMessage()
    }

    private fun showScreenCapturePermissionFailedDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.screen_capture_permission_required_title))
            .setMessage(getString(R.string.screen_capture_permission_failed))
            .setPositiveButton(android.R.string.ok, null)
            .showWithScrollableMessage()
    }

    private fun showFloatingTranslateLanguageDialog() {
        val ocrSettings = settingsStore.loadOcrApiSettings()
        val supportedLanguages = TranslationLanguage.supportedForOcr(ocrSettings.useLocalOcr)
        val currentLanguage = pendingFloatingTranslateLanguage
            ?: defaultFloatingTranslateLanguage()
        dialogs.showLanguageSettingConfirmDialog(
            context = requireContext(),
            languages = supportedLanguages,
            currentLanguage = currentLanguage
        ) { selectedLanguage ->
            launchScreenCapturePermissionRequest(selectedLanguage)
        }
    }

    private fun defaultFloatingTranslateLanguage(): TranslationLanguage {
        return TranslationLanguage.resolveForOcr(
            TranslationLanguage.JA_TO_ZH,
            settingsStore.loadOcrApiSettings().useLocalOcr
        )
    }

    private fun startFloatingTranslateEntry(
        resultCode: Int,
        resultData: Intent,
        language: TranslationLanguage
    ) {
        val context = requireContext()
        ContextCompat.startForegroundService(
            context,
            Intent(context, FloatingBallOverlayService::class.java).apply {
                action = FloatingBallOverlayService.ACTION_START
                putExtra(FloatingBallOverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(FloatingBallOverlayService.EXTRA_RESULT_DATA, resultData)
                putExtra(FloatingBallOverlayService.EXTRA_LANGUAGE, language.prefValue)
            }
        )
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
    }

    private fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(requireContext())
    }

    override fun onDestroyView() {
        folderLoadGeneration += 1
        folderLoadJob?.cancel()
        folderLoadJob = null
        folderContentLoadGeneration += 1
        folderContentLoadJob?.cancel()
        folderContentLoadJob = null
        imageConversionDialog?.dismiss()
        imageConversionDialog = null
        LibraryUiBridge.unregister(uiCallbacks)
        modelErrorController.onDestroy()
        regionDetectionModeIndicator = null
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        modelErrorController.onResume()
        currentFolder?.let { folder ->
            syncExportActionState(folder)
            syncRegionDetectionMode(folder)
        }
    }

    private fun showFolderList() {
        currentFolder = null
        currentParentFolder = null
        folderContentLoadGeneration += 1
        folderContentLoadJob?.cancel()
        folderContentLoadJob = null
        resetFolderTopBar(forceVisible = true)
        uiCallbacks.clearFolderStatus()
        exitActiveSelectionMode()
        selectionManager.exitLibrarySelectionMode()
        folderAdapter.clearActionSelection()
        chapterAdapter.clearActionSelection()
        loadFolders()
        if (!binding.folderDetailContainer.isVisible) {
            applyFolderListVisibleState()
            return
        }
        animateFolderTransition(showDetail = false)
    }

    private fun showFolderDetail(folder: File, parentFolder: File? = null) {
        currentFolder = folder
        currentParentFolder = parentFolder
        resetFolderTopBar(forceVisible = true)
        binding.folderTitle.text = buildFolderTitle(folder)
        binding.folderFullTranslateSwitch.isChecked = preferencesGateway.isFullTranslateEnabled(folder)
        binding.folderGlossaryProcessingSwitch.isChecked =
            preferencesGateway.isGlossaryProcessingEnabled(folder)
        syncRegionDetectionMode(folder)
        binding.folderVlDirectTranslateSwitch.isChecked =
            preferencesGateway.isVlDirectTranslateEnabled(folder)
        updateFolderTranslationSwitchStates(folder)
        updateLanguageSettingButton(folder)
        updateReadingModeButton(folder)
        updateFolderContentMode(folder)
        exitActiveSelectionMode()
        chapterAdapter.submit(emptyList())
        imageAdapter.submit(emptyList())
        loadImages(folder)
        if (binding.folderDetailContainer.isVisible && !binding.libraryListContainer.isVisible) {
            binding.folderDetailContainer.alpha = 1f
            binding.folderDetailContainer.translationY = 0f
            AppLogger.log("Library", "Opened folder ${folder.name}")
            return
        }
        animateFolderTransition(showDetail = true)
        AppLogger.log("Library", "Opened folder ${folder.name}")
    }

    private fun syncRegionDetectionMode(folder: File) {
        val checkedId = when (preferencesGateway.getRegionDetectionSelection(folder)) {
            RegionDetectionSelection.BUBBLES_ONLY -> R.id.folder_detection_mode_bubbles
            RegionDetectionSelection.TEXT_ONLY -> R.id.folder_detection_mode_text
            RegionDetectionSelection.BUBBLES_AND_TEXT -> R.id.folder_detection_mode_bubbles_and_text
        }
        // Restoring persisted state should never animate: the control may be
        // re-attached (returning from the reading tab) with the indicator at its
        // default position, and sliding it in from the left looks like a glitch.
        suppressRegionDetectionIndicatorAnimation = true
        try {
            if (binding.folderBubbleDetectionModeGroup.checkedRadioButtonId != checkedId) {
                binding.folderBubbleDetectionModeGroup.check(checkedId)
            }
            regionDetectionModeIndicator?.setChecked(checkedId, animate = false)
        } finally {
            suppressRegionDetectionIndicatorAnimation = false
        }
    }

    private fun setupRegionDetectionModeIndicator() {
        regionDetectionModeIndicator = RegionDetectionModeIndicator(
            group = binding.folderBubbleDetectionModeGroup,
            indicator = binding.folderBubbleDetectionModeIndicator
        )
    }

    private fun updateRegionDetectionModeIndicator(checkedId: Int) {
        regionDetectionModeIndicator?.setChecked(
            checkedId = checkedId,
            animate = !suppressRegionDetectionIndicatorAnimation
        )
    }

    private fun animateFolderTransition(showDetail: Boolean) {
        if (isFolderTransitionRunning) return
        isFolderTransitionRunning = true

        val outgoing = if (showDetail) binding.libraryListContainer else binding.folderDetailContainer
        val incoming = if (showDetail) binding.folderDetailContainer else binding.libraryListContainer
        val offset = resources.displayMetrics.density * 24

        outgoing.animate().cancel()
        incoming.animate().cancel()
        binding.addFolderFab.animate().cancel()

        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.translationY = if (showDetail) offset else -offset * 0.5f

        outgoing.visibility = View.VISIBLE
        outgoing.alpha = 1f
        outgoing.translationY = 0f

        if (showDetail) {
            binding.addFolderFab.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction { binding.addFolderFab.visibility = View.GONE }
                .start()
        } else {
            binding.addFolderFab.visibility = View.VISIBLE
            binding.addFolderFab.alpha = 0f
            binding.addFolderFab.translationY = offset
            binding.addFolderFab.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .start()
        }

        outgoing.animate()
            .alpha(0f)
            .translationY(if (showDetail) -offset * 0.35f else offset * 0.35f)
            .setDuration(170L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    outgoing.visibility = View.GONE
                    outgoing.alpha = 1f
                    outgoing.translationY = 0f
                    outgoing.animate().setListener(null)
                }

                override fun onAnimationCancel(animation: Animator) {
                    outgoing.visibility = View.GONE
                    outgoing.alpha = 1f
                    outgoing.translationY = 0f
                    outgoing.animate().setListener(null)
                }
            })
            .start()

        incoming.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    incoming.alpha = 1f
                    incoming.translationY = 0f
                    incoming.animate().setListener(null)
                    isFolderTransitionRunning = false
                }

                override fun onAnimationCancel(animation: Animator) {
                    incoming.alpha = 1f
                    incoming.translationY = 0f
                    incoming.animate().setListener(null)
                    isFolderTransitionRunning = false
                }
            })
            .start()
    }

    private fun applyFolderListVisibleState() {
        binding.libraryListContainer.animate().cancel()
        binding.folderDetailContainer.animate().cancel()
        binding.addFolderFab.animate().cancel()
        resetFolderTopBar(forceVisible = true)
        binding.libraryListContainer.visibility = View.VISIBLE
        binding.libraryListContainer.alpha = 1f
        binding.libraryListContainer.translationY = 0f
        binding.folderDetailContainer.visibility = View.GONE
        binding.folderDetailContainer.alpha = 1f
        binding.folderDetailContainer.translationY = 0f
        binding.addFolderFab.visibility = View.VISIBLE
        binding.addFolderFab.alpha = 1f
        binding.addFolderFab.translationY = 0f
        isFolderTransitionRunning = false
    }

    private fun setupFolderDetailScrollBehavior() {
        val threshold = (resources.displayMetrics.density * 20).toInt().coerceAtLeast(1)
        binding.folderDetailScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (!binding.folderDetailContainer.isVisible) {
                lastFolderDetailScrollY = scrollY
                return@setOnScrollChangeListener
            }
            val delta = scrollY - lastFolderDetailScrollY
            lastFolderDetailScrollY = scrollY
            if (scrollY <= 0) {
                folderTopBarScrollAccumulated = 0
                setFolderTopBarVisible(true)
                return@setOnScrollChangeListener
            }
            if (delta == 0) return@setOnScrollChangeListener
            val sameDirection =
                (delta > 0 && folderTopBarScrollAccumulated > 0) ||
                    (delta < 0 && folderTopBarScrollAccumulated < 0)
            folderTopBarScrollAccumulated = if (folderTopBarScrollAccumulated == 0 || sameDirection) {
                folderTopBarScrollAccumulated + delta
            } else {
                delta
            }
            if (folderTopBarScrollAccumulated >= threshold) {
                setFolderTopBarVisible(false)
                folderTopBarScrollAccumulated = 0
            } else if (folderTopBarScrollAccumulated <= -threshold) {
                setFolderTopBarVisible(true)
                folderTopBarScrollAccumulated = 0
            }
        }
    }

    private fun setupFolderTopBarOverlay() {
        folderDetailContentBaseTopPadding = binding.folderDetailContent.paddingTop
        binding.folderTopBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (_binding == null) return@addOnLayoutChangeListener
            updateFolderDetailContentTopInset()
        }
        binding.folderTopBar.doOnLayout {
            if (_binding == null) return@doOnLayout
            updateFolderDetailContentTopInset()
        }
    }

    private fun updateFolderDetailContentTopInset() {
        val topBar = binding.folderTopBar
        topBar.bringToFront()
        val content = binding.folderDetailContent
        val targetTopPadding = folderDetailContentBaseTopPadding + topBar.height
        if (content.paddingTop == targetTopPadding) return
        content.setPadding(
            content.paddingLeft,
            targetTopPadding,
            content.paddingRight,
            content.paddingBottom
        )
    }

    private fun resetFolderTopBar(forceVisible: Boolean) {
        lastFolderDetailScrollY = binding.folderDetailScroll.scrollY
        folderTopBarScrollAccumulated = 0
        if (forceVisible) {
            binding.folderTopBar.doOnLayout {
                if (_binding == null) return@doOnLayout
                setFolderTopBarVisible(true, immediate = true)
            }
        }
    }

    private fun setFolderTopBarVisible(visible: Boolean, immediate: Boolean = false) {
        if (isFolderTopBarVisible == visible && !immediate) return
        isFolderTopBarVisible = visible
        val topBar = binding.folderTopBar
        topBar.animate().cancel()
        topBar.bringToFront()
        if (immediate) {
            topBar.alpha = if (visible) 1f else 0f
            topBar.translationY = if (visible) 0f else -topBar.height.toFloat()
            return
        }
        topBar.animate()
            .alpha(if (visible) 1f else 0f)
            .translationY(if (visible) 0f else -topBar.height.toFloat())
            .setDuration(180L)
            .start()
    }

    private fun loadFolders() {
        if (_binding == null || !isAdded) return
        val generation = ++folderLoadGeneration
        folderLoadJob?.cancel()
        val sortField = preferencesGateway.getLibrarySortField()
        val ascending = preferencesGateway.isLibrarySortAscending()
        val filter = activeFolderFilter
        folderLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val folders = repository.listFolders(sortField, ascending)
                buildFolderPreviewItems(folders).filter { item ->
                    when (filter) {
                        null -> true
                        // Status is intentionally not available on the home screen. It requires
                        // reading every page result and is shown after opening a folder instead.
                        is FolderFilter.Status -> true
                        is FolderFilter.CustomTag -> item.customTags.any { it == filter.tag }
                    }
                }
            }
            if (!isAdded || _binding == null || generation != folderLoadGeneration) return@launch
            folderAdapter.submit(items)
            binding.libraryEmpty.text = getString(
                if (filter == null) R.string.folder_empty else R.string.folder_filter_empty
            )
            binding.libraryEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            updateActiveFolderFilter()
            updateLibrarySortControl()
            if (selectionManager.isLibrarySelectionMode) {
                updateLibrarySelectionActions()
            }

            if (items.none { !it.statsLoaded }) return@launch
            val completedItems = withContext(Dispatchers.IO) {
                items.map { item ->
                    currentCoroutineContext().ensureActive()
                    if (item.statsLoaded) item else loadAndCacheFolderStats(item)
                }
            }
            if (!isAdded || _binding == null || generation != folderLoadGeneration) return@launch
            folderAdapter.submit(completedItems)
        }
    }

    private fun buildFolderPreviewItems(folders: List<File>): List<FolderItem> {
        val items = ArrayList<FolderItem>(folders.size)
        for (folder in folders) {
            val cachedStatus = preferencesGateway.getCachedFolderStatus(folder)
            val cachedStats = preferencesGateway.getCachedFolderStats(folder)
            items += FolderItem(
                folder = folder,
                imageCount = cachedStats?.imageCount ?: 0,
                chapterCount = cachedStats?.chapterCount ?: 0,
                isCollection = repository.isCollectionFolder(folder),
                status = cachedStatus ?: FolderStatus.UNTRANSLATED,
                customTags = preferencesGateway.getFolderTags(folder)
                    .sortedWith(String.CASE_INSENSITIVE_ORDER),
                statsLoaded = cachedStats != null,
                statusLoaded = cachedStatus != null
            )
        }
        return items
    }

    /**
     * Unified folder stats computation: counts images and chapters, caches the result, and
     * returns the counts. Used by all three stats-loading paths to ensure consistency.
     */
    private fun computeAndCacheFolderStats(folder: File, isCollection: Boolean): Pair<Int, Int> {
        val chapters = if (isCollection) repository.listChildFolders(folder) else emptyList()
        val imageCount = if (isCollection) {
            chapters.sumOf { chapter -> repository.listImages(chapter).size }
        } else {
            repository.listImages(folder).size
        }
        preferencesGateway.setCachedFolderStats(folder, imageCount, chapters.size)
        return imageCount to chapters.size
    }

    private fun loadAndCacheFolderStats(item: FolderItem): FolderItem {
        val (imageCount, chapterCount) = computeAndCacheFolderStats(item.folder, item.isCollection)
        return item.copy(
            imageCount = imageCount,
            chapterCount = chapterCount,
            statsLoaded = true
        )
    }

    private suspend fun buildFolderItems(folders: List<File>): List<FolderItem> {
        val items = ArrayList<FolderItem>(folders.size)
        for (folder in folders) {
            currentCoroutineContext().ensureActive()
            items += buildFolderItem(folder)
        }
        return items
    }

    private fun toggleLibrarySortField() {
        val next = when (preferencesGateway.getLibrarySortField()) {
            LibrarySortField.NAME -> LibrarySortField.TIME
            LibrarySortField.TIME -> LibrarySortField.NAME
        }
        preferencesGateway.setLibrarySortField(next)
        loadFolders()
    }

    private fun toggleLibrarySortOrder() {
        preferencesGateway.setLibrarySortAscending(!preferencesGateway.isLibrarySortAscending())
        loadFolders()
    }

    private fun updateLibrarySortControl() {
        val field = preferencesGateway.getLibrarySortField()
        val ascending = preferencesGateway.isLibrarySortAscending()
        binding.librarySortField.setText(
            when (field) {
                LibrarySortField.NAME -> R.string.library_sort_by_name
                LibrarySortField.TIME -> R.string.library_sort_by_time
            }
        )
        binding.librarySortOrder.setImageResource(
            if (ascending) R.drawable.ic_sort_arrow_up else R.drawable.ic_sort_arrow_down
        )
        binding.librarySortOrder.contentDescription = getString(
            if (ascending) R.string.library_sort_order_asc else R.string.library_sort_order_desc
        )
    }

    private fun loadImages(folder: File) {
        syncExportActionState(folder)
        val generation = ++folderContentLoadGeneration
        folderContentLoadJob?.cancel()
        folderContentLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                if (repository.isCollectionFolder(folder)) {
                    val items = buildFolderItems(repository.listChildFolders(folder))
                    val (imageCount, chapterCount) = computeAndCacheFolderStats(folder, isCollection = true)
                    preferencesGateway.setCachedFolderStatus(
                        folder,
                        resolveFolderStatus(items.map { it.status })
                    )
                    FolderContent.Chapters(items)
                } else {
                    val items = repository.listImages(folder).map { file ->
                        ImageItem(file = file, translated = isImageTranslated(file, folder))
                    }
                    computeAndCacheFolderStats(folder, isCollection = false)
                    preferencesGateway.setCachedFolderStatus(
                        folder,
                        resolveFolderStatus(items.map { item ->
                            if (item.translated) FolderStatus.TRANSLATED else FolderStatus.UNTRANSLATED
                        })
                    )
                    FolderContent.Images(items)
                }
            }
            if (!isAdded || _binding == null || generation != folderContentLoadGeneration ||
                currentFolder?.absolutePath != folder.absolutePath
            ) return@launch
            when (content) {
                is FolderContent.Chapters -> {
                    chapterAdapter.submit(content.items)
                    imageAdapter.submit(emptyList())
                    binding.folderChapterList.visibility = View.VISIBLE
                    binding.folderImageList.visibility = View.GONE
                    binding.folderImagesEmpty.text = getString(R.string.folder_chapters_empty)
                    binding.folderImagesEmpty.visibility =
                        if (content.items.isEmpty()) View.VISIBLE else View.GONE
                    uiCallbacks.clearFolderStatus()
                }
                is FolderContent.Images -> {
                    imageAdapter.submit(content.items)
                    chapterAdapter.submit(emptyList())
                    binding.folderChapterList.visibility = View.GONE
                    binding.folderImageList.visibility = View.VISIBLE
                    binding.folderImagesEmpty.text = getString(R.string.folder_images_empty)
                    binding.folderImagesEmpty.visibility =
                        if (content.items.isEmpty()) View.VISIBLE else View.GONE
                    if (selectionController.isSelectionMode) {
                        selectionController.updateSelectionActions()
                    } else {
                        uiCallbacks.clearFolderStatus()
                    }
                }
            }
        }
    }

    private fun syncExportActionState(folder: File) {
        val exportEnabled = !importExportCoordinator.isExportActiveFor(folder)
        binding.folderExport.isEnabled = exportEnabled
        binding.folderExportCollection.isEnabled = exportEnabled
    }

    private fun openFolder(folder: File) {
        folderAdapter.clearActionSelection()
        showFolderDetail(folder)
    }

    private fun openChildFolder(folder: File) {
        if (selectionManager.isChapterSelectionMode) return
        chapterAdapter.clearActionSelection()
        val parent = currentFolder ?: return
        showFolderDetail(folder, parent)
    }

    private fun openTutorial() {
        val (githubUrl, giteeUrl) = getTutorialUrls()

        // For non-Chinese languages, open GitHub directly without asking
        if (!isChineseLanguage()) {
            openUrlOrToast(githubUrl)
            return
        }

        // For Chinese users, ask which mirror to use
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.tutorial_open_title)
            .setMessage(R.string.tutorial_open_message)
            .setPositiveButton(R.string.tutorial_open_mirror) { _, _ ->
                openUrlOrToast(giteeUrl)
            }
            .setNegativeButton(R.string.tutorial_open_github) { _, _ ->
                openUrlOrToast(githubUrl)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .showWithScrollableMessage()
    }

    private fun openUrlOrToast(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        val manager = requireContext().packageManager
        if (intent.resolveActivity(manager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCreateEntryDialog() {
        dialogs.showCreateEntryDialog(
            context = requireContext(),
            onCreateFolder = { showCreateFolderDialog() },
            onCreateCollection = { showCreateCollectionDialog() }
        )
    }

    private fun showCreateFolderDialog() {
        dialogs.showCreateFolderDialog(requireContext()) { name ->
            val folder = repository.createFolder(name)
            if (folder == null) {
                AppLogger.log("Library", "Create folder failed: $name")
                Toast.makeText(requireContext(), R.string.folder_create_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Created folder ${folder.name}")
                loadFolders()
            }
        }
    }

    private fun showCreateCollectionDialog() {
        dialogs.showCreateCollectionDialog(requireContext()) { name ->
            val folder = repository.createCollection(name)
            if (folder == null) {
                AppLogger.log("Library", "Create collection failed: $name")
                Toast.makeText(requireContext(), R.string.folder_create_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Created collection ${folder.name}")
                loadFolders()
            }
        }
    }

    private fun showCreateChapterDialog(parent: File) {
        dialogs.showCreateChapterDialog(requireContext()) { name ->
            val folder = repository.createChildFolder(parent, name)
            if (folder == null) {
                AppLogger.log("Library", "Create chapter failed in ${parent.name}: $name")
                Toast.makeText(requireContext(), R.string.folder_create_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Created chapter ${folder.name} in ${parent.name}")
                preferencesGateway.invalidateCachedFolderStats(parent)
                loadImages(parent)
                loadFolders()
            }
        }
    }

    private fun addImagesToFolder(uris: List<Uri>) {
        val folder = currentFolder ?: return
        val wasEmpty = repository.listImages(folder).isEmpty()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val added = importExportCoordinator.addImages(folder, uris)
            if (wasEmpty && added.isNotEmpty()) {
                preferencesGateway.autoDetectAndSetReadingMode(folder, added)
            }
            withContext(Dispatchers.Main) {
                if (added.isEmpty() && uris.isNotEmpty()) {
                    Toast.makeText(requireContext(), R.string.import_images_failed, Toast.LENGTH_SHORT).show()
                } else if (wasEmpty && added.isNotEmpty()) {
                    updateReadingModeButton(folder)
                }
                AppLogger.log("Library", "Added ${added.size} images to ${folder.name}")
                loadImages(folder)
                loadFolders()
            }
        }
    }

    private fun handleAddContentClick() {
        val folder = currentFolder ?: return
        if (repository.isCollectionFolder(folder)) {
            showCreateChapterDialog(folder)
        } else {
            pickImages.launch(arrayOf("image/*"))
        }
    }

    private fun navigateBackFromDetail() {
        val parentFolder = currentParentFolder
        if (parentFolder != null) {
            showFolderDetail(parentFolder)
        } else {
            showFolderList()
        }
    }

    private fun importChildChapters() {
        val parentFolder = currentFolder ?: return
        if (!repository.isCollectionFolder(parentFolder)) return
        pendingChapterImportParent = parentFolder
        importExportCoordinator.requestImportDirectory { initialUri ->
            pickChapterImportTree.launch(initialUri)
        }
    }

    private fun importFromEhViewer() {
        importExportCoordinator.requestImportDirectory { initialUri ->
            pickImportTree.launch(initialUri)
        }
    }

    private fun importFromArchiveOrPdf(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val assessment = withContext(Dispatchers.IO) {
                importExportCoordinator.assessImportMemory(requireContext(), uri)
            }
            if (!assessment.shouldWarn) {
                startArchiveOrPdfImport(uri, riskAlreadyAccepted = false)
                return@launch
            }
            showImportMemoryWarning(assessment) {
                startArchiveOrPdfImport(uri, riskAlreadyAccepted = true)
            }
        }
    }

    private fun startArchiveOrPdfImport(uri: Uri, riskAlreadyAccepted: Boolean) {
        importExportCoordinator.importFromArchiveOrPdf(
            uiContext = requireContext(),
            uri = uri,
            scope = viewLifecycleOwner.lifecycleScope,
            riskAlreadyAccepted = riskAlreadyAccepted,
            onConfirmMemoryRisk = ::awaitImportMemoryWarning,
            onShowFolderList = { showFolderList() }
        )
    }

    private fun showImportMemoryWarning(
        assessment: ResourceAssessment,
        onImportAnyway: () -> Unit
    ) {
        ResourceWarningDialogs.createBuilder(requireContext(), assessment)
            .setNegativeButton(R.string.import_anyway) { _, _ -> onImportAnyway() }
            .setPositiveButton(R.string.import_cancel, null)
            .showWithScrollableMessage()
    }

    private suspend fun awaitImportMemoryWarning(assessment: ResourceAssessment): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val dialog = ResourceWarningDialogs.createBuilder(requireContext(), assessment)
                .setNegativeButton(R.string.import_anyway) { _, _ ->
                    if (continuation.isActive) continuation.resume(true)
                }
                .setPositiveButton(R.string.import_cancel) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(false)
                }
                .showWithScrollableMessage()
            continuation.invokeOnCancellation { dialog.dismiss() }
        }
    }

    private fun confirmDeleteFolder(folder: File) {
        val parentCollection = folder.parentFile?.takeIf(repository::isCollectionFolder)
        dialogs.confirmDeleteFolder(requireContext(), folder.name) {
            val deleted = repository.deleteFolder(folder)
            if (!deleted) {
                AppLogger.log("Library", "Delete folder failed: ${folder.name}")
                Toast.makeText(requireContext(), R.string.folder_delete_failed, Toast.LENGTH_SHORT).show()
            } else {
                preferencesGateway.clearFolderTreeSettings(folder)
                parentCollection?.let(preferencesGateway::invalidateCachedFolderStats)
                readingProgressStore.removeTree(folder)
                AppLogger.log("Library", "Deleted folder ${folder.name}")
            }
            refreshFolderViewsAfterMutation(folder)
        }
    }

    private fun showRenameFolderDialog(folder: File) {
        dialogs.showRenameFolderDialog(requireContext(), folder.name) { inputName ->
            val renamed = repository.renameFolder(folder, inputName)
            if (renamed == null) {
                AppLogger.log("Library", "Rename folder failed: ${folder.name} -> $inputName")
                Toast.makeText(requireContext(), R.string.folder_rename_failed, Toast.LENGTH_SHORT).show()
            } else {
                preferencesGateway.migrateFolderSettings(folder, renamed)
                readingProgressStore.migrateTree(folder, renamed)
                AppLogger.log("Library", "Renamed folder ${folder.name} -> ${renamed.name}")
                refreshFolderViewsAfterMutation(folder, renamed)
            }
        }
    }

    private fun showEditFolderTagsDialog(item: FolderItem) {
        dialogs.showEditFolderTagsDialog(
            context = requireContext(),
            statusLabel = item.status.labelRes.takeIf { item.statsLoaded }?.let(::getString),
            initialTags = item.customTags.toSet()
        ) { tags ->
            preferencesGateway.setFolderTags(item.folder, tags)
            loadFolders()
        }
    }

    private fun applyFolderFilter(filter: FolderFilter?) {
        if (selectionManager.isLibrarySelectionMode) {
            selectionManager.exitLibrarySelectionMode()
        }
        activeFolderFilter = if (activeFolderFilter == filter) null else filter
        loadFolders()
    }

    private fun updateActiveFolderFilter() {
        val filter = activeFolderFilter
        binding.libraryActiveFilter.isVisible = filter != null
        binding.libraryActiveFilter.text = filter?.let {
            val label = when (it) {
                is FolderFilter.Status -> getString(it.status.labelRes)
                is FolderFilter.CustomTag -> it.tag
            }
            getString(R.string.folder_filter_active, label)
        }.orEmpty()
    }

    private fun showMoveFolderPicker(folder: File) {
        val sourceCollection = folder.parentFile?.takeIf(repository::isCollectionFolder)
        val collections = repository
            .listFolders(LibrarySortField.NAME, ascending = true)
            .filter { it.absolutePath != folder.absolutePath }
            .filter { repository.isCollectionFolder(it) }
            .filter { it.absolutePath != folder.parentFile?.absolutePath }
        if (collections.isEmpty()) {
            Toast.makeText(requireContext(), R.string.folder_move_no_collections, Toast.LENGTH_SHORT).show()
            return
        }
        dialogs.showMoveFolderDialog(requireContext(), collections.map { buildFolderTitle(it) }) { index ->
            val targetCollection = collections.getOrNull(index) ?: return@showMoveFolderDialog
            val moved = repository.moveFolderToCollection(folder, targetCollection)
            if (moved == null) {
                AppLogger.log("Library", "Move folder failed: ${folder.name} -> ${targetCollection.name}")
                Toast.makeText(requireContext(), R.string.folder_move_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Moved folder ${folder.name} -> ${targetCollection.name}/${moved.name}")
                sourceCollection?.let(preferencesGateway::invalidateCachedFolderStats)
                preferencesGateway.invalidateCachedFolderStats(targetCollection)
                refreshFolderViewsAfterMutation(folder, moved)
            }
        }
    }

    private fun refreshFolderViewsAfterMutation(original: File, updated: File? = null) {
        val visibleFolder = currentFolder
        val visibleParent = currentParentFolder
        val currentPath = visibleFolder?.absolutePath
        val parentPath = visibleParent?.absolutePath
        val originalPath = original.absolutePath
        val originalParentPath = original.parentFile?.absolutePath

        when {
            currentPath == originalPath -> {
                if (updated == null) {
                    showFolderList()
                } else {
                    showFolderDetail(updated, visibleParent)
                }
            }
            parentPath == originalPath -> {
                if (updated == null) {
                    showFolderList()
                } else {
                    showFolderDetail(visibleFolder ?: updated, updated)
                }
            }
            currentPath != null && originalParentPath == currentPath -> {
                loadImages(visibleFolder)
                loadFolders()
            }
            else -> loadFolders()
        }
    }

    private fun refreshFolderViewsAfterBatchMutation(deletedFolders: List<File>) {
        val currentPath = currentFolder?.absolutePath
        val parentPath = currentParentFolder?.absolutePath
        val deletedPaths = deletedFolders.map { it.absolutePath }.toHashSet()
        val deletedParentPaths = deletedFolders.mapNotNull { it.parentFile?.absolutePath }.toHashSet()

        when {
            currentPath != null && deletedPaths.contains(currentPath) -> showFolderList()
            parentPath != null && deletedPaths.contains(parentPath) -> showFolderList()
            currentPath != null && deletedParentPaths.contains(currentPath) -> {
                currentFolder?.let(::loadImages)
                loadFolders()
            }
            else -> loadFolders()
        }
    }

    private fun translateFolder() {
        val folder = currentFolder ?: return
        selectionController.exitSelectionMode()
        if (repository.isCollectionFolder(folder)) {
            runCollectionTranslation(folder, force = false)
            return
        }
        runTranslation(folder, repository.listImages(folder), force = false)
    }

    private fun runTranslation(folder: File, images: List<File>, force: Boolean) {
        _binding?.folderTranslate?.isEnabled = false
        TranslationKeepAliveService.startTranslationTask(
            requireContext(),
            taskFactory.buildFolderDescriptor(folder, images, force)
        )
    }

    private fun runCollectionTranslation(collectionFolder: File, force: Boolean) {
        _binding?.folderImportChapters?.isEnabled = false
        _binding?.folderExportCollection?.isEnabled = false
        _binding?.folderTranslateCollection?.isEnabled = false
        _binding?.folderCollectionAddChapter?.isEnabled = false
        TranslationKeepAliveService.startTranslationTask(
            requireContext(),
            taskFactory.buildCollectionDescriptor(collectionFolder, force)
        )
    }

    private fun updateGlossaryProcessingSwitchState(folder: File) {
        val enabled = !preferencesGateway.isFullTranslateEnabled(folder)
        binding.folderGlossaryProcessingSwitch.isEnabled = enabled
        binding.folderGlossaryProcessingInfo.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateVlDirectTranslateSwitchState(folder: File) {
        val enabled = !preferencesGateway.isFullTranslateEnabled(folder)
        if (!enabled && preferencesGateway.isVlDirectTranslateEnabled(folder)) {
            preferencesGateway.setVlDirectTranslateEnabled(folder, false)
            if (binding.folderVlDirectTranslateSwitch.isChecked) {
                binding.folderVlDirectTranslateSwitch.isChecked = false
            }
        }
        binding.folderVlDirectTranslateSwitch.isEnabled = enabled
        binding.folderVlDirectTranslateInfo.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateFolderTranslationSwitchStates(folder: File) {
        updateGlossaryProcessingSwitchState(folder)
        updateVlDirectTranslateSwitchState(folder)
    }

    private fun exportFolder() {
        val folder = currentFolder ?: return
        val images = repository.listImages(folder)
        viewLifecycleOwner.lifecycleScope.launch {
            val suggestedThreads = importExportCoordinator.suggestExportThreadCount(images)
            showFolderExportOptions(folder, images, suggestedThreads)
        }
    }

    private fun showFolderExportOptions(
        folder: File,
        images: List<File>,
        suggestedThreads: Int
    ) {
        dialogs.showExportOptionsDialog(
            context = requireContext(),
            defaultThreads = suggestedThreads,
            defaultExportFormat = importExportCoordinator.getExportFormatDefault(),
            exportRootPathHint = importExportCoordinator.buildExportRootPathPreview()
        ) { exportThreads, exportFormat ->
            confirmExportResources(images, exportThreads) {
                startFolderExport(folder, images, exportThreads, exportFormat)
            }
        }
    }

    private fun startFolderExport(
        folder: File,
        images: List<File>,
        exportThreads: Int,
        exportFormat: LibraryImportExportCoordinator.ExportFormat
    ) {
        importExportCoordinator.exportFolder(
            uiContext = requireContext(),
            folder = folder,
            images = images,
            exportThreads = exportThreads,
            exportFormat = exportFormat,
            requestExportDirectoryPermission = { initialUri -> pickExportTree.launch(initialUri) },
            requestLegacyPermission = {
                requestStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            },
            onExitSelectionMode = { selectionController.exitSelectionMode() },
            onSetExportEnabled = { enabled -> _binding?.folderExport?.isEnabled = enabled }
        )
    }

    private fun exportCollection() {
        val folder = currentFolder ?: return
        val chapterImages = buildChapterImagesForCollection(folder)
        if (chapterImages.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.folder_chapters_empty))
            return
        }
        val allImages = chapterImages.flatMap { it.second }
        viewLifecycleOwner.lifecycleScope.launch {
            val suggestedThreads = importExportCoordinator.suggestExportThreadCount(allImages)
            showCollectionExportOptions(folder, chapterImages, allImages, suggestedThreads)
        }
    }

    private fun showCollectionExportOptions(
        folder: File,
        chapterImages: List<Pair<File, List<File>>>,
        allImages: List<File>,
        suggestedThreads: Int
    ) {
        dialogs.showExportOptionsDialog(
            context = requireContext(),
            defaultThreads = suggestedThreads,
            defaultExportFormat = importExportCoordinator.getExportFormatDefault(),
            exportRootPathHint = importExportCoordinator.buildExportRootPathPreview()
        ) { exportThreads, exportFormat ->
            confirmExportResources(allImages, exportThreads) {
                startCollectionExport(folder, chapterImages, exportThreads, exportFormat)
            }
        }
    }

    private fun startCollectionExport(
        folder: File,
        chapterImages: List<Pair<File, List<File>>>,
        exportThreads: Int,
        exportFormat: LibraryImportExportCoordinator.ExportFormat
    ) {
        importExportCoordinator.exportCollection(
            uiContext = requireContext(),
            collectionFolder = folder,
            chapterImages = chapterImages,
            exportThreads = exportThreads,
            exportFormat = exportFormat,
            requestExportDirectoryPermission = { initialUri -> pickExportTree.launch(initialUri) },
            requestLegacyPermission = {
                requestStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            },
            onExitSelectionMode = { selectionController.exitSelectionMode() },
            onSetExportEnabled = { enabled -> _binding?.folderExportCollection?.isEnabled = enabled }
        )
    }

    private fun confirmExportResources(
        images: List<File>,
        requestedThreads: Int,
        onConfirmed: () -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val assessment = importExportCoordinator.assessExportResources(images, requestedThreads)
            if (!assessment.shouldWarn) {
                onConfirmed()
                return@launch
            }
            showResourceWarning(assessment, onConfirmed)
        }
    }

    private fun showResourceWarning(
        assessment: ResourceAssessment,
        onContinue: () -> Unit
    ) {
        ResourceWarningDialogs.createBuilder(requireContext(), assessment)
            .setNegativeButton(R.string.resource_continue_anyway) { _, _ -> onContinue() }
            .setPositiveButton(R.string.resource_cancel, null)
            .showWithScrollableMessage()
    }

    private fun buildChapterImagesForCollection(collectionFolder: File): List<Pair<File, List<File>>> {
        return repository.listChildFolders(collectionFolder).map { chapter ->
            chapter to repository.listImages(chapter)
        }.filter { it.second.isNotEmpty() }
    }

    private fun startReading() {
        val folder = currentFolder ?: return
        exitActiveSelectionMode()
        if (repository.isCollectionFolder(folder)) {
            val images = repository.listChildFolders(folder).flatMap { repository.listImages(it) }
            if (images.isEmpty()) {
                uiCallbacks.setFolderStatus(getString(R.string.folder_chapters_empty))
                return
            }
            val startIndex = readingProgressStore.load(folder)
            val readingMode = preferencesGateway.getReadingMode(folder)
            readingSessionViewModel.setFolder(folder, images, startIndex, readingMode)
            (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
            return
        }
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        AppLogger.log("Library", "Start reading ${folder.name}, ${images.size} images")
        val startIndex = readingProgressStore.load(folder)
        val readingMode = preferencesGateway.getReadingMode(folder)
        readingSessionViewModel.setFolder(folder, images, startIndex, readingMode)
        (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
    }

    private fun openImageInReader(imageFile: File) {
        val folder = currentFolder ?: return
        if (selectionController.isSelectionMode) return
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        val startIndex = images.indexOfFirst { it.absolutePath == imageFile.absolutePath }
        if (startIndex < 0) return
        AppLogger.log("Library", "Open image ${imageFile.name} at index $startIndex in ${folder.name}")
        val readingMode = preferencesGateway.getReadingMode(folder)
        readingSessionViewModel.setFolder(folder, images, startIndex, readingMode)
        (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
    }

    private fun showFullTranslateInfo() {
        dialogs.showFullTranslateInfo(requireContext())
    }

    private fun updateLanguageSettingButton(folder: File) {
        val useLocalOcr = settingsStore.loadOcrApiSettings().useLocalOcr
        val language = TranslationLanguage.resolveForOcr(
            preferencesGateway.getTranslationLanguage(folder),
            useLocalOcr
        )
        val displayName = language.displayName(requireContext())
        binding.folderLanguageSetting.text = getString(R.string.folder_language_setting, displayName)
    }

    private fun updateReadingModeButton(folder: File) {
        val mode = preferencesGateway.getReadingMode(folder)
        binding.folderReadingModeButton.text = getString(
            R.string.folder_reading_mode_format,
            getString(mode.labelRes)
        )
    }

    private fun showLanguageSettingDialog() {
        val folder = currentFolder ?: return
        val useLocalOcr = settingsStore.loadOcrApiSettings().useLocalOcr
        val currentLanguage = preferencesGateway.getTranslationLanguage(folder)
        dialogs.showLanguageSettingDialog(
            context = requireContext(),
            languages = TranslationLanguage.supportedForOcr(useLocalOcr),
            currentLanguage = currentLanguage
        ) { selectedLanguage ->
            preferencesGateway.setTranslationLanguage(folder, selectedLanguage)
            updateLanguageSettingButton(folder)
            AppLogger.log("Library", "Set language for ${folder.name}: ${selectedLanguage.name}")
        }
    }

    private fun showFolderReadingModeDialog() {
        val folder = currentFolder ?: return
        val currentMode = preferencesGateway.getReadingMode(folder)
        dialogs.showFolderReadingModeDialog(requireContext(), currentMode) { selectedMode ->
            preferencesGateway.setReadingMode(folder, selectedMode)
            readingSessionViewModel.updateReadingMode(folder, selectedMode)
            updateReadingModeButton(folder)
            AppLogger.log("Library", "Set reading mode for ${folder.name}: ${selectedMode.prefValue}")
        }
    }

    private fun buildFolderItem(folder: File): FolderItem {
        val isCollection = repository.isCollectionFolder(folder)
        val chapters = if (isCollection) repository.listChildFolders(folder) else emptyList()
        val images = if (isCollection) {
            chapters.flatMap { repository.listImages(it) }
        } else {
            repository.listImages(folder)
        }
        val (imageCount, chapterCount) = computeAndCacheFolderStats(folder, isCollection)
        return FolderItem(
            folder = folder,
            imageCount = imageCount,
            chapterCount = chapterCount,
            isCollection = isCollection,
            status = resolveFolderStatus(folder, images),
            customTags = preferencesGateway.getFolderTags(folder)
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        )
    }

    private fun resolveFolderStatus(folder: File, images: List<File>): FolderStatus {
        if (images.isEmpty()) return FolderStatus.UNTRANSLATED
        val allTranslated = images.all { image -> isImageTranslated(image, folder) }
        return if (allTranslated) FolderStatus.TRANSLATED else FolderStatus.UNTRANSLATED
    }

    private fun resolveFolderStatus(statuses: List<FolderStatus>): FolderStatus {
        return if (statuses.isNotEmpty() && statuses.all { it == FolderStatus.TRANSLATED }) {
            FolderStatus.TRANSLATED
        } else {
            FolderStatus.UNTRANSLATED
        }
    }

    private fun isImageTranslated(image: File, folder: File): Boolean {
        // Align list/folder badges with engine skip rules: only SUCCESS or manual counts.
        val result = translationStore.load(image) ?: return false
        if (result.metadata.isManual()) return true
        return result.metadata.status == PageTranslationStatus.SUCCESS
    }

    private fun buildFolderTitle(folder: File): String {
        return if (repository.isCollectionFolder(folder)) {
            folder.name + getString(R.string.folder_collection_title_suffix)
        } else {
            folder.name
        }
    }

    private fun updateFolderContentMode(folder: File) {
        val isCollection = repository.isCollectionFolder(folder)
        val useParentCollectionSettings =
            repository.resolveSettingsFolder(folder).absolutePath != folder.absolutePath
        binding.folderAddImages.visibility = if (isCollection) View.GONE else View.VISIBLE
        binding.folderRead.visibility = if (isCollection) View.GONE else View.VISIBLE
        binding.folderCollectionActions.visibility = if (isCollection) View.VISIBLE else View.GONE
        binding.folderExport.visibility = if (isCollection) View.GONE else View.VISIBLE
        binding.folderTranslate.visibility = if (isCollection) View.GONE else View.VISIBLE
        binding.folderTranslationSettings.visibility =
            if (useParentCollectionSettings) View.GONE else View.VISIBLE
        binding.folderReadingSettings.visibility =
            if (useParentCollectionSettings) View.GONE else View.VISIBLE
        binding.folderSelectionActions.visibility = View.GONE
        binding.folderRenameSelected.visibility = View.GONE
        binding.folderRetranslateSelected.visibility = if (isCollection) View.GONE else View.VISIBLE
        if (isCollection) {
            selectionController.exitSelectionMode()
        }
    }

    private fun handleSelectAllClick() {
        if (selectionManager.isChapterSelectionMode) {
            selectionManager.toggleSelectAllChapters()
            return
        }
        selectionController.toggleSelectAllImages()
    }

    private fun updateLibrarySelectionActions() {
        if (!selectionManager.isLibrarySelectionMode || _binding == null) return
        val count = selectionManager.librarySelectedCount()
        uiCallbacks.setFolderStatus(getString(R.string.library_selection_count, count))
        binding.librarySelectAll.text = getString(
            if (selectionManager.isLibraryAllSelected()) R.string.clear_all else R.string.select_all
        )
    }

    private fun applyTranslationActionsEnabled(enabled: Boolean) {
        val binding = _binding ?: return
        binding.folderTranslate.isEnabled = enabled
        binding.folderImportChapters.isEnabled = enabled
        binding.folderExportCollection.isEnabled = enabled
        binding.folderTranslateCollection.isEnabled = enabled
        binding.folderCollectionAddChapter.isEnabled = enabled
        // RadioGroup#isEnabled does not reliably propagate to its children,
        // so update each mode option explicitly. Otherwise the three-state
        // selector can remain locked after the service reports completion.
        binding.folderBubbleDetectionModeGroup.isEnabled = enabled
        binding.folderDetectionModeBubbles.isEnabled = enabled
        binding.folderDetectionModeText.isEnabled = enabled
        binding.folderDetectionModeBubblesAndText.isEnabled = enabled
        if (selectionManager.isLibrarySelectionMode) {
            binding.librarySelectAll.isEnabled = enabled
            binding.libraryTranslateSelected.isEnabled = enabled
            binding.libraryDeleteSelected.isEnabled = enabled
            binding.libraryCancelSelection.isEnabled = enabled
        }
    }

    private fun translateSelectedLibraryFolders() {
        val selected = selectionManager.librarySelectedFolders()
        if (selected.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.translate_folders_empty))
            return
        }
        val tasks = selected.flatMap { taskFactory.buildTasksForFolder(it, force = false) }
        _binding?.librarySelectAll?.isEnabled = false
        _binding?.libraryTranslateSelected?.isEnabled = false
        _binding?.libraryDeleteSelected?.isEnabled = false
        _binding?.libraryCancelSelection?.isEnabled = false
        TranslationKeepAliveService.startTranslationTask(
            requireContext(),
            taskFactory.buildBatchDescriptor(tasks)
        )
    }

    private fun confirmDeleteSelectedLibraryFolders() {
        val selected = selectionManager.librarySelectedFolders()
        if (selected.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.folder_delete_empty))
            return
        }
        dialogs.confirmDeleteSelectedLibraryFolders(requireContext(), selected.size) {
            var failed = false
            selected.forEach { folder ->
                if (!repository.deleteFolder(folder)) {
                    failed = true
                } else {
                    preferencesGateway.clearFolderTreeSettings(folder)
                    readingProgressStore.removeTree(folder)
                }
            }
            if (failed) {
                AppLogger.log("Library", "Delete selected root folders failed")
                Toast.makeText(requireContext(), R.string.delete_folders_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Deleted ${selected.size} root folders")
            }
            selectionManager.exitLibrarySelectionMode()
            refreshFolderViewsAfterBatchMutation(selected)
        }
    }

    private fun handleDeleteSelectedClick() {
        if (selectionManager.isChapterSelectionMode) {
            confirmDeleteSelectedChapters()
            return
        }
        selectionController.confirmDeleteSelectedImages(currentFolder)
    }

    private fun exitActiveSelectionMode() {
        selectionManager.exitChapterSelectionMode()
        selectionController.exitSelectionMode()
    }

    private fun updateChapterSelectionActions() {
        if (!selectionManager.isChapterSelectionMode) return
        val count = selectionManager.chapterSelectedCount()
        uiCallbacks.setFolderStatus(getString(R.string.chapter_selection_count, count))
        binding.folderSelectAll.text = getString(
            if (selectionManager.isChapterAllSelected()) R.string.clear_all else R.string.select_all
        )
        binding.folderRenameSelected.visibility = if (count == 1) View.VISIBLE else View.GONE
    }

    private fun confirmDeleteSelectedChapters() {
        val folder = currentFolder ?: return
        val selected = selectionManager.chapterSelectedFolders()
        if (selected.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.delete_chapters_empty))
            return
        }
        dialogs.confirmDeleteSelectedFolders(requireContext(), selected.size) {
            var failed = false
            selected.forEach { child ->
                if (!repository.deleteFolder(child)) {
                    failed = true
                } else {
                    preferencesGateway.clearFolderTreeSettings(child)
                    readingProgressStore.removeTree(child)
                }
            }
            if (failed) {
                AppLogger.log("Library", "Delete selected chapters failed in ${folder.name}")
                Toast.makeText(requireContext(), R.string.delete_chapters_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Deleted ${selected.size} chapters from ${folder.name}")
            }
            preferencesGateway.invalidateCachedFolderStats(folder)
            selectionManager.exitChapterSelectionMode()
            loadImages(folder)
            loadFolders()
        }
    }

    private fun renameSelectedChapter() {
        val selected = selectionManager.chapterSelectedFolders()
        if (selected.size != 1) return
        selectionManager.exitChapterSelectionMode()
        showRenameFolderDialog(selected.first())
    }

    private sealed interface FolderFilter {
        data class Status(val status: FolderStatus) : FolderFilter
        data class CustomTag(val tag: String) : FolderFilter
    }

}
