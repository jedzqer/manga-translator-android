package com.manga.translate

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.Toast
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayoutMediator
import com.manga.translate.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: MainPagerAdapter
    private lateinit var crashStateStore: CrashStateStore
    private lateinit var updateIgnoreStore: UpdateIgnoreStore
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeMode = SettingsStore(this).loadThemeMode()
        if (themeMode == ThemeMode.PINK) {
            setTheme(R.style.Theme_MangaTranslator_Pink)
        }
        AppCompatDelegate.setDefaultNightMode(themeMode.nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        crashStateStore = CrashStateStore(this)
        updateIgnoreStore = UpdateIgnoreStore(this)

        pagerAdapter = MainPagerAdapter(this)
        binding.mainPager.adapter = pagerAdapter
        binding.mainPager.isUserInputEnabled =
            binding.mainPager.currentItem != MainPagerAdapter.READING_INDEX
        TabLayoutMediator(binding.mainTabs, binding.mainPager) { tab, position ->
            tab.setText(pagerAdapter.getTitleRes(position))
        }.attach()
        binding.mainPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.mainPager.isUserInputEnabled = position != MainPagerAdapter.READING_INDEX
            }
        })
        requestNotificationPermissionIfNeeded()
        maybeShowCrashDialog()
        checkForUpdate()
    }

    fun switchToTab(index: Int) {
        binding.mainPager.setCurrentItem(index, true)
    }

    fun setPagerSwipeEnabled(enabled: Boolean) {
        binding.mainPager.isUserInputEnabled = enabled
    }

    private fun checkForUpdate() {
        if (hasCheckedUpdate) return
        hasCheckedUpdate = true
        lifecycleScope.launch {
            val updateInfo = UpdateChecker.fetchUpdateInfo()
            if (updateInfo == null) return@launch
            AppLogger.log(
                "UpdateChecker",
                "Local version=${VersionInfo.VERSION_NAME} (${VersionInfo.VERSION_CODE}), " +
                    "remote version=${updateInfo.versionName} (${updateInfo.versionCode})"
            )
            if (!isNewerVersion(updateInfo)) return@launch
            if (updateIgnoreStore.isIgnored(updateInfo.versionCode)) return@launch
            if (isFinishing || isDestroyed) return@launch
            showUpdateDialog(updateInfo)
        }
    }

    fun showUpdateDialog(
        updateInfo: UpdateInfo,
        showIgnoreButton: Boolean = true,
        titleOverride: String? = null
    ) {
        val versionLabel = buildVersionLabel(updateInfo)
        val dialogView = layoutInflater.inflate(R.layout.dialog_update, null)
        val contentView = dialogView.findViewById<TextView>(R.id.update_dialog_content)
        val message = buildUpdateDialogMessage(updateInfo, versionLabel, contentView.currentTextColor)
        contentView.text = message
        val builder = AlertDialog.Builder(this)
            .setTitle(titleOverride ?: getString(R.string.update_dialog_title, versionLabel))
            .setView(dialogView)
            .setNegativeButton(R.string.update_dialog_cancel, null)
            .setPositiveButton(R.string.update_dialog_download) { _, _ ->
                startDownload(updateInfo)
            }
        if (showIgnoreButton) {
            builder.setNeutralButton(R.string.update_dialog_ignore) { _, _ ->
                updateIgnoreStore.saveIgnoredVersionCode(updateInfo.versionCode)
            }
        }
        builder.show()
    }

    private fun startDownload(updateInfo: UpdateInfo) {
        val downloadUrl = resolveDownloadUrl(updateInfo.apkUrl)
        val versionLabel = buildVersionLabel(updateInfo)
        val safeVersion = versionLabel.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fileName = Uri.parse(downloadUrl).lastPathSegment
            ?: "manga-translator-$safeVersion.apk"
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle(getString(R.string.update_download_title, versionLabel))
            .setDescription(getString(R.string.update_download_description, versionLabel))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadManager = getSystemService(DownloadManager::class.java)
        if (downloadManager == null) {
            AppLogger.log("MainActivity", "DownloadManager not available")
            return
        }
        downloadManager.enqueue(request)
    }

    private fun resolveDownloadUrl(apkUrl: String): String {
        val normalizedUrl = apkUrl.trim()
        val githubUrl = normalizedUrl
            .replace(
                "https://gitee.com/jedzqer/manga-translator/releases/download/",
                "https://github.com/jedzqer/manga-translator/releases/download/"
            )
        val giteeUrl = normalizedUrl
            .replace(
                "https://github.com/jedzqer/manga-translator/releases/download/",
                "https://gh-proxy.com/https://github.com/jedzqer/manga-translator/releases/download/"
            )
        val source = SettingsStore(this).loadLinkSource()
        return if (source == LinkSource.GITHUB) githubUrl else giteeUrl
    }

    private fun maybeShowCrashDialog() {
        if (!crashStateStore.wasCrashedLastRun()) return
        crashStateStore.clearCrashFlag()
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_dialog_title)
            .setMessage(R.string.crash_dialog_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.crash_dialog_share) { _, _ ->
                shareLatestLog()
            }
            .show()
    }

    private fun shareLatestLog() {
        val latest = AppLogger.listLogFiles().firstOrNull()
        if (latest == null || !latest.exists()) {
            AppLogger.log("MainActivity", "No crash logs available to share")
            Toast.makeText(this, R.string.logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            latest
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, getString(R.string.crash_dialog_share))
        val manager = packageManager
        if (chooser.resolveActivity(manager) != null) {
            startActivity(chooser)
        } else {
            AppLogger.log("MainActivity", "No activity to share crash logs")
        }
    }

    companion object {
        private var hasCheckedUpdate = false
    }

    private fun isNewerVersion(updateInfo: UpdateInfo): Boolean {
        val remoteCode = updateInfo.versionCode
        if (remoteCode <= 0) return false
        return remoteCode > VersionInfo.VERSION_CODE
    }

    private fun buildVersionLabel(updateInfo: UpdateInfo): String {
        val versionName = updateInfo.versionName.trim()
        if (versionName.isNotBlank()) {
            val urlVersion = extractVersionFromUrl(updateInfo.apkUrl)
            if (urlVersion != null && isMoreSpecificVersion(urlVersion, versionName)) {
                return urlVersion
            }
            return versionName
        }
        return if (updateInfo.versionCode > 0) updateInfo.versionCode.toString() else "unknown"
    }

    fun isRemoteNewer(updateInfo: UpdateInfo): Boolean {
        return isNewerVersion(updateInfo)
    }

    private fun buildUpdateDialogMessage(
        updateInfo: UpdateInfo,
        versionLabel: String,
        textColor: Int
    ): CharSequence {
        val latestChangelog = updateInfo.changelog.trim()
        if (latestChangelog.isBlank() && updateInfo.history.isEmpty()) {
            return getString(R.string.update_dialog_message_default)
        }
        val builder = SpannableStringBuilder()
        builder.append(getString(R.string.update_dialog_latest_header, versionLabel)).append('\n')
        if (latestChangelog.isNotBlank()) {
            builder.append(latestChangelog).append('\n')
        }
        builder.append('\n')
            .append(getString(R.string.update_dialog_tutorial_tip))
            .append('\n')
        val history = updateInfo.history.filterNot {
            it.versionName.equals(versionLabel, ignoreCase = true)
        }
        if (history.isNotEmpty()) {
            builder.append('\n')
                .append(getString(R.string.update_dialog_history_header))
                .append('\n')
            history.forEach { entry ->
                builder.append('\n')
                    .append(entry.versionName)
                if (entry.releasedAt.isNotBlank()) {
                    builder.append(' ')
                    appendDimmedText(builder, entry.releasedAt, textColor)
                }
                builder.append('\n')
                    .append(entry.changelog.trim())
                    .append('\n')
            }
        }
        return builder
    }

    private fun appendDimmedText(
        builder: SpannableStringBuilder,
        text: String,
        textColor: Int
    ) {
        val start = builder.length
        builder.append(text)
        val end = builder.length
        builder.setSpan(
            ForegroundColorSpan(adjustAlpha(textColor, 0.6f)),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            RelativeSizeSpan(0.85f),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (android.graphics.Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun extractVersionFromUrl(url: String): String? {
        return Regex("(\\d+\\.\\d+\\.\\d+)").find(url)?.value
    }

    private fun isMoreSpecificVersion(candidate: String, current: String): Boolean {
        return candidate.count { it == '.' } > current.count { it == '.' }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        if (shouldShowRequestPermissionRationale(permission)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    notificationPermissionLauncher.launch(permission)
                }
                .show()
        } else {
            notificationPermissionLauncher.launch(permission)
        }
    }
}
