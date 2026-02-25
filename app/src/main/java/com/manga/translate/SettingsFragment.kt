package com.manga.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manga.translate.databinding.DialogLlmParamsBinding
import com.manga.translate.databinding.DialogOcrSettingsBinding
import com.manga.translate.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsStore: SettingsStore
    private val numberFormatter by lazy {
        NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            isGroupingUsed = false
        }
    }

    private fun formatNumber(value: Number): String = numberFormatter.format(value)
    private fun formatNumberOrEmpty(value: Number?): String = value?.let(::formatNumber).orEmpty()
    private fun parseIntInput(text: String?): Int? = runCatching {
        numberFormatter.parse(text?.trim().orEmpty())?.toInt()
    }.getOrNull()

    private fun parseDoubleInput(text: String?): Double? = runCatching {
        numberFormatter.parse(text?.trim().orEmpty())?.toDouble()
    }.getOrNull()

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
        binding.apiTimeoutInput.setText(formatNumber(settingsStore.loadApiTimeoutSeconds()))
        binding.maxConcurrencyInput.setText(formatNumber(settingsStore.loadMaxConcurrency()))
        binding.textLayoutSwitch.isChecked = settingsStore.loadUseHorizontalText()
        binding.modelIoLoggingSwitch.isChecked = settingsStore.loadModelIoLogging()
        val themeMode = settingsStore.loadThemeMode()
        updateThemeButton(themeMode)
        val readingMode = settingsStore.loadReadingDisplayMode()
        updateReadingDisplayButton(readingMode)
        val linkSource = settingsStore.loadLinkSource()
        updateLinkSourceButton(linkSource)
        binding.textLayoutSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.saveUseHorizontalText(isChecked)
            AppLogger.log("Settings", "Text layout set to ${if (isChecked) "horizontal" else "vertical"}")
        }
        binding.modelIoLoggingSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.saveModelIoLogging(isChecked)
            AppLogger.log(
                "Settings",
                "Model I/O logging ${if (isChecked) "enabled" else "disabled"}"
            )
        }
        binding.themeButton.setOnClickListener {
            showThemeDialog()
        }
        binding.readingDisplayButton.setOnClickListener {
            showReadingDisplayDialog()
        }
        binding.linkSourceButton.setOnClickListener {
            showLinkSourceDialog()
        }

        binding.fetchModelsButton.setOnClickListener {
            fetchModelList()
        }

        binding.llmParamsButton.setOnClickListener {
            showLlmParamsDialog()
        }

        binding.ocrSettingsButton.setOnClickListener {
            showOcrSettingsDialog()
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
            persistSettings()
        }
    }

    private fun persistSettings() {
        val url = binding.apiUrlInput.text?.toString()?.trim().orEmpty()
        val key = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
        val model = binding.modelNameInput.text?.toString()?.trim().orEmpty()
        settingsStore.save(ApiSettings(url, key, model))
        val timeoutInput = binding.apiTimeoutInput.text?.toString()?.trim()
        val timeoutSeconds = parseIntInput(timeoutInput) ?: settingsStore.loadApiTimeoutSeconds()
        settingsStore.saveApiTimeoutSeconds(timeoutSeconds)
        val normalizedTimeout = settingsStore.loadApiTimeoutSeconds()
        val normalizedTimeoutText = formatNumber(normalizedTimeout)
        if (normalizedTimeoutText != timeoutInput) {
            binding.apiTimeoutInput.setText(normalizedTimeoutText)
        }
        val concurrencyInput = binding.maxConcurrencyInput.text?.toString()?.trim()
        val maxConcurrency = parseIntInput(concurrencyInput) ?: settingsStore.loadMaxConcurrency()
        val normalized = maxConcurrency.coerceIn(1, 50)
        settingsStore.saveMaxConcurrency(normalized)
        val normalizedConcurrencyText = formatNumber(normalized)
        if (normalizedConcurrencyText != concurrencyInput) {
            binding.maxConcurrencyInput.setText(normalizedConcurrencyText)
        }
        AppLogger.log("Settings", "API settings saved")
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
            .setNeutralButton(R.string.share_error_logs) { _, _ ->
                shareErrorLogsArchive()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareErrorLogsArchive() {
        val archive = AppLogger.createErrorLogsArchive(requireContext())
        if (archive == null || !archive.exists()) {
            Toast.makeText(requireContext(), R.string.error_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        shareLogFile(archive, getString(R.string.share_error_logs))
    }

    private fun shareLogFile(file: File, chooserTitle: String = getString(R.string.share_logs)) {
        if (!file.exists()) {
            Toast.makeText(requireContext(), R.string.logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val mimeType = if (file.extension.lowercase(Locale.US) == "zip") {
            "application/zip"
        } else {
            "text/plain"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle)
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

    private fun showLinkSourceDialog() {
        val sources = LinkSource.entries
        val labels = sources.map { getString(it.labelRes) }.toTypedArray()
        val current = settingsStore.loadLinkSource()
        val checkedIndex = sources.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.link_source_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selected = sources[which]
                settingsStore.saveLinkSource(selected)
                updateLinkSourceButton(selected)
                AppLogger.log("Settings", "Link source set to ${selected.prefValue}")
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateLinkSourceButton(source: LinkSource) {
        val label = getString(source.labelRes)
        binding.linkSourceButton.text = getString(R.string.link_source_format, label)
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
                loadAndShowUpdateDialog()
            }
            .show()
    }

    private fun loadAndShowUpdateDialog() {
        val hostActivity = activity as? MainActivity ?: return
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setView(ProgressBar(requireContext()))
            .setCancelable(false)
            .create()
        loadingDialog.show()
        lifecycleScope.launch {
            try {
                val updateInfo = UpdateChecker.fetchUpdateInfo(30_000)
                if (!isAdded) return@launch
                if (updateInfo == null) {
                    Toast.makeText(
                        requireContext(),
                        R.string.update_dialog_load_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                if (hostActivity.isFinishing || hostActivity.isDestroyed) return@launch
                val title = if (hostActivity.isRemoteNewer(updateInfo)) {
                    null
                } else {
                    getString(R.string.update_dialog_no_update_title)
                }
                hostActivity.showUpdateDialog(
                    updateInfo,
                    showIgnoreButton = false,
                    titleOverride = title
                )
            } finally {
                if (loadingDialog.isShowing) {
                    loadingDialog.dismiss()
                }
            }
        }
    }

    private fun showLlmParamsDialog() {
        val currentParams = settingsStore.loadLlmParameters()
        val dialogBinding = DialogLlmParamsBinding.inflate(layoutInflater)
        dialogBinding.temperatureInput.setText(formatNumberOrEmpty(currentParams.temperature))
        dialogBinding.topPInput.setText(formatNumberOrEmpty(currentParams.topP))
        dialogBinding.topKInput.setText(formatNumberOrEmpty(currentParams.topK))
        dialogBinding.maxOutputTokensInput.setText(formatNumberOrEmpty(currentParams.maxOutputTokens))
        dialogBinding.frequencyPenaltyInput.setText(formatNumberOrEmpty(currentParams.frequencyPenalty))
        dialogBinding.presencePenaltyInput.setText(formatNumberOrEmpty(currentParams.presencePenalty))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.llm_params_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
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

    private fun showOcrSettingsDialog() {
        val currentSettings = settingsStore.loadOcrApiSettings()
        val dialogBinding = DialogOcrSettingsBinding.inflate(layoutInflater)
        dialogBinding.useLocalOcrSwitch.isChecked = currentSettings.useLocalOcr
        dialogBinding.ocrApiUrlInput.setText(currentSettings.apiUrl)
        dialogBinding.ocrApiKeyInput.setText(currentSettings.apiKey)
        dialogBinding.ocrModelNameInput.setText(currentSettings.modelName)
        dialogBinding.ocrApiTimeoutInput.setText(currentSettings.timeoutSeconds.toString())

        fun updateInputsEnabled(useLocalOcr: Boolean) {
            val enabled = !useLocalOcr
            dialogBinding.ocrApiUrlLayout.isEnabled = enabled
            dialogBinding.ocrApiKeyLayout.isEnabled = enabled
            dialogBinding.ocrModelNameLayout.isEnabled = enabled
            dialogBinding.ocrApiTimeoutLayout.isEnabled = enabled
            dialogBinding.ocrApiUrlInput.isEnabled = enabled
            dialogBinding.ocrApiKeyInput.isEnabled = enabled
            dialogBinding.ocrModelNameInput.isEnabled = enabled
            dialogBinding.ocrApiTimeoutInput.isEnabled = enabled
            dialogBinding.ocrSettingsNote.setText(
                if (useLocalOcr) R.string.ocr_settings_note_local else R.string.ocr_settings_note_api
            )
        }

        updateInputsEnabled(currentSettings.useLocalOcr)
        dialogBinding.useLocalOcrSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateInputsEnabled(isChecked)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ocr_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val timeoutInput = dialogBinding.ocrApiTimeoutInput.text?.toString()?.trim()
                val timeoutSeconds = parseIntInput(timeoutInput)
                    ?.coerceIn(OCR_TIMEOUT_MIN_SECONDS, OCR_TIMEOUT_MAX_SECONDS)
                    ?: currentSettings.timeoutSeconds
                val settings = OcrApiSettings(
                    useLocalOcr = dialogBinding.useLocalOcrSwitch.isChecked,
                    apiUrl = dialogBinding.ocrApiUrlInput.text?.toString()?.trim().orEmpty(),
                    apiKey = dialogBinding.ocrApiKeyInput.text?.toString()?.trim().orEmpty(),
                    modelName = dialogBinding.ocrModelNameInput.text?.toString()?.trim().orEmpty(),
                    timeoutSeconds = timeoutSeconds
                )
                settingsStore.saveOcrApiSettings(settings)
                AppLogger.log(
                    "Settings",
                    "OCR mode set to ${if (settings.useLocalOcr) "local" else "openai-compatible api"}"
                )
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
            return parseDoubleInput(trimmed).also { if (it == null) hasInvalid = true }
        }
        fun parseInt(text: String?): Int? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            return parseIntInput(trimmed).also { if (it == null) hasInvalid = true }
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
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
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
        private const val OCR_TIMEOUT_MIN_SECONDS = 30
        private const val OCR_TIMEOUT_MAX_SECONDS = 1200
    }

    private data class ParsedLlmParams(
        val params: LlmParameterSettings,
        val hasInvalid: Boolean
    )
}
