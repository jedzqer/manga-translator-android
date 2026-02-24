package com.manga.translate

/**
 * Normalizes punctuation for vertical text rendering.
 * It only converts symbols and keeps all non-symbol characters unchanged.
 */
object VerticalTextSymbolConverter {
    private val symbolMap: Map<Char, String> = mapOf(
        ',' to "︐",
        '.' to "︒",
        ':' to "︓",
        ';' to "︔",
        '!' to "︕",
        '?' to "︖",
        '(' to "︵",
        ')' to "︶",
        '[' to "︹",
        ']' to "︺",
        '{' to "︷",
        '}' to "︸",
        '<' to "︿",
        '>' to "﹀",
        '…' to "︙",
        '—' to "︱",
        '，' to "︐",
        '。' to "︒",
        '、' to "︑",
        '：' to "︓",
        '；' to "︔",
        '！' to "︕",
        '？' to "︖",
        '（' to "︵",
        '）' to "︶",
        '［' to "︹",
        '］' to "︺",
        '｛' to "︷",
        '｝' to "︸",
        '《' to "︽",
        '》' to "︾",
        '〈' to "︿",
        '〉' to "﹀",
        '「' to "﹁",
        '」' to "﹂",
        '『' to "﹃",
        '』' to "﹄",
        '【' to "︻",
        '】' to "︼",
        '〔' to "︹",
        '〕' to "︺",
        '－' to "︱",
        'ー' to "︱"
    )

    fun convert(text: String): String {
        if (text.isEmpty()) return text
        val output = StringBuilder(text.length)
        for (ch in text) {
            output.append(symbolMap[ch] ?: ch)
        }
        return output.toString()
    }
}
