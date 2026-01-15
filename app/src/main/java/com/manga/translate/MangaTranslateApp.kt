package com.manga.translate

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class MangaTranslateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        val themeMode = SettingsStore(this).loadThemeMode()
        AppCompatDelegate.setDefaultNightMode(themeMode.nightMode)
        val crashStateStore = CrashStateStore(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.log("Crash", "Uncaught exception on ${thread.name}", throwable)
            crashStateStore.markCrashed()
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
