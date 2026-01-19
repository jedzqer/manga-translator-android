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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manga.translate.databinding.DialogLlmParamsBinding
import com.manga.translate.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        binding.apiTimeoutInput.setText(settingsStore.loadApiTimeoutSeconds().toString())
        binding.maxConcurrencyInput.setText(settingsStore.loadMaxConcurrency().toString())
        binding.textLayoutSwitch.isChecked = settingsStore.loadUseHorizontalText()
        val themeMode = settingsStore.loadThemeMode()
        updateThemeButton(themeMode)
        val readingMode = settingsStore.loadReadingDisplayMode()
        updateReadingDisplayButton(readingMode)
        binding.textLayoutSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.saveUseHorizontalText(isChecked)
            AppLogger.log("Settings", "Text layout set to ${if (isChecked) "horizontal" else "vertical"}")
        }
        binding.themeButton.setOnClickListener {
            showThemeDialog()
        }
        binding.readingDisplayButton.setOnClickListener {
            showReadingDisplayDialog()
        }

        binding.saveButton.setOnClickListener {
            persistSettings(showToast = true)
        }

        binding.fetchModelsButton.setOnClickListener {
            fetchModelList()
        }

        binding.llmParamsButton.setOnClickListener {
            showLlmParamsDialog()
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

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            persistSettings(showToast = false)
        }
    }

    private fun persistSettings(showToast: Boolean) {
        val url = binding.apiUrlInput.text?.toString()?.trim().orEmpty()
        val key = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
        val model = binding.modelNameInput.text?.toString()?.trim().orEmpty()
        settingsStore.save(ApiSettings(url, key, model))
        val timeoutInput = binding.apiTimeoutInput.text?.toString()?.trim()
        val timeoutSeconds = timeoutInput?.toIntOrNull() ?: settingsStore.loadApiTimeoutSeconds()
        settingsStore.saveApiTimeoutSeconds(timeoutSeconds)
        val normalizedTimeout = settingsStore.loadApiTimeoutSeconds()
        if (normalizedTimeout.toString() != timeoutInput) {
            binding.apiTimeoutInput.setText(normalizedTimeout.toString())
        }
        val concurrencyInput = binding.maxConcurrencyInput.text?.toString()?.trim()
        val maxConcurrency = concurrencyInput?.toIntOrNull() ?: settingsStore.loadMaxConcurrency()
        val normalized = maxConcurrency.coerceIn(1, 50)
        settingsStore.saveMaxConcurrency(normalized)
        if (normalized.toString() != concurrencyInput) {
            binding.maxConcurrencyInput.setText(normalized.toString())
        }
        AppLogger.log("Settings", "API settings saved")
        if (showToast) {
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
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

    private fun showThemeDialog() {
        val modes = ThemeMode.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val currentMode = settingsStore.loadThemeMode()
        val checkedIndex = modes.indexOf(currentMode).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.theme_setting_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selected = modes[which]
                settingsStore.saveThemeMode(selected)
                updateThemeButton(selected)
                applyThemeSelection(selected)
                AppLogger.log("Settings", "Theme set to ${selected.prefValue}")
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyThemeSelection(mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
        activity?.recreate()
    }

    private fun updateThemeButton(mode: ThemeMode) {
        val label = getString(mode.labelRes)
        binding.themeButton.text = getString(R.string.theme_setting_format, label)
    }

    private fun showReadingDisplayDialog() {
        val modes = ReadingDisplayMode.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val currentMode = settingsStore.loadReadingDisplayMode()
        val checkedIndex = modes.indexOf(currentMode).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reading_display_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selected = modes[which]
                settingsStore.saveReadingDisplayMode(selected)
                updateReadingDisplayButton(selected)
                AppLogger.log("Settings", "Reading display mode set to ${selected.prefValue}")
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateReadingDisplayButton(mode: ReadingDisplayMode) {
        val label = getString(mode.labelRes)
        binding.readingDisplayButton.text = getString(R.string.reading_display_format, label)
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

    private fun showLlmParamsDialog() {
        val currentParams = settingsStore.loadLlmParameters()
        val dialogBinding = DialogLlmParamsBinding.inflate(layoutInflater)
        dialogBinding.temperatureInput.setText(currentParams.temperature?.toString().orEmpty())
        dialogBinding.topPInput.setText(currentParams.topP?.toString().orEmpty())
        dialogBinding.topKInput.setText(currentParams.topK?.toString().orEmpty())
        dialogBinding.maxOutputTokensInput.setText(currentParams.maxOutputTokens?.toString().orEmpty())
        dialogBinding.frequencyPenaltyInput.setText(currentParams.frequencyPenalty?.toString().orEmpty())
        dialogBinding.presencePenaltyInput.setText(currentParams.presencePenalty?.toString().orEmpty())
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.llm_params_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save_settings) { _, _ ->
                val parsed = parseLlmParams(dialogBinding)
                settingsStore.saveLlmParameters(parsed.params)
                if (parsed.hasInvalid) {
                    Toast.makeText(
                        requireContext(),
                        R.string.llm_params_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                AppLogger.log("Settings", "LLM params updated")
            }
            .setNeutralButton(R.string.llm_params_clear) { _, _ ->
                settingsStore.saveLlmParameters(
                    LlmParameterSettings(
                        temperature = null,
                        topP = null,
                        topK = null,
                        maxOutputTokens = null,
                        frequencyPenalty = null,
                        presencePenalty = null
                    )
                )
                AppLogger.log("Settings", "LLM params cleared")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseLlmParams(
        dialogBinding: DialogLlmParamsBinding
    ): ParsedLlmParams {
        var hasInvalid = false
        fun parseDouble(text: String?): Double? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            return trimmed.toDoubleOrNull().also { if (it == null) hasInvalid = true }
        }
        fun parseInt(text: String?): Int? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            return trimmed.toIntOrNull().also { if (it == null) hasInvalid = true }
        }
        val params = LlmParameterSettings(
            temperature = parseDouble(dialogBinding.temperatureInput.text?.toString()),
            topP = parseDouble(dialogBinding.topPInput.text?.toString()),
            topK = parseInt(dialogBinding.topKInput.text?.toString()),
            maxOutputTokens = parseInt(dialogBinding.maxOutputTokensInput.text?.toString()),
            frequencyPenalty = parseDouble(dialogBinding.frequencyPenaltyInput.text?.toString()),
            presencePenalty = parseDouble(dialogBinding.presencePenaltyInput.text?.toString())
        )
        return ParsedLlmParams(params, hasInvalid)
    }

    private fun fetchModelList() {
        val apiUrl = binding.apiUrlInput.text?.toString()?.trim().orEmpty()
        val apiKey = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
        if (apiUrl.isBlank()) {
            Toast.makeText(requireContext(), R.string.api_url_required, Toast.LENGTH_SHORT).show()
            return
        }
        binding.fetchModelsButton.isEnabled = false
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.fetch_models_title)
            .setMessage(R.string.fetch_models_loading)
            .setCancelable(false)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    LlmClient(requireContext()).fetchModelList(apiUrl, apiKey)
                }
                if (models.isEmpty()) {
                    showModelFetchError("EMPTY_RESPONSE")
                } else {
                    showModelSelectionDialog(models)
                }
            } catch (e: LlmRequestException) {
                showModelFetchError(e.errorCode)
            } finally {
                loadingDialog.dismiss()
                binding.fetchModelsButton.isEnabled = true
            }
        }
    }

    private fun showModelSelectionDialog(models: List<String>) {
        val items = models.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.fetch_models_title)
            .setItems(items) { _, which ->
                binding.modelNameInput.setText(items[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showModelFetchError(code: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.fetch_models_failed_title)
            .setMessage(getString(R.string.fetch_models_failed_message, code))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun resolveVersionName(): String {
        val context = requireContext()
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: VersionInfo.VERSION_NAME
        } catch (e: Exception) {
            VersionInfo.VERSION_NAME
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

    private data class ParsedLlmParams(
        val params: LlmParameterSettings,
        val hasInvalid: Boolean
    )
}
