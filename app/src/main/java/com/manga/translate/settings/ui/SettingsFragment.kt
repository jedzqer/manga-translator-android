package com.manga.translate.settings.ui

import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manga.translate.R
import com.manga.translate.background.TranslationKeepAliveService
import com.manga.translate.databinding.FragmentSettingsBinding
import com.manga.translate.di.appContainer
import com.manga.translate.model.ApiFormat
import com.manga.translate.model.AppLanguage
import com.manga.translate.model.LinkSource
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.model.ReadingPageAnimationMode
import com.manga.translate.model.ThemeMode
import com.manga.translate.network.LlmRequestException
import com.manga.translate.platform.AppLogger
import com.manga.translate.rendering.BubbleFont
import com.manga.translate.settings.CustomRequestParameter
import com.manga.translate.settings.SettingsMainForm
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.dialogs.AboutDialog
import com.manga.translate.settings.ui.dialogs.BackupOperationCancelHost
import com.manga.translate.settings.ui.dialogs.BackupProgressDialog
import com.manga.translate.settings.ui.dialogs.AiProviderProfilesDialog
import com.manga.translate.settings.ui.dialogs.ApiFormatDialog
import com.manga.translate.settings.ui.dialogs.BubbleFontSettingsDialog
import com.manga.translate.settings.ui.dialogs.CustomRequestParamsDialog
import com.manga.translate.settings.ui.dialogs.FloatingBubbleRenderSettingsDialog
import com.manga.translate.settings.ui.dialogs.FloatingTranslateSettingsDialog
import com.manga.translate.settings.ui.dialogs.LanguageDialog
import com.manga.translate.settings.ui.dialogs.LinkSourceDialog
import com.manga.translate.settings.ui.dialogs.LlmParamsDialog
import com.manga.translate.settings.ui.dialogs.LogsDialog
import com.manga.translate.settings.ui.dialogs.ModelSelectionDialog
import com.manga.translate.settings.ui.dialogs.NormalBubbleRenderSettingsDialog
import com.manga.translate.settings.ui.dialogs.OcrSettingsDialog
import com.manga.translate.settings.ui.dialogs.ReadingDisplayDialog
import com.manga.translate.settings.ui.dialogs.ReadingPageAnimationDialog
import com.manga.translate.settings.ui.dialogs.ThemeDialog
import com.manga.translate.settings.ui.dialogs.ThinkingLengthDialog
import com.manga.translate.settings.ui.dialogs.TranslationStyleDialog
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen. Each dialog lives in its own class under
 * [com.manga.translate.settings.ui.dialogs]; network calls are delegated to
 * [SettingsNetworkController] and file/Store access to [SettingsDataController].
 * This class keeps the entry methods, the main view binding and the button
 * label refreshers that mutate [binding].
 */
class SettingsFragment : Fragment(), BackupOperationCancelHost {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    internal val fragmentBinding: FragmentSettingsBinding get() = binding

    /** Runs the current locked backup export/import; canceled by the lock dialog. */
    private var backupOperationJob: Job? = null

    private val appContainer by lazy(LazyThreadSafetyMode.NONE) { requireContext().appContainer }
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.settingsStore }
    private val networkController by lazy(LazyThreadSafetyMode.NONE) {
        SettingsNetworkController(appContainer.llmClient)
    }
    private val dataController by lazy(LazyThreadSafetyMode.NONE) {
        SettingsDataController({ requireContext() }, settingsStore)
    }
    private val modelSelectionDialog by lazy(LazyThreadSafetyMode.NONE) {
        ModelSelectionDialog(this)
    }

    private val numberFormatter by lazy {
        NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            isGroupingUsed = false
        }
    }

    /**
     * The currently shown bubble font dialog. Held here because the document
     * picker launcher must be registered on the Fragment (only Fragment /
     * Activity can register activity results); the launcher callback forwards
     * imported font names to the dialog through
     * [BubbleFontSettingsDialog.onUploadedFontImported].
     */
    internal var activeBubbleFontDialog: BubbleFontSettingsDialog? = null

    private val uploadBubbleFontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val dialog = activeBubbleFontDialog ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val importedFileName = try {
                dataController.importUploadedFont(uri)
            } catch (e: Exception) {
                AppLogger.log("Settings", "Failed to import uploaded font", e)
                Toast.makeText(
                    requireContext(),
                    R.string.bubble_font_upload_failed,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            dialog.onUploadedFontImported(importedFileName)
            Toast.makeText(
                requireContext(),
                getString(R.string.bubble_font_upload_success, importedFileName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val appContext = requireContext().applicationContext
        runLockedBackupOperation(getString(R.string.backup_export_running)) {
            try {
                dataController.exportBackup(uri)
                Toast.makeText(appContext, R.string.backup_export_success, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                // User aborted mid-export: drop the partially written archive.
                runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
                throw e
            } catch (e: Exception) {
                AppLogger.log("Settings", "Failed to export app backup", e)
                Toast.makeText(appContext, R.string.backup_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val openBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val appContext = requireContext().applicationContext
        runLockedBackupOperation(getString(R.string.backup_import_running)) {
            try {
                val result = dataController.importBackup(uri)
                if (_binding != null) {
                    reloadSettingsUiFromStore()
                }
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.backup_import_success, result.mangaFiles),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("Settings", "Failed to import app backup", e)
                Toast.makeText(appContext, R.string.backup_import_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onBackupOperationCancelRequested() {
        backupOperationJob?.cancel()
        context?.applicationContext?.let {
            Toast.makeText(it, R.string.backup_operation_canceled, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Runs a backup export/import behind a modal lock dialog so the library
     * and preferences cannot be touched while files are being zipped or
     * restored. Any active background translation task is canceled first —
     * it would otherwise write into the library concurrently.
     */
    private fun runLockedBackupOperation(message: CharSequence, operation: suspend () -> Unit) {
        if (backupOperationJob?.isActive == true ||
            childFragmentManager.findFragmentByTag(BACKUP_LOCK_DIALOG_TAG) != null
        ) {
            return
        }
        val appContext = requireContext().applicationContext
        backupOperationJob = lifecycleScope.launch {
            BackupProgressDialog.newInstance(message)
                .showAllowingStateLoss(childFragmentManager, BACKUP_LOCK_DIALOG_TAG)
            try {
                if (!TranslationKeepAliveService.awaitTranslationStopped(appContext)) {
                    AppLogger.log(
                        "Settings",
                        "Background translation still running after cancel timeout — continuing with backup"
                    )
                }
                operation()
            } finally {
                backupOperationJob = null
                (childFragmentManager.findFragmentByTag(BACKUP_LOCK_DIALOG_TAG) as? BackupProgressDialog)
                    ?.dismissAllowingStateLoss()
            }
        }
    }

    internal fun launchBubbleFontUpload() {
        uploadBubbleFontLauncher.launch(
            arrayOf(
                "font/*",
                "application/x-font-ttf",
                "application/x-font-otf",
                "application/font-sfnt",
                "application/octet-stream",
                "*/*"
            )
        )
    }

    // Number formatting helpers shared with the dialog classes.

    internal fun formatNumber(value: Number): String = numberFormatter.format(value)

    internal fun formatNumberOrEmpty(value: Number?): String = value?.let(::formatNumber).orEmpty()

    internal fun parseIntInput(text: String?): Int? = runCatching {
        numberFormatter.parse(text?.trim().orEmpty())?.toInt()
    }.getOrNull()

    internal fun parseDoubleInput(text: String?): Double? = runCatching {
        numberFormatter.parse(text?.trim().orEmpty())?.toDouble()
    }.getOrNull()

    internal fun resolveColorAttr(attrRes: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attrRes, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(requireContext(), typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

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
        reloadSettingsUiFromStore()
        binding.modelIoLoggingSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.saveModelIoLogging(isChecked)
            AppLogger.log(
                "Settings",
                "Model I/O logging ${if (isChecked) "enabled" else "disabled"}"
            )
        }
        binding.enableThinkingSwitch.setOnCheckedChangeListener { _, isChecked ->
            val current = settingsStore.loadLlmParameters()
            if (current.enableThinking == isChecked) return@setOnCheckedChangeListener
            settingsStore.saveLlmParameters(current.copy(enableThinking = isChecked))
            updateThinkingLengthButton()
            AppLogger.log(
                "Settings",
                "enable_thinking ${if (isChecked) "enabled" else "disabled"}"
            )
        }
        binding.thinkingLengthButton.setOnClickListener {
            showThinkingLengthDialog()
        }
        binding.themeButton.setOnClickListener {
            showThemeDialog()
        }
        binding.languageButton.setOnClickListener {
            showLanguageDialog()
        }
        binding.readingDisplayButton.setOnClickListener {
            showReadingDisplayDialog()
        }
        binding.readingPageAnimationButton.setOnClickListener {
            showReadingPageAnimationDialog()
        }
        binding.linkSourceButton.setOnClickListener {
            showLinkSourceDialog()
        }
        binding.apiFormatButton.setOnClickListener {
            showApiFormatDialog()
        }

        binding.fetchModelsButton.setOnClickListener {
            fetchModelList()
        }

        binding.aiProviderProfilesButton.setOnClickListener {
            persistSettings()
            showAiProviderProfilesDialog()
        }

        binding.llmParamsButton.setOnClickListener {
            showLlmParamsDialog()
        }

        binding.customRequestParamsButton.setOnClickListener {
            showCustomRequestParamsDialog()
        }

        binding.ocrSettingsButton.setOnClickListener {
            showOcrSettingsDialog()
        }

        binding.translationStyleButton.setOnClickListener {
            showTranslationStyleDialog()
        }

        binding.floatingTranslateSettingsButton.setOnClickListener {
            showFloatingTranslateSettingsDialog()
        }

        binding.bubbleFontSettingsButton.setOnClickListener {
            showBubbleFontSettingsDialog()
        }

        binding.normalBubbleRenderSettingsButton.setOnClickListener {
            showNormalBubbleRenderSettingsDialog()
        }

        binding.floatingBubbleRenderSettingsButton.setOnClickListener {
            showFloatingBubbleRenderSettingsDialog()
        }

        binding.viewLogsButton.setOnClickListener {
            AppLogger.log("Settings", "View current log")
            showLogsDialog()
        }

        binding.openLogsFolderButton.setOnClickListener {
            AppLogger.log("Settings", "Share log file")
            showLogFilesDialog()
        }

        binding.exportBackupButton.setOnClickListener {
            persistSettings()
            createBackupLauncher.launch("manga-translator-backup.zip")
        }

        binding.importBackupButton.setOnClickListener {
            openBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }

        binding.aboutButton.setOnClickListener {
            showAboutDialog()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activeBubbleFontDialog = null
        _binding = null
    }

    private companion object {
        private const val BACKUP_LOCK_DIALOG_TAG = "backup_lock_dialog"
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            persistSettings()
        }
    }

    internal fun persistSettings() {
        val url = binding.apiUrlInput.text?.toString()?.trim().orEmpty()
        val key = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
        val model = binding.modelNameInput.text?.toString()?.trim().orEmpty()
        val timeoutInput = binding.apiTimeoutInput.text?.toString()?.trim()
        val timeoutSeconds = parseIntInput(timeoutInput) ?: settingsStore.loadApiTimeoutSeconds()
        val retryCountInput = binding.apiRetryCountInput.text?.toString()?.trim()
        val apiRetryCount = parseIntInput(retryCountInput) ?: settingsStore.loadApiRetryCount()
        val concurrencyInput = binding.maxConcurrencyInput.text?.toString()?.trim()
        val maxConcurrency = parseIntInput(concurrencyInput) ?: settingsStore.loadMaxConcurrency()
        val persisted = settingsStore.persistMainSettings(
            SettingsMainForm(
                apiUrl = url,
                apiKey = key,
                modelName = model,
                apiFormat = currentApiFormat(),
                apiTimeoutSeconds = timeoutSeconds,
                apiRetryCount = apiRetryCount,
                maxConcurrency = maxConcurrency
            )
        )
        val normalizedTimeoutText = formatNumber(persisted.apiTimeoutSeconds)
        if (normalizedTimeoutText != timeoutInput) {
            binding.apiTimeoutInput.setText(normalizedTimeoutText)
        }
        val normalizedRetryCountText = formatNumber(persisted.apiRetryCount)
        if (normalizedRetryCountText != retryCountInput) {
            binding.apiRetryCountInput.setText(normalizedRetryCountText)
        }
        val normalizedConcurrencyText = formatNumber(persisted.maxConcurrency)
        if (normalizedConcurrencyText != concurrencyInput) {
            binding.maxConcurrencyInput.setText(normalizedConcurrencyText)
        }
        AppLogger.log("Settings", "API settings saved")
    }

    internal fun currentApiFormat(): ApiFormat {
        return binding.apiFormatButton.getTag(R.id.api_format_button) as? ApiFormat
            ?: settingsStore.load().apiFormat
    }

    internal fun updateApiFormatButton(format: ApiFormat) {
        binding.apiFormatButton.setTag(R.id.api_format_button, format)
        updateLabeledButton(binding.apiFormatButton, R.string.api_format_format, format.labelRes)
    }

    internal fun updateApiSettingsNote(format: ApiFormat) {
        binding.apiUrlHintText.setText(
            when (format) {
                ApiFormat.OPENAI_COMPATIBLE -> R.string.api_settings_note_openai
                ApiFormat.OPENAI_RESPONSES -> R.string.api_settings_note_openai_responses
                ApiFormat.GEMINI -> R.string.api_settings_note_gemini
            }
        )
    }

    internal fun updateThemeButton(mode: ThemeMode) {
        updateLabeledButton(binding.themeButton, R.string.theme_setting_format, mode.labelRes)
    }

    internal fun updateLanguageButton(language: AppLanguage) {
        updateLabeledButton(binding.languageButton, R.string.language_setting_format, language.labelRes)
    }

    internal fun updateReadingDisplayButton(mode: ReadingDisplayMode) {
        updateLabeledButton(binding.readingDisplayButton, R.string.reading_display_format, mode.labelRes)
    }

    internal fun updateReadingPageAnimationButton(mode: ReadingPageAnimationMode) {
        updateLabeledButton(
            binding.readingPageAnimationButton,
            R.string.reading_page_animation_format,
            mode.labelRes
        )
    }

    internal fun updateLinkSourceButton(source: LinkSource) {
        updateLabeledButton(binding.linkSourceButton, R.string.link_source_format, source.labelRes)
    }

    internal fun updateCustomRequestParamsButton(parameters: List<CustomRequestParameter>) {
        binding.customRequestParamsButton.text = getString(
            R.string.custom_request_params_button_format,
            parameters.count { it.key.isNotBlank() }
        )
    }

    internal fun updateAiProviderProfilesButton() {
        val state = settingsStore.loadAiProviderProfilesState()
        binding.aiProviderProfilesButton.text = getString(
            R.string.ai_provider_profiles_button_format,
            state.activeProfileName ?: getString(R.string.ai_provider_profiles_none),
            state.profiles.size
        )
    }

    internal fun reloadSettingsUiFromStore() {
        val settings = settingsStore.load()
        binding.apiUrlInput.setText(settings.apiUrl)
        binding.apiKeyInput.setText(settings.apiKey)
        binding.modelNameInput.setText(settings.modelName)
        updateApiFormatButton(settings.apiFormat)
        updateApiSettingsNote(settings.apiFormat)
        binding.apiTimeoutInput.setText(formatNumber(settingsStore.loadApiTimeoutSeconds()))
        binding.apiRetryCountInput.setText(formatNumber(settingsStore.loadApiRetryCount()))
        binding.maxConcurrencyInput.setText(formatNumber(settingsStore.loadMaxConcurrency()))
        binding.modelIoLoggingSwitch.isChecked = settingsStore.loadModelIoLogging()
        binding.enableThinkingSwitch.isChecked = settingsStore.loadLlmParameters().enableThinking
        updateThinkingLengthButton()
        updateLanguageButton(settingsStore.loadAppLanguage())
        updateThemeButton(settingsStore.loadThemeMode())
        updateReadingDisplayButton(settingsStore.loadReadingDisplayMode())
        updateReadingPageAnimationButton(settingsStore.loadReadingPageAnimationMode())
        updateLinkSourceButton(settingsStore.loadLinkSource())
        updateCustomRequestParamsButton(settingsStore.loadCustomRequestParameters())
        updateAiProviderProfilesButton()
        updateBubbleFontSettingsButton()
        updateNormalBubbleRenderSettingsButton()
        updateFloatingBubbleRenderSettingsButton()
    }

    internal fun updateNormalBubbleRenderSettingsButton() {
        binding.normalBubbleRenderSettingsButton.setText(
            R.string.normal_bubble_render_settings_button
        )
    }

    internal fun updateBubbleFontSettingsButton() {
        val fontSettings = settingsStore.loadBubbleFontSettings()
        val labelRes = if (
            fontSettings.font == BubbleFont.CUSTOM_FILE &&
            fontSettings.customFontFileName.isNotBlank()
        ) {
            R.string.bubble_font_settings_button_uploaded
        } else {
            R.string.bubble_font_settings_button
        }
        binding.bubbleFontSettingsButton.setText(labelRes)
    }

    internal fun updateFloatingBubbleRenderSettingsButton() {
        binding.floatingBubbleRenderSettingsButton.setText(
            R.string.floating_bubble_render_settings_button
        )
    }

    internal fun updateThinkingLengthButton() {
        val params = settingsStore.loadLlmParameters()
        val enabled = params.enableThinking
        binding.thinkingLengthButton.isEnabled = enabled
        binding.thinkingLengthButton.alpha = if (enabled) 1f else 0.5f
        updateLabeledButton(
            binding.thinkingLengthButton,
            R.string.thinking_length_format,
            params.thinkingLength.labelRes
        )
    }

    private fun updateLabeledButton(view: TextView, @StringRes formatRes: Int, @StringRes labelRes: Int) {
        view.text = getString(formatRes, getString(labelRes))
    }

    // Entry methods: each dialog lives in its own class under dialogs/.

    private fun showLogsDialog() = LogsDialog(this, dataController).showLogs()

    private fun showLogFilesDialog() = LogsDialog(this, dataController).showLogFiles()

    private fun showThemeDialog() = ThemeDialog(this, settingsStore).show()

    private fun showLanguageDialog() = LanguageDialog(this, settingsStore).show()

    private fun showApiFormatDialog() = ApiFormatDialog(this, settingsStore).show()

    private fun showReadingDisplayDialog() = ReadingDisplayDialog(this, settingsStore).show()

    private fun showReadingPageAnimationDialog() = ReadingPageAnimationDialog(this, settingsStore).show()

    private fun showLinkSourceDialog() = LinkSourceDialog(this, settingsStore).show()

    private fun showBubbleFontSettingsDialog() =
        BubbleFontSettingsDialog(this, settingsStore, dataController).show()

    private fun showNormalBubbleRenderSettingsDialog() =
        NormalBubbleRenderSettingsDialog(this, settingsStore).show()

    private fun showFloatingBubbleRenderSettingsDialog() =
        FloatingBubbleRenderSettingsDialog(this, settingsStore).show()

    private fun showAiProviderProfilesDialog() = AiProviderProfilesDialog(this, dataController).show()

    private fun showAboutDialog() = AboutDialog(this, networkController).show()

    private fun showThinkingLengthDialog() = ThinkingLengthDialog(this, settingsStore).show()

    private fun showLlmParamsDialog() = LlmParamsDialog(this, settingsStore).show()

    private fun showCustomRequestParamsDialog() = CustomRequestParamsDialog(this, settingsStore).show()

    private fun showTranslationStyleDialog() = TranslationStyleDialog(this, settingsStore).show()

    private fun showOcrSettingsDialog() = OcrSettingsDialog(this, settingsStore).show()

    private fun showFloatingTranslateSettingsDialog() =
        FloatingTranslateSettingsDialog(this, settingsStore).show()

    private fun fetchModelList() {
        val apiUrl = binding.apiUrlInput.text?.toString()?.trim().orEmpty()
        val apiKey = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
        val apiFormat = currentApiFormat()
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
                    networkController.fetchModelList(apiUrl, apiKey, apiFormat)
                }
                if (models.isEmpty()) {
                    modelSelectionDialog.showFetchError("EMPTY_RESPONSE")
                } else {
                    modelSelectionDialog.showModelSelection(models)
                }
            } catch (e: LlmRequestException) {
                modelSelectionDialog.showFetchError(e.errorCode, e.responseBody)
            } finally {
                loadingDialog.dismiss()
                binding.fetchModelsButton.isEnabled = true
            }
        }
    }
}
