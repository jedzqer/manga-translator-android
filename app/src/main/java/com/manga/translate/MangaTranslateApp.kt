package com.manga.translate

import android.app.Application

class MangaTranslateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.log("Crash", "Uncaught exception on ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
