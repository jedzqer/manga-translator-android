package com.manga.translate.model

import androidx.annotation.StringRes
import com.manga.translate.R

enum class ThinkingLength(
    val prefValue: String,
    @param:StringRes val labelRes: Int
) {
    AUTO("auto", R.string.thinking_length_auto),
    LOW("low", R.string.thinking_length_low),
    MEDIUM("medium", R.string.thinking_length_medium),
    HIGH("high", R.string.thinking_length_high),
    XHIGH("xhigh", R.string.thinking_length_xhigh);

    fun openAiBudgetTokens(): Int? {
        return when (this) {
            AUTO -> null
            LOW -> 1024
            MEDIUM -> DEFAULT_OPENAI_MEDIUM
            HIGH -> 8192
            XHIGH -> 16384
        }
    }

    fun geminiThinkingBudget(): Int {
        return when (this) {
            AUTO -> -1
            LOW -> 1024
            MEDIUM -> 8192
            HIGH -> 24576
            XHIGH -> 32768
        }
    }

    companion object {
        val DEFAULT = AUTO
        private const val DEFAULT_OPENAI_MEDIUM = 4096

        fun fromPref(value: String?): ThinkingLength {
            return entries.firstOrNull { it.prefValue == value } ?: DEFAULT
        }

        fun fromLegacyBudget(budget: Int?): ThinkingLength {
            if (budget == null) return DEFAULT
            if (budget < 0) return AUTO
            return entries
                .mapNotNull { length -> length.openAiBudgetTokens()?.let { length to it } }
                .minByOrNull { (_, tokens) -> kotlin.math.abs(tokens.toLong() - budget) }
                ?.first
                ?: DEFAULT
        }
    }
}
