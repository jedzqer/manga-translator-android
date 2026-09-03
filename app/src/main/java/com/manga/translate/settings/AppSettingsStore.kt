package com.manga.translate.settings

import com.manga.translate.model.AppLanguage
import com.manga.translate.model.LinkSource
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.model.ReadingPageAnimationMode
import com.manga.translate.model.ThemeMode
import com.manga.translate.platform.PromptAssetResolver

internal class AppSettingsStore(
    private val storage: SettingsStoreStorage
) {
    fun loadUseXnnpack(): Boolean {
        return storage.prefs.getBoolean(SettingsStore.KEY_USE_XNNPACK, false)
    }

    fun saveUseXnnpack(enabled: Boolean) {
        storage.editSettings(setOf(SettingsStore.KEY_USE_XNNPACK)) {
            putBoolean(SettingsStore.KEY_USE_XNNPACK, enabled)
        }
    }

    fun loadThemeMode(): ThemeMode {
        val saved = storage.prefs.getString(
            SettingsStore.KEY_THEME_MODE,
            ThemeMode.FOLLOW_SYSTEM.prefValue
        )
        return ThemeMode.fromPref(saved)
    }

    fun loadAppLanguage(): AppLanguage {
        val saved = storage.prefs.getString(
            SettingsStore.KEY_APP_LANGUAGE,
            AppLanguage.FOLLOW_SYSTEM.prefValue
        )
        return AppLanguage.fromPref(saved)
    }

    fun saveAppLanguage(language: AppLanguage) {
        storage.editSettings(setOf(SettingsStore.KEY_APP_LANGUAGE)) {
            putString(SettingsStore.KEY_APP_LANGUAGE, language.prefValue)
        }
    }

    fun saveThemeMode(mode: ThemeMode) {
        storage.editSettings(setOf(SettingsStore.KEY_THEME_MODE)) {
            putString(SettingsStore.KEY_THEME_MODE, mode.prefValue)
        }
    }

    fun loadCustomThemeColors(): CustomThemeColors {
        val background = storage.prefs.getInt(
            SettingsStore.KEY_CUSTOM_THEME_BACKGROUND,
            CustomThemeColors.DEFAULT.background
        ) or 0xFF000000.toInt()
        val surface = storage.prefs.getInt(
            SettingsStore.KEY_CUSTOM_THEME_SURFACE,
            CustomThemeColors.DEFAULT.surface
        ) or 0xFF000000.toInt()
        val accent = storage.prefs.getInt(
            SettingsStore.KEY_CUSTOM_THEME_ACCENT,
            CustomThemeColors.DEFAULT.accent
        ) or 0xFF000000.toInt()
        fun loadColor(key: String, fallback: Int): Int {
            return storage.prefs.getInt(key, fallback) or 0xFF000000.toInt()
        }

        return CustomThemeColors(
            background = background,
            surface = surface,
            surfaceAlt = loadColor(
                SettingsStore.KEY_CUSTOM_THEME_SURFACE_ALT,
                CustomThemeColors.DEFAULT.surfaceAlt
            ),
            accent = accent,
            accentContent = loadColor(
                SettingsStore.KEY_CUSTOM_THEME_ACCENT_CONTENT,
                CustomThemeColors.DEFAULT.accentContent
            ),
            foreground = loadColor(
                SettingsStore.KEY_CUSTOM_THEME_FOREGROUND,
                CustomThemeColors.DEFAULT.foreground
            ),
            mutedForeground = loadColor(
                SettingsStore.KEY_CUSTOM_THEME_MUTED_FOREGROUND,
                CustomThemeColors.DEFAULT.mutedForeground
            ),
            outline = loadColor(
                SettingsStore.KEY_CUSTOM_THEME_OUTLINE,
                CustomThemeColors.DEFAULT.outline
            ),
            buttonFill = loadColor(
                SettingsStore.KEY_CUSTOM_THEME_BUTTON_FILL,
                CustomThemeColors.DEFAULT.buttonFill
            ),
            buttonPressed = loadColor(
                SettingsStore.KEY_CUSTOM_THEME_BUTTON_PRESSED,
                CustomThemeColors.DEFAULT.buttonPressed
            ),
            buttonText = loadColor(
                SettingsStore.KEY_CUSTOM_THEME_BUTTON_TEXT,
                CustomThemeColors.DEFAULT.buttonText
            ),
            heroStart = loadColor(
                SettingsStore.KEY_CUSTOM_THEME_HERO_START,
                CustomThemeColors.DEFAULT.heroStart
            ),
            heroEnd = loadColor(
                SettingsStore.KEY_CUSTOM_THEME_HERO_END,
                CustomThemeColors.DEFAULT.heroEnd
            )
        )
    }

    fun saveCustomThemeColors(colors: CustomThemeColors) {
        storage.editSettings(
            setOf(
                SettingsStore.KEY_CUSTOM_THEME_BACKGROUND,
                SettingsStore.KEY_CUSTOM_THEME_SURFACE,
                SettingsStore.KEY_CUSTOM_THEME_SURFACE_ALT,
                SettingsStore.KEY_CUSTOM_THEME_ACCENT,
                SettingsStore.KEY_CUSTOM_THEME_ACCENT_CONTENT,
                SettingsStore.KEY_CUSTOM_THEME_FOREGROUND,
                SettingsStore.KEY_CUSTOM_THEME_MUTED_FOREGROUND,
                SettingsStore.KEY_CUSTOM_THEME_OUTLINE,
                SettingsStore.KEY_CUSTOM_THEME_BUTTON_FILL,
                SettingsStore.KEY_CUSTOM_THEME_BUTTON_PRESSED,
                SettingsStore.KEY_CUSTOM_THEME_BUTTON_TEXT,
                SettingsStore.KEY_CUSTOM_THEME_HERO_START,
                SettingsStore.KEY_CUSTOM_THEME_HERO_END
            )
        ) {
            putInt(SettingsStore.KEY_CUSTOM_THEME_BACKGROUND, colors.background)
            putInt(SettingsStore.KEY_CUSTOM_THEME_SURFACE, colors.surface)
            putInt(SettingsStore.KEY_CUSTOM_THEME_SURFACE_ALT, colors.surfaceAlt)
            putInt(SettingsStore.KEY_CUSTOM_THEME_ACCENT, colors.accent)
            putInt(SettingsStore.KEY_CUSTOM_THEME_ACCENT_CONTENT, colors.accentContent)
            putInt(SettingsStore.KEY_CUSTOM_THEME_FOREGROUND, colors.foreground)
            putInt(SettingsStore.KEY_CUSTOM_THEME_MUTED_FOREGROUND, colors.mutedForeground)
            putInt(SettingsStore.KEY_CUSTOM_THEME_OUTLINE, colors.outline)
            putInt(SettingsStore.KEY_CUSTOM_THEME_BUTTON_FILL, colors.buttonFill)
            putInt(SettingsStore.KEY_CUSTOM_THEME_BUTTON_PRESSED, colors.buttonPressed)
            putInt(SettingsStore.KEY_CUSTOM_THEME_BUTTON_TEXT, colors.buttonText)
            putInt(SettingsStore.KEY_CUSTOM_THEME_HERO_START, colors.heroStart)
            putInt(SettingsStore.KEY_CUSTOM_THEME_HERO_END, colors.heroEnd)
        }
    }

    fun loadReadingDisplayMode(): ReadingDisplayMode {
        val saved = storage.prefs.getString(
            SettingsStore.KEY_READING_DISPLAY_MODE,
            ReadingDisplayMode.FIT_WIDTH.prefValue
        )
        return ReadingDisplayMode.fromPref(saved)
    }

    fun saveReadingDisplayMode(mode: ReadingDisplayMode) {
        storage.editSettings(setOf(SettingsStore.KEY_READING_DISPLAY_MODE)) {
            putString(SettingsStore.KEY_READING_DISPLAY_MODE, mode.prefValue)
        }
    }

    fun loadReadingPageAnimationMode(): ReadingPageAnimationMode {
        val saved = storage.prefs.getString(
            SettingsStore.KEY_READING_PAGE_ANIMATION_MODE,
            ReadingPageAnimationMode.HORIZONTAL_SLIDE.prefValue
        )
        return ReadingPageAnimationMode.fromPref(saved)
    }

    fun saveReadingPageAnimationMode(mode: ReadingPageAnimationMode) {
        storage.editSettings(setOf(SettingsStore.KEY_READING_PAGE_ANIMATION_MODE)) {
            putString(SettingsStore.KEY_READING_PAGE_ANIMATION_MODE, mode.prefValue)
        }
    }

    fun loadTranslationStyle(): String {
        val saved = storage.prefs.getString(SettingsStore.KEY_TRANSLATION_STYLE, null)
        if (!saved.isNullOrBlank()) return saved
        return if (PromptAssetResolver.isTraditionalChinese(storage.appContext)) {
            SettingsStore.DEFAULT_TRANSLATION_STYLE_HANT
        } else {
            SettingsStore.DEFAULT_TRANSLATION_STYLE
        }
    }

    fun saveTranslationStyle(style: String) {
        storage.editSettings(setOf(SettingsStore.KEY_TRANSLATION_STYLE)) {
            putString(SettingsStore.KEY_TRANSLATION_STYLE, style.trim())
        }
    }

    fun loadLinkSource(): LinkSource {
        val saved = storage.prefs.getString(
            SettingsStore.KEY_LINK_SOURCE,
            LinkSource.GITHUB.prefValue
        )
        return LinkSource.fromPref(saved)
    }

    fun saveLinkSource(source: LinkSource) {
        storage.editSettings(setOf(SettingsStore.KEY_LINK_SOURCE)) {
            putString(SettingsStore.KEY_LINK_SOURCE, source.prefValue)
        }
    }

    fun hasShownTutorialPrompt(): Boolean {
        return storage.prefs.getBoolean(SettingsStore.KEY_TUTORIAL_PROMPT_SHOWN, false)
    }

    fun markTutorialPromptShown() {
        storage.editSettings(setOf(SettingsStore.KEY_TUTORIAL_PROMPT_SHOWN)) {
            putBoolean(SettingsStore.KEY_TUTORIAL_PROMPT_SHOWN, true)
        }
    }
}
