package com.manga.translate.app

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayoutMediator
import com.manga.translate.R
import com.manga.translate.background.TranslationKeepAliveService
import com.manga.translate.databinding.ActivityMainBinding
import com.manga.translate.di.appContainer
import com.manga.translate.model.LinkSource
import com.manga.translate.model.ThemeMode
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.GlobalTaskProgressState
import com.manga.translate.platform.GlobalTaskProgressStage
import com.manga.translate.platform.GlobalTaskProgressStore
import com.manga.translate.platform.showWithScrollableMessage
import com.manga.translate.theming.CustomThemeUiApplier
import com.manga.translate.theming.ThemePaletteRuntime
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: MainPagerAdapter
    private val appContainer by lazy(LazyThreadSafetyMode.NONE) { applicationContext.appContainer }
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.settingsStore }
    private val crashStateStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.crashStateStore }
    private val updateIgnoreStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.updateIgnoreStore }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressHideJob: Job? = null
    private val hideGlobalProgressRunnable = Runnable {
        if (!::binding.isInitialized) return@Runnable
        binding.globalProgressCard.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                binding.globalProgressCard.visibility = android.view.View.GONE
            }
            .start()
    }
    private val delayedUpdateCheckRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            checkForUpdate()
        }
    }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeMode = settingsStore.loadThemeMode()
        if (themeMode == ThemeMode.PASTEL) {
            setTheme(R.style.Theme_MangaTranslator_Pastel)
        } else if (themeMode == ThemeMode.DEEP_SEA) {
            setTheme(R.style.Theme_MangaTranslator_DeepSea)
        } else if (themeMode == ThemeMode.CUSTOM) {
            val palette = ThemePaletteRuntime.activate(settingsStore.loadCustomThemeColors())
            setTheme(
                if (palette.isDark) R.style.Theme_MangaTranslator_Custom_Dark
                else R.style.Theme_MangaTranslator_Custom_Light
            )
        } else {
            ThemePaletteRuntime.clear()
        }
        AppCompatDelegate.setDefaultNightMode(themeMode.nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    fragment: Fragment,
                    view: View,
                    savedInstanceState: Bundle?
                ) {
                    CustomThemeUiApplier.apply(view)
                }
            },
            true
        )
        CustomThemeUiApplier.applyToActivity(this)

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
        observeGlobalProgress()
        requestNotificationPermissionIfNeeded()
        maybeShowCrashDialog()
        scheduleUpdateCheck()
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    fun switchToTab(index: Int) {
        binding.mainPager.setCurrentItem(index, true)
    }

    fun setPagerSwipeEnabled(enabled: Boolean) {
        binding.mainPager.isUserInputEnabled = enabled
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(TranslationKeepAliveService.EXTRA_OPEN_LIBRARY_TAB, false) == true) {
            switchToTab(MainPagerAdapter.LIBRARY_INDEX)
            intent.removeExtra(TranslationKeepAliveService.EXTRA_OPEN_LIBRARY_TAB)
        }
    }

    private fun checkForUpdate() {
        if (hasCheckedUpdate) return
        hasCheckedUpdate = true
        lifecycleScope.launch {
            val updateInfo = UpdateChecker.fetchUpdateInfo(
                includePreview = updateIgnoreStore.loadAcceptPreviewUpdates(),
                languageKey = UpdateChecker.resolveChangelogLanguageKey(this@MainActivity)
            )
            if (updateInfo == null) return@launch
            latestUpdateInfo = updateInfo
            AppLogger.log(
                "UpdateChecker",
                "Local version=${VersionInfo.VERSION_NAME} (${VersionInfo.VERSION_CODE}), " +
                    "remote version=${updateInfo.versionName} (${updateInfo.versionCode}, ${updateInfo.releaseChannel})"
            )
            if (!isNewerVersion(updateInfo)) return@launch
            if (updateIgnoreStore.isIgnored(updateInfo.versionCode)) return@launch
            if (isFinishing || isDestroyed) return@launch
            showUpdateDialog(updateInfo)
        }
    }

    private fun scheduleUpdateCheck() {
        mainHandler.postDelayed(delayedUpdateCheckRunnable, UPDATE_CHECK_DELAY_MS)
    }

    fun showUpdateDialog(
        updateInfo: UpdateInfo,
        showIgnoreButton: Boolean = true,
        titleOverride: String? = null
    ) {
        val versionLabel = buildVersionLabel(updateInfo)
        val dialogView = layoutInflater.inflate(R.layout.dialog_update, null)
        val contentScrollView = dialogView.findViewById<NestedScrollView>(R.id.update_dialog_scroll)
        val latestTitleView = dialogView.findViewById<TextView>(R.id.update_dialog_latest_title)
        val latestContentView = dialogView.findViewById<TextView>(R.id.update_dialog_latest_content)
        val historyContainer = dialogView.findViewById<android.view.View>(R.id.update_dialog_history_container)
        val historyContentView = dialogView.findViewById<TextView>(R.id.update_dialog_history_content)
        val previewSwitch = dialogView.findViewById<SwitchMaterial>(R.id.update_dialog_preview_switch)
        val actionsContainer = dialogView.findViewById<View>(R.id.update_dialog_actions)
        val negativeButton = dialogView.findViewById<AppCompatButton>(R.id.update_dialog_negative_button)
        val neutralButton = dialogView.findViewById<AppCompatButton>(R.id.update_dialog_neutral_button)
        val positiveButton = dialogView.findViewById<AppCompatButton>(R.id.update_dialog_positive_button)
        latestTitleView.text = getString(
            if (updateInfo.releaseChannel == ReleaseChannel.PREVIEW) {
                R.string.update_dialog_latest_header_preview
            } else {
                R.string.update_dialog_latest_header
            },
            versionLabel
        )
        latestContentView.text = buildLatestUpdateDialogMessage(updateInfo)
        previewSwitch.isChecked = updateIgnoreStore.loadAcceptPreviewUpdates()
        previewSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateIgnoreStore.saveAcceptPreviewUpdates(isChecked)
        }
        val historyMessage = buildHistoryUpdateDialogMessage(updateInfo, versionLabel)
        historyContainer.visibility = if (historyMessage.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        historyContentView.text = historyMessage
        val dialog = AlertDialog.Builder(this)
            .setTitle(titleOverride ?: getString(R.string.update_dialog_title, versionLabel))
            .setView(dialogView)
            .create()
        styleUpdateDialogButton(negativeButton)
        styleUpdateDialogButton(neutralButton)
        styleUpdateDialogButton(positiveButton)
        actionsContainer.visibility = View.VISIBLE
        negativeButton.text = getString(
            if (showIgnoreButton) R.string.update_dialog_ignore else R.string.update_dialog_cancel
        )
        negativeButton.setOnClickListener {
            if (showIgnoreButton) {
                updateIgnoreStore.saveIgnoredVersionCode(updateInfo.versionCode)
            }
            dialog.dismiss()
        }
        neutralButton.setOnClickListener {
            openProjectPage()
            dialog.dismiss()
        }
        positiveButton.setOnClickListener {
            dialog.dismiss()
            startDownload(updateInfo)
        }
        dialog.show()
        val screenHeight = resources.displayMetrics.heightPixels
        val maxContentHeight = (screenHeight * 0.5f).toInt()
        contentScrollView.post {
            val measuredHeight = contentScrollView.getChildAt(0)?.measuredHeight ?: 0
            val targetHeight = measuredHeight.coerceAtMost(maxContentHeight)
            contentScrollView.layoutParams = contentScrollView.layoutParams.apply {
                height = targetHeight
            }
            contentScrollView.requestLayout()
            actionsContainer.requestLayout()
        }
    }

    private fun styleUpdateDialogButton(button: AppCompatButton) {
        button.background = ContextCompat.getDrawable(this, R.drawable.bg_button_rounded)
        button.setTextColor(resolveThemeButtonTextColor())
        button.isAllCaps = false
        button.visibility = View.VISIBLE
    }

    private fun resolveThemeButtonTextColor(): Int {
        return when (settingsStore.loadThemeMode()) {
            ThemeMode.PASTEL -> ContextCompat.getColor(this, R.color.pastel_button_text)
            ThemeMode.DEEP_SEA -> ContextCompat.getColor(this, R.color.deep_sea_button_text)
            ThemeMode.CUSTOM -> ThemePaletteRuntime.customPalette?.buttonText
                ?: ContextCompat.getColor(this, R.color.light_button_text)
            else -> if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            ) {
                ContextCompat.getColor(this, R.color.dark_button_text)
            } else {
                ContextCompat.getColor(this, R.color.light_button_text)
            }
        }
    }

    private fun startDownload(updateInfo: UpdateInfo) {
        val downloadUrl = resolveDownloadUrl(updateInfo.apkUrl)
        val versionLabel = buildVersionLabel(updateInfo)
        val safeVersion = versionLabel.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fileName = downloadUrl.toUri().lastPathSegment
            ?: "manga-translator-$safeVersion.apk"
        val request = DownloadManager.Request(downloadUrl.toUri())
            .setTitle(getString(R.string.update_download_title, versionLabel))
            .setDescription(getString(R.string.update_download_description, versionLabel))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadManager = getSystemService(DownloadManager::class.java)
        if (downloadManager == null) {
            AppLogger.log("MainActivity", "DownloadManager not available")
            Toast.makeText(this, R.string.update_download_failed_manual_tip, Toast.LENGTH_LONG).show()
            return
        }
        try {
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            AppLogger.log("MainActivity", "Failed to enqueue update download", e)
            Toast.makeText(this, R.string.update_download_failed_manual_tip, Toast.LENGTH_LONG).show()
        }
    }

    private fun resolveDownloadUrl(apkUrl: String): String {
        val normalizedUrl = apkUrl.trim()
        val githubUrl = normalizedUrl
            .replace(
                "https://gitee.com/jedzqer/manga-translator/releases/download/",
                "https://github.com/jedzqer/manga-translator-android/releases/download/"
            )
        val giteeUrl = normalizedUrl
            .replace(
                "https://github.com/jedzqer/manga-translator-android/releases/download/",
                "https://gh-proxy.com/https://github.com/jedzqer/manga-translator-android/releases/download/"
            )
        val source = settingsStore.loadLinkSource()
        return if (source == LinkSource.GITHUB) githubUrl else giteeUrl
    }

    private fun openProjectPage() {
        val intent = Intent(Intent.ACTION_VIEW, PROJECT_URL.toUri())
        val manager = packageManager
        if (intent.resolveActivity(manager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, PROJECT_URL, Toast.LENGTH_SHORT).show()
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
            .showWithScrollableMessage()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(hideGlobalProgressRunnable)
        mainHandler.removeCallbacks(delayedUpdateCheckRunnable)
        progressHideJob?.cancel()
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
        @Volatile
        private var latestUpdateInfo: UpdateInfo? = null
        private const val UPDATE_CHECK_DELAY_MS = 2_000L
        private const val PROJECT_URL = "https://github.com/jedzqer/manga-translator"

        fun getLatestUpdateInfo(): UpdateInfo? = latestUpdateInfo
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

    private fun buildLatestUpdateDialogMessage(updateInfo: UpdateInfo): String {
        val latestChangelog = updateInfo.changelog.trim()
        if (latestChangelog.isBlank()) {
            return getString(R.string.update_dialog_message_default)
        }
        return latestChangelog
    }

    private fun buildHistoryUpdateDialogMessage(
        updateInfo: UpdateInfo,
        versionLabel: String
    ): String {
        val history = updateInfo.history.filterNot {
            it.versionName.equals(versionLabel, ignoreCase = true)
        }
        if (history.isEmpty()) return ""
        val builder = StringBuilder()
        history.forEachIndexed { index, entry ->
            if (index > 0) {
                builder.append("\n\n")
            }
            builder.append(entry.versionName)
            if (entry.releaseChannel == ReleaseChannel.PREVIEW) {
                builder.append(getString(R.string.update_dialog_preview_suffix))
            }
            if (entry.releasedAt.isNotBlank()) {
                builder.append("  ").append(entry.releasedAt)
            }
            val changelog = entry.changelog.trim()
            if (changelog.isNotBlank()) {
                builder.append('\n').append(changelog)
            }
        }
        return builder.toString()
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
                .showWithScrollableMessage()
        } else {
            notificationPermissionLauncher.launch(permission)
        }
    }

    private fun observeGlobalProgress() {
        progressHideJob?.cancel()
        progressHideJob = lifecycleScope.launch {
            GlobalTaskProgressStore.state.collect { state ->
                renderGlobalProgress(state)
            }
        }
    }

    private fun renderGlobalProgress(state: GlobalTaskProgressState) {
        mainHandler.removeCallbacks(hideGlobalProgressRunnable)
        if (!state.visible) {
            hideGlobalProgressRunnable.run()
            return
        }

        binding.globalProgressText.text = buildCompactProgressText(state)
        val textColor = if (state.error) {
            ContextCompat.getColor(this, android.R.color.holo_red_dark)
        } else {
            resolveColorAttr(R.attr.buttonTextColor)
        }
        binding.globalProgressText.setTextColor(textColor)

        if (binding.globalProgressCard.visibility != android.view.View.VISIBLE) {
            binding.globalProgressCard.alpha = 0f
            binding.globalProgressCard.visibility = android.view.View.VISIBLE
            binding.globalProgressCard.animate().alpha(1f).setDuration(180L).start()
        } else {
            binding.globalProgressCard.alpha = 1f
        }

        if (state.terminal) {
            mainHandler.postDelayed(hideGlobalProgressRunnable, 2200L)
        }
    }

    private fun buildCompactProgressText(state: GlobalTaskProgressState): String {
        val label = when {
            state.title.contains(getString(R.string.global_progress_export)) ->
                getString(R.string.global_progress_export)
            else -> getString(R.string.global_progress_translate)
        }
        if (state.terminal) {
            if (state.detail == getString(R.string.translation_canceled)) {
                return state.detail
            }
            return if (state.error) {
                getString(R.string.global_progress_failed, label)
            } else {
                getString(R.string.global_progress_done, label)
            }
        }

        // Prefer detailed progress whenever the task layer publishes it. The
        // stage shortcuts below were hiding counts for OCR/glossary/preparing.
        val progress = extractProgress(state)
        if (progress != null) {
            val compactDetail = state.detail.substringBefore("  ").trim()
            if (compactDetail.isNotBlank()) {
                return compactDetail
            }
            state.failedCount?.let { failedCount ->
                return getString(
                    R.string.folder_translation_processed,
                    progress.first,
                    progress.second,
                    failedCount
                )
            }
            return getString(R.string.global_progress_count, label, progress.first, progress.second)
        }

        // Status-only updates (for example "正在取消翻译…", a raw OCR
        // stage string, or "准备翻译 · 共 N 页") are already compact; show
        // them instead of replacing them with a generic stage label.
        val compactDetail = state.detail.substringBefore("  ").trim()
        if (compactDetail.isNotBlank()) {
            return compactDetail
        }

        when (state.stage) {
            GlobalTaskProgressStage.PREPARING_TRANSLATION ->
                return getString(R.string.global_progress_preparing_translation)
            GlobalTaskProgressStage.DETECTING_REGIONS ->
                return getString(R.string.detecting_bubbles)
            GlobalTaskProgressStage.OCR ->
                return getString(R.string.global_progress_in_progress, "OCR")
            GlobalTaskProgressStage.GLOSSARY ->
                return getString(
                    R.string.global_progress_in_progress,
                    getString(R.string.global_progress_glossary)
                )
            GlobalTaskProgressStage.TRANSLATING, null -> Unit
        }
        return getString(R.string.global_progress_in_progress, label)
    }

    private fun extractProgress(state: GlobalTaskProgressState): Pair<Int, Int>? {
        val progress = state.progress
        val total = state.total
        if (progress != null && total != null && total > 0) {
            return progress.coerceIn(0, total) to total
        }
        val match = Regex("(\\d+)\\s*/\\s*(\\d+)").find(state.detail) ?: return null
        val parsedProgress = match.groupValues[1].toIntOrNull() ?: return null
        val parsedTotal = match.groupValues[2].toIntOrNull() ?: return null
        if (parsedTotal <= 0) return null
        return parsedProgress.coerceIn(0, parsedTotal) to parsedTotal
    }

    private fun resolveColorAttr(attrRes: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attrRes, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }
}
