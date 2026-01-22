package com.manga.translate

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.manga.translate.databinding.FragmentLibraryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private lateinit var readingProgressStore: ReadingProgressStore
    private val settingsStore by lazy { SettingsStore(requireContext()) }
    private val folderAdapter = LibraryFolderAdapter(
        onClick = { openFolder(it.folder) },
        onDelete = { confirmDeleteFolder(it.folder) }
    )
    private val imageAdapter = FolderImageAdapter(
        onSelectionChanged = { updateSelectionActions() },
        onItemLongPress = { enterSelectionMode(it.file) },
        onItemClick = { openImageInReader(it.file) }
    )
    private var currentFolder: File? = null
    private var imageSelectionMode = false
    private val prefs by lazy {
        requireContext().getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
    }
    private val ehViewerTreeKey = "ehviewer_tree_uri"
    private val fullTranslateKeyPrefix = "full_translate_enabled_"
    private val languageKeyPrefix = "translation_language_"
    private val tutorialUrl =
        "https://github.com/jedzqer/manga-translator/blob/main/Tutorial/简中教程.md"
    private var pendingExportAfterPermission = false

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingExportAfterPermission && granted) {
            pendingExportAfterPermission = false
            exportFolderInternal()
            return@registerForActivityResult
        }
        pendingExportAfterPermission = false
        if (!granted) {
            val message = getString(R.string.export_permission_denied)
            setFolderStatus(message)
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
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
            handleEhViewerTreeSelection(uri)
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
        binding.folderList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderList.adapter = folderAdapter
        binding.folderImageList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderImageList.adapter = imageAdapter

        binding.addFolderFab.setOnClickListener { showCreateFolderDialog() }
        binding.importEhviewerButton.setOnClickListener { importFromEhViewer() }
        binding.tutorialButton.setOnClickListener { openTutorial() }
        binding.folderBackButton.setOnClickListener { showFolderList() }
        binding.folderAddImages.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        binding.folderExport.setOnClickListener { exportFolder() }
        binding.folderTranslate.setOnClickListener { translateFolder() }
        binding.folderRead.setOnClickListener { startReading() }
        binding.folderSelectAll.setOnClickListener { toggleSelectAllImages() }
        binding.folderDeleteSelected.setOnClickListener { confirmDeleteSelectedImages() }
        binding.folderCancelSelection.setOnClickListener { exitSelectionMode() }
        binding.folderRetranslateSelected.setOnClickListener { retranslateSelectedImages() }
        binding.folderFullTranslateInfo.setOnClickListener { showFullTranslateInfo() }
        binding.folderLanguageSetting.setOnClickListener { showLanguageSettingDialog() }
        binding.folderFullTranslateSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentFolder?.let { setFullTranslateEnabled(it, isChecked) }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (imageSelectionMode) {
                        exitSelectionMode()
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
        binding.libraryListContainer.visibility = View.VISIBLE
        binding.folderDetailContainer.visibility = View.GONE
        binding.addFolderFab.visibility = View.VISIBLE
        binding.tutorialButton.visibility = View.VISIBLE
        binding.importEhviewerButton.visibility = View.VISIBLE
        clearFolderStatus()
        exitSelectionMode()
        folderAdapter.clearDeleteSelection()
        loadFolders()
    }

    private fun showFolderDetail(folder: File) {
        currentFolder = folder
        binding.folderTitle.text = folder.name
        binding.folderFullTranslateSwitch.isChecked = isFullTranslateEnabled(folder)
        updateLanguageSettingButton(folder)
        binding.libraryListContainer.visibility = View.GONE
        binding.folderDetailContainer.visibility = View.VISIBLE
        binding.addFolderFab.visibility = View.GONE
        binding.tutorialButton.visibility = View.GONE
        binding.importEhviewerButton.visibility = View.GONE
        exitSelectionMode()
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
        binding.folderImagesEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (imageSelectionMode) {
            updateSelectionActions()
        } else {
            clearFolderStatus()
        }
    }

    private fun openFolder(folder: File) {
        showFolderDetail(folder)
    }

    private fun openTutorial() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tutorialUrl))
        val manager = requireContext().packageManager
        if (intent.resolveActivity(manager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), tutorialUrl, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCreateFolderDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.folder_name_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_folder)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString().orEmpty()
                val folder = repository.createFolder(name)
                if (folder == null) {
                    AppLogger.log("Library", "Create folder failed: $name")
                    Toast.makeText(
                        requireContext(),
                        R.string.folder_create_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AppLogger.log("Library", "Created folder ${folder.name}")
                    loadFolders()
                }
            }
            .show()
    }

    private fun addImagesToFolder(uris: List<Uri>) {
        val folder = currentFolder ?: return
        val added = repository.addImages(folder, uris)
        AppLogger.log("Library", "Added ${added.size} images to ${folder.name}")
        loadImages(folder)
        loadFolders()
    }

    private fun importFromEhViewer() {
        val treeUri = getEhViewerTreeUri()
        if (treeUri == null || !hasEhViewerPermission(treeUri)) {
            Toast.makeText(
                requireContext(),
                R.string.ehviewer_permission_hint,
                Toast.LENGTH_LONG
            ).show()
            requestEhViewerPermission()
            return
        }
        showEhViewerSubfolderPicker(treeUri)
    }

    private fun requestEhViewerPermission() {
        val initialUri = buildEhViewerInitialUri()
        pickEhViewerTree.launch(initialUri)
    }

    private fun handleEhViewerTreeSelection(uri: Uri) {
        if (!isEhViewerTree(uri)) {
            Toast.makeText(
                requireContext(),
                R.string.ehviewer_permission_invalid,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            AppLogger.log("Library", "Persist ehviewer permission failed", e)
        }
        prefs.edit().putString(ehViewerTreeKey, uri.toString()).apply()
        showEhViewerSubfolderPicker(uri)
    }

    private fun showEhViewerSubfolderPicker(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(requireContext(), treeUri)
        if (root == null || !root.canRead()) {
            Toast.makeText(
                requireContext(),
                R.string.ehviewer_permission_required,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val folders = root.listFiles().filter { it.isDirectory }
        if (folders.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.ehviewer_no_subfolders,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val names = folders.map { it.name ?: "未命名" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ehviewer_select_folder)
            .setItems(names) { _, index ->
                val folder = folders[index]
                val defaultName = folder.name ?: ""
                promptEhViewerImportName(defaultName) { importName ->
                    importEhViewerFolder(folder, importName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptEhViewerImportName(defaultName: String, onConfirm: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.folder_name_hint)
            setText(defaultName)
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ehviewer_import_name_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.folder_create_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    onConfirm(name)
                }
            }
            .show()
    }

    private fun importEhViewerFolder(source: DocumentFile, importName: String) {
        val folder = repository.createFolder(importName)
        if (folder == null) {
            Toast.makeText(
                requireContext(),
                R.string.ehviewer_folder_exists,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val images = source.listFiles().filter { it.isFile && isImageDocument(it) }
        if (images.isEmpty()) {
            folder.deleteRecursively()
            Toast.makeText(
                requireContext(),
                R.string.ehviewer_no_images,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val added = repository.addImages(folder, images.map { it.uri })
            withContext(Dispatchers.Main) {
                if (added.isEmpty()) {
                    folder.deleteRecursively()
                    Toast.makeText(
                        requireContext(),
                        R.string.ehviewer_import_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.ehviewer_import_done, added.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                loadFolders()
                showFolderList()
            }
        }
    }

    private fun getEhViewerTreeUri(): Uri? {
        return prefs.getString(ehViewerTreeKey, null)?.let(Uri::parse)
    }

    private fun hasEhViewerPermission(uri: Uri): Boolean {
        val persisted = requireContext()
            .contentResolver
            .persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
        val root = DocumentFile.fromTreeUri(requireContext(), uri)
        return persisted && root?.canRead() == true
    }

    private fun isEhViewerTree(uri: Uri): Boolean {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            docId.contains("EhViewer/download", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private fun buildEhViewerInitialUri(): Uri? {
        return try {
            DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:EhViewer/download"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun isImageDocument(file: DocumentFile): Boolean {
        val name = file.name?.lowercase().orEmpty()
        return name.endsWith(".jpg") ||
            name.endsWith(".jpeg") ||
            name.endsWith(".png") ||
            name.endsWith(".webp")
    }

    private fun confirmDeleteFolder(folder: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.folder_delete)
            .setMessage(getString(R.string.folder_delete_confirm, folder.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.folder_delete) { _, _ ->
                val deleted = repository.deleteFolder(folder)
                if (!deleted) {
                    AppLogger.log("Library", "Delete folder failed: ${folder.name}")
                    Toast.makeText(
                        requireContext(),
                        R.string.folder_delete_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AppLogger.log("Library", "Deleted folder ${folder.name}")
                }
                loadFolders()
            }
            .show()
    }

    private fun translateFolder() {
        val folder = currentFolder ?: return
        exitSelectionMode()
        if (isFullTranslateEnabled(folder)) {
            translateFolderFull(folder, repository.listImages(folder), force = false)
        } else {
            translateFolderStandard(folder, repository.listImages(folder), force = false)
        }
    }

    private fun translateFolderStandard(folder: File, images: List<File>, force: Boolean) {
        if (images.isEmpty()) {
            setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        val pendingImages = resolvePendingImages(images, force)
        if (pendingImages.isEmpty()) {
            setFolderStatus(getString(R.string.translation_done))
            return
        }
        val llmClient = LlmClient(requireContext())
        if (!llmClient.isConfigured()) {
            setFolderStatus(getString(R.string.missing_api_settings))
            return
        }
        binding.folderTranslate.isEnabled = false
        TranslationKeepAliveService.start(requireContext())
        TranslationKeepAliveService.updateStatus(
            requireContext(),
            getString(R.string.translation_preparing)
        )
        AppLogger.log(
            "Library",
            "Start translating folder ${folder.name}, ${pendingImages.size} images"
        )
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            try {
                val glossary = glossaryStore.load(folder)
                val language = getTranslationLanguage(folder)
                var translatedCount = 0
                setFolderStatus(getString(R.string.translation_preparing))
                for (image in pendingImages) {
                    val result = try {
                        translationPipeline.translateImage(image, glossary, force, language) { }
                    } catch (e: LlmRequestException) {
                        AppLogger.log("Library", "Translation aborted for ${image.name}", e)
                        showApiErrorDialog(e.errorCode)
                        failed = true
                        break
                    } catch (e: LlmResponseException) {
                        AppLogger.log("Library", "Invalid model response for ${image.name}", e)
                        showModelErrorDialog(e.responseContent)
                        failed = true
                        break
                    } catch (e: Exception) {
                        AppLogger.log("Library", "Translation failed for ${image.name}", e)
                        null
                    }
                    if (result != null) {
                        translationPipeline.saveResult(image, result)
                        translatedCount += 1
                    } else {
                        failed = true
                    }
                    if (glossary.isNotEmpty()) {
                        glossaryStore.save(folder, glossary)
                    }
                    setFolderStatus(
                        getString(
                            R.string.folder_translation_count,
                            translatedCount,
                            pendingImages.size
                        )
                    )
                    TranslationKeepAliveService.updateProgress(
                        requireContext(),
                        translatedCount,
                        pendingImages.size
                    )
                    if (failed) break
                }
                setFolderStatus(
                    if (failed) getString(R.string.translation_failed) else getString(R.string.translation_done)
                )
                AppLogger.log(
                    "Library",
                    "Folder translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                )
                loadImages(folder)
            } finally {
                _binding?.let { binding ->
                    binding.folderTranslate.isEnabled = true
                }
                TranslationKeepAliveService.stop(requireContext())
            }
        }
    }

    private fun translateFolderFull(folder: File, images: List<File>, force: Boolean) {
        if (images.isEmpty()) {
            setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        val pendingImages = resolvePendingImages(images, force)
        if (pendingImages.isEmpty()) {
            setFolderStatus(getString(R.string.translation_done))
            return
        }
        val llmClient = LlmClient(requireContext())
        if (!llmClient.isConfigured()) {
            setFolderStatus(getString(R.string.missing_api_settings))
            return
        }
        binding.folderTranslate.isEnabled = false
        TranslationKeepAliveService.start(requireContext())
        TranslationKeepAliveService.updateStatus(
            requireContext(),
            getString(R.string.translation_preparing)
        )
        AppLogger.log(
            "Library",
            "Start full-page translating folder ${folder.name}, ${pendingImages.size} images"
        )
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            try {
                val glossary = glossaryStore.load(folder).toMutableMap()
                val language = getTranslationLanguage(folder)
                val extractState = extractStateStore.load(folder)
                val ocrResults = ArrayList<PageOcrResult>(pendingImages.size)
                var ocrCount = 0
                setFolderStatus(getString(R.string.translation_preparing))
                for (image in pendingImages) {
                    val result = try {
                        translationPipeline.ocrImage(image, force, language) { }
                    } catch (e: Exception) {
                        AppLogger.log("Library", "OCR failed for ${image.name}", e)
                        null
                    }
                    if (result != null) {
                        ocrResults.add(result)
                    } else {
                        failed = true
                    }
                    ocrCount += 1
                    setFolderStatus(getString(R.string.translation_preparing))
                }
                val glossaryPages = ocrResults.filterNot {
                    translationStore.translationFileFor(it.imageFile).exists()
                        || extractState.contains(it.imageFile.name)
                }
                val glossaryText = buildGlossaryText(glossaryPages)
                if (glossaryText.isNotBlank()) {
                    setFolderStatus(
                        getString(R.string.translation_preparing),
                        getString(R.string.folder_glossary_progress)
                    )
                    val abstractPromptAsset = when (language) {
                        TranslationLanguage.EN_TO_ZH -> "en-zh-llm_prompts_abstract.json"
                        TranslationLanguage.JA_TO_ZH -> "llm_prompts_abstract.json"
                    }
                    val extracted = llmClient.extractGlossary(
                        glossaryText,
                        glossary,
                        abstractPromptAsset
                    )
                    if (extracted != null) {
                        if (extracted.isNotEmpty()) {
                            for ((key, value) in extracted) {
                                if (!glossary.containsKey(key)) {
                                    glossary[key] = value
                                }
                            }
                            glossaryStore.save(folder, glossary)
                        }
                        for (page in glossaryPages) {
                            extractState.add(page.imageFile.name)
                        }
                        extractStateStore.save(folder, extractState)
                    }
                }
                val maxConcurrency = SettingsStore(requireContext()).loadMaxConcurrency()
                val semaphore = Semaphore(maxConcurrency)
                val translatedCount = AtomicInteger(0)
                val hasFailures = AtomicBoolean(false)
                val requestFailed = AtomicBoolean(false)
                val reportedModelError = AtomicBoolean(false)
                setFolderStatus(getString(R.string.translation_preparing))
                coroutineScope {
                    val tasks = ocrResults.map { page ->
                        async {
                            semaphore.withPermit {
                                if (requestFailed.get()) {
                                    return@withPermit
                                }
                                val fullTransPromptAsset = when (language) {
                                    TranslationLanguage.EN_TO_ZH -> "en-zh-llm_prompts_FullTrans.json"
                                    TranslationLanguage.JA_TO_ZH -> "llm_prompts_FullTrans.json"
                                }
                                val result = try {
                                    translationPipeline.translateFullPage(
                                        page,
                                        glossary,
                                        fullTransPromptAsset,
                                        language
                                    ) { }
                                } catch (e: LlmResponseException) {
                                    AppLogger.log(
                                        "Library",
                                        "Invalid model response for ${page.imageFile.name}",
                                        e
                                    )
                                    hasFailures.set(true)
                                    if (reportedModelError.compareAndSet(false, true)) {
                                        withContext(Dispatchers.Main) {
                                            showModelErrorDialog(e.responseContent)
                                        }
                                    }
                                    null
                                } catch (e: LlmRequestException) {
                                    requestFailed.set(true)
                                    throw e
                                } catch (e: Exception) {
                                    AppLogger.log(
                                        "Library",
                                        "Full-page translation failed for ${page.imageFile.name}",
                                        e
                                    )
                                    null
                                }
                                if (requestFailed.get()) {
                                    return@withPermit
                                }
                                if (result != null) {
                                    translationPipeline.saveResult(page.imageFile, result)
                                    translatedCount.incrementAndGet()
                                } else {
                                    hasFailures.set(true)
                                }
                                withContext(Dispatchers.Main) {
                                    val count = translatedCount.get()
                                    setFolderStatus(
                                        getString(
                                            R.string.folder_translation_count,
                                            count,
                                            pendingImages.size
                                        )
                                    )
                                    TranslationKeepAliveService.updateProgress(
                                        requireContext(),
                                        count,
                                        pendingImages.size
                                    )
                                }
                            }
                        }
                    }
                    tasks.awaitAll()
                }
                failed = failed || hasFailures.get()
                setFolderStatus(
                    if (failed) getString(R.string.translation_failed) else getString(R.string.translation_done)
                )
                AppLogger.log(
                    "Library",
                    "Full-page translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                )
                loadImages(folder)
            } catch (e: LlmRequestException) {
                AppLogger.log("Library", "Full-page translation aborted", e)
                showApiErrorDialog(e.errorCode)
                setFolderStatus(getString(R.string.translation_failed))
            } finally {
                _binding?.let { binding ->
                    binding.folderTranslate.isEnabled = true
                }
                TranslationKeepAliveService.stop(requireContext())
            }
        }
    }

    private fun exportFolder() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingExportAfterPermission = true
                requestStoragePermission.launch(permission)
                return
            }
        }
        exportFolderInternal()
    }

    private fun exportFolderInternal() {
        val folder = currentFolder ?: return
        exitSelectionMode()
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        val appContext = requireContext().applicationContext
        val renderer = BubbleRenderer(appContext)
        val verticalLayoutEnabled = !settingsStore.loadUseHorizontalText()
        binding.folderExport.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            var exported = 0
            var failed = false
            try {
                setFolderStatus(getString(R.string.exporting_progress, exported, images.size))
                for (image in images) {
                    val success = withContext(Dispatchers.IO) {
                        exportImageWithBubbles(
                            appContext,
                            renderer,
                            image,
                            folder.name,
                            verticalLayoutEnabled
                        )
                    }
                    if (!success) {
                        failed = true
                    }
                    exported += 1
                    setFolderStatus(getString(R.string.exporting_progress, exported, images.size))
                }
                setFolderStatus(
                    if (failed) getString(R.string.export_failed) else getString(R.string.export_done)
                )
                if (!failed && isAdded) {
                    val path = "/Pictures/manga-translate/${folder.name}"
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.export_success_title)
                        .setMessage(getString(R.string.export_success_message, path))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                AppLogger.log(
                    "Library",
                    "Export ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                )
            } finally {
                _binding?.let { binding ->
                    binding.folderExport.isEnabled = true
                }
            }
        }
    }

    private fun exportImageWithBubbles(
        context: Context,
        renderer: BubbleRenderer,
        imageFile: File,
        folderName: String,
        verticalLayoutEnabled: Boolean
    ): Boolean {
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return false
        val translation = translationStore.load(imageFile)
        val output = if (translation != null && translation.bubbles.any { it.text.isNotBlank() }) {
            renderer.render(bitmap, translation, verticalLayoutEnabled)
        } else {
            bitmap
        }
        val spec = resolveExportSpec(imageFile.name)
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveBitmapToMediaStore(context, output, spec, folderName)
        } else {
            saveBitmapToLegacyStorage(output, spec, folderName)
        }
        if (output !== bitmap) {
            output.recycle()
        }
        bitmap.recycle()
        if (!success) {
            AppLogger.log("Library", "Export failed for ${imageFile.name}")
        }
        return success
    }

    private fun saveBitmapToMediaStore(
        context: Context,
        bitmap: Bitmap,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, spec.displayName)
            put(MediaStore.Images.Media.MIME_TYPE, spec.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/manga-translate/$folderName")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        val success = try {
            resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(spec.format, spec.quality, output)
            } ?: false
        } catch (e: Exception) {
            AppLogger.log("Library", "Export write failed: ${spec.displayName}", e)
            false
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        if (!success) {
            resolver.delete(uri, null, null)
        }
        return success
    }

    private fun saveBitmapToLegacyStorage(
        bitmap: Bitmap,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        val root = Environment.getExternalStorageDirectory()
        val exportDir = File(root, "Pictures/manga-translate/$folderName")
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            AppLogger.log("Library", "Export directory create failed: ${exportDir.absolutePath}")
            return false
        }
        val target = resolveUniqueFile(exportDir, spec.displayName)
        return try {
            FileOutputStream(target).use { output ->
                bitmap.compress(spec.format, spec.quality, output)
            }
        } catch (e: Exception) {
            AppLogger.log("Library", "Export write failed: ${target.name}", e)
            false
        }
    }

    private fun resolveExportSpec(fileName: String): ExportSpec {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val baseName = fileName.substringBeforeLast('.', fileName)
        val format = when (ext) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP
            }
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.JPEG
        }
        val mimeType = when (ext) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "image/jpeg"
        }
        val normalizedExt = when (ext) {
            "png", "webp", "jpg", "jpeg" -> ext
            else -> "jpg"
        }
        val displayName = if (ext == normalizedExt && ext.isNotEmpty()) {
            fileName
        } else {
            "$baseName.$normalizedExt"
        }
        val quality = when (format) {
            Bitmap.CompressFormat.PNG -> 100
            else -> 95
        }
        return ExportSpec(displayName, mimeType, format, quality)
    }

    private fun resolveUniqueFile(folder: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var candidate = File(folder, fileName)
        var index = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(folder, "${base}_$index$suffix")
            index += 1
        }
        return candidate
    }

    private data class ExportSpec(
        val displayName: String,
        val mimeType: String,
        val format: Bitmap.CompressFormat,
        val quality: Int
    )

    private fun showApiErrorDialog(errorCode: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.api_request_failed_title)
            .setMessage(getString(R.string.api_request_failed_message, errorCode))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showModelErrorDialog(responseContent: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_response_failed_title)
            .setMessage(getString(R.string.model_response_failed_message, responseContent))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun startReading() {
        val folder = currentFolder ?: return
        exitSelectionMode()
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        AppLogger.log("Library", "Start reading ${folder.name}, ${images.size} images")
        val startIndex = readingProgressStore.load(folder)
        readingSessionViewModel.setFolder(folder, images, startIndex)
        (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
    }

    private fun openImageInReader(imageFile: File) {
        val folder = currentFolder ?: return
        if (imageSelectionMode) return
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        val startIndex = images.indexOfFirst { it.absolutePath == imageFile.absolutePath }
        if (startIndex < 0) return
        AppLogger.log("Library", "Open image ${imageFile.name} at index $startIndex in ${folder.name}")
        readingSessionViewModel.setFolder(folder, images, startIndex)
        (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
    }

    private fun enterSelectionMode(target: File) {
        if (!imageSelectionMode) {
            imageSelectionMode = true
            imageAdapter.setSelectionMode(true)
            binding.folderSelectionActions.visibility = View.VISIBLE
        }
        imageAdapter.toggleSelectionAndNotify(target)
        updateSelectionActions()
    }

    private fun exitSelectionMode() {
        if (!imageSelectionMode) return
        imageSelectionMode = false
        imageAdapter.setSelectionMode(false)
        binding.folderSelectionActions.visibility = View.GONE
        clearFolderStatus()
    }

    private fun updateSelectionActions() {
        if (!imageSelectionMode) return
        val count = imageAdapter.selectedCount()
        setFolderStatus(getString(R.string.folder_selection_count, count))
        val buttonText = if (imageAdapter.areAllSelected()) {
            getString(R.string.clear_all)
        } else {
            getString(R.string.select_all)
        }
        binding.folderSelectAll.text = buttonText
    }

    private fun toggleSelectAllImages() {
        if (!imageSelectionMode) return
        if (imageAdapter.areAllSelected()) {
            imageAdapter.clearSelection()
        } else {
            imageAdapter.selectAll()
        }
        updateSelectionActions()
    }

    private fun confirmDeleteSelectedImages() {
        val folder = currentFolder ?: return
        val selected = imageAdapter.getSelectedFiles()
        if (selected.isEmpty()) {
            setFolderStatus(getString(R.string.delete_images_empty))
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_selected)
            .setMessage(getString(R.string.delete_images_confirm, selected.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_selected) { _, _ ->
                var failed = false
                for (file in selected) {
                    if (!file.delete()) {
                        failed = true
                    }
                    translationStore.translationFileFor(file).delete()
                    ocrStore.ocrFileFor(file).delete()
                }
                if (failed) {
                    AppLogger.log("Library", "Delete selected images failed in ${folder.name}")
                    Toast.makeText(
                        requireContext(),
                        R.string.delete_images_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AppLogger.log("Library", "Deleted ${selected.size} images from ${folder.name}")
                }
                exitSelectionMode()
                loadImages(folder)
                loadFolders()
            }
            .show()
    }

    private fun retranslateSelectedImages() {
        val folder = currentFolder ?: return
        val selected = imageAdapter.getSelectedFiles()
        if (selected.isEmpty()) {
            setFolderStatus(getString(R.string.retranslate_images_empty))
            return
        }
        exitSelectionMode()
        if (isFullTranslateEnabled(folder)) {
            translateFolderFull(folder, selected, force = true)
        } else {
            translateFolderStandard(folder, selected, force = true)
        }
    }

    private fun setFolderStatus(left: String, right: String = "") {
        _binding?.let { binding ->
            binding.folderProgressLeft.text = left
            binding.folderProgressRight.text = right
        }
    }

    private fun clearFolderStatus() {
        setFolderStatus("")
    }

    private fun showFullTranslateInfo() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.folder_full_translate_info_title)
            .setMessage(getString(R.string.folder_full_translate_info))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun isFullTranslateEnabled(folder: File): Boolean {
        return prefs.getBoolean(fullTranslateKeyPrefix + folder.absolutePath, true)
    }

    private fun setFullTranslateEnabled(folder: File, enabled: Boolean) {
        prefs.edit().putBoolean(fullTranslateKeyPrefix + folder.absolutePath, enabled).apply()
    }

    private fun getTranslationLanguage(folder: File): TranslationLanguage {
        val value = prefs.getString(languageKeyPrefix + folder.absolutePath, null)
        return TranslationLanguage.fromString(value)
    }

    private fun setTranslationLanguage(folder: File, language: TranslationLanguage) {
        prefs.edit().putString(languageKeyPrefix + folder.absolutePath, language.name).apply()
        updateLanguageSettingButton(folder)
    }

    private fun updateLanguageSettingButton(folder: File) {
        val language = getTranslationLanguage(folder)
        val displayName = getString(language.displayNameResId)
        binding.folderLanguageSetting.text = getString(R.string.folder_language_setting, displayName)
    }

    private fun showLanguageSettingDialog() {
        val folder = currentFolder ?: return
        val currentLanguage = getTranslationLanguage(folder)
        val languages = TranslationLanguage.values()
        val languageNames = languages.map { getString(it.displayNameResId) }.toTypedArray()
        val currentIndex = languages.indexOf(currentLanguage)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.folder_language_setting_title)
            .setSingleChoiceItems(languageNames, currentIndex) { dialog, which ->
                val selectedLanguage = languages[which]
                setTranslationLanguage(folder, selectedLanguage)
                AppLogger.log("Library", "Set language for ${folder.name}: ${selectedLanguage.name}")
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildGlossaryText(pages: List<PageOcrResult>): String {
        val builder = StringBuilder()
        for (page in pages) {
            for (bubble in page.bubbles) {
                val text = bubble.text.trim()
                if (text.isNotBlank()) {
                    builder.append("<b>").append(text).append("</b>\n")
                }
            }
        }
        return builder.toString().trim()
    }

    private fun resolvePendingImages(images: List<File>, force: Boolean): List<File> {
        return if (force) {
            images
        } else {
            images.filterNot { translationStore.translationFileFor(it).exists() }
        }
    }
}
