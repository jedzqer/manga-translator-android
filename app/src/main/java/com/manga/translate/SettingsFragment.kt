package com.manga.translate

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.manga.translate.databinding.FragmentSettingsBinding

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

        binding.saveButton.setOnClickListener {
            val url = binding.apiUrlInput.text?.toString()?.trim().orEmpty()
            val key = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
            val model = binding.modelNameInput.text?.toString()?.trim().orEmpty()
            settingsStore.save(ApiSettings(url, key, model))
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }

        binding.viewLogsButton.setOnClickListener {
            showLogsDialog()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showLogsDialog() {
        val logs = AppLogger.readLogs().ifBlank { getString(R.string.logs_empty) }
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
            .setTitle(R.string.logs_title)
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
}
