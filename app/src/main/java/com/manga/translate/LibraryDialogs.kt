package com.manga.translate

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

internal class LibraryDialogs {
    private fun formatInt(value: Int): String = String.format(Locale.getDefault(), "%d", value)

    fun showCreateFolderDialog(context: Context, onConfirm: (String) -> Unit) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.folder_name_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.create_folder)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirm(input.text?.toString().orEmpty())
            }
            .show()
    }

    fun confirmDeleteFolder(context: Context, folderName: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.folder_delete)
            .setMessage(context.getString(R.string.folder_delete_confirm, folderName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.folder_delete) { _, _ -> onConfirm() }
            .show()
    }

    fun showFullTranslateInfo(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.folder_full_translate_info_title)
            .setMessage(context.getString(R.string.folder_full_translate_info))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showLanguageSettingDialog(
        context: Context,
        currentLanguage: TranslationLanguage,
        onSelected: (TranslationLanguage) -> Unit
    ) {
        val languages = TranslationLanguage.values()
        val languageNames = languages.map { context.getString(it.displayNameResId) }.toTypedArray()
        val currentIndex = languages.indexOf(currentLanguage)

        AlertDialog.Builder(context)
            .setTitle(R.string.folder_language_setting_title)
            .setSingleChoiceItems(languageNames, currentIndex) { dialog, which ->
                onSelected(languages[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showFixedLanguageDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.folder_language_setting_title)
            .setSingleChoiceItems(
                arrayOf(context.getString(R.string.folder_language_to_zh)),
                0
            ) { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showApiErrorDialog(context: Context, errorCode: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.api_request_failed_title)
            .setMessage(context.getString(R.string.api_request_failed_message, errorCode))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showModelErrorDialog(context: Context, responseContent: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.model_response_failed_title)
            .setMessage(context.getString(R.string.model_response_failed_message, responseContent))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showEhViewerSubfolderPicker(
        context: Context,
        folders: List<DocumentFile>,
        onPicked: (DocumentFile) -> Unit
    ) {
        val names = folders.map { it.name ?: "未命名" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.ehviewer_select_folder)
            .setItems(names) { _, index -> onPicked(folders[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showEhViewerImportNameDialog(
        context: Context,
        defaultName: String,
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.folder_name_hint)
            setText(defaultName)
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.ehviewer_import_name_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(context, R.string.folder_create_failed, Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(name)
                }
            }
            .show()
    }

    fun showExportSuccessDialog(context: Context, path: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.export_success_title)
            .setMessage(context.getString(R.string.export_success_message, path))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showExportOptionsDialog(
        context: Context,
        defaultThreads: Int,
        defaultExportAsCbz: Boolean,
        hasEmbeddedImages: Boolean,
        exportRootPathHint: String,
        onConfirm: (Int, Boolean, Boolean) -> Unit
    ) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.export_thread_hint)
            setText(formatInt(defaultThreads))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val cbzCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.export_as_cbz_option)
            isChecked = defaultExportAsCbz
        }
        val embeddedCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.export_embedded_images_option)
            isChecked = hasEmbeddedImages
            isEnabled = hasEmbeddedImages
            if (!hasEmbeddedImages) {
                alpha = 0.5f
            }
        }
        val pathHintView = TextView(context).apply {
            val topMargin = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                8f,
                context.resources.displayMetrics
            ).toInt()
            setPadding(0, topMargin, 0, 0)
            text = context.getString(R.string.export_path_hint_format, exportRootPathHint)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val side = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                20f,
                context.resources.displayMetrics
            ).toInt()
            val vertical = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                12f,
                context.resources.displayMetrics
            ).toInt()
            setPadding(side, vertical, side, vertical)
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                cbzCheckBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                embeddedCheckBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                pathHintView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.export_options_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val threadCount = input.text?.toString()?.toIntOrNull()
                if (threadCount == null || threadCount !in 1..16) {
                    Toast.makeText(context, R.string.export_thread_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onConfirm(threadCount, cbzCheckBox.isChecked, embeddedCheckBox.isChecked)
            }
            .show()
    }

    fun showEmbedOptionsDialog(
        context: Context,
        defaultThreads: Int,
        defaultUseWhiteBubbleCover: Boolean,
        defaultUseEllipseLimit: Boolean,
        defaultUseImageRepair: Boolean,
        onConfirm: (Int, Boolean, Boolean, Boolean) -> Unit
    ) {
        val note = TextView(context).apply {
            text = context.getString(R.string.embed_thread_note)
        }
        val input = EditText(context).apply {
            hint = context.getString(R.string.embed_thread_hint)
            setText(formatInt(defaultThreads))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val whiteCoverCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.embed_white_cover_option)
            isChecked = defaultUseWhiteBubbleCover
        }
        val ellipseLimitCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.embed_ellipse_limit_option)
            isChecked = defaultUseEllipseLimit
            isEnabled = defaultUseWhiteBubbleCover
            alpha = if (defaultUseWhiteBubbleCover) 1f else 0.5f
        }
        val imageRepairCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.embed_image_repair_option)
            isChecked = defaultUseImageRepair
        }
        whiteCoverCheckBox.setOnCheckedChangeListener { _, isChecked ->
            ellipseLimitCheckBox.isEnabled = isChecked
            ellipseLimitCheckBox.alpha = if (isChecked) 1f else 0.5f
            if (!isChecked) {
                ellipseLimitCheckBox.isChecked = false
            }
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val side = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                20f,
                context.resources.displayMetrics
            ).toInt()
            val vertical = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                12f,
                context.resources.displayMetrics
            ).toInt()
            setPadding(side, vertical, side, vertical)
            addView(
                note,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        8f,
                        context.resources.displayMetrics
                    ).toInt()
                }
            )
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                whiteCoverCheckBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        10f,
                        context.resources.displayMetrics
                    ).toInt()
                }
            )
            addView(
                ellipseLimitCheckBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                imageRepairCheckBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.embed_options_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val threadCount = input.text?.toString()?.toIntOrNull()
                if (threadCount == null || threadCount !in 1..16) {
                    Toast.makeText(context, R.string.embed_thread_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onConfirm(
                    threadCount,
                    whiteCoverCheckBox.isChecked,
                    ellipseLimitCheckBox.isChecked,
                    imageRepairCheckBox.isChecked
                )
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
            .show()
    }
}
