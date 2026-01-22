package com.manga.translate

enum class TranslationLanguage(val displayNameResId: Int) {
    JA_TO_ZH(R.string.folder_language_ja_to_zh),
    EN_TO_ZH(R.string.folder_language_en_to_zh);

    companion object {
        fun fromString(value: String?): TranslationLanguage {
            return when (value) {
                "EN_TO_ZH" -> EN_TO_ZH
                else -> JA_TO_ZH
            }
        }
    }
}
