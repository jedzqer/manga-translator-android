package com.manga.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.manga.translate.databinding.FragmentSettingsBinding
import java.io.File

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsStore: SettingsStore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsStore = SettingsStore(requireContext())
        val settings = settingsStore.load()
        binding.apiUrlInput.setText(settings.apiUrl)
        binding.apiKeyInput.setText(settings.apiKey)
        binding.modelNameInput.setText(settings.modelName)
        binding.maxConcurrencyInput.setText(settingsStore.loadMaxConcurrency().toString())
        binding.textLayoutSwitch.isChecked = settingsStore.loadUseHorizontalText()
        binding.textLayoutSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.saveUseHorizontalText(isChecked)
            AppLogger.log("Settings", "Text layout set to ${if (isChecked) "horizontal" else "vertical"}")
        }

        binding.saveButton.setOnClickListener {
            val url = binding.apiUrlInput.text?.toString()?.trim().orEmpty()
            val key = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
            val model = binding.modelNameInput.text?.toString()?.trim().orEmpty()
            settingsStore.save(ApiSettings(url, key, model))
            val concurrencyInput = binding.maxConcurrencyInput.text?.toString()?.trim()
            val maxConcurrency = concurrencyInput?.toIntOrNull() ?: settingsStore.loadMaxConcurrency()
            val normalized = maxConcurrency.coerceIn(1, 50)
            settingsStore.saveMaxConcurrency(normalized)
            if (normalized.toString() != concurrencyInput) {
                binding.maxConcurrencyInput.setText(normalized.toString())
            }
            AppLogger.log("Settings", "API settings saved")
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }

        binding.viewLogsButton.setOnClickListener {
            AppLogger.log("Settings", "View current log")
            showLogsDialog()
        }

        binding.openLogsFolderButton.setOnClickListener {
            AppLogger.log("Settings", "Share log file")
            showLogFilesDialog()
        }

        binding.aboutButton.setOnClickListener {
            showAboutDialog()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showLogsDialog() {
        val logs = AppLogger.readLogs().ifBlank { getString(R.string.logs_empty) }
        showLogTextDialog(getString(R.string.logs_title), logs)
    }

    private fun showLogFilesDialog() {
        val files = AppLogger.listLogFiles()
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), R.string.logs_folder_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.logs_folder_title)
            .setItems(names) { _, which ->
                shareLogFile(files[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareLogFile(file: File) {
        if (!file.exists()) {
            Toast.makeText(requireContext(), R.string.logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, getString(R.string.share_logs))
        val manager = requireContext().packageManager
        if (chooser.resolveActivity(manager) != null) {
            AppLogger.log("Settings", "Share log file ${file.name}")
            startActivity(chooser)
        } else {
            Toast.makeText(requireContext(), R.string.share_logs_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLogTextDialog(title: String, logs: String) {
        val padding = (resources.displayMetrics.density * 16).toInt()
        val textView = TextView(requireContext()).apply {
            text = logs
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(requireContext()).apply {
            addView(textView)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(scrollView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.copy_logs) { _, _ ->
                val clipboard = requireContext()
                    .getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("logs", logs))
                Toast.makeText(requireContext(), R.string.copy_logs, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showAboutDialog() {
        val versionName = resolveVersionName()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.about_dialog_title)
            .setMessage(getString(R.string.about_dialog_message, versionName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.about_open_project) { _, _ ->
                openUrl(PROJECT_URL)
            }
            .setNeutralButton(R.string.about_view_updates) { _, _ ->
                openUrl(RELEASES_URL)
            }
            .show()
    }

    private fun resolveVersionName(): String {
        val context = requireContext()
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            BuildConfig.VERSION_NAME
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        val manager = requireContext().packageManager
        if (intent.resolveActivity(manager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), url, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val PROJECT_URL = "https://github.com/jedzqer/manga-translator"
        private const val RELEASES_URL = "https://github.com/jedzqer/manga-translator/releases"
    }
}
