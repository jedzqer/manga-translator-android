package com.manga.translate.model

import android.graphics.RectF

enum class BubbleSource(val jsonValue: String) {
    BUBBLE_DETECTOR("bubble_detector"),
    TEXT_DETECTOR("text_detector"),
    MANUAL("manual"),
    UNKNOWN("unknown");

    val isFreeBubble: Boolean get() = this == TEXT_DETECTOR || this == MANUAL

    /** Only text-detector bubbles are subject to the free-bubble shrink setting. */
    val usesFreeBubbleShrink: Boolean get() = this == TEXT_DETECTOR

    companion object {
        fun fromJson(value: String?): BubbleSource {
            return entries.firstOrNull { it.jsonValue.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

enum class PageTranslationStatus(val jsonValue: String) {
    UNKNOWN(""),
    SUCCESS("success"),
    PARTIAL("partial"),
    SKIPPED("skipped"),
    FAILED("failed");

    companion object {
        fun fromJson(value: String?): PageTranslationStatus {
            if (value.isNullOrBlank()) return UNKNOWN
            return entries.firstOrNull { it.jsonValue.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

data class TranslationMetadata(
    val sourceLastModified: Long = 0L,
    val sourceFileSize: Long = 0L,
    val mode: String = "",
    val language: String = "",
    val promptAsset: String = "",
    val apiFormat: String = "",
    val ocrCacheMode: String = "",
    val version: Int = CURRENT_VERSION,
    val status: PageTranslationStatus = PageTranslationStatus.UNKNOWN
) {
    fun isManual(): Boolean {
        return mode == MODE_MANUAL
    }

    fun matchesSource(imageFile: java.io.File): Boolean {
        return sourceLastModified == imageFile.lastModified() &&
            sourceFileSize == imageFile.length()
    }

    fun withSourceFingerprint(imageFile: java.io.File): TranslationMetadata {
        return copy(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length()
        )
    }

    companion object {
        const val CURRENT_VERSION = 2
        const val MODE_STANDARD = "standard"
        const val MODE_FULL_PAGE = "full_page"
        const val MODE_VL_DIRECT = "vl_direct"
        const val MODE_MANUAL = "manual"
    }
}

data class OcrMetadata(
    val sourceLastModified: Long = 0L,
    val sourceFileSize: Long = 0L,
    val cacheMode: String = "",
    val language: String = "",
    val engineModel: String = "",
    val version: Int = CURRENT_VERSION
) {
    fun matchesSource(imageFile: java.io.File): Boolean {
        return sourceLastModified == imageFile.lastModified() &&
            sourceFileSize == imageFile.length()
    }

    fun matches(expected: OcrMetadata): Boolean {
        return version == expected.version &&
            cacheMode == expected.cacheMode &&
            language == expected.language &&
            engineModel == expected.engineModel
    }

    companion object {
        const val CURRENT_VERSION = 2
    }
}

enum class BubbleTranslationState(val jsonValue: String) {
    PENDING("pending"),
    TRANSLATED("translated");

    companion object {
        fun fromJson(value: String?): BubbleTranslationState {
            return entries.firstOrNull { it.jsonValue.equals(value, ignoreCase = true) } ?: PENDING
        }
    }
}

data class BubbleTranslation(
    val id: Int,
    val rect: RectF,
    val originalText: String = "",
    val translatedText: String = "",
    val translationState: BubbleTranslationState = if (translatedText.isNotBlank()) {
        BubbleTranslationState.TRANSLATED
    } else {
        BubbleTranslationState.PENDING
    },
    val source: BubbleSource = BubbleSource.UNKNOWN,
    val maskContour: FloatArray? = null,
    val ownerImageName: String? = null
) {
    fun supportsResizeEditing(): Boolean = maskContour == null

    fun resolvedOwnerImageName(defaultImageName: String): String {
        return ownerImageName?.trim().orEmpty().ifBlank { defaultImageName }
    }

    fun isOwnedBy(imageName: String?): Boolean {
        if (imageName.isNullOrBlank()) return true
        return resolvedOwnerImageName(imageName) == imageName
    }

    val text: String
        get() = when {
            translationState == BubbleTranslationState.TRANSLATED && translatedText.isNotBlank() -> translatedText
            originalText.isNotBlank() -> originalText
            else -> translatedText
        }

    val sourceText: String
        get() = originalText.ifBlank {
            if (translationState == BubbleTranslationState.PENDING) {
                translatedText
            } else {
                ""
            }
        }

    fun hasDisplayText(): Boolean = text.isNotBlank()

    fun needsTranslationRetry(): Boolean = translationState == BubbleTranslationState.PENDING

    fun withTranslationResult(value: String): BubbleTranslation {
        val normalized = value.trim()
        return if (normalized.isNotBlank()) {
            copy(
                translatedText = normalized,
                translationState = BubbleTranslationState.TRANSLATED
            )
        } else {
            copy(
                translatedText = "",
                translationState = BubbleTranslationState.PENDING
            )
        }
    }

    fun withRecognizedOriginalText(value: String): BubbleTranslation {
        return copy(
            originalText = value.trim(),
            translatedText = "",
            translationState = BubbleTranslationState.PENDING
        )
    }

    fun withManualText(value: String): BubbleTranslation {
        return if (value.isBlank()) {
            copy(
                originalText = "",
                translatedText = "",
                translationState = BubbleTranslationState.PENDING
            )
        } else {
            copy(
                translatedText = value,
                translationState = BubbleTranslationState.TRANSLATED
            )
        }
    }

    fun withContentFrom(other: BubbleTranslation): BubbleTranslation {
        return copy(
            originalText = other.originalText,
            translatedText = other.translatedText,
            translationState = other.translationState
        )
    }

    companion object {
        fun pending(
            id: Int,
            rect: RectF,
            originalText: String = "",
            source: BubbleSource = BubbleSource.UNKNOWN,
            maskContour: FloatArray? = null,
            ownerImageName: String? = null
        ): BubbleTranslation {
            return BubbleTranslation(
                id = id,
                rect = rect,
                originalText = originalText,
                translatedText = "",
                translationState = BubbleTranslationState.PENDING,
                source = source,
                maskContour = maskContour,
                ownerImageName = ownerImageName
            )
        }

        fun translated(
            id: Int,
            rect: RectF,
            translatedText: String,
            source: BubbleSource = BubbleSource.UNKNOWN,
            maskContour: FloatArray? = null,
            originalText: String = "",
            ownerImageName: String? = null
        ): BubbleTranslation {
            return BubbleTranslation(
                id = id,
                rect = rect,
                originalText = originalText,
                translatedText = translatedText,
                translationState = if (translatedText.isNotBlank()) {
                    BubbleTranslationState.TRANSLATED
                } else {
                    BubbleTranslationState.PENDING
                },
                source = source,
                maskContour = maskContour,
                ownerImageName = ownerImageName
            )
        }
    }
}

data class TranslationResult(
    val imageName: String,
    val width: Int,
    val height: Int,
    val bubbles: List<BubbleTranslation>,
    val metadata: TranslationMetadata = TranslationMetadata()
) {
    /**
     * 是否含跨页合并产生的坐标。条漫模式下 [CrossPageBubbleMerger] 会把下一页气泡并入本页，
     * 合并后的 `rect.bottom` 超出本页高度；这类坐标在普通阅读模式渲染时会错位或跑出页面，
     * 故缓存校验需按阅读模式拒绝复用。
     */
    fun hasCrossPageBubbleGeometry(): Boolean {
        if (height <= 0) return false
        val limit = height + CROSS_PAGE_GEOMETRY_TOLERANCE_PX
        return bubbles.any { it.rect.bottom > limit }
    }

    private companion object {
        /** 允许少量越界（渲染留白 / 浮点误差），只把明显跨页的坐标判为合并结果。 */
        const val CROSS_PAGE_GEOMETRY_TOLERANCE_PX = 2f
    }
}

fun TranslationResult.deriveStatus(): PageTranslationStatus {
    if (bubbles.isEmpty()) return PageTranslationStatus.SUCCESS
    val statusBubbles = if (
        metadata.mode == TranslationMetadata.MODE_STANDARD ||
        metadata.mode == TranslationMetadata.MODE_FULL_PAGE
    ) {
        bubbles.filter {
            it.originalText.isNotBlank() ||
                it.translationState == BubbleTranslationState.TRANSLATED ||
                it.translatedText.isNotBlank()
        }
    } else {
        bubbles
    }
    if (statusBubbles.isEmpty()) return PageTranslationStatus.SUCCESS
    val translated = statusBubbles.count {
        it.translationState == BubbleTranslationState.TRANSLATED && it.translatedText.isNotBlank()
    }
    return if (translated == statusBubbles.size) {
        PageTranslationStatus.SUCCESS
    } else {
        PageTranslationStatus.PARTIAL
    }
}
