package com.manga.translate.translation

import com.manga.translate.model.BubbleTranslation

/**
 * Shared merge logic for both floating & text bubble translation coordinators.
 * Merges translated text back into the original bubble list while filtering out removed bubbles.
 */
internal fun mergeBubbleTranslations(
    allBubbles: List<BubbleTranslation>,
    translatedMap: Map<Int, String>,
    removedBubbleIds: Set<Int>
): List<BubbleTranslation> {
    return allBubbles.filterNot { it.id in removedBubbleIds }.map { bubble ->
        translatedMap[bubble.id]?.let { translated ->
            bubble.withTranslationResult(translated)
        } ?: bubble
    }
}
