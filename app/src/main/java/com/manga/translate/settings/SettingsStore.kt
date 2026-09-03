package com.manga.translate.settings

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.manga.translate.R
import com.manga.translate.model.ApiFormat
import com.manga.translate.model.AppLanguage
import com.manga.translate.model.FloatingBallGestureAction
import com.manga.translate.model.LinkSource
import com.manga.translate.model.OcrApiFormat
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.model.ReadingPageAnimationMode
import com.manga.translate.model.ThemeMode
import com.manga.translate.model.ThinkingLength
import com.manga.translate.rendering.BubbleFont
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

const val PRIMARY_PROVIDER_ID = "primary"
const val OCR_PROVIDER_ID = "ocr"

data class ApiSettings(
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val apiFormat: ApiFormat = ApiFormat.OPENAI_COMPATIBLE,
    val providerId: String = PRIMARY_PROVIDER_ID
) {
    fun isValid(): Boolean {
        return apiUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank()
    }
}

data class CustomThemeColors(
    val background: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val accent: Int,
    val accentContent: Int,
    val foreground: Int,
    val mutedForeground: Int,
    val outline: Int,
    val buttonFill: Int,
    val buttonPressed: Int,
    val buttonText: Int,
    val heroStart: Int,
    val heroEnd: Int
) {
    companion object {
        val DEFAULT = fromBaseColors(
            background = 0xFFF5F7FB.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            accent = 0xFF51AFFF.toInt()
        )

        fun fromBaseColors(background: Int, surface: Int, accent: Int): CustomThemeColors {
            val opaqueBackground = opaque(background)
            val opaqueSurface = opaque(surface)
            val opaqueAccent = opaque(accent)
            val foreground = bestContrastingColor(opaqueSurface)
            val surfaceAlt = ColorUtils.blendARGB(opaqueSurface, opaqueAccent, 0.12f)
            val accentContent = ensureContrast(opaqueAccent, opaqueSurface, 4.5)
            val mutedForeground = ColorUtils.blendARGB(foreground, opaqueSurface, 0.38f)
            val outline = ColorUtils.blendARGB(foreground, opaqueSurface, 0.78f)
            val buttonFill = surfaceAlt
            return CustomThemeColors(
                background = opaqueBackground,
                surface = opaqueSurface,
                surfaceAlt = surfaceAlt,
                accent = opaqueAccent,
                accentContent = accentContent,
                foreground = foreground,
                mutedForeground = mutedForeground,
                outline = outline,
                buttonFill = buttonFill,
                buttonPressed = opaqueAccent,
                buttonText = bestContrastingColor(buttonFill),
                heroStart = opaqueAccent,
                heroEnd = ColorUtils.blendARGB(opaqueAccent, foreground, 0.22f)
            )
        }

        private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

        private fun bestContrastingColor(background: Int): Int {
            val black = Color.rgb(20, 24, 32)
            val white = Color.rgb(248, 250, 252)
            return if (
                ColorUtils.calculateContrast(black, background) >=
                ColorUtils.calculateContrast(white, background)
            ) black else white
        }

        private fun ensureContrast(color: Int, background: Int, minimum: Double): Int {
            if (ColorUtils.calculateContrast(color, background) >= minimum) return color
            val target = bestContrastingColor(background)
            var low = 0f
            var high = 1f
            repeat(12) {
                val amount = (low + high) / 2f
                val candidate = ColorUtils.blendARGB(color, target, amount)
                if (ColorUtils.calculateContrast(candidate, background) >= minimum) {
                    high = amount
                } else {
                    low = amount
                }
            }
            return ColorUtils.blendARGB(color, target, high)
        }
    }
}

data class OcrApiSettings(
    val useLocalOcr: Boolean,
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val timeoutSeconds: Int,
    val apiOcrConcurrencyLimit: Int = 1,
    // 0 = auto (determined by device performance); positive = manual override
    val localOcrConcurrencyLimit: Int = 0,
    val ocrApiFormat: OcrApiFormat = OcrApiFormat.OPENAI_COMPATIBLE
) {
    fun isValid(): Boolean {
        return useLocalOcr || (apiUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank())
    }
}

data class FloatingTranslateApiSettings(
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val timeoutSeconds: Int,
    val useVlDirectTranslate: Boolean,
    val ocrConcurrencyLimit: Int,
    val aiApiConcurrencyLimit: Int,
    val proofreadingModeEnabled: Boolean,
    val autoCloseOnScreenChangeEnabled: Boolean,
    val singleTapAction: FloatingBallGestureAction,
    val doubleTapAction: FloatingBallGestureAction,
    val longPressAction: FloatingBallGestureAction,
    val tripleTapAction: FloatingBallGestureAction,
    val detectionTopInsetPercent: Int = 0,
    val detectionBottomInsetPercent: Int = 0
)

data class NormalBubbleRenderSettings(
    val shrinkPercent: Int,
    val opacityPercent: Int,
    val freeBubbleShrinkPercent: Int,
    val freeBubbleOpacityPercent: Int,
    val useHorizontalText: Boolean,
    val autoAdaptBubbleColor: Boolean = false,
    val autoAdaptFreeBubbleColor: Boolean = true,
    val font: BubbleFont = BubbleFont.SYSTEM_DEFAULT,
    val customFontUrl: String = "",
    val customFontFileName: String = "",
    val isBold: Boolean = false
)

data class BubbleFontSettings(
    val font: BubbleFont = BubbleFont.SYSTEM_DEFAULT,
    val customFontFileName: String = "",
    val isBold: Boolean = false
)

enum class FloatingBubbleShape(val prefValue: String, val labelRes: Int) {
    RECTANGLE("rectangle", R.string.floating_bubble_shape_rectangle),
    INSCRIBED_ELLIPSE("inscribed_ellipse", R.string.floating_bubble_shape_inscribed_ellipse);

    companion object {
        fun fromPref(value: String?): FloatingBubbleShape {
            return entries.firstOrNull { it.prefValue == value } ?: RECTANGLE
        }
    }
}

data class FloatingBubbleRenderSettings(
    val sizeAdjustPercent: Int,
    val opacityPercent: Int,
    val shape: FloatingBubbleShape,
    val useHorizontalText: Boolean,
    val autoAdaptBubbleColor: Boolean = true,
    val font: BubbleFont = BubbleFont.SYSTEM_DEFAULT,
    val customFontUrl: String = "",
    val customFontFileName: String = "",
    val isBold: Boolean = false
)

data class CustomRequestParameter(
    val key: String,
    val value: String,
    val enabled: Boolean = true
)

data class AiProviderProfile(
    val name: String,
    val mainSettings: ApiSettings,
    val apiTimeoutSeconds: Int,
    val apiRetryCount: Int,
    val maxConcurrency: Int,
    val ocrSettings: OcrApiSettings,
    val floatingTranslateSettings: FloatingTranslateApiSettings,
    val llmParameters: LlmParameterSettings,
    val customRequestParameters: List<CustomRequestParameter>
)

data class AiProviderProfilesState(
    val activeProfileName: String?,
    val profiles: List<AiProviderProfile>
)

internal data class SettingsMainForm(
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val apiFormat: ApiFormat,
    val apiTimeoutSeconds: Int,
    val apiRetryCount: Int,
    val maxConcurrency: Int
)

internal data class SettingsPersistenceResult(
    val apiTimeoutSeconds: Int,
    val apiRetryCount: Int,
    val maxConcurrency: Int
)

class SettingsStore(context: Context) {
    private val storage = SettingsStoreStorage(context)
    private val apiSettingsStore = ApiSettingsStore(storage)
    private val ocrSettingsStore = OcrSettingsStore(storage)
    private val renderSettingsStore = RenderSettingsStore(storage)
    private val appSettingsStore = AppSettingsStore(storage)
    private val llmParameterStore = LlmParameterStore(storage)
    private val providerProfileStore = ProviderProfileStore(
        storage = storage,
        apiSettingsStore = apiSettingsStore,
        ocrSettingsStore = ocrSettingsStore,
        llmParameterStore = llmParameterStore
    )

    val settingsVersion: StateFlow<Long>
        get() = storage.settingsObserver.version

    val settingChanges: SharedFlow<Set<String>>
        get() = storage.settingsObserver.changes

    fun load(): ApiSettings = apiSettingsStore.load()

    fun save(settings: ApiSettings) {
        apiSettingsStore.save(settings)
    }

    fun loadFloatingTranslateApiSettings(): FloatingTranslateApiSettings {
        return apiSettingsStore.loadFloatingTranslateApiSettings()
    }

    fun loadResolvedFloatingTranslateApiSettings(): ApiSettings {
        return apiSettingsStore.loadResolvedFloatingTranslateApiSettings()
    }

    fun saveFloatingTranslateApiSettings(settings: FloatingTranslateApiSettings) {
        apiSettingsStore.saveFloatingTranslateApiSettings(settings)
    }

    fun loadOcrApiSettings(): OcrApiSettings = ocrSettingsStore.loadOcrApiSettings()

    fun saveOcrApiSettings(settings: OcrApiSettings) {
        ocrSettingsStore.saveOcrApiSettings(settings)
    }

    fun loadUseHorizontalText(): Boolean = renderSettingsStore.loadUseHorizontalText()

    fun saveUseHorizontalText(enabled: Boolean) {
        renderSettingsStore.saveUseHorizontalText(enabled)
    }

    fun loadNormalBubbleRenderSettings(): NormalBubbleRenderSettings {
        return renderSettingsStore.loadNormalBubbleRenderSettings()
    }

    fun saveNormalBubbleRenderSettings(settings: NormalBubbleRenderSettings) {
        renderSettingsStore.saveNormalBubbleRenderSettings(settings)
    }

    fun loadFloatingBubbleRenderSettings(): FloatingBubbleRenderSettings {
        return renderSettingsStore.loadFloatingBubbleRenderSettings()
    }

    fun saveFloatingBubbleRenderSettings(settings: FloatingBubbleRenderSettings) {
        renderSettingsStore.saveFloatingBubbleRenderSettings(settings)
    }

    fun loadBubbleFontSettings(): BubbleFontSettings {
        return renderSettingsStore.loadBubbleFontSettings()
    }

    fun saveBubbleFontSettings(settings: BubbleFontSettings) {
        renderSettingsStore.saveBubbleFontSettings(settings)
    }

    fun loadModelIoLogging(): Boolean = apiSettingsStore.loadModelIoLogging()

    fun saveModelIoLogging(enabled: Boolean) {
        apiSettingsStore.saveModelIoLogging(enabled)
    }

    fun loadUseXnnpack(): Boolean = appSettingsStore.loadUseXnnpack()

    fun saveUseXnnpack(enabled: Boolean) {
        appSettingsStore.saveUseXnnpack(enabled)
    }

    internal fun persistMainSettings(form: SettingsMainForm): SettingsPersistenceResult {
        return apiSettingsStore.persistMainSettings(form)
    }

    fun loadApiRetryCount(): Int = apiSettingsStore.loadApiRetryCount()

    fun saveApiRetryCount(value: Int) {
        apiSettingsStore.saveApiRetryCount(value)
    }

    fun loadMaxConcurrency(): Int = apiSettingsStore.loadMaxConcurrency()

    fun saveMaxConcurrency(value: Int) {
        apiSettingsStore.saveMaxConcurrency(value)
    }

    fun loadApiTimeoutSeconds(): Int = apiSettingsStore.loadApiTimeoutSeconds()

    fun loadApiTimeoutMs(): Int = apiSettingsStore.loadApiTimeoutMs()

    fun saveApiTimeoutSeconds(value: Int) {
        apiSettingsStore.saveApiTimeoutSeconds(value)
    }

    fun loadThemeMode(): ThemeMode = appSettingsStore.loadThemeMode()

    fun loadCustomThemeColors(): CustomThemeColors = appSettingsStore.loadCustomThemeColors()

    fun loadAppLanguage(): AppLanguage = appSettingsStore.loadAppLanguage()

    fun saveAppLanguage(language: AppLanguage) {
        appSettingsStore.saveAppLanguage(language)
    }

    fun saveThemeMode(mode: ThemeMode) {
        appSettingsStore.saveThemeMode(mode)
    }

    fun saveCustomThemeColors(colors: CustomThemeColors) {
        appSettingsStore.saveCustomThemeColors(colors)
    }

    fun loadReadingDisplayMode(): ReadingDisplayMode = appSettingsStore.loadReadingDisplayMode()

    fun saveReadingDisplayMode(mode: ReadingDisplayMode) {
        appSettingsStore.saveReadingDisplayMode(mode)
    }

    fun loadReadingPageAnimationMode(): ReadingPageAnimationMode {
        return appSettingsStore.loadReadingPageAnimationMode()
    }

    fun saveReadingPageAnimationMode(mode: ReadingPageAnimationMode) {
        appSettingsStore.saveReadingPageAnimationMode(mode)
    }

    fun loadBubbleConfThresholdPercent(): Int = renderSettingsStore.loadBubbleConfThresholdPercent()

    fun saveBubbleConfThresholdPercent(value: Int) {
        renderSettingsStore.saveBubbleConfThresholdPercent(value)
    }

    fun loadTranslationBubbleOpacityPercent(): Int {
        return renderSettingsStore.loadTranslationBubbleOpacityPercent()
    }

    fun loadTranslationBubbleOpacity(): Float = renderSettingsStore.loadTranslationBubbleOpacity()

    fun saveTranslationBubbleOpacityPercent(value: Int) {
        renderSettingsStore.saveTranslationBubbleOpacityPercent(value)
    }

    fun loadTranslationStyle(): String = appSettingsStore.loadTranslationStyle()

    fun saveTranslationStyle(style: String) {
        appSettingsStore.saveTranslationStyle(style)
    }

    fun loadLinkSource(): LinkSource = appSettingsStore.loadLinkSource()

    fun saveLinkSource(source: LinkSource) {
        appSettingsStore.saveLinkSource(source)
    }

    fun hasShownTutorialPrompt(): Boolean = appSettingsStore.hasShownTutorialPrompt()

    fun markTutorialPromptShown() = appSettingsStore.markTutorialPromptShown()

    fun loadLlmParameters(): LlmParameterSettings = llmParameterStore.loadLlmParameters()

    fun saveLlmParameters(settings: LlmParameterSettings) {
        llmParameterStore.saveLlmParameters(settings)
    }

    fun loadCustomRequestParameters(): List<CustomRequestParameter> {
        return providerProfileStore.loadCustomRequestParameters()
    }

    fun saveCustomRequestParameters(parameters: List<CustomRequestParameter>) {
        providerProfileStore.saveCustomRequestParameters(parameters)
    }

    internal fun notifyImportedSettings() {
        storage.notifyImportedSettings()
    }

    fun loadAiProviderProfilesState(): AiProviderProfilesState {
        return providerProfileStore.loadAiProviderProfilesState()
    }

    fun saveCurrentAsAiProviderProfile(name: String): Boolean {
        return providerProfileStore.saveCurrentAsAiProviderProfile(name)
    }

    fun overwriteActiveAiProviderProfile(): Boolean {
        return providerProfileStore.overwriteActiveAiProviderProfile()
    }

    fun applyAiProviderProfile(name: String): Boolean {
        return providerProfileStore.applyAiProviderProfile(name)
    }

    fun deleteAiProviderProfile(name: String): Boolean {
        return providerProfileStore.deleteAiProviderProfile(name)
    }

    companion object {
        internal const val PREFS_NAME = "manga_translate_settings"
        internal const val AI_PROVIDER_PROFILES_FILE_NAME = "ai_provider_profiles.json"
        internal const val KEY_API_URL = "api_url"
        internal const val KEY_API_KEY = "api_key"
        internal const val KEY_MODEL_NAME = "model_name"
        internal const val KEY_API_FORMAT = "api_format"
        internal const val KEY_OCR_USE_LOCAL = "ocr_use_local"
        internal const val KEY_OCR_API_URL = "ocr_api_url"
        internal const val KEY_OCR_API_KEY = "ocr_api_key"
        internal const val KEY_OCR_MODEL_NAME = "ocr_model_name"
        internal const val KEY_FLOATING_API_URL = "floating_api_url"
        internal const val KEY_FLOATING_API_KEY = "floating_api_key"
        internal const val KEY_FLOATING_MODEL_NAME = "floating_model_name"
        internal const val KEY_FLOATING_TIMEOUT_SECONDS = "floating_timeout_seconds"
        internal const val KEY_FLOATING_USE_VL_DIRECT_TRANSLATE = "floating_use_vl_direct_translate"
        internal const val KEY_FLOATING_VL_TRANSLATE_CONCURRENCY = "floating_vl_translate_concurrency"
        internal const val KEY_FLOATING_OCR_CONCURRENCY = "floating_ocr_concurrency"
        internal const val KEY_FLOATING_PROOFREADING_MODE_ENABLED =
            "floating_proofreading_mode_enabled"
        internal const val KEY_FLOATING_AUTO_CLOSE_ON_SCREEN_CHANGE_ENABLED =
            "floating_auto_close_on_screen_change_enabled"
        internal const val KEY_FLOATING_SINGLE_TAP_ACTION = "floating_single_tap_action"
        internal const val KEY_FLOATING_DOUBLE_TAP_ACTION = "floating_double_tap_action"
        internal const val KEY_FLOATING_LONG_PRESS_ACTION = "floating_long_press_action"
        internal const val KEY_FLOATING_TRIPLE_TAP_ACTION = "floating_triple_tap_action"
        internal const val KEY_FLOATING_DETECTION_TOP_INSET_PERCENT =
            "floating_detection_top_inset_percent"
        internal const val KEY_FLOATING_DETECTION_BOTTOM_INSET_PERCENT =
            "floating_detection_bottom_inset_percent"
        internal const val KEY_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT =
            "floating_bubble_size_adjust_percent"
        internal const val KEY_FLOATING_BUBBLE_OPACITY_PERCENT = "floating_bubble_opacity_percent"
        internal const val KEY_FLOATING_BUBBLE_SHAPE = "floating_bubble_shape"
        internal const val KEY_FLOATING_BUBBLE_HORIZONTAL_TEXT = "floating_bubble_horizontal_text"
        internal const val KEY_FLOATING_BUBBLE_AUTO_ADAPT_COLOR =
            "floating_bubble_auto_adapt_color"
        internal const val KEY_OCR_API_TIMEOUT_SECONDS = "ocr_api_timeout_seconds"
        internal const val KEY_OCR_API_CONCURRENCY = "ocr_api_concurrency"
        internal const val KEY_LOCAL_OCR_CONCURRENCY = "local_ocr_concurrency"
        internal const val KEY_OCR_API_FORMAT = "ocr_api_format"
        internal const val KEY_HORIZONTAL_TEXT = "horizontal_text_layout"
        internal const val KEY_NORMAL_BUBBLE_SHRINK_PERCENT = "normal_bubble_shrink_percent"
        internal const val KEY_NORMAL_FREE_BUBBLE_SHRINK_PERCENT =
            // Versioned intentionally so the old non-zero setting is discarded.
            "normal_free_bubble_shrink_percent_v2"
        internal const val KEY_NORMAL_FREE_BUBBLE_OPACITY_PERCENT =
            "normal_free_bubble_opacity_percent"
        internal const val KEY_NORMAL_FREE_BUBBLE_AUTO_ADAPT_COLOR =
            "normal_free_bubble_auto_adapt_color"
        internal const val KEY_NORMAL_BUBBLE_AUTO_ADAPT_COLOR =
            "normal_bubble_auto_adapt_color"
        internal const val KEY_BUBBLE_FONT = "bubble_font"
        internal const val KEY_BUBBLE_CUSTOM_FONT_FILE = "bubble_custom_font_file"
        internal const val KEY_BUBBLE_FONT_BOLD = "bubble_font_bold"
        internal const val KEY_MODEL_IO_LOGGING = "model_io_logging"
        internal const val KEY_USE_XNNPACK = "use_xnnpack"
        internal const val KEY_API_RETRY_COUNT = "api_retry_count"
        internal const val KEY_MAX_CONCURRENCY = "max_concurrency"
        internal const val KEY_API_TIMEOUT_SECONDS = "api_timeout_seconds"
        internal const val KEY_APP_LANGUAGE = "app_language"
        internal const val KEY_THEME_MODE = "theme_mode"
        internal const val KEY_CUSTOM_THEME_BACKGROUND = "custom_theme_background"
        internal const val KEY_CUSTOM_THEME_SURFACE = "custom_theme_surface"
        internal const val KEY_CUSTOM_THEME_SURFACE_ALT = "custom_theme_surface_alt"
        internal const val KEY_CUSTOM_THEME_ACCENT = "custom_theme_accent"
        internal const val KEY_CUSTOM_THEME_ACCENT_CONTENT = "custom_theme_accent_content"
        internal const val KEY_CUSTOM_THEME_FOREGROUND = "custom_theme_foreground"
        internal const val KEY_CUSTOM_THEME_MUTED_FOREGROUND = "custom_theme_muted_foreground"
        internal const val KEY_CUSTOM_THEME_OUTLINE = "custom_theme_outline"
        internal const val KEY_CUSTOM_THEME_BUTTON_FILL = "custom_theme_button_fill"
        internal const val KEY_CUSTOM_THEME_BUTTON_PRESSED = "custom_theme_button_pressed"
        internal const val KEY_CUSTOM_THEME_BUTTON_TEXT = "custom_theme_button_text"
        internal const val KEY_CUSTOM_THEME_HERO_START = "custom_theme_hero_start"
        internal const val KEY_CUSTOM_THEME_HERO_END = "custom_theme_hero_end"
        internal const val KEY_READING_DISPLAY_MODE = "reading_display_mode"
        internal const val KEY_READING_PAGE_ANIMATION_MODE = "reading_page_animation_mode"
        internal const val KEY_TRANSLATION_BUBBLE_OPACITY_PERCENT =
            "translation_bubble_opacity_percent"
        internal const val KEY_BUBBLE_CONF_THRESHOLD_PERCENT =
            "comic_bubble_conf_threshold_percent_v3"
        internal const val KEY_LINK_SOURCE = "link_source"
        internal const val KEY_TUTORIAL_PROMPT_SHOWN = "tutorial_prompt_shown"
        internal const val KEY_LLM_TEMPERATURE = "llm_temperature"
        internal const val KEY_LLM_TOP_P = "llm_top_p"
        internal const val KEY_LLM_TOP_K = "llm_top_k"
        internal const val KEY_LLM_MAX_OUTPUT_TOKENS = "llm_max_output_tokens"
        internal const val KEY_LLM_ENABLE_THINKING = "llm_enable_thinking"
        internal const val KEY_LLM_THINKING_BUDGET = "llm_thinking_budget"
        internal const val KEY_LLM_THINKING_LENGTH = "llm_thinking_length"
        internal const val KEY_LLM_FREQUENCY_PENALTY = "llm_frequency_penalty"
        internal const val KEY_LLM_PRESENCE_PENALTY = "llm_presence_penalty"
        internal const val KEY_CUSTOM_REQUEST_PARAMETERS = "custom_request_parameters"
        internal const val KEY_TRANSLATION_STYLE = "translation_style"
        // 仅作变更通知 topic id 使用，实际数据存于 ai_provider_profiles.json
        internal const val KEY_AI_PROVIDER_PROFILES_STATE = "ai_provider_profiles_state"
        internal const val LEGACY_SETTINGS_JSON_VERSION = 1
        internal const val SETTINGS_JSON_SCHEMA_VERSION = 2
        internal const val DEFAULT_LLM_TEMPERATURE = 0.8
        internal const val DEFAULT_LLM_TOP_P = 1.0
        internal const val DEFAULT_LLM_ENABLE_THINKING = false
        internal const val DEFAULT_TRANSLATION_STYLE =
            "请以普通日漫翻译风格翻译，语言自然流畅，符合中文漫画阅读习惯。"
        internal const val DEFAULT_TRANSLATION_STYLE_HANT =
            "請以普通日漫翻譯風格翻譯，語言自然流暢，符合中文漫畫閱讀習慣。"
        internal const val DEFAULT_API_URL = "https://api.siliconflow.cn/v1"
        internal const val DEFAULT_MODEL = "Qwen/Qwen3.5-35B-A3B"
        internal const val DEFAULT_OCR_API_URL = "https://api.siliconflow.cn/v1"
        internal const val DEFAULT_OCR_MODEL_NAME = "Qwen/Qwen3-VL-8B-Instruct"
        internal const val DEFAULT_OCR_API_TIMEOUT_SECONDS = 300
        const val MIN_OCR_API_TIMEOUT_SECONDS = 30
        const val MAX_OCR_API_TIMEOUT_SECONDS = 1200
        internal const val DEFAULT_OCR_API_CONCURRENCY = 3
        const val MIN_OCR_API_CONCURRENCY = 1
        const val MAX_OCR_API_CONCURRENCY = 50
        internal const val DEFAULT_LOCAL_OCR_CONCURRENCY = 0
        internal const val MIN_LOCAL_OCR_CONCURRENCY = 0
        internal const val MAX_LOCAL_OCR_CONCURRENCY = 8
        internal const val DEFAULT_FLOATING_OCR_CONCURRENCY = 1
        internal const val MIN_FLOATING_OCR_CONCURRENCY = 1
        internal const val MAX_FLOATING_OCR_CONCURRENCY = 50
        internal const val DEFAULT_FLOATING_AI_API_CONCURRENCY = 20
        internal const val MIN_FLOATING_AI_API_CONCURRENCY = 1
        internal const val MAX_FLOATING_AI_API_CONCURRENCY = 50
        internal val DEFAULT_FLOATING_SINGLE_TAP_ACTION = FloatingBallGestureAction.START_TRANSLATE
        internal val DEFAULT_FLOATING_DOUBLE_TAP_ACTION = FloatingBallGestureAction.CLEAR_SCREEN
        internal val DEFAULT_FLOATING_LONG_PRESS_ACTION = FloatingBallGestureAction.OPEN_MENU
        internal val DEFAULT_FLOATING_TRIPLE_TAP_ACTION = FloatingBallGestureAction.NONE
        internal const val DEFAULT_FLOATING_API_TIMEOUT_SECONDS = 300
        const val MIN_FLOATING_API_TIMEOUT_SECONDS = 30
        const val MAX_FLOATING_API_TIMEOUT_SECONDS = 1200
        internal const val DEFAULT_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT = 0
        internal const val DEFAULT_FLOATING_BUBBLE_OPACITY_PERCENT = 100
        internal const val MIN_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT = -30
        internal const val MAX_FLOATING_BUBBLE_SIZE_ADJUST_PERCENT = 30
        internal const val DEFAULT_FLOATING_BUBBLE_AUTO_ADAPT_COLOR = true
        internal const val DEFAULT_NORMAL_BUBBLE_SHRINK_PERCENT = 10
        internal const val MIN_NORMAL_BUBBLE_SHRINK_PERCENT = 0
        internal const val MAX_NORMAL_BUBBLE_SHRINK_PERCENT = 30
        internal const val DEFAULT_NORMAL_FREE_BUBBLE_SHRINK_PERCENT = 0
        internal const val DEFAULT_NORMAL_FREE_BUBBLE_OPACITY_PERCENT = 90
        internal const val DEFAULT_NORMAL_FREE_BUBBLE_AUTO_ADAPT_COLOR = true
        internal const val DEFAULT_NORMAL_BUBBLE_AUTO_ADAPT_COLOR = false
        internal const val DEFAULT_MAX_CONCURRENCY = 8
        internal const val MIN_MAX_CONCURRENCY = 1
        internal const val MAX_MAX_CONCURRENCY = 200
        internal const val DEFAULT_API_RETRY_COUNT = 3
        internal const val MIN_API_RETRY_COUNT = 1
        internal const val MAX_API_RETRY_COUNT = 50
        internal const val DEFAULT_API_TIMEOUT_SECONDS = 300
        internal const val MIN_API_TIMEOUT_SECONDS = 30
        internal const val MAX_API_TIMEOUT_SECONDS = 1200
        internal const val DEFAULT_TRANSLATION_BUBBLE_OPACITY_PERCENT = 100
        internal const val MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT = 0
        internal const val MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT = 100
        internal const val DEFAULT_BUBBLE_CONF_THRESHOLD_PERCENT = 10
        internal const val MIN_BUBBLE_CONF_THRESHOLD_PERCENT = 1
        internal const val MAX_BUBBLE_CONF_THRESHOLD_PERCENT = 95
    }
}

data class LlmParameterSettings(
    val temperature: Double?,
    val topP: Double?,
    val topK: Int?,
    val maxOutputTokens: Int?,
    val enableThinking: Boolean,
    val thinkingLength: ThinkingLength = ThinkingLength.DEFAULT,
    val frequencyPenalty: Double?,
    val presencePenalty: Double?
)
