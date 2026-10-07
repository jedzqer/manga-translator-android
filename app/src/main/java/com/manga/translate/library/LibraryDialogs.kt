package com.manga.translate.library

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.manga.translate.R
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.platform.ErrorDialogFormatter
import com.manga.translate.platform.PromptAssetResolver
import com.manga.translate.platform.showModelErrorDialog
import com.manga.translate.platform.showWithScrollableMessage
import java.util.Locale

private const val MAX_FOLDER_TAG_LENGTH = 24

internal class LibraryDialogs {
    private fun formatInt(value: Int): String = String.format(Locale.getDefault(), "%d", value)
    private fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics
    ).toInt()

    private fun showMessageDialog(
        context: Context,
        titleRes: Int,
        message: CharSequence,
        positiveRes: Int = android.R.string.ok,
        onPositive: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(positiveRes) { _, _ -> onPositive?.invoke() }
            .showWithScrollableMessage()
    }

    private fun showTextInputDialog(
        context: Context,
        titleRes: Int,
        initialText: String = "",
        trimResult: Boolean = false,
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.folder_name_hint)
            if (initialText.isNotEmpty()) {
                setText(initialText)
                setSelection(text.length)
            }
        }
        applyDialogTextColors(context, input)
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text?.toString().orEmpty()
                onConfirm(if (trimResult) value.trim() else value)
            }
            .show()
    }

    private fun showSingleChoiceDialog(
        context: Context,
        titleRes: Int,
        items: Array<String>,
        checkedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSingleChoiceConfirmDialog(
        context: Context,
        titleRes: Int,
        items: Array<String>,
        checkedIndex: Int,
        onConfirmed: (Int) -> Unit
    ) {
        var selectedIndex = if (items.isNotEmpty()) {
            checkedIndex.coerceIn(0, items.lastIndex)
        } else {
            -1
        }
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selectedIndex >= 0) {
                    onConfirmed(selectedIndex)
                }
            }
            .show()
    }

    private fun buildDialogContainer(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20f), dp(context, 12f), dp(context, 20f), dp(context, 12f))
        }
    }

    private fun matchWrapLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun resolveColorAttr(context: Context, attrRes: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attrRes, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun createStatusChipView(context: Context, label: String): TextView {
        return TextView(context).apply {
            text = label
            setTextAppearance(R.style.Widget_MangaTranslator_BodyMuted)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = ContextCompat.getDrawable(context, R.drawable.bg_status_chip)
            val horizontal = dp(context, 8f)
            val vertical = dp(context, 2f)
            setPadding(horizontal, vertical, horizontal, vertical)
            isClickable = false
            isFocusable = false
        }
    }

    private fun createRemovableTagView(
        context: Context,
        tag: String,
        onRemove: () -> Unit
    ): LinearLayout {
        val horizontal = dp(context, 8f)
        val vertical = dp(context, 2f)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_status_chip)
            setPadding(horizontal, vertical, horizontal / 2, vertical)
            addView(
                TextView(context).apply {
                    text = tag
                    setTextAppearance(R.style.Widget_MangaTranslator_BodyMuted)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
            addView(
                TextView(context).apply {
                    text = "×"
                    setTextAppearance(R.style.Widget_MangaTranslator_BodyMuted)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    val pad = dp(context, 4f)
                    setPadding(pad, 0, pad, 0)
                    setOnClickListener { onRemove() }
                }
            )
        }
    }

    private fun applyDialogTextColors(
        context: Context,
        textView: TextView,
        useHintColor: Boolean = false
    ) {
        val textColorAttr = if (useHintColor) R.attr.dialogHintTextColor else R.attr.dialogTextColor
        textView.setTextColor(resolveColorAttr(context, textColorAttr))
        TextViewCompat.setCompoundDrawableTintList(
            textView,
            ColorStateList.valueOf(resolveColorAttr(context, R.attr.dialogTextColor))
        )
        if (textView is EditText) {
            textView.setHintTextColor(resolveColorAttr(context, R.attr.dialogHintTextColor))
        }
        if (textView is CheckBox) {
            textView.buttonTintList =
                ColorStateList.valueOf(resolveColorAttr(context, R.attr.dialogTextColor))
        }
    }

    fun showCreateFolderDialog(context: Context, onConfirm: (String) -> Unit) {
        showTextInputDialog(context, R.string.create_folder, onConfirm = onConfirm)
    }

    fun showCreateCollectionDialog(context: Context, onConfirm: (String) -> Unit) {
        showTextInputDialog(context, R.string.create_collection, onConfirm = onConfirm)
    }

    fun showCreateChapterDialog(context: Context, onConfirm: (String) -> Unit) {
        showTextInputDialog(context, R.string.create_chapter, onConfirm = onConfirm)
    }

    fun showCreateEntryDialog(
        context: Context,
        onCreateFolder: () -> Unit,
        onCreateCollection: () -> Unit
    ) {
        val items = arrayOf(
            context.getString(R.string.create_folder),
            context.getString(R.string.create_collection)
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.create_entry_title)
            .setItems(items) { _, which ->
                if (which == 0) onCreateFolder() else onCreateCollection()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun confirmDeleteFolder(context: Context, folderName: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.folder_delete)
            .setMessage(context.getString(R.string.folder_delete_confirm, folderName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.folder_delete) { _, _ -> onConfirm() }
            .showWithScrollableMessage()
    }

    fun showRenameFolderDialog(
        context: Context,
        oldName: String,
        onConfirm: (String) -> Unit
    ) {
        showTextInputDialog(context, R.string.folder_rename, initialText = oldName, onConfirm = onConfirm)
    }

    fun showEditFolderTagsDialog(
        context: Context,
        statusLabel: String?,
        initialTags: Set<String>,
        onConfirm: (Set<String>) -> Unit
    ) {
        val tags = initialTags.toSortedSet(String.CASE_INSENSITIVE_ORDER)
        val container = buildDialogContainer(context)
        val description = TextView(context).apply {
            setText(R.string.folder_edit_tags_hint)
            setTextAppearance(R.style.Widget_MangaTranslator_BodyMuted)
        }
        val tagGroup = ChipGroup(context).apply {
            isSingleLine = false
            chipSpacingHorizontal = dp(context, 8f)
            chipSpacingVertical = dp(context, 6f)
        }
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val input = TextInputEditText(context).apply {
            hint = context.getString(R.string.folder_tag_name_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(MAX_FOLDER_TAG_LENGTH))
        }
        applyDialogTextColors(context, input)
        val addButton = Button(context).apply {
            setText(R.string.folder_tag_add)
            isAllCaps = false
        }

        fun renderTags() {
            tagGroup.removeAllViews()
            statusLabel?.let { tagGroup.addView(createStatusChipView(context, it)) }
            tags.forEach { tag ->
                tagGroup.addView(
                    createRemovableTagView(context, tag) {
                        tags.remove(tag)
                        renderTags()
                    }
                )
            }
        }

        fun addTag() {
            val tag = input.text?.toString()?.trim().orEmpty()
            when {
                tag.isEmpty() -> return
                tag == context.getString(R.string.image_translated) ||
                    tag == context.getString(R.string.image_not_translated) -> {
                    Toast.makeText(context, R.string.folder_tag_reserved, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    tags.add(tag)
                    input.text?.clear()
                    renderTags()
                }
            }
        }

        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(
            addButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(context, 8f) }
        )
        container.addView(description, matchWrapLayoutParams())
        container.addView(tagGroup, matchWrapLayoutParams().apply { topMargin = dp(context, 12f) })
        container.addView(inputRow, matchWrapLayoutParams().apply { topMargin = dp(context, 12f) })
        addButton.setOnClickListener { addTag() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addTag()
                true
            } else {
                false
            }
        }
        renderTags()

        AlertDialog.Builder(context)
            .setTitle(R.string.folder_edit_tags)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm(tags.toSet()) }
            .show()
    }

    fun showMoveFolderDialog(
        context: Context,
        collections: List<String>,
        onSelected: (Int) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.folder_move_title)
            .setItems(collections.toTypedArray()) { _, which -> onSelected(which) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showFullTranslateInfo(context: Context) {
        showMessageDialog(
            context,
            R.string.folder_full_translate_info_title,
            context.getString(R.string.folder_full_translate_info)
        )
    }

    fun showGlossaryProcessingInfo(context: Context) {
        showMessageDialog(
            context,
            R.string.folder_glossary_processing_info_title,
            context.getString(R.string.folder_glossary_processing_note)
        )
    }

    fun showVlDirectTranslateInfo(context: Context) {
        showMessageDialog(
            context,
            R.string.folder_use_vl_direct_translate_info_title,
            context.getString(R.string.folder_use_vl_direct_translate_note)
        )
    }

    fun showLanguageSettingDialog(
        context: Context,
        languages: List<TranslationLanguage>,
        currentLanguage: TranslationLanguage,
        onSelected: (TranslationLanguage) -> Unit
    ) {
        val languageNames = languages.map { it.displayName(context) }.toTypedArray()
        val currentIndex = languages.indexOf(currentLanguage).coerceAtLeast(0)
        showSingleChoiceDialog(context, R.string.folder_language_setting_title, languageNames, currentIndex) {
            onSelected(languages[it])
        }
    }

    fun showLanguageSettingConfirmDialog(
        context: Context,
        languages: List<TranslationLanguage>,
        currentLanguage: TranslationLanguage,
        onSelected: (TranslationLanguage) -> Unit
    ) {
        val languageNames = languages.map { it.displayName(context) }.toTypedArray()
        val currentIndex = languages.indexOf(currentLanguage).coerceAtLeast(0)
        showSingleChoiceConfirmDialog(
            context,
            R.string.folder_language_setting_title,
            languageNames,
            currentIndex
        ) {
            onSelected(languages[it])
        }
    }

    fun showFixedLanguageDialog(context: Context) {
        showSingleChoiceDialog(
            context,
            R.string.folder_language_setting_title,
            arrayOf(
                context.getString(
                    R.string.folder_language_to_target,
                    PromptAssetResolver.translationTargetDisplayName(context)
                )
            ),
            0
        ) { }
    }

    fun showFolderReadingModeDialog(
        context: Context,
        currentMode: FolderReadingMode,
        onSelected: (FolderReadingMode) -> Unit
    ) {
        val modes = FolderReadingMode.entries
        val names = modes.map { context.getString(it.labelRes) }.toTypedArray()
        val currentIndex = modes.indexOf(currentMode)
        showSingleChoiceDialog(context, R.string.folder_reading_mode_title, names, currentIndex) {
            onSelected(modes[it])
        }
    }

    fun showApiErrorDialog(context: Context, errorCode: LlmErrorCode, detail: String? = null) {
        showApiErrorDialog(context, errorCode.value, detail)
    }

    fun showApiErrorDialog(context: Context, errorCode: String, detail: String? = null) {
        showMessageDialog(
            context,
            R.string.api_request_failed_title,
            context.getString(
                R.string.api_request_failed_message,
                ErrorDialogFormatter.formatApiErrorMessage(context, errorCode, detail)
            )
        )
    }

    fun showModelErrorDialog(
        context: Context,
        responseContent: String,
        onRetry: (() -> Unit)? = null,
        onSkip: (() -> Unit)? = null,
        onUnresolvedDismiss: (() -> Unit)? = null,
        onDialogDismissed: (() -> Unit)? = null,
        negativeButtonResId: Int = R.string.translation_skip,
        windowType: Int? = null
    ): AlertDialog {
        return com.manga.translate.platform.showModelErrorDialog(
            context = context,
            responseContent = responseContent,
            onRetry = onRetry,
            onSkip = onSkip,
            onUnresolvedDismiss = onUnresolvedDismiss,
            onDialogDismissed = onDialogDismissed,
            negativeButtonResId = negativeButtonResId,
            windowType = windowType
        )
    }

    fun showEhViewerSubfolderPicker(
        context: Context,
        folders: List<DocumentFile>,
        onPicked: (DocumentFile) -> Unit
    ) {
        val unnamed = context.getString(R.string.unnamed_folder)
        val names = folders.map { it.name ?: unnamed }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.ehviewer_select_folder)
            .setItems(names) { _, index -> onPicked(folders[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showDocumentFolderMultiPicker(
        context: Context,
        titleRes: Int,
        folders: List<DocumentFile>,
        onPicked: (List<DocumentFile>) -> Unit
    ) {
        val unnamed = context.getString(R.string.unnamed_folder)
        val names = folders.map { it.name ?: unnamed }.toTypedArray()
        val checked = BooleanArray(folders.size)
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = folders.filterIndexed { index, _ -> checked[index] }
                onPicked(selected)
            }
            .show()
    }

    fun showChapterImportSourcePicker(
        context: Context,
        onFolderSelected: () -> Unit,
        onArchiveSelected: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.chapter_import_source_title)
            .setItems(
                arrayOf(
                    context.getString(R.string.chapter_import_from_folder),
                    context.getString(R.string.chapter_import_from_file)
                )
            ) { _, which ->
                if (which == 0) onFolderSelected() else onArchiveSelected()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showEhViewerImportNameDialog(
        context: Context,
        defaultName: String,
        onConfirm: (String) -> Unit
    ) {
        showTextInputDialog(
            context,
            R.string.ehviewer_import_name_title,
            initialText = defaultName,
            trimResult = true
        ) { name ->
            if (name.isEmpty()) {
                Toast.makeText(context, R.string.folder_create_failed, Toast.LENGTH_SHORT).show()
            } else {
                onConfirm(name)
            }
        }
    }

    fun showExportSuccessDialog(context: Context, path: String) {
        showMessageDialog(
            context,
            R.string.export_success_title,
            context.getString(R.string.export_success_message, path)
        )
    }

    fun showExportOptionsDialog(
        context: Context,
        defaultThreads: Int,
        defaultExportFormat: LibraryImportExportCoordinator.ExportFormat,
        exportRootPathHint: String,
        onConfirm: (Int, LibraryImportExportCoordinator.ExportFormat) -> Unit
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_export_options, null)
        val input = dialogView.findViewById<TextInputEditText>(R.id.export_thread_input).apply {
            setText(formatInt(defaultThreads))
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val formatGroup = dialogView.findViewById<RadioGroup>(R.id.export_format_group)
        val imageDirRadio = dialogView.findViewById<RadioButton>(R.id.export_format_images)
        val cbzRadio = dialogView.findViewById<RadioButton>(R.id.export_format_cbz)
        val pdfRadio = dialogView.findViewById<RadioButton>(R.id.export_format_pdf)
        formatGroup.check(
            when (defaultExportFormat) {
                LibraryImportExportCoordinator.ExportFormat.IMAGE_DIR -> imageDirRadio.id
                LibraryImportExportCoordinator.ExportFormat.CBZ -> cbzRadio.id
                LibraryImportExportCoordinator.ExportFormat.PDF -> pdfRadio.id
            }
        )
        dialogView.findViewById<TextView>(R.id.export_path_hint).apply {
            text = context.getString(R.string.export_path_hint_format, exportRootPathHint)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.export_options_title)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val threadCount = input.text?.toString()?.toIntOrNull()
                if (threadCount == null || threadCount !in 1..16) {
                    Toast.makeText(context, R.string.export_thread_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val exportFormat = when (formatGroup.checkedRadioButtonId) {
                    cbzRadio.id -> LibraryImportExportCoordinator.ExportFormat.CBZ
                    pdfRadio.id -> LibraryImportExportCoordinator.ExportFormat.PDF
                    else -> LibraryImportExportCoordinator.ExportFormat.IMAGE_DIR
                }
                onConfirm(threadCount, exportFormat)
            }
            .show()
    }

    fun confirmDeleteSelectedImages(
        context: Context,
        selectedCount: Int,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.delete_selected)
            .setMessage(context.getString(R.string.delete_images_confirm, selectedCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_selected) { _, _ -> onConfirm() }
            .showWithScrollableMessage()
    }

    fun confirmDeleteSelectedFolders(
        context: Context,
        selectedCount: Int,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.delete_selected)
            .setMessage(context.getString(R.string.delete_chapters_confirm, selectedCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_selected) { _, _ -> onConfirm() }
            .showWithScrollableMessage()
    }

    fun confirmDeleteSelectedLibraryFolders(
        context: Context,
        selectedCount: Int,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.delete_selected)
            .setMessage(context.getString(R.string.delete_folders_confirm, selectedCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_selected) { _, _ -> onConfirm() }
            .showWithScrollableMessage()
    }
}
