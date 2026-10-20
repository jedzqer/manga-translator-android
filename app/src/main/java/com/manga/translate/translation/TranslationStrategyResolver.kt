package com.manga.translate.translation

import java.io.File

/** Resolves folder or chapter translation policy without reaching back into app wiring. */
internal fun interface TranslationStrategyResolver {
    fun resolveTranslationStyle(folder: File, globalStyle: String): String
}

