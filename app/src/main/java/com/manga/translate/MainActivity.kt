package com.manga.translate

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.manga.translate.BuildConfig
import com.google.android.material.tabs.TabLayoutMediator
import com.manga.translate.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: MainPagerAdapter
    private lateinit var crashStateStore: CrashStateStore
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        crashStateStore = CrashStateStore(this)

        pagerAdapter = MainPagerAdapter(this)
        binding.mainPager.adapter = pagerAdapter
        binding.mainPager.isUserInputEnabled = true
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

    private fun checkForUpdate() {
        if (hasCheckedUpdate) return
        hasCheckedUpdate = true
        lifecycleScope.launch {
            val updateInfo = UpdateChecker.fetchUpdateInfo()
            if (updateInfo == null) return@launch
            if (!isNewerVersion(updateInfo.versionName, BuildConfig.VERSION_NAME)) return@launch
            if (isFinishing || isDestroyed) return@launch
            showUpdateDialog(updateInfo)
        }
    }

    private fun showUpdateDialog(updateInfo: UpdateInfo) {
        val versionLabel = buildVersionLabel(updateInfo)
        val message = if (updateInfo.changelog.isNotBlank()) {
            getString(R.string.update_dialog_message, updateInfo.changelog)
        } else {
            getString(R.string.update_dialog_message_default)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_dialog_title, versionLabel))
            .setMessage(message)
            .setNegativeButton(R.string.update_dialog_cancel, null)
            .setPositiveButton(R.string.update_dialog_download) { _, _ ->
                startDownload(updateInfo)
            }
            .setNeutralButton(R.string.update_dialog_open_release) { _, _ ->
                openUrl(RELEASES_URL)
            }
            .show()
    }

    private fun startDownload(updateInfo: UpdateInfo) {
        val versionLabel = buildVersionLabel(updateInfo)
        val safeVersion = versionLabel.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fileName = Uri.parse(updateInfo.apkUrl).lastPathSegment
            ?: "manga-translator-$safeVersion.apk"
        val request = DownloadManager.Request(Uri.parse(updateInfo.apkUrl))
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

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppLogger.log("MainActivity", "No activity to open url: $url", e)
        }
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
        private const val RELEASES_URL = "https://github.com/jedzqer/manga-translator/releases"
        private var hasCheckedUpdate = false
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        return compareVersionName(remote, local) > 0
    }

    private fun compareVersionName(left: String, right: String): Int {
        val leftParts = extractVersionParts(left)
        val rightParts = extractVersionParts(right)
        val maxSize = maxOf(leftParts.size, rightParts.size)
        for (i in 0 until maxSize) {
            val l = leftParts.getOrElse(i) { 0 }
            val r = rightParts.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
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

    private fun extractVersionFromUrl(url: String): String? {
        return Regex("(\\d+\\.\\d+\\.\\d+)").find(url)?.value
    }

    private fun isMoreSpecificVersion(candidate: String, current: String): Boolean {
        return candidate.count { it == '.' } > current.count { it == '.' }
    }

    private fun extractVersionParts(version: String): List<Int> {
        val matcher = Regex("\\d+").findAll(version)
        val parts = mutableListOf<Int>()
        for (match in matcher) {
            parts.add(match.value.toIntOrNull() ?: 0)
        }
        return parts
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
