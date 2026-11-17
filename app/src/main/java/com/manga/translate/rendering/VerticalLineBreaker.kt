package com.manga.translate.rendering

/**
 * Kinsoku-aware line breaking for vertical text.
 *
 * Vertical layout cannot wrap by counting characters: closing punctuation must not
 * open a column, opening brackets must not close one, and Latin words or numbers
 * must stay inside a single column. Tokens are grouped into indivisible clusters
 * first, then packed into columns holding at most [maxRows] glyphs. A cluster that
 * no longer fits the remaining rows moves to the next column as a whole; a cluster
 * longer than a whole column hangs off its bottom (ぶら下げ) instead of being torn
 * apart, and the font auto-scaling deals with the resulting overflow.
 *
 * The input is plain text, not [VerticalTextSymbolConverter] output: clustering has
 * to see the original symbols, because "-" (minus) and "ー" (long vowel) both render
 * as "︱" and cannot be told apart afterwards. Each emitted [Glyph] carries the
 * converted glyph that the renderer draws.
 *
 * Clustering never depends on [maxRows]: a word is kept whole, so shrinking the font
 * can only improve the fit and the size search stays monotonic.
 *
 * The classification sets contain both the plain and the vertical presentation forms,
 * so punctuation that already arrives converted still obeys the same rules. A minus
 * that was converted upstream cannot be recovered: it becomes the same "︱" as a long
 * vowel, so callers must hand the breaker the original text.
 *
 * Two situations cannot satisfy the rules without rewriting the text, and are left
 * as they are: a paragraph that begins with closing punctuation (there is no previous
 * glyph to attach it to) and an opening bracket immediately before an explicit
 * newline (attaching across the paragraph break would move text into another
 * paragraph). A minus sign stays at the head of its number's column.
 *
 * A cluster longer than the whole bubble can still exceed the height limit at every
 * font size; [VerticalTextLayoutCalculator] then reports that layout as not fitting
 * and the caller clips it, instead of splitting the term.
 */
internal object VerticalLineBreaker {
    /** Glyphs that must never be the first character of a column (行頭禁則). */
    private const val NO_COLUMN_START =
        "、。，．！？：；％‰℃" +
            ",.:;!?" +
            "︑︒︐︖︓︔︕" +
            "）｝］〕〗〙〛︶︸︺" +
            ")]}>" +
            "」』】》〉﹂﹄︼︾﹀" +
            "…‥・ー〜ゝゞヽヾ々" +
            "—―" +
            "︙︱" +
            "ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ"

    /** Glyphs that must never be the last character of a column (行末禁則). */
    private const val NO_COLUMN_END =
        "（｛［〔〖〘〚︵︷︹" +
            "([{<" +
            "「『【《〈﹁﹃︻︽︿"

    private const val NEWLINE = '\n'

    /** One positioned glyph. A surrogate pair stays a single unit. */
    data class Glyph(val text: String, val column: Int, val row: Int)

    data class Result(val glyphs: List<Glyph>, val columns: Int, val usedRows: Int)

    private data class Cluster(val units: List<String>)

    fun breakText(text: String, maxRows: Int): Result {
        val rows = maxRows.coerceAtLeast(1)
        val glyphs = ArrayList<Glyph>(text.length)
        var column = 0
        var row = 0
        var usedRows = 0
        for (cluster in buildClusters(text)) {
            if (cluster == null) {
                // Explicit paragraph break: the empty column is intentional.
                column += 1
                row = 0
                continue
            }
            val start = skipLeadingSpaces(cluster)
            if (start >= cluster.units.size) {
                // Whitespace never opens a column; inside a filled column it keeps its
                // row so words written with spaces stay visually separated.
                if (row > 0 && row < rows) {
                    row += 1
                    if (row > usedRows) usedRows = row
                }
                continue
            }
            // Decide before touching the column counter: skipping an all-blank cluster
            // must never leave an empty column behind.
            if (row > 0 && row + (cluster.units.size - start) > rows) {
                column += 1
                row = 0
            }
            for (index in start until cluster.units.size) {
                glyphs.add(Glyph(VerticalTextSymbolConverter.convert(cluster.units[index]), column, row))
                row += 1
            }
            if (row > usedRows) usedRows = row
        }
        return Result(glyphs = glyphs, columns = column + 1, usedRows = usedRows)
    }

    private fun skipLeadingSpaces(cluster: Cluster): Int {
        var start = 0
        while (start < cluster.units.size && cluster.units[start].isBlank()) start += 1
        return start
    }

    /** Splits the text into clusters, keeping a null entry for every explicit newline. */
    private fun buildClusters(text: String): List<Cluster?> {
        val tokens = holdOpeners(mergeNoStart(tokenize(text)))
        val clusters = ArrayList<Cluster?>(tokens.size)
        for (token in tokens) {
            if (token == null) {
                clusters.add(null)
            } else if (token.isNotEmpty()) {
                clusters.add(Cluster(unitsOf(token)))
            }
        }
        return clusters
    }

    /**
     * Emits explicit newlines (null), whitespace runs (single space), one token per
     * Latin/number word with its inner and trailing symbols, and one token per other
     * code point. Tokens never depend on [maxRows].
     */
    private fun tokenize(text: String): List<String?> {
        val tokens = ArrayList<String?>(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val length = Character.charCount(codePoint)
            // A mark that follows a non-word base (e.g. "и" + U+0301) has to stay with it,
            // otherwise the mark would be laid out as a glyph of its own in the next column.
            if (isGraphemeExtend(codePoint)) {
                val mark = text.substring(index, index + length)
                if (tokens.isNotEmpty() && tokens[tokens.lastIndex] != null) {
                    tokens[tokens.lastIndex] = tokens[tokens.lastIndex] + mark
                } else {
                    tokens.add(mark)
                }
                index += length
                continue
            }
            if (text[index] == NEWLINE) {
                tokens.add(null)
                index += length
                continue
            }
            if (text[index].isWhitespace()) {
                val start = index
                while (index < text.length && text[index].isWhitespace() &&
                    text[index] != NEWLINE
                ) {
                    index += Character.charCount(text.codePointAt(index))
                }
                if (index > start) tokens.add(" ")
                continue
            }
            if (isWordCodePoint(codePoint) || isSignedNumberStart(text, index, codePoint)) {
                index = addWord(text, index, tokens)
                continue
            }
            tokens.add(text.substring(index, index + length))
            index += length
        }
        return tokens
    }

    /** Collects one Latin/number run, keeping separators and trailing symbols inside. */
    private fun addWord(text: String, from: Int, tokens: MutableList<String?>): Int {
        val units = ArrayList<String>()
        var cursor = from
        while (cursor < text.length) {
            val current = text.codePointAt(cursor)
            val currentLength = Character.charCount(current)
            val nextStart = cursor + currentLength
            val nextCodePoint = if (nextStart < text.length) text.codePointAt(nextStart) else -1
            if (isWordContinuation(current)) {
                units.add(text.substring(cursor, nextStart))
                cursor = nextStart
                continue
            }
            // Joins two parts: "1,200", "12－34", "A+B", "50%off".
            if (isWordConnector(current) && isWordCodePoint(nextCodePoint)) {
                units.add(text.substring(cursor, nextStart))
                cursor = nextStart
                continue
            }
            // Symbol that belongs to the end of the term: "12%", "C++", "C#", "5℃".
            if (isWordSuffix(current)) {
                units.add(text.substring(cursor, nextStart))
                cursor = nextStart
                continue
            }
            break
        }
        if (units.isNotEmpty()) tokens.add(units.joinToString(""))
        return cursor
    }

    /**
     * Attaches closing punctuation to the previous visible token. Whitespace between
     * a glyph and its punctuation is dropped, never used as the anchor. A run of
     * punctuation is collected in [suffix] and appended once, so a long run stays
     * linear instead of copying the growing token for every character.
     */
    private fun mergeNoStart(tokens: List<String?>): List<String?> {
        val merged = ArrayList<String?>(tokens.size)
        val suffix = StringBuilder()
        var anchor = -1
        fun flush() {
            if (anchor >= 0) {
                merged[anchor] = merged[anchor] + suffix.toString()
                suffix.setLength(0)
                anchor = -1
            }
        }
        for (token in tokens) {
            if (token == null) {
                flush()
                merged.add(null)
                continue
            }
            if (token.isBlank()) {
                merged.add(token)
                continue
            }
            if (token.first() in NO_COLUMN_START) {
                if (anchor < 0) {
                    var target = merged.size - 1
                    while (target >= 0 && merged[target]?.isBlank() == true) target -= 1
                    if (target < 0 || merged[target] == null) {
                        // A paragraph that genuinely starts with punctuation has no
                        // previous glyph to attach it to, so it has to open the column.
                        merged.add(token)
                        continue
                    }
                    // Drop the whitespace the punctuation was written after.
                    while (merged.size - 1 > target) merged.removeAt(merged.size - 1)
                    anchor = target
                } else {
                    while (merged.size - 1 > anchor && merged.last()?.isBlank() == true) {
                        merged.removeAt(merged.size - 1)
                    }
                }
                suffix.append(token)
                continue
            }
            flush()
            merged.add(token)
        }
        flush()
        return merged
    }

    /**
     * Holds opening brackets back until the following visible token is known, so an
     * opener never ends a column and never carries a space into a new one.
     */
    private fun holdOpeners(tokens: List<String?>): List<String?> {
        val held = ArrayList<String?>(tokens.size)
        val pending = StringBuilder()
        fun flushPending() {
            if (pending.isNotEmpty()) {
                held.add(pending.toString())
                pending.setLength(0)
            }
        }
        for (token in tokens) {
            if (token == null) {
                flushPending()
                held.add(null)
                continue
            }
            if (token.isBlank()) {
                // A space right after an opener is swallowed by it.
                if (pending.isEmpty()) held.add(token)
                continue
            }
            val bodyEnd = openerSuffixStart(token)
            val body = token.substring(0, bodyEnd)
            val openers = token.substring(bodyEnd)
            if (body.isNotEmpty()) {
                held.add(pending.toString() + body)
                pending.setLength(0)
            }
            pending.append(openers)
        }
        flushPending()
        return held
    }

    /** Index where the trailing run of opening brackets starts (token length if none). */
    private fun openerSuffixStart(token: String): Int {
        var index = token.length
        while (index > 0 && token[index - 1] in NO_COLUMN_END) index -= 1
        return index
    }

    /**
     * Splits a token into drawable units. A combining mark or variation selector joins
     * the preceding code point, so "e" + U+0301 and "か" + U+3099 are measured and drawn
     * as one glyph instead of taking their own rows. Boundaries are taken once, so a long
     * run of marks stays linear.
     */
    private fun unitsOf(token: String): List<String> {
        val starts = ArrayList<Int>(token.length)
        var index = 0
        while (index < token.length) {
            val codePoint = token.codePointAt(index)
            if (starts.isEmpty() || !isGraphemeExtend(codePoint)) starts.add(index)
            index += Character.charCount(codePoint)
        }
        val units = ArrayList<String>(starts.size)
        for (position in starts.indices) {
            val end = if (position + 1 < starts.size) starts[position + 1] else token.length
            units.add(token.substring(starts[position], end))
        }
        return units
    }

    private fun isWordCodePoint(codePoint: Int): Boolean {
        if (codePoint < 0 || codePoint >= 0x10000) return false
        val ch = codePoint.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
            ch in 'Ａ'..'Ｚ' || ch in 'ａ'..'ｚ' || ch in '０'..'９'
        ) {
            return true
        }
        // Accented Latin letters ("café", "Škoda") keep the term in one column too.
        return (ch in '\u00C0'..'\u024F' || ch in '\u1E00'..'\u1EFF') && ch.isLetter()
    }

    /** A combining mark or variation selector rides with the preceding glyph. */
    private fun isGraphemeExtend(codePoint: Int): Boolean = when (codePoint) {
        in 0x0300..0x036F, in 0x0483..0x0489, in 0x0591..0x05C7,
        in 0x0610..0x061A, in 0x064B..0x065F, 0x0670, in 0x06D6..0x06ED,
        in 0x1AB0..0x1AFF, in 0x1DC0..0x1DFF, in 0x20D0..0x20FF,
        in 0x3099..0x309A, in 0xFE00..0xFE0F, in 0xFE20..0xFE2F,
        in 0xE0100..0xE01EF -> true
        else -> false
    }

    /** A combining mark continues the preceding word. */
    private fun isWordContinuation(codePoint: Int): Boolean =
        isWordCodePoint(codePoint) || isGraphemeExtend(codePoint)

    /** Sign that opens a number: "-12", "－0.5". */
    private fun isWordPrefix(codePoint: Int): Boolean {
        if (codePoint >= 0x10000) return false
        return codePoint.toChar() in "-－−"
    }

    /** True when a sign is followed by a term and therefore opens a new word. */
    private fun isSignedNumberStart(text: String, index: Int, sign: Int): Boolean {
        if (!isWordPrefix(sign)) return false
        val next = index + Character.charCount(sign)
        return next < text.length && isWordCodePoint(text.codePointAt(next))
    }

    /** Both ASCII and vertical/full-width forms, because some text arrives converted. */
    private fun isWordConnector(codePoint: Int): Boolean {
        if (codePoint >= 0x10000) return false
        return when (codePoint.toChar()) {
            '.', ',', ':', ';', '-', '/', '_', '%', '&', '+', '#', '@', '\'', '’',
            '．', '，', '：', '；', '︐', '︒', '︓', '︔', '︱', '－', '／', '＿', '％',
            '＋', '−' -> true
            else -> false
        }
    }

    /** Symbols that stay attached to the end of a term: "12%", "C++", "C#", "5℃". */
    private fun isWordSuffix(codePoint: Int): Boolean {
        if (codePoint >= 0x10000) return false
        return when (codePoint.toChar()) {
            '%', '％', '‰', '℃', '°', '+', '＋', '#', '＃', '&', '＆' -> true
            else -> false
        }
    }
}
