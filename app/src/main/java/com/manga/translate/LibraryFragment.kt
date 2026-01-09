package com.manga.translate

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.manga.translate.databinding.FragmentLibraryBinding
import kotlinx.coroutines.launch
import java.io.File

class LibraryFragment : Fragment() {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()
    private lateinit var repository: LibraryRepository
    private lateinit var translationPipeline: TranslationPipeline
    private val translationStore = TranslationStore()
    private val folderAdapter = LibraryFolderAdapter { openFolder(it.folder) }
    private val imageAdapter = FolderImageAdapter()
    private var currentFolder: File? = null

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            addImagesToFolder(uris)
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
        binding.folderList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderList.adapter = folderAdapter
        binding.folderImageList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderImageList.adapter = imageAdapter

        binding.addFolderFab.setOnClickListener { showCreateFolderDialog() }
        binding.folderBackButton.setOnClickListener { showFolderList() }
        binding.folderAddImages.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        binding.folderTranslate.setOnClickListener { translateFolder() }
        binding.folderRead.setOnClickListener { startReading() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
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
        binding.folderStatus.text = ""
        loadFolders()
    }

    private fun showFolderDetail(folder: File) {
        currentFolder = folder
        binding.folderTitle.text = folder.name
        binding.libraryListContainer.visibility = View.GONE
        binding.folderDetailContainer.visibility = View.VISIBLE
        binding.addFolderFab.visibility = View.GONE
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
        binding.folderStatus.text = ""
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
                    Toast.makeText(
                        requireContext(),
                        R.string.folder_create_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    loadFolders()
                }
            }
            .show()
    }

    private fun addImagesToFolder(uris: List<Uri>) {
        val folder = currentFolder ?: return
        repository.addImages(folder, uris)
        loadImages(folder)
        loadFolders()
    }

    private fun translateFolder() {
        val folder = currentFolder ?: return
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            binding.folderStatus.text = getString(R.string.folder_images_empty)
            return
        }
        val llmClient = LlmClient(requireContext())
        if (!llmClient.isConfigured()) {
            binding.folderStatus.text = getString(R.string.missing_api_settings)
            return
        }
        binding.folderTranslate.isEnabled = false
        binding.folderStatus.text = getString(R.string.translating_bubbles)
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            try {
                var index = 0
                for (image in images) {
                    index += 1
                    binding.folderStatus.text = getString(R.string.translation_progress, index, images.size)
                    val result = try {
                        translationPipeline.translateImage(image) { progress ->
                            binding.folderStatus.post { binding.folderStatus.text = progress }
                        }
                    } catch (e: Exception) {
                        AppLogger.log("Library", "Translation failed for ${image.name}", e)
                        null
                    }
                    if (result != null) {
                        translationPipeline.saveResult(image, result)
                    } else {
                        failed = true
                    }
                }
                binding.folderStatus.text = if (failed) {
                    getString(R.string.translation_failed)
                } else {
                    getString(R.string.translation_done)
                }
                loadImages(folder)
            } finally {
                binding.folderTranslate.isEnabled = true
            }
        }
    }

    private fun startReading() {
        val folder = currentFolder ?: return
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            binding.folderStatus.text = getString(R.string.folder_images_empty)
            return
        }
        readingSessionViewModel.setFolder(folder, images)
        (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
    }
}
