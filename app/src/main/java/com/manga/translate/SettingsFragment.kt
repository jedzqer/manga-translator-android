package com.manga.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import com.google.android.material.snackbar.Snackbar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isGone
import androidx.core.content.FileProvider
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manga.translate.databinding.DialogCustomRequestParamsBinding
import com.manga.translate.databinding.DialogAiProviderProfilesBinding
import com.manga.translate.databinding.DialogLlmParamsBinding
import com.manga.translate.databinding.DialogMultiProviderSchedulingBinding
import com.manga.translate.databinding.DialogOcrSettingsBinding
import com.manga.translate.databinding.DialogFloatingBubbleRenderSettingsBinding
import com.manga.translate.databinding.DialogFloatingTranslateSettingsBinding
import com.manga.translate.databinding.DialogNormalBubbleRenderSettingsBinding
import com.manga.translate.databinding.FragmentSettingsBinding
import com.manga.translate.databinding.ItemAdditionalTranslationProviderBinding
import com.manga.translate.databinding.ItemCustomRequestParamBinding
import com.manga.translate.di.appContainer
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val appContainer by lazy(LazyThreadSafetyMode.NONE) { requireContext().appContainer }
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.settingsStore }
    private val llmClient by lazy(LazyThreadSafetyMode.NONE) { appContainer.llmClient }
    private val vlmModelManager by lazy { VlmModelManager(requireContext()) }
    
    private val importTextModelLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleModelImport(it, false) }
    }

    private val importMmprojModelLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleModelImport(it, true) }
    }

    private lateinit var settingsPersistenceController: SettingsPersistenceController
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

    private data class RequestParamProviderOption(
        val providerId: String,
        val label: String
    )

    private fun buildCustomRequestParamProviderOptions(): List<RequestParamProviderOption> {
        val options = mutableListOf(
            RequestParamProviderOption(
                providerId = PRIMARY_PROVIDER_ID,
                label = getString(R.string.custom_request_params_provider_primary)
            ),
            RequestParamProviderOption(
                providerId = OCR_PROVIDER_ID,
                label = getString(R.string.custom_request_params_provider_ocr)
            )
        )
        settingsStore.loadAdditionalTranslationProviders().forEachIndexed { index, _ ->
            options += RequestParamProviderOption(
                providerId = "additional_${index + 1}",
                label = settingsStore.defaultAdditionalProviderName(index)
            )
        }
        return options
    }

    private fun setupCustomRequestParamProviderDropdown(
        inputView: MaterialAutoCompleteTextView,
        options: List<RequestParamProviderOption>,
        selectedProviderId: String
    ) {
        val labels = options.map { it.label }
        val textColor = resolveColorAttr(R.attr.dialogTextColor)
        inputView.setAdapter(
            object : ArrayAdapter<String>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                labels
            ) {
                private fun applyThemeTextColor(view: View): View {
                    (view as? TextView)?.setTextColor(textColor)
                    return view
                }

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return applyThemeTextColor(super.getView(position, convertView, parent))
                }

                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    return applyThemeTextColor(super.getDropDownView(position, convertView, parent))
                }
            }
        )
        val selectedLabel = options.firstOrNull { it.providerId == selectedProviderId }?.label
            ?: options.first().label
        inputView.setText(selectedLabel, false)
    }

    private fun parseCustomRequestParamProviderId(
        inputView: MaterialAutoCompleteTextView,
        options: List<RequestParamProviderOption>
    ): String {
        val selectedLabel = inputView.text?.toString()?.trim().orEmpty()
        return options.firstOrNull { it.label == selectedLabel }?.providerId
            ?: PRIMARY_PROVIDER_ID
    }

    private fun resolveCustomRequestParamProviderLabel(providerId: String): String {
        return buildCustomRequestParamProviderOptions()
            .firstOrNull { it.providerId == providerId }
            ?.label
            ?: getString(R.string.custom_request_params_provider_primary)
    }

    private fun setupFloatingGestureActionDropdown(
        inputView: MaterialAutoCompleteTextView,
        currentAction: FloatingBallGestureAction
    ) {
        val actions = FloatingBallGestureAction.entries
        val labels = actions.map { getString(it.labelRes) }
        val textColor = resolveColorAttr(R.attr.dialogTextColor)
        inputView.setAdapter(
            object : ArrayAdapter<String>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                labels
            ) {
                private fun applyThemeTextColor(view: View): View {
                    (view as? TextView)?.setTextColor(textColor)
                    return view
                }

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return applyThemeTextColor(super.getView(position, convertView, parent))
                }

                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    return applyThemeTextColor(super.getDropDownView(position, convertView, parent))
                }
            }
        )
        inputView.setText(getString(currentAction.labelRes), false)
    }

    private fun setupTranslationLanguageDropdown(
        inputView: MaterialAutoCompleteTextView,
        currentLanguage: TranslationLanguage
    ) {
        val languages = TranslationLanguage.entries
        val labels = languages.map { getString(it.displayNameResId) }
        val textColor = resolveColorAttr(R.attr.dialogTextColor)
        inputView.setAdapter(
            object : ArrayAdapter<String>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                labels
            ) {
                private fun applyThemeTextColor(view: View): View {
                    (view as? TextView)?.setTextColor(textColor)
                    return view
                }

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return applyThemeTextColor(super.getView(position, convertView, parent))
                }

                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    return applyThemeTextColor(super.getDropDownView(position, convertView, parent))
                }
            }
        )
        inputView.setText(getString(currentLanguage.displayNameResId), false)
    }

    private fun parseTranslationLanguage(
        inputView: MaterialAutoCompleteTextView,
        defaultLanguage: TranslationLanguage
    ): TranslationLanguage {
        val selectedLabel = inputView.text?.toString()?.trim().orEmpty()
        if (selectedLabel.isBlank()) return defaultLanguage
        return TranslationLanguage.entries.firstOrNull {
            getString(it.displayNameResId) == selectedLabel
        } ?: defaultLanguage
    }

    private fun parseFloatingGestureAction(
        inputView: MaterialAutoCompleteTextView,
        defaultAction: FloatingBallGestureAction
    ): FloatingBallGestureAction {
        val selectedLabel = inputView.text?.toString()?.trim().orEmpty()
        if (selectedLabel.isBlank()) return defaultAction
        return FloatingBallGestureAction.entries.firstOrNull {
            getString(it.labelRes) == selectedLabel
        } ?: defaultAction
    }

    private fun setupFloatingBubbleShapeDropdown(
        inputView: MaterialAutoCompleteTextView,
        currentShape: FloatingBubbleShape
    ) {
        val shapes = FloatingBubbleShape.entries
        val labels = shapes.map { getString(it.labelRes) }
        val textColor = resolveColorAttr(R.attr.dialogTextColor)
        inputView.setAdapter(
            object : ArrayAdapter<String>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                labels
            ) {
                private fun applyThemeTextColor(view: View): View {
                    (view as? TextView)?.setTextColor(textColor)
                    return view
                }

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return applyThemeTextColor(super.getView(position, convertView, parent))
                }

                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    return applyThemeTextColor(super.getDropDownView(position, convertView, parent))
                }
            }
        )
        inputView.threshold = 0
        inputView.setOnClickListener { inputView.showDropDown() }
        inputView.setText(getString(currentShape.labelRes), false)
    }

    private fun parseFloatingBubbleShape(
        inputView: MaterialAutoCompleteTextView,
        defaultShape: FloatingBubbleShape
    ): FloatingBubbleShape {
        val selectedLabel = inputView.text?.toString()?.trim().orEmpty()
        if (selectedLabel.isBlank()) return defaultShape
        return FloatingBubbleShape.entries.firstOrNull {
            getString(it.labelRes) == selectedLabel
        } ?: defaultShape
    }

    private fun resolveColorAttr(attrRes: Int): Int {
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
        settingsPersistenceController = SettingsPersistenceController(settingsStore)
        reloadSettingsUiFromStore()
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

        binding.multiProviderSchedulingButton.setOnClickListener {
            showMultiProviderSchedulingDialog()
        }

        binding.customRequestParamsButton.setOnClickListener {
            showCustomRequestParamsDialog()
        }

        binding.ocrSettingsButton.setOnClickListener {
            showOcrSettingsDialog()
        }

        binding.floatingTranslateSettingsButton.setOnClickListener {
            showFloatingTranslateSettingsDialog()
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

        binding.aboutButton.setOnClickListener {
            showAboutDialog()
        }

        binding.btnImportTextModel.setOnClickListener { importTextModelLauncher.launch("*/*") }
        binding.btnImportMmprojModel.setOnClickListener { importMmprojModelLauncher.launch("*/*") }
        updateVlmModelStatus()
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
        val timeoutInput = binding.apiTimeoutInput.text?.toString()?.trim()
        val timeoutSeconds = parseIntInput(timeoutInput) ?: settingsStore.loadApiTimeoutSeconds()
        val retryCountInput = binding.apiRetryCountInput.text?.toString()?.trim()
        val apiRetryCount = parseIntInput(retryCountInput) ?: settingsStore.loadApiRetryCount()
        val concurrencyInput = binding.maxConcurrencyInput.text?.toString()?.trim()
        val maxConcurrency = parseIntInput(concurrencyInput) ?: settingsStore.loadMaxConcurrency()
        val persisted = settingsPersistenceController.persistMainForm(
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
        if (!persisted.concurrencySaved) {
            val minimumConcurrency = requiredMainTranslationProviderConcurrency()
            Toast.makeText(
                requireContext(),
                getString(R.string.max_concurrency_provider_count_error, minimumConcurrency),
                Toast.LENGTH_SHORT
            ).show()
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
            setTextColor(resolveColorAttr(R.attr.dialogTextColor))
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
        showSingleChoiceSettingDialog(
            titleRes = R.string.theme_setting_title,
            options = ThemeMode.entries,
            current = settingsStore.loadThemeMode(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            settingsStore.saveThemeMode(selected)
            updateThemeButton(selected)
            applyThemeSelection(selected)
            AppLogger.log("Settings", "Theme set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }

    private fun showLanguageDialog() {
        showSingleChoiceSettingDialog(
            titleRes = R.string.language_setting_title,
            options = AppLanguage.entries,
            current = settingsStore.loadAppLanguage(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            settingsStore.saveAppLanguage(selected)
            updateLanguageButton(selected)
            AppCompatDelegate.setApplicationLocales(selected.toLocales())
            AppLogger.log("Settings", "App language set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }

    private fun applyThemeSelection(mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
        activity?.recreate()
    }

    private fun showApiFormatDialog() {
        showSingleChoiceSettingDialog(
            titleRes = R.string.api_format_title,
            options = ApiFormat.entries,
            current = currentApiFormat(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            updateApiFormatButton(selected)
            updateApiSettingsNote(selected)
            AppLogger.log("Settings", "API format set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }

    private fun currentApiFormat(): ApiFormat {
        return binding.apiFormatButton.getTag(R.id.api_format_button) as? ApiFormat
            ?: settingsStore.load().apiFormat
    }

    private fun updateApiFormatButton(format: ApiFormat) {
        binding.apiFormatButton.setTag(R.id.api_format_button, format)
        updateLabeledButton(binding.apiFormatButton, R.string.api_format_format, format.labelRes)
    }

    private fun updateApiSettingsNote(format: ApiFormat) {
        binding.apiUrlHintText.setText(
            when (format) {
                ApiFormat.OPENAI_COMPATIBLE -> R.string.api_settings_note_openai
                ApiFormat.GEMINI -> R.string.api_settings_note_gemini
            }
        )
    }

    private fun updateThemeButton(mode: ThemeMode) {
        updateLabeledButton(binding.themeButton, R.string.theme_setting_format, mode.labelRes)
    }

    private fun updateLanguageButton(language: AppLanguage) {
        updateLabeledButton(binding.languageButton, R.string.language_setting_format, language.labelRes)
    }

    private fun showReadingDisplayDialog() {
        showSingleChoiceSettingDialog(
            titleRes = R.string.reading_display_title,
            options = ReadingDisplayMode.entries,
            current = settingsStore.loadReadingDisplayMode(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            settingsStore.saveReadingDisplayMode(selected)
            updateReadingDisplayButton(selected)
            AppLogger.log("Settings", "Reading display mode set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }

    private fun updateReadingDisplayButton(mode: ReadingDisplayMode) {
        updateLabeledButton(binding.readingDisplayButton, R.string.reading_display_format, mode.labelRes)
    }

    private fun showReadingPageAnimationDialog() {
        showSingleChoiceSettingDialog(
            titleRes = R.string.reading_page_animation_title,
            options = ReadingPageAnimationMode.entries,
            current = settingsStore.loadReadingPageAnimationMode(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            settingsStore.saveReadingPageAnimationMode(selected)
            updateReadingPageAnimationButton(selected)
            AppLogger.log("Settings", "Reading page animation mode set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }

    private fun updateReadingPageAnimationButton(mode: ReadingPageAnimationMode) {
        updateLabeledButton(
            binding.readingPageAnimationButton,
            R.string.reading_page_animation_format,
            mode.labelRes
        )
    }

    private fun showLinkSourceDialog() {
        showSingleChoiceSettingDialog(
            titleRes = R.string.link_source_title,
            options = LinkSource.entries,
            current = settingsStore.loadLinkSource(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            settingsStore.saveLinkSource(selected)
            updateLinkSourceButton(selected)
            AppLogger.log("Settings", "Link source set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }

    private fun updateLinkSourceButton(source: LinkSource) {
        updateLabeledButton(binding.linkSourceButton, R.string.link_source_format, source.labelRes)
    }

    private fun updateCustomRequestParamsButton(parameters: List<CustomRequestParameter>) {
        binding.customRequestParamsButton.text = getString(
            R.string.custom_request_params_button_format,
            parameters.count { it.key.isNotBlank() }
        )
    }

    private fun updateMultiProviderSchedulingButton(
        providers: List<AdditionalTranslationProvider>
    ) {
        binding.multiProviderSchedulingButton.text = getString(
            R.string.multi_provider_scheduling_button_format,
            providers.size
        )
    }

    private fun handleModelImport(uri: Uri, isMmproj: Boolean) {
        lifecycleScope.launch {
            val success = vlmModelManager.importModelFromUri(uri, isMmproj)
            if (success) {
                Snackbar.make(binding.root, "导入成功: ${vlmModelManager.getFileName(uri)}", Snackbar.LENGTH_SHORT).show()
                updateVlmModelStatus()
            } else {
                Snackbar.make(binding.root, "导入失败", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateVlmModelStatus() {
        binding.tvTextModelStatus.text = if (vlmModelManager.textModelFile.exists()) {
            "语言模型 (LLM): 已导入 (${vlmModelManager.textModelFile.length() / 1024 / 1024} MB)"
        } else {
            "语言模型 (LLM): 未导入"
        }

        binding.tvMmprojModelStatus.text = if (vlmModelManager.mmprojModelFile.exists()) {
            "视觉模型 (mmproj): 已导入 (${vlmModelManager.mmprojModelFile.length() / 1024 / 1024} MB)"
        } else {
            "视觉模型 (mmproj): 未导入"
        }
    }

    private fun requiredMainTranslationProviderConcurrency(): Int {
        val mainSettings = ApiSettings(
            apiUrl = binding.apiUrlInput.text?.toString()?.trim().orEmpty(),
            apiKey = binding.apiKeyInput.text?.toString()?.trim().orEmpty(),
            modelName = binding.modelNameInput.text?.toString()?.trim().orEmpty(),
            apiFormat = currentApiFormat(),
            providerId = PRIMARY_PROVIDER_ID
        )
        var count = if (mainSettings.isValid()) 1 else 0
        count += settingsStore.loadAdditionalTranslationProviders().count { it.enabled && it.isConfigured() }
        return count.coerceAtLeast(1)
    }

    private fun updateAiProviderProfilesButton() {
        val state = settingsStore.loadAiProviderProfilesState()
        binding.aiProviderProfilesButton.text = getString(
            R.string.ai_provider_profiles_button_format,
            state.activeProfileName ?: getString(R.string.ai_provider_profiles_none),
            state.profiles.size
        )
    }

    private fun reloadSettingsUiFromStore() {
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
        updateLanguageButton(settingsStore.loadAppLanguage())
        updateThemeButton(settingsStore.loadThemeMode())
        updateReadingDisplayButton(settingsStore.loadReadingDisplayMode())
        updateReadingPageAnimationButton(settingsStore.loadReadingPageAnimationMode())
        updateLinkSourceButton(settingsStore.loadLinkSource())
        updateMultiProviderSchedulingButton(settingsStore.loadAdditionalTranslationProviders())
        updateCustomRequestParamsButton(settingsStore.loadCustomRequestParameters())
        updateAiProviderProfilesButton()
        updateNormalBubbleRenderSettingsButton()
        updateFloatingBubbleRenderSettingsButton()
    }

    private fun updateNormalBubbleRenderSettingsButton() {
        binding.normalBubbleRenderSettingsButton.setText(
            R.string.normal_bubble_render_settings_button
        )
    }

    private fun updateFloatingBubbleRenderSettingsButton() {
        binding.floatingBubbleRenderSettingsButton.setText(
            R.string.floating_bubble_render_settings_button
        )
    }

    private fun showNormalBubbleRenderSettingsDialog() {
        val currentSettings = settingsStore.loadNormalBubbleRenderSettings()
        val dialogBinding = DialogNormalBubbleRenderSettingsBinding.inflate(layoutInflater)
        dialogBinding.normalBubbleShrinkPercentInput.setText(
            formatNumber(currentSettings.shrinkPercent)
        )
        dialogBinding.normalBubbleOpacityPercentInput.setText(
            formatNumber(currentSettings.opacityPercent)
        )
        dialogBinding.normalBubbleFreeShrinkPercentInput.setText(
            formatNumber(currentSettings.freeBubbleShrinkPercent)
        )
        dialogBinding.normalBubbleFreeOpacityPercentInput.setText(
            formatNumber(currentSettings.freeBubbleOpacityPercent)
        )
        val seekBarProgress = ((currentSettings.minAreaPerCharSp - 16f) / 2.4f).roundToInt().coerceIn(0, 100)
        dialogBinding.normalBubbleMinAreaSeekbar.progress = seekBarProgress
        dialogBinding.normalBubbleMinAreaValueLabel.text =
            getString(R.string.normal_bubble_min_area_value, currentSettings.minAreaPerCharSp.roundToInt())
        dialogBinding.normalBubbleMinAreaSeekbar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sp2 = (16f + progress * 2.4f).roundToInt()
                    dialogBinding.normalBubbleMinAreaValueLabel.text =
                        getString(R.string.normal_bubble_min_area_value, sp2)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )
        dialogBinding.normalBubbleHorizontalTextSwitch.isChecked = currentSettings.useHorizontalText
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.normal_bubble_render_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updated = NormalBubbleRenderSettings(
                    shrinkPercent = parseIntInput(
                        dialogBinding.normalBubbleShrinkPercentInput.text?.toString()
                    ) ?: currentSettings.shrinkPercent,
                    opacityPercent = parseIntInput(
                        dialogBinding.normalBubbleOpacityPercentInput.text?.toString()
                    ) ?: currentSettings.opacityPercent,
                    freeBubbleShrinkPercent = parseIntInput(
                        dialogBinding.normalBubbleFreeShrinkPercentInput.text?.toString()
                    ) ?: currentSettings.freeBubbleShrinkPercent,
                    freeBubbleOpacityPercent = parseIntInput(
                        dialogBinding.normalBubbleFreeOpacityPercentInput.text?.toString()
                    ) ?: currentSettings.freeBubbleOpacityPercent,
                    minAreaPerCharSp = 16f + dialogBinding.normalBubbleMinAreaSeekbar.progress * 2.4f,
                    useHorizontalText = dialogBinding.normalBubbleHorizontalTextSwitch.isChecked
                )
                settingsStore.saveNormalBubbleRenderSettings(updated)
                updateNormalBubbleRenderSettingsButton()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFloatingBubbleRenderSettingsDialog() {
        val currentSettings = settingsStore.loadFloatingBubbleRenderSettings()
        val dialogBinding = DialogFloatingBubbleRenderSettingsBinding.inflate(layoutInflater)
        dialogBinding.floatingBubbleSizeAdjustPercentInput.setText(
            formatNumber(currentSettings.sizeAdjustPercent)
        )
        dialogBinding.floatingBubbleOpacityPercentInput.setText(
            formatNumber(currentSettings.opacityPercent)
        )
        setupFloatingBubbleShapeDropdown(
            dialogBinding.floatingBubbleShapeInput,
            currentSettings.shape
        )
        dialogBinding.floatingBubbleHorizontalTextSwitch.isChecked = currentSettings.useHorizontalText
        val seekBarProgress = ((currentSettings.minAreaPerCharSp - 16f) / 2.4f).roundToInt().coerceIn(0, 100)
        dialogBinding.floatingBubbleMinAreaSeekbar.progress = seekBarProgress
        dialogBinding.floatingBubbleMinAreaValueLabel.text =
            getString(R.string.floating_bubble_min_area_value, currentSettings.minAreaPerCharSp.roundToInt())
        dialogBinding.floatingBubbleMinAreaSeekbar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sp2 = (16f + progress * 2.4f).roundToInt()
                    dialogBinding.floatingBubbleMinAreaValueLabel.text =
                        getString(R.string.floating_bubble_min_area_value, sp2)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.floating_bubble_render_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updated = FloatingBubbleRenderSettings(
                    sizeAdjustPercent = parseIntInput(
                        dialogBinding.floatingBubbleSizeAdjustPercentInput.text?.toString()
                    ) ?: currentSettings.sizeAdjustPercent,
                    opacityPercent = parseIntInput(
                        dialogBinding.floatingBubbleOpacityPercentInput.text?.toString()
                    ) ?: currentSettings.opacityPercent,
                    shape = parseFloatingBubbleShape(
                        dialogBinding.floatingBubbleShapeInput,
                        currentSettings.shape
                    ),
                    useHorizontalText = dialogBinding.floatingBubbleHorizontalTextSwitch.isChecked,
                    minAreaPerCharSp = 16f + dialogBinding.floatingBubbleMinAreaSeekbar.progress * 2.4f
                )
                settingsStore.saveFloatingBubbleRenderSettings(updated)
                updateFloatingBubbleRenderSettingsButton()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAiProviderProfilesDialog() {
        val dialogBinding = DialogAiProviderProfilesBinding.inflate(layoutInflater)
        val profileNames = ArrayList<String>()
        var selectedName: String? = null
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = profileNames.size

            override fun getItem(position: Int): String = profileNames[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(
                    R.layout.item_ai_provider_profile,
                    parent,
                    false
                )
                val name = getItem(position)
                val nameView = view.findViewById<TextView>(R.id.ai_provider_profile_name)
                val checkView = view.findViewById<CheckedTextView>(R.id.ai_provider_profile_check)
                val isChecked = name == selectedName
                nameView.text = name
                view.isActivated = isChecked
                checkView.isChecked = isChecked
                return view
            }
        }
        dialogBinding.aiProviderProfilesList.adapter = adapter

        fun refreshProfiles(preferredSelection: String? = selectedName) {
            val state = settingsStore.loadAiProviderProfilesState()
            val names = state.profiles.map { it.name }
            profileNames.clear()
            profileNames.addAll(names)
            adapter.notifyDataSetChanged()
            selectedName = preferredSelection?.takeIf { it in names } ?: state.activeProfileName
            val checkedIndex = selectedName?.let(names::indexOf) ?: -1
            if (checkedIndex >= 0) {
                dialogBinding.aiProviderProfilesList.setItemChecked(checkedIndex, true)
            } else {
                dialogBinding.aiProviderProfilesList.clearChoices()
            }
            adapter.notifyDataSetChanged()
            dialogBinding.aiProviderProfilesCurrentText.text = state.activeProfileName?.let {
                getString(R.string.ai_provider_profiles_current, it)
            } ?: getString(R.string.ai_provider_profiles_current_none)
            dialogBinding.aiProviderProfilesNoteText.text = if (names.isEmpty()) {
                getString(R.string.ai_provider_profiles_empty)
            } else {
                getString(R.string.ai_provider_profiles_note)
            }
            dialogBinding.aiProviderProfilesApplyButton.isEnabled = names.isNotEmpty()
            dialogBinding.aiProviderProfilesDeleteButton.isEnabled = selectedName != null
            dialogBinding.aiProviderProfilesOverwriteButton.isEnabled = state.activeProfileName != null
            updateAiProviderProfilesButton()
        }

        dialogBinding.aiProviderProfilesList.setOnItemClickListener { _, _, position, _ ->
            selectedName = profileNames.getOrNull(position)
            dialogBinding.aiProviderProfilesDeleteButton.isEnabled = selectedName != null
            adapter.notifyDataSetChanged()
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.ai_provider_profiles_title)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialogBinding.aiProviderProfilesSaveNewButton.setOnClickListener {
            showCreateAiProviderProfileDialog { profileName ->
                persistSettings()
                val saved = settingsStore.saveCurrentAsAiProviderProfile(profileName)
                if (!saved) {
                    Toast.makeText(
                        requireContext(),
                        R.string.ai_provider_profiles_name_duplicate,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@showCreateAiProviderProfileDialog
                }
                reloadSettingsUiFromStore()
                refreshProfiles(profileName)
                Toast.makeText(requireContext(), R.string.ai_provider_profiles_saved, Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.aiProviderProfilesOverwriteButton.setOnClickListener {
            persistSettings()
            if (!settingsStore.overwriteActiveAiProviderProfile()) {
                Toast.makeText(
                    requireContext(),
                    R.string.ai_provider_profiles_overwrite_missing,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            refreshProfiles()
            Toast.makeText(requireContext(), R.string.ai_provider_profiles_overwritten, Toast.LENGTH_SHORT).show()
        }

        dialogBinding.aiProviderProfilesApplyButton.setOnClickListener {
            val profileName = selectedName
            if (profileName == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.ai_provider_profiles_select_required,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (!settingsStore.applyAiProviderProfile(profileName)) {
                Toast.makeText(
                    requireContext(),
                    R.string.ai_provider_profiles_select_required,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            reloadSettingsUiFromStore()
            Toast.makeText(
                requireContext(),
                getString(R.string.ai_provider_profiles_applied, profileName),
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }

        dialogBinding.aiProviderProfilesDeleteButton.setOnClickListener {
            val profileName = selectedName
            if (profileName == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.ai_provider_profiles_select_required,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.ai_provider_profiles_delete_confirm, profileName))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (settingsStore.deleteAiProviderProfile(profileName)) {
                        if (settingsStore.loadAiProviderProfilesState().activeProfileName == null) {
                            selectedName = null
                        }
                        refreshProfiles()
                        Toast.makeText(
                            requireContext(),
                            R.string.ai_provider_profiles_deleted,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        refreshProfiles()
        dialog.show()
    }

    private fun showCreateAiProviderProfileDialog(onConfirm: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.ai_provider_profiles_name_hint)
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.ai_provider_profiles_name_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.ai_provider_profiles_name_empty,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                onConfirm(name)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showAboutDialog() {
        val versionName = resolveVersionName()
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        val messageView = dialogView.findViewById<TextView>(R.id.about_dialog_message)
        messageView.text = getString(R.string.about_dialog_message, versionName)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.about_dialog_title)
            .setView(dialogView)
            .create()
        dialogView.findViewById<View>(R.id.about_dialog_cancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.about_dialog_open_project).setOnClickListener {
            dialog.dismiss()
            openUrl(PROJECT_URL)
        }
        dialogView.findViewById<View>(R.id.about_dialog_view_updates).setOnClickListener {
            dialog.dismiss()
            loadAndShowUpdateDialog()
        }
        dialog.show()
    }

    private fun loadAndShowUpdateDialog() {
        val hostActivity = activity as? MainActivity ?: return
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setView(ProgressBar(requireContext()))
            .create()
        loadingDialog.setCanceledOnTouchOutside(false)
        var loadJob: Job? = null
        loadingDialog.setOnCancelListener {
            loadJob?.cancel()
        }
        loadingDialog.show()
        loadJob = lifecycleScope.launch {
            try {
                val updateInfo = UpdateChecker.fetchUpdateInfo(
                    timeoutMs = 30_000,
                    includePreview = true
                )
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
            } catch (_: CancellationException) {
                AppLogger.log("Settings", "Update dialog loading cancelled by user")
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
        val supportsThinkingParams = supportsSiliconFlowThinkingParams()
        dialogBinding.temperatureInput.setText(formatNumberOrEmpty(currentParams.temperature))
        dialogBinding.topPInput.setText(formatNumberOrEmpty(currentParams.topP))
        dialogBinding.topKInput.setText(formatNumberOrEmpty(currentParams.topK))
        dialogBinding.maxOutputTokensInput.setText(formatNumberOrEmpty(currentParams.maxOutputTokens))
        dialogBinding.enableThinkingSwitch.isChecked = currentParams.enableThinking
        dialogBinding.thinkingBudgetInput.setText(formatNumberOrEmpty(currentParams.thinkingBudget))
        dialogBinding.frequencyPenaltyInput.setText(formatNumberOrEmpty(currentParams.frequencyPenalty))
        dialogBinding.presencePenaltyInput.setText(formatNumberOrEmpty(currentParams.presencePenalty))
        dialogBinding.enableThinkingSwitch.isGone = !supportsThinkingParams
        dialogBinding.thinkingBudgetInputLayout.isGone = !supportsThinkingParams
        dialogBinding.llmParamsNote.setText(
            if (supportsThinkingParams) R.string.llm_params_note_siliconflow else R.string.llm_params_note
        )
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
                        enableThinking = false,
                        thinkingBudget = null,
                        frequencyPenalty = null,
                        presencePenalty = null
                    )
                )
                AppLogger.log("Settings", "LLM params cleared")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomRequestParamsDialog() {
        val dialogBinding = DialogCustomRequestParamsBinding.inflate(layoutInflater)
        val existing = settingsStore.loadCustomRequestParameters()
        val providerOptions = buildCustomRequestParamProviderOptions()

        fun updateRowVisualState(rowBinding: ItemCustomRequestParamBinding) {
            val enabled = rowBinding.customRequestParamEnabledSwitch.isChecked
            rowBinding.customRequestParamFieldsContainer.alpha = if (enabled) 1f else 0.58f
            rowBinding.customRequestParamTitle.alpha = if (enabled) 1f else 0.72f
        }

        fun refreshRowTitles() {
            for (index in 0 until dialogBinding.customRequestParamsContainer.childCount) {
                val child = dialogBinding.customRequestParamsContainer.getChildAt(index)
                val rowBinding = ItemCustomRequestParamBinding.bind(child)
                rowBinding.customRequestParamTitle.text = getString(
                    R.string.custom_request_params_row_title,
                    index + 1
                )
            }
        }

        fun addRow(
            parameter: CustomRequestParameter = CustomRequestParameter("", "")
        ) {
            val rowBinding = ItemCustomRequestParamBinding.inflate(
                layoutInflater,
                dialogBinding.customRequestParamsContainer,
                false
            )
            rowBinding.customRequestParamEnabledSwitch.isChecked = parameter.enabled
            rowBinding.customRequestParamKeyInput.setText(parameter.key)
            rowBinding.customRequestParamValueInput.setText(parameter.value)
            setupCustomRequestParamProviderDropdown(
                rowBinding.customRequestParamTargetProviderInput,
                providerOptions,
                parameter.targetProviderId
            )
            rowBinding.customRequestParamEnabledSwitch.setOnCheckedChangeListener { _, _ ->
                updateRowVisualState(rowBinding)
            }
            rowBinding.customRequestParamDeleteButton.setOnClickListener {
                dialogBinding.customRequestParamsContainer.removeView(rowBinding.root)
                if (dialogBinding.customRequestParamsContainer.childCount == 0) {
                    addRow()
                } else {
                    refreshRowTitles()
                }
            }
            dialogBinding.customRequestParamsContainer.addView(rowBinding.root)
            updateRowVisualState(rowBinding)
            refreshRowTitles()
        }

        if (existing.isEmpty()) {
            addRow()
        } else {
            existing.forEach(::addRow)
        }
        dialogBinding.customRequestParamsAddButton.setOnClickListener {
            addRow()
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_request_params_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.llm_params_clear, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parameters = collectCustomRequestParameters(dialogBinding, providerOptions)
                val validationError = validateCustomRequestParameters(parameters)
                if (validationError != null) {
                    Toast.makeText(requireContext(), validationError, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                settingsStore.saveCustomRequestParameters(parameters)
                updateCustomRequestParamsButton(parameters)
                Toast.makeText(requireContext(), R.string.custom_request_params_saved, Toast.LENGTH_SHORT).show()
                AppLogger.log("Settings", "Custom request params updated")
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                settingsStore.saveCustomRequestParameters(emptyList())
                updateCustomRequestParamsButton(emptyList())
                AppLogger.log("Settings", "Custom request params cleared")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showMultiProviderSchedulingDialog() {
        val dialogBinding = DialogMultiProviderSchedulingBinding.inflate(layoutInflater)
        val existing = settingsStore.loadAdditionalTranslationProviders()

        fun updateRowVisualState(rowBinding: ItemAdditionalTranslationProviderBinding) {
            val enabled = rowBinding.translationProviderEnabledSwitch.isChecked
            rowBinding.translationProviderFieldsContainer.alpha = if (enabled) 1f else 0.58f
            rowBinding.translationProviderTitle.alpha = if (enabled) 1f else 0.72f
        }

        fun refreshRowTitles() {
            for (index in 0 until dialogBinding.multiProviderSchedulingContainer.childCount) {
                val child = dialogBinding.multiProviderSchedulingContainer.getChildAt(index)
                val rowBinding = ItemAdditionalTranslationProviderBinding.bind(child)
                rowBinding.translationProviderTitle.text = getString(
                    R.string.multi_provider_scheduling_row_title,
                    index + 1
                )
            }
        }

        fun addRow(
            provider: AdditionalTranslationProvider = AdditionalTranslationProvider(
                name = "",
                apiUrl = "",
                apiKey = "",
                modelName = "",
                weight = 1
            )
        ) {
            val rowBinding = ItemAdditionalTranslationProviderBinding.inflate(
                layoutInflater,
                dialogBinding.multiProviderSchedulingContainer,
                false
            )
            rowBinding.translationProviderEnabledSwitch.isChecked = provider.enabled
            rowBinding.translationProviderApiUrlInput.setText(provider.apiUrl)
            rowBinding.translationProviderApiKeyInput.setText(provider.apiKey)
            rowBinding.translationProviderModelNameInput.setText(provider.modelName)
            rowBinding.translationProviderWeightInput.setText(formatNumber(provider.weight))
            rowBinding.translationProviderEnabledSwitch.setOnCheckedChangeListener { _, _ ->
                updateRowVisualState(rowBinding)
            }
            rowBinding.translationProviderDeleteButton.setOnClickListener {
                dialogBinding.multiProviderSchedulingContainer.removeView(rowBinding.root)
                if (dialogBinding.multiProviderSchedulingContainer.childCount == 0) {
                    addRow()
                } else {
                    refreshRowTitles()
                }
            }
            dialogBinding.multiProviderSchedulingContainer.addView(rowBinding.root)
            updateRowVisualState(rowBinding)
            refreshRowTitles()
        }

        if (existing.isEmpty()) {
            addRow()
        } else {
            existing.forEach(::addRow)
        }
        dialogBinding.multiProviderSchedulingAddButton.setOnClickListener {
            addRow()
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.multi_provider_scheduling_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.llm_params_clear, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val providers = collectAdditionalTranslationProviders(dialogBinding)
                val validationError = validateAdditionalTranslationProviders(providers)
                if (validationError != null) {
                    Toast.makeText(requireContext(), validationError, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val requiredConcurrency = (
                    if (ApiSettings(
                            apiUrl = binding.apiUrlInput.text?.toString()?.trim().orEmpty(),
                            apiKey = binding.apiKeyInput.text?.toString()?.trim().orEmpty(),
                            modelName = binding.modelNameInput.text?.toString()?.trim().orEmpty(),
                            apiFormat = currentApiFormat(),
                            providerId = PRIMARY_PROVIDER_ID
                        ).isValid()
                    ) 1 else 0
                    ) + providers.count { it.enabled && it.isConfigured() }
                val currentConcurrency = parseIntInput(
                    binding.maxConcurrencyInput.text?.toString()?.trim()
                ) ?: settingsStore.loadMaxConcurrency()
                if (currentConcurrency < requiredConcurrency.coerceAtLeast(1)) {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.max_concurrency_provider_count_error,
                            requiredConcurrency.coerceAtLeast(1)
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                settingsStore.saveAdditionalTranslationProviders(providers)
                val saved = settingsStore.loadAdditionalTranslationProviders()
                updateMultiProviderSchedulingButton(saved)
                Toast.makeText(
                    requireContext(),
                    R.string.multi_provider_scheduling_saved,
                    Toast.LENGTH_SHORT
                ).show()
                AppLogger.log("Settings", "Multi-provider scheduling updated")
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                settingsStore.saveAdditionalTranslationProviders(emptyList())
                updateMultiProviderSchedulingButton(emptyList())
                AppLogger.log("Settings", "Multi-provider scheduling cleared")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showOcrSettingsDialog() {
        val currentSettings = settingsStore.loadOcrApiSettings()
        val dialogBinding = DialogOcrSettingsBinding.inflate(layoutInflater)
        dialogBinding.useLocalOcrSwitch.isChecked = currentSettings.useLocalOcr
        dialogBinding.ocrApiUrlInput.setText(currentSettings.apiUrl)
        dialogBinding.ocrApiKeyInput.setText(currentSettings.apiKey)
        dialogBinding.ocrModelNameInput.setText(currentSettings.modelName)
        dialogBinding.ocrApiTimeoutInput.setText(
            String.format(Locale.getDefault(), "%d", currentSettings.timeoutSeconds)
        )

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
                    japaneseLocalOcrEngine = JapaneseLocalOcrEngine.MANGA_OCR_MOBILE,
                    apiUrl = dialogBinding.ocrApiUrlInput.text?.toString()?.trim().orEmpty(),
                    apiKey = dialogBinding.ocrApiKeyInput.text?.toString()?.trim().orEmpty(),
                    modelName = dialogBinding.ocrModelNameInput.text?.toString()?.trim().orEmpty(),
                    timeoutSeconds = timeoutSeconds
                )
                settingsStore.saveOcrApiSettings(settings)
                AppLogger.log(
                    "Settings",
                    "OCR mode set to ${
                        if (settings.useLocalOcr) {
                            "local:${settings.japaneseLocalOcrEngine.prefValue}"
                        } else {
                            "openai-compatible api"
                        }
                    }"
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFloatingTranslateSettingsDialog() {
        val currentSettings = settingsStore.loadFloatingTranslateApiSettings()
        val dialogBinding = DialogFloatingTranslateSettingsBinding.inflate(layoutInflater)
        dialogBinding.floatingApiUrlInput.setText(currentSettings.apiUrl)
        dialogBinding.floatingApiKeyInput.setText(currentSettings.apiKey)
        dialogBinding.floatingModelNameInput.setText(currentSettings.modelName)
        dialogBinding.floatingApiTimeoutInput.setText(
            formatNumber(currentSettings.timeoutSeconds)
        )
        dialogBinding.floatingUseVlDirectTranslateSwitch.isChecked =
            currentSettings.useVlDirectTranslate
        dialogBinding.floatingProofreadingModeSwitch.isChecked =
            currentSettings.proofreadingModeEnabled
        dialogBinding.floatingAutoCloseOnScreenChangeSwitch.isChecked =
            currentSettings.autoCloseOnScreenChangeEnabled
        setupFloatingGestureActionDropdown(
            dialogBinding.floatingSingleTapActionInput,
            currentSettings.singleTapAction
        )
        setupFloatingGestureActionDropdown(
            dialogBinding.floatingDoubleTapActionInput,
            currentSettings.doubleTapAction
        )
        setupFloatingGestureActionDropdown(
            dialogBinding.floatingLongPressActionInput,
            currentSettings.longPressAction
        )
        setupFloatingGestureActionDropdown(
            dialogBinding.floatingTripleTapActionInput,
            currentSettings.tripleTapAction
        )
        setupTranslationLanguageDropdown(
            dialogBinding.floatingLanguageInput,
            currentSettings.language
        )
        dialogBinding.floatingVlTranslateConcurrencyInput.setText(
            formatNumber(currentSettings.ocrConcurrencyLimit)
        )
        dialogBinding.floatingUseVlDirectTranslateSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                Toast.makeText(
                    requireContext(),
                    R.string.floating_use_vl_direct_translate_warning,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.floating_translate_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val timeoutInput =
                    dialogBinding.floatingApiTimeoutInput.text?.toString()?.trim()
                val timeoutSeconds = parseIntInput(timeoutInput)
                    ?.coerceIn(FLOATING_TIMEOUT_MIN_SECONDS, FLOATING_TIMEOUT_MAX_SECONDS)
                    ?: currentSettings.timeoutSeconds
                val concurrencyInput =
                    dialogBinding.floatingVlTranslateConcurrencyInput.text?.toString()?.trim()
                val ocrConcurrencyLimit = parseIntInput(concurrencyInput)
                    ?.coerceIn(1, 16)
                    ?: currentSettings.ocrConcurrencyLimit
                settingsStore.saveFloatingTranslateApiSettings(
                    FloatingTranslateApiSettings(
                        apiUrl = dialogBinding.floatingApiUrlInput.text?.toString()?.trim().orEmpty(),
                        apiKey = dialogBinding.floatingApiKeyInput.text?.toString()?.trim().orEmpty(),
                        modelName = dialogBinding.floatingModelNameInput.text?.toString()?.trim().orEmpty(),
                        language = parseTranslationLanguage(
                            dialogBinding.floatingLanguageInput,
                            currentSettings.language
                        ),
                        timeoutSeconds = timeoutSeconds,
                        useVlDirectTranslate =
                            dialogBinding.floatingUseVlDirectTranslateSwitch.isChecked,
                        ocrConcurrencyLimit = ocrConcurrencyLimit,
                        proofreadingModeEnabled =
                            dialogBinding.floatingProofreadingModeSwitch.isChecked,
                        autoCloseOnScreenChangeEnabled =
                            dialogBinding.floatingAutoCloseOnScreenChangeSwitch.isChecked,
                        singleTapAction = parseFloatingGestureAction(
                            dialogBinding.floatingSingleTapActionInput,
                            currentSettings.singleTapAction
                        ),
                        doubleTapAction = parseFloatingGestureAction(
                            dialogBinding.floatingDoubleTapActionInput,
                            currentSettings.doubleTapAction
                        ),
                        longPressAction = parseFloatingGestureAction(
                            dialogBinding.floatingLongPressActionInput,
                            currentSettings.longPressAction
                        ),
                        tripleTapAction = parseFloatingGestureAction(
                            dialogBinding.floatingTripleTapActionInput,
                            currentSettings.tripleTapAction
                        )
                    )
                )
                AppLogger.log("Settings", "Floating translate API settings updated")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseLlmParams(
        dialogBinding: DialogLlmParamsBinding
    ): ParsedLlmParams {
        var hasInvalid = false
        val supportsThinkingParams = supportsSiliconFlowThinkingParams()
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
            enableThinking = supportsThinkingParams && dialogBinding.enableThinkingSwitch.isChecked,
            thinkingBudget = if (supportsThinkingParams) {
                parseInt(dialogBinding.thinkingBudgetInput.text?.toString())
            } else {
                null
            },
            frequencyPenalty = parseDouble(dialogBinding.frequencyPenaltyInput.text?.toString()),
            presencePenalty = parseDouble(dialogBinding.presencePenaltyInput.text?.toString())
        )
        return ParsedLlmParams(params, hasInvalid)
    }

    private fun collectCustomRequestParameters(
        dialogBinding: DialogCustomRequestParamsBinding,
        providerOptions: List<RequestParamProviderOption>
    ): List<CustomRequestParameter> {
        val collected = mutableListOf<CustomRequestParameter>()
        for (index in 0 until dialogBinding.customRequestParamsContainer.childCount) {
            val child = dialogBinding.customRequestParamsContainer.getChildAt(index)
            val rowBinding = ItemCustomRequestParamBinding.bind(child)
            collected += CustomRequestParameter(
                key = rowBinding.customRequestParamKeyInput.text?.toString()?.trim().orEmpty(),
                value = rowBinding.customRequestParamValueInput.text?.toString().orEmpty(),
                enabled = rowBinding.customRequestParamEnabledSwitch.isChecked,
                targetProviderId = parseCustomRequestParamProviderId(
                    rowBinding.customRequestParamTargetProviderInput,
                    providerOptions
                )
            )
        }
        return collected
    }

    private fun collectAdditionalTranslationProviders(
        dialogBinding: DialogMultiProviderSchedulingBinding
    ): List<AdditionalTranslationProvider> {
        val collected = mutableListOf<AdditionalTranslationProvider>()
        for (index in 0 until dialogBinding.multiProviderSchedulingContainer.childCount) {
            val child = dialogBinding.multiProviderSchedulingContainer.getChildAt(index)
            val rowBinding = ItemAdditionalTranslationProviderBinding.bind(child)
            collected += AdditionalTranslationProvider(
                name = settingsStore.defaultAdditionalProviderName(index),
                apiUrl = rowBinding.translationProviderApiUrlInput.text?.toString()?.trim().orEmpty(),
                apiKey = rowBinding.translationProviderApiKeyInput.text?.toString()?.trim().orEmpty(),
                modelName = rowBinding.translationProviderModelNameInput.text?.toString()?.trim().orEmpty(),
                weight = parseIntInput(
                    rowBinding.translationProviderWeightInput.text?.toString()?.trim()
                ) ?: 0,
                enabled = rowBinding.translationProviderEnabledSwitch.isChecked
            )
        }
        return collected
    }

    private fun validateCustomRequestParameters(parameters: List<CustomRequestParameter>): String? {
        val activeKeys = LinkedHashSet<String>()
        parameters.forEach { parameter ->
            val key = parameter.key.trim()
            val value = parameter.value.trim()
            if (key.isBlank() && value.isBlank()) return@forEach
            if (key.isBlank()) {
                return getString(R.string.custom_request_params_empty_row_error)
            }
            if (!parameter.enabled) return@forEach
            val scopedKey = "${parameter.targetProviderId}\u0000$key"
            if (!activeKeys.add(scopedKey)) {
                return getString(
                    R.string.custom_request_params_duplicate_error_scoped,
                    resolveCustomRequestParamProviderLabel(parameter.targetProviderId),
                    key
                )
            }
        }
        val activeParamKeys = parameters
            .filter { it.enabled }
            .mapNotNull {
                val key = it.key.trim()
                if (key.isBlank() && it.value.trim().isBlank()) {
                    null
                } else {
                    parameterReservedKeyScope(it.targetProviderId) to key
                }
            }
        val conflict = activeParamKeys.firstOrNull { (providerId, key) ->
            key in LlmClient.reservedRequestKeys(resolveRequestParamApiFormat(providerId))
        }
        return if (conflict != null) {
            getString(R.string.custom_request_params_conflict_error, conflict.second)
        } else {
            null
        }
    }

    private fun parameterReservedKeyScope(providerId: String): String {
        return providerId.trim().ifBlank { PRIMARY_PROVIDER_ID }
    }

    private fun resolveRequestParamApiFormat(providerId: String): ApiFormat {
        return if (providerId == OCR_PROVIDER_ID) {
            ApiFormat.OPENAI_COMPATIBLE
        } else {
            currentApiFormat()
        }
    }

    private fun validateAdditionalTranslationProviders(
        providers: List<AdditionalTranslationProvider>
    ): String? {
        providers.forEach { provider ->
            val allBlank = provider.apiUrl.isBlank() &&
                provider.apiKey.isBlank() &&
                provider.modelName.isBlank()
            if (allBlank) return@forEach
            if (!provider.enabled) return@forEach
            if (!provider.isConfigured()) {
                return getString(R.string.multi_provider_scheduling_empty_field_error)
            }
            if (provider.weight <= 0) {
                return getString(R.string.multi_provider_scheduling_invalid_weight_error)
            }
        }
        return null
    }

    private fun supportsSiliconFlowThinkingParams(): Boolean {
        if (currentApiFormat() != ApiFormat.OPENAI_COMPATIBLE) return false
        return isSiliconFlowUrl(binding.apiUrlInput.text?.toString())
    }

    private fun isSiliconFlowUrl(url: String?): Boolean {
        val normalized = url?.trim().orEmpty().lowercase(Locale.US)
        if (normalized.isBlank()) return false
        return normalized.startsWith("https://api.siliconflow.cn") ||
            normalized.startsWith("http://api.siliconflow.cn")
    }

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
                    llmClient.fetchModelList(apiUrl, apiKey, apiFormat)
                }
                if (models.isEmpty()) {
                    showModelFetchError("EMPTY_RESPONSE")
                } else {
                    showModelSelectionDialog(models)
                }
            } catch (e: LlmRequestException) {
                showModelFetchError(e.errorCode, e.responseBody)
            } finally {
                loadingDialog.dismiss()
                binding.fetchModelsButton.isEnabled = true
            }
        }
    }

    private fun showModelSelectionDialog(models: List<String>) {
        val items = models.toTypedArray()
        val currentSelection = binding.modelNameInput.text?.toString()?.trim().orEmpty()
        var selectedIndex = items.indexOf(currentSelection)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.fetch_models_title)
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selectedIndex >= 0) {
                    binding.modelNameInput.setText(items[selectedIndex])
                }
            }
            .setNeutralButton(R.string.llm_params_clear) { _, _ ->
                binding.modelNameInput.setText("")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showModelFetchError(code: String, detail: String? = null) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.fetch_models_failed_title)
            .setMessage(
                getString(
                    R.string.fetch_models_failed_message,
                    ErrorDialogFormatter.formatApiErrorMessage(requireContext(), code, detail)
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .showWithScrollableMessage()
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

    private fun updateLabeledButton(view: TextView, @StringRes formatRes: Int, @StringRes labelRes: Int) {
        view.text = getString(formatRes, getString(labelRes))
    }

    private fun <T> showSingleChoiceSettingDialog(
        @StringRes titleRes: Int,
        options: List<T>,
        current: T,
        labelRes: (T) -> Int,
        onSelected: (dialog: android.content.DialogInterface, selected: T) -> Unit
    ) {
        val labels = options.map { getString(labelRes(it)) }.toTypedArray()
        val checkedIndex = options.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                onSelected(dialog, options[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val PROJECT_URL = "https://github.com/jedzqer/manga-translator"
        private const val RELEASES_URL = "https://github.com/jedzqer/manga-translator/releases"
        private const val OCR_TIMEOUT_MIN_SECONDS = 30
        private const val OCR_TIMEOUT_MAX_SECONDS = 1200
        private const val FLOATING_TIMEOUT_MIN_SECONDS = 30
        private const val FLOATING_TIMEOUT_MAX_SECONDS = 1200
    }

    private data class ParsedLlmParams(
        val params: LlmParameterSettings,
        val hasInvalid: Boolean
    )
}
