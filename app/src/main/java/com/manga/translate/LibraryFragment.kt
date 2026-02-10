package com.manga.translate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.manga.translate.databinding.FragmentLibraryBinding
import java.io.File

class LibraryFragment : Fragment() {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()
    private lateinit var repository: LibraryRepository
    private lateinit var translationPipeline: TranslationPipeline
    private val translationStore = TranslationStore()
    private val glossaryStore = GlossaryStore()
    private val extractStateStore = ExtractStateStore()
    private val ocrStore = OcrStore()
    private val embeddedStateStore = EmbeddedStateStore()
    private lateinit var readingProgressStore: ReadingProgressStore
    private val settingsStore by lazy { SettingsStore(requireContext()) }
    private val dialogs = LibraryDialogs()

    private val prefs by lazy {
        requireContext().getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
    }

    private lateinit var preferencesGateway: LibraryPreferencesGateway
    private lateinit var translationCoordinator: FolderTranslationCoordinator
    private lateinit var embedCoordinator: FolderEmbedCoordinator
    private lateinit var importExportCoordinator: LibraryImportExportCoordinator
    private lateinit var selectionController: LibrarySelectionController

    private var currentFolder: File? = null
    private var embedActionsEnabled: Boolean = true

    private val tutorialUrlGithub =
        "https://github.com/jedzqer/manga-translator/blob/main/Tutorial/简中教程.md"
    private val tutorialUrlGitee =
        "https://gitee.com/jedzqer/manga-translator/blob/main/Tutorial/简中教程.md"

    private val folderAdapter = LibraryFolderAdapter(
        onClick = { openFolder(it.folder) },
        onDelete = { confirmDeleteFolder(it.folder) }
    )

    private val imageAdapter = FolderImageAdapter(
        onSelectionChanged = { selectionController.updateSelectionActions() },
        onItemLongPress = { selectionController.enterSelectionMode(it.file) },
        onItemClick = { openImageInReader(it.file) }
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

        override fun showToast(resId: Int) {
            if (!isAdded) return
            Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
        }

        override fun showToastMessage(message: String) {
            if (!isAdded) return
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }

        override fun showApiError(code: String) {
            if (!isAdded) return
            dialogs.showApiErrorDialog(requireContext(), code)
        }

        override fun showModelError(content: String) {
            if (!isAdded) return
            dialogs.showModelErrorDialog(requireContext(), content)
        }

        override fun refreshFolders() {
            if (!isAdded || _binding == null) return
            loadFolders()
        }

        override fun refreshImages(folder: File) {
            if (!isAdded || _binding == null) return
            loadImages(folder)
        }

        override fun isFragmentActive(): Boolean {
            return isAdded && _binding != null
        }
    }

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        importExportCoordinator.handleStoragePermissionResult(granted) {
            val folder = currentFolder ?: return@handleStoragePermissionResult
            importExportCoordinator.exportFolderAfterPermission(
                uiContext = requireContext(),
                folder = folder,
                images = repository.listImages(folder),
                scope = viewLifecycleOwner.lifecycleScope,
                onExitSelectionMode = { selectionController.exitSelectionMode() },
                onSetExportEnabled = { enabled -> _binding?.folderExport?.isEnabled = enabled }
            )
        }
    }

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            addImagesToFolder(uris)
        }
    }

    private val pickEhViewerTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            importExportCoordinator.handleEhViewerTreeSelection(
                uiContext = requireContext(),
                uri = uri,
                scope = viewLifecycleOwner.lifecycleScope,
                onShowFolderList = { showFolderList() }
            )
        }
    }

    private val pickCbzFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importFromCbz(uri)
        }
    }

    private val pickExportTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            importExportCoordinator.handleExportTreeSelection(uri) {
                val folder = currentFolder ?: return@handleExportTreeSelection
                importExportCoordinator.exportFolderAfterPermission(
                    uiContext = requireContext(),
                    folder = folder,
                    images = repository.listImages(folder),
                    scope = viewLifecycleOwner.lifecycleScope,
                    onExitSelectionMode = { selectionController.exitSelectionMode() },
                    onSetExportEnabled = { enabled -> _binding?.folderExport?.isEnabled = enabled }
                )
            }
        } else {
            importExportCoordinator.handleExportTreeCanceled()
        }
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
        repository = LibraryRepository(requireContext())
        translationPipeline = TranslationPipeline(requireContext())
        readingProgressStore = ReadingProgressStore(requireContext())

        preferencesGateway = LibraryPreferencesGateway(requireContext(), prefs)
        translationCoordinator = FolderTranslationCoordinator(
            context = requireContext(),
            translationPipeline = translationPipeline,
            glossaryStore = glossaryStore,
            extractStateStore = extractStateStore,
            translationStore = translationStore,
            settingsStore = settingsStore,
            ui = uiCallbacks
        )
        embedCoordinator = FolderEmbedCoordinator(
            context = requireContext(),
            translationStore = translationStore,
            settingsStore = settingsStore,
            prefs = prefs,
            embeddedStateStore = embeddedStateStore,
            ui = uiCallbacks
        )
        importExportCoordinator = LibraryImportExportCoordinator(
            context = requireContext(),
            repository = repository,
            translationStore = translationStore,
            settingsStore = settingsStore,
            prefs = prefs,
            preferencesGateway = preferencesGateway,
            dialogs = dialogs,
            ui = uiCallbacks
        )
        selectionController = LibrarySelectionController(
            imageAdapter = imageAdapter,
            translationStore = translationStore,
            ocrStore = ocrStore,
            repository = repository,
            ui = uiCallbacks,
            dialogs = dialogs,
            bindingProvider = { _binding },
            contextProvider = { if (isAdded) requireContext() else null },
            onRetranslateRequested = { folder, selected, force ->
                runTranslation(folder, selected, force)
            }
        )

        binding.folderList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderList.adapter = folderAdapter
        binding.folderImageList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderImageList.adapter = imageAdapter

        binding.addFolderFab.setOnClickListener { showCreateFolderDialog() }
        binding.importEhviewerButton.setOnClickListener { importFromEhViewer() }
        binding.importCbzButton.setOnClickListener {
            pickCbzFile.launch(arrayOf("*/*"))
        }
        binding.tutorialButton.setOnClickListener { openTutorial() }
        binding.folderBackButton.setOnClickListener { showFolderList() }
        binding.folderAddImages.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        binding.folderExport.setOnClickListener { exportFolder() }
        binding.folderTranslate.setOnClickListener { translateFolder() }
        binding.folderRead.setOnClickListener { startReading() }
        binding.folderEmbed.setOnClickListener { embedFolder() }
        binding.folderUnembed.setOnClickListener { cancelEmbed() }
        binding.folderSelectAll.setOnClickListener { selectionController.toggleSelectAllImages() }
        binding.folderDeleteSelected.setOnClickListener { selectionController.confirmDeleteSelectedImages(currentFolder) }
        binding.folderCancelSelection.setOnClickListener { selectionController.exitSelectionMode() }
        binding.folderRetranslateSelected.setOnClickListener {
            val folder = currentFolder
            val enabled = folder?.let { preferencesGateway.isFullTranslateEnabled(it) } ?: true
            selectionController.retranslateSelectedImages(folder, enabled)
        }
        binding.folderFullTranslateInfo.setOnClickListener { showFullTranslateInfo() }
        binding.folderLanguageSetting.setOnClickListener { showLanguageSettingDialog() }
        binding.folderFullTranslateSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentFolder?.let { preferencesGateway.setFullTranslateEnabled(it, isChecked) }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectionController.isSelectionMode) {
                        selectionController.exitSelectionMode()
                        return
                    }
                    if (binding.folderDetailContainer.visibility == View.VISIBLE) {
                        showFolderList()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        loadFolders()
        showFolderList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showFolderList() {
        currentFolder = null
        embedActionsEnabled = true
        binding.libraryListContainer.visibility = View.VISIBLE
        binding.folderDetailContainer.visibility = View.GONE
        binding.addFolderFab.visibility = View.VISIBLE
        binding.tutorialButton.visibility = View.VISIBLE
        binding.importCbzButton.visibility = View.VISIBLE
        binding.importEhviewerButton.visibility = View.VISIBLE
        uiCallbacks.clearFolderStatus()
        selectionController.exitSelectionMode()
        folderAdapter.clearDeleteSelection()
        loadFolders()
    }

    private fun showFolderDetail(folder: File) {
        currentFolder = folder
        binding.folderTitle.text = folder.name
        binding.folderFullTranslateSwitch.isChecked = preferencesGateway.isFullTranslateEnabled(folder)
        updateLanguageSettingButton(folder)
        updateEmbedButtonState(folder)
        binding.libraryListContainer.visibility = View.GONE
        binding.folderDetailContainer.visibility = View.VISIBLE
        binding.addFolderFab.visibility = View.GONE
        binding.tutorialButton.visibility = View.GONE
        binding.importCbzButton.visibility = View.GONE
        binding.importEhviewerButton.visibility = View.GONE
        selectionController.exitSelectionMode()
        AppLogger.log("Library", "Opened folder ${folder.name}")
        loadImages(folder)
    }

    private fun loadFolders() {
        val folders = repository.listFolders()
        val items = folders.map { folder ->
            FolderItem(folder, repository.listImages(folder).size)
        }
        folderAdapter.submit(items)
        binding.libraryEmpty.text = getString(R.string.folder_empty)
        binding.libraryEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadImages(folder: File) {
        val images = repository.listImages(folder)
        val items = images.map { file ->
            ImageItem(file, translationStore.translationFileFor(file).exists())
        }
        imageAdapter.submit(items)
        updateEmbedButtonState(folder)
        binding.folderImagesEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (selectionController.isSelectionMode) {
            selectionController.updateSelectionActions()
        } else {
            uiCallbacks.clearFolderStatus()
        }
    }

    private fun openFolder(folder: File) {
        showFolderDetail(folder)
    }

    private fun openTutorial() {
        val source = settingsStore.loadLinkSource()
        val url = if (source == LinkSource.GITHUB) tutorialUrlGithub else tutorialUrlGitee
        openUrlOrToast(url)
    }

    private fun openUrlOrToast(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val manager = requireContext().packageManager
        if (intent.resolveActivity(manager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), url, Toast.LENGTH_SHORT).show()
        }
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

    private fun addImagesToFolder(uris: List<Uri>) {
        val folder = currentFolder ?: return
        val added = repository.addImages(folder, uris)
        AppLogger.log("Library", "Added ${added.size} images to ${folder.name}")
        loadImages(folder)
        loadFolders()
    }

    private fun importFromEhViewer() {
        importExportCoordinator.importFromEhViewer(
            uiContext = requireContext(),
            requestEhViewerPermission = { initialUri -> pickEhViewerTree.launch(initialUri) },
            scope = viewLifecycleOwner.lifecycleScope,
            onShowFolderList = { showFolderList() }
        )
    }

    private fun importFromCbz(uri: Uri) {
        importExportCoordinator.importFromCbz(
            uiContext = requireContext(),
            uri = uri,
            scope = viewLifecycleOwner.lifecycleScope,
            onShowFolderList = { showFolderList() }
        )
    }

    private fun confirmDeleteFolder(folder: File) {
        dialogs.confirmDeleteFolder(requireContext(), folder.name) {
            val deleted = repository.deleteFolder(folder)
            if (!deleted) {
                AppLogger.log("Library", "Delete folder failed: ${folder.name}")
                Toast.makeText(requireContext(), R.string.folder_delete_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Deleted folder ${folder.name}")
            }
            loadFolders()
        }
    }

    private fun translateFolder() {
        val folder = currentFolder ?: return
        selectionController.exitSelectionMode()
        runTranslation(folder, repository.listImages(folder), force = false)
    }

    private fun runTranslation(folder: File, images: List<File>, force: Boolean) {
        val fullTranslate = preferencesGateway.isFullTranslateEnabled(folder)
        val language = preferencesGateway.getTranslationLanguage(folder)
        translationCoordinator.translateFolder(
            scope = viewLifecycleOwner.lifecycleScope,
            folder = folder,
            images = images,
            force = force,
            fullTranslate = fullTranslate,
            language = language,
            onTranslateEnabled = { enabled -> _binding?.folderTranslate?.isEnabled = enabled }
        )
    }

    private fun exportFolder() {
        val folder = currentFolder ?: return
        dialogs.showExportOptionsDialog(
            context = requireContext(),
            defaultThreads = importExportCoordinator.getExportThreadCount(),
            defaultExportAsCbz = importExportCoordinator.getExportAsCbzDefault(),
            exportRootPathHint = importExportCoordinator.buildExportRootPathPreview()
        ) { exportThreads, exportAsCbz ->
            importExportCoordinator.exportFolder(
                uiContext = requireContext(),
                folder = folder,
                images = repository.listImages(folder),
                scope = viewLifecycleOwner.lifecycleScope,
                exportThreads = exportThreads,
                exportAsCbz = exportAsCbz,
                requestExportDirectoryPermission = { initialUri -> pickExportTree.launch(initialUri) },
                requestLegacyPermission = {
                    requestStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                },
                onExitSelectionMode = { selectionController.exitSelectionMode() },
                onSetExportEnabled = { enabled -> _binding?.folderExport?.isEnabled = enabled }
            )
        }
    }

    private fun embedFolder() {
        val folder = currentFolder ?: return
        selectionController.exitSelectionMode()
        val images = repository.listImages(folder)
        dialogs.showEmbedOptionsDialog(
            context = requireContext(),
            defaultThreads = embedCoordinator.getEmbedThreadCount()
        ) { embedThreads ->
            embedCoordinator.embedFolder(
                scope = viewLifecycleOwner.lifecycleScope,
                folder = folder,
                images = images,
                embedThreads = embedThreads,
                onSetActionsEnabled = { enabled ->
                    setEmbedActionsEnabled(enabled)
                }
            )
        }
    }

    private fun cancelEmbed() {
        val folder = currentFolder ?: return
        selectionController.exitSelectionMode()
        embedCoordinator.cancelEmbed(
            scope = viewLifecycleOwner.lifecycleScope,
            folder = folder,
            onSetActionsEnabled = { enabled ->
                setEmbedActionsEnabled(enabled)
            }
        )
    }

    private fun startReading() {
        val folder = currentFolder ?: return
        selectionController.exitSelectionMode()
        val originalImages = repository.listImages(folder)
        if (originalImages.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        val resolution = resolveReadingImages(folder, originalImages)
        val images = resolution.images
        val embeddedMode = resolution.embeddedMode
        if (resolution.forcedBubbleMode) {
            uiCallbacks.showToast(R.string.folder_read_force_bubble_unembedded)
        }
        AppLogger.log(
            "Library",
            "Start reading ${folder.name}, ${images.size} images, embedded=$embeddedMode"
        )
        val startIndex = readingProgressStore.load(folder)
        readingSessionViewModel.setFolder(folder, images, startIndex, embeddedMode)
        (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
    }

    private fun openImageInReader(imageFile: File) {
        val folder = currentFolder ?: return
        if (selectionController.isSelectionMode) return
        val originalImages = repository.listImages(folder)
        if (originalImages.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        val originalIndex = originalImages.indexOfFirst { it.absolutePath == imageFile.absolutePath }
        if (originalIndex < 0) return
        val resolution = resolveReadingImages(folder, originalImages)
        val images = resolution.images
        val embeddedMode = resolution.embeddedMode
        if (resolution.forcedBubbleMode) {
            uiCallbacks.showToast(R.string.folder_read_force_bubble_unembedded)
        }
        val startIndex = if (embeddedMode) {
            originalIndex.coerceIn(0, images.lastIndex.coerceAtLeast(0))
        } else {
            val index = images.indexOfFirst { it.absolutePath == imageFile.absolutePath }
            if (index < 0) return
            index
        }
        AppLogger.log(
            "Library",
            "Open image ${imageFile.name} at index $startIndex in ${folder.name}, embedded=$embeddedMode"
        )
        readingSessionViewModel.setFolder(folder, images, startIndex, embeddedMode)
        (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
    }

    private fun resolveReadingImages(folder: File, originalImages: List<File>): ReadingImagesResolution {
        if (!embeddedStateStore.isEmbedded(folder)) {
            return ReadingImagesResolution(
                images = originalImages,
                embeddedMode = false,
                forcedBubbleMode = false
            )
        }
        val embeddedImages = embeddedStateStore.listEmbeddedImages(folder)
        if (embeddedImages.isEmpty()) {
            return ReadingImagesResolution(
                images = originalImages,
                embeddedMode = false,
                forcedBubbleMode = false
            )
        }
        val embeddedByName = embeddedImages.associateBy { it.name }
        val ordered = ArrayList<File>(originalImages.size)
        for (image in originalImages) {
            val embedded = embeddedByName[image.name]
            if (embedded == null) {
                AppLogger.log("Library", "Embedded image missing for ${image.name} in ${folder.name}")
                return ReadingImagesResolution(
                    images = originalImages,
                    embeddedMode = false,
                    forcedBubbleMode = true
                )
            }
            ordered.add(embedded)
        }
        return ReadingImagesResolution(
            images = ordered,
            embeddedMode = true,
            forcedBubbleMode = false
        )
    }

    private fun setEmbedActionsEnabled(enabled: Boolean) {
        embedActionsEnabled = enabled
        val folder = currentFolder
        _binding?.let { view ->
            view.folderRead.isEnabled = enabled
            view.folderEmbed.isEnabled = enabled
            view.folderUnembed.isEnabled = enabled && folder?.let { embeddedStateStore.isEmbedded(it) } == true
        }
    }

    private fun updateEmbedButtonState(folder: File) {
        val embedded = embeddedStateStore.isEmbedded(folder)
        binding.folderRead.isEnabled = embedActionsEnabled
        binding.folderEmbed.isEnabled = embedActionsEnabled
        binding.folderUnembed.isEnabled = embedActionsEnabled && embedded
    }

    private fun showFullTranslateInfo() {
        dialogs.showFullTranslateInfo(requireContext())
    }

    private fun updateLanguageSettingButton(folder: File) {
        val language = preferencesGateway.getTranslationLanguage(folder)
        val displayName = getString(language.displayNameResId)
        binding.folderLanguageSetting.text = getString(R.string.folder_language_setting, displayName)
    }

    private fun showLanguageSettingDialog() {
        val folder = currentFolder ?: return
        val currentLanguage = preferencesGateway.getTranslationLanguage(folder)
        dialogs.showLanguageSettingDialog(requireContext(), currentLanguage) { selectedLanguage ->
            preferencesGateway.setTranslationLanguage(folder, selectedLanguage)
            updateLanguageSettingButton(folder)
            AppLogger.log("Library", "Set language for ${folder.name}: ${selectedLanguage.name}")
        }
    }

    private data class ReadingImagesResolution(
        val images: List<File>,
        val embeddedMode: Boolean,
        val forcedBubbleMode: Boolean
    )
}
