package com.manga.translate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.manga.translate.databinding.FragmentLibraryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LibraryFragment : Fragment() {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()
    private lateinit var repository: LibraryRepository
    private lateinit var translationPipeline: TranslationPipeline
    private val translationStore = TranslationStore()
    private val glossaryStore = GlossaryStore()
    private lateinit var readingProgressStore: ReadingProgressStore
    private val folderAdapter = LibraryFolderAdapter(
        onClick = { openFolder(it.folder) },
        onDelete = { confirmDeleteFolder(it.folder) }
    )
    private val imageAdapter = FolderImageAdapter(
        onSelectionChanged = { updateSelectionActions() },
        onItemLongPress = { enterSelectionMode(it.file) }
    )
    private var currentFolder: File? = null
    private var imageSelectionMode = false
    private val prefs by lazy {
        requireContext().getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
    }
    private val ehViewerTreeKey = "ehviewer_tree_uri"

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
        binding.folderBackButton.setOnClickListener { showFolderList() }
        binding.folderAddImages.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        binding.folderTranslate.setOnClickListener { translateFolder() }
        binding.folderRead.setOnClickListener { startReading() }
        binding.folderSelectAll.setOnClickListener { toggleSelectAllImages() }
        binding.folderDeleteSelected.setOnClickListener { confirmDeleteSelectedImages() }
        binding.folderCancelSelection.setOnClickListener { exitSelectionMode() }

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
        binding.importEhviewerButton.visibility = View.VISIBLE
        clearFolderStatus()
        exitSelectionMode()
        folderAdapter.clearDeleteSelection()
        loadFolders()
    }

    private fun showFolderDetail(folder: File) {
        currentFolder = folder
        binding.folderTitle.text = folder.name
        binding.libraryListContainer.visibility = View.GONE
        binding.folderDetailContainer.visibility = View.VISIBLE
        binding.addFolderFab.visibility = View.GONE
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
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        val pendingImages = images.filterNot { translationStore.translationFileFor(it).exists() }
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
        AppLogger.log(
            "Library",
            "Start translating folder ${folder.name}, ${pendingImages.size} images"
        )
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            try {
                val glossary = glossaryStore.load(folder)
                var translatedCount = 0
                setFolderStatus(
                    getString(
                        R.string.folder_translation_progress,
                        translatedCount,
                        pendingImages.size
                    ),
                    getString(R.string.detecting_bubbles)
                )
                for (image in pendingImages) {
                    val result = try {
                        translationPipeline.translateImage(image, glossary) { progress ->
                            binding.folderProgressRight.post { binding.folderProgressRight.text = progress }
                        }
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
                            R.string.folder_translation_progress,
                            translatedCount,
                            pendingImages.size
                        ),
                        if (translatedCount < pendingImages.size) {
                            getString(R.string.detecting_bubbles)
                        } else {
                            ""
                        }
                    )
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
                binding.folderTranslate.isEnabled = true
                TranslationKeepAliveService.stop(requireContext())
            }
        }
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

    private fun setFolderStatus(left: String, right: String = "") {
        binding.folderProgressLeft.text = left
        binding.folderProgressRight.text = right
    }

    private fun clearFolderStatus() {
        setFolderStatus("")
    }
}
