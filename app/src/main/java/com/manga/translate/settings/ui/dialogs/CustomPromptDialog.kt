package com.manga.translate.settings.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.manga.translate.R
import com.manga.translate.di.appContainer
import com.manga.translate.settings.CustomPromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PromptEditorState : ViewModel() {
    var mode = 0
    val drafts = mutableMapOf<Int, String>()
    var importMode: Int? = null
    var exportText: String? = null
}

class CustomPromptDialog : DialogFragment() {
    private val state by viewModels<PromptEditorState>()
    private var editor: EditText? = null
    private val store get() = requireContext().appContainer.customPromptStore
    private val asset get() = CustomPromptStore.assets[state.mode]

    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val mode = state.importMode
        state.importMode = null
        if (uri != null && mode != null) lifecycleScope.launch {
            val context = requireContext()
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        val bytes = it.readBytesBounded()
                        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    } ?: error("Cannot open document")
                    CustomPromptStore.validate(text, store.builtIn(context, CustomPromptStore.assets[mode]), preserveDocumentation = true)
                }
            }.onSuccess {
                state.drafts[mode] = it
                if (mode == state.mode) editor?.setText(it)
            }.onFailure { showError() }
        }
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = state.exportText
        state.exportText = null
        if (uri != null && text != null) lifecycleScope.launch {
            val context = requireContext()
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(text)
                    } ?: error("Cannot open document")
                }
            }.onFailure { showError() }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val content = layoutInflater.inflate(R.layout.dialog_custom_prompt, null)
        editor = content.findViewById<EditText>(R.id.prompt_editor).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(state.drafts[state.mode] ?: store.readForEditing(context, asset))
        }
        val spinner = content.findViewById<Spinner>(R.id.prompt_mode)
        spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.custom_prompt_modes))
        spinner.setSelection(state.mode)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == state.mode) return
                state.drafts[state.mode] = editor?.text.toString()
                state.mode = position
                editor?.setText(state.drafts[position] ?: store.readForEditing(context, asset))
            }
        }
        content.findViewById<View>(R.id.prompt_import).setOnClickListener {
            state.importMode = state.mode
            importDocument.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
        }
        content.findViewById<View>(R.id.prompt_export).setOnClickListener {
            runCatching {
                val valid = CustomPromptStore.validate(editor?.text.toString(), store.builtIn(context, asset), preserveDocumentation = true)
                state.exportText = valid
                exportDocument.launch(asset.substringAfterLast('/'))
            }.onFailure { showError() }
        }
        content.findViewById<View>(R.id.prompt_reset).setOnClickListener {
            val selectedMode = state.mode
            val selectedAsset = asset
            AlertDialog.Builder(context).setMessage(R.string.custom_prompt_reset_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        runCatching { withContext(Dispatchers.IO) {
                            store.reset(context, selectedAsset)
                            store.builtIn(context, selectedAsset)
                        } }
                            .onSuccess { builtIn ->
                                state.drafts.remove(selectedMode)
                                if (state.mode == selectedMode) editor?.setText(builtIn)
                            }.onFailure { showError() }
                    }
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        return AlertDialog.Builder(context).setTitle(R.string.custom_prompt_title)
            .setView(content).setPositiveButton(R.string.custom_prompt_save, null)
            .setNegativeButton(android.R.string.cancel, null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val selectedAsset = asset
                        val text = editor?.text.toString()
                        lifecycleScope.launch {
                            runCatching { withContext(Dispatchers.IO) { store.save(context, selectedAsset, text) } }
                                .onSuccess {
                                    Toast.makeText(context, R.string.custom_prompt_saved, Toast.LENGTH_SHORT).show()
                                }.onFailure { showError() }
                        }
                    }
                }
            }
    }

    override fun onDestroyView() {
        editor?.let { state.drafts[state.mode] = it.text.toString() }
        editor = null
        super.onDestroyView()
    }

    private fun showError() {
        if (isAdded) AlertDialog.Builder(requireContext()).setMessage(R.string.custom_prompt_error)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= CustomPromptStore.MAX_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
