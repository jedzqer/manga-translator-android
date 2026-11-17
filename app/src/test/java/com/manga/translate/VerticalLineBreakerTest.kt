package com.manga.translate

import com.manga.translate.rendering.VerticalLineBreaker
import com.manga.translate.rendering.VerticalTextSymbolConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalLineBreakerTest {

    /**
     * 行頭禁則 characters listed in the issue, converted the way the renderer draws
     * them. Deliberately built from the issue, not copied from the breaker.
     */
    private val noColumnStart =
        VerticalTextSymbolConverter.convert("、。，．！？：；）｝］」』】》〉…ー")

    /** 行末禁則 characters listed in the issue, also converted. */
    private val noColumnEnd =
        VerticalTextSymbolConverter.convert("（｛［「『【《〈")

    /** One entry per column, glyph text top to bottom; empty columns are kept. */
    private fun columns(result: VerticalLineBreaker.Result): List<List<String>> =
        (0 until result.columns).map { index ->
            result.glyphs.filter { it.column == index }.sortedBy { it.row }.map { it.text }
        }

    private fun assertKinsoku(result: VerticalLineBreaker.Result) {
        for ((index, column) in columns(result).withIndex()) {
            if (column.isEmpty()) continue
            assertTrue(
                "column $index must not start with closing punctuation: $column",
                column.first() !in noColumnStart
            )
            assertTrue(
                "column $index must not end with an opening bracket: $column",
                column.last() !in noColumnEnd
            )
        }
    }

    @Test
    fun closingPunctuationFollowsThePreviousGlyph() {
        val result = VerticalLineBreaker.breakText("テスト、次", maxRows = 3)
        assertKinsoku(result)
        assertEquals(
            listOf(listOf("テ", "ス"), listOf("ト", "︑", "次")),
            columns(result)
        )
    }

    @Test
    fun closingPunctuationHangsInsteadOfOpeningAColumn() {
        // With one row per column '要。' cannot fit, so the pair hangs below the
        // single row; '。' must never be the first glyph of the next column.
        val result = VerticalLineBreaker.breakText("要。次", maxRows = 1)
        assertKinsoku(result)
        assertEquals(listOf(listOf("要", "︒"), listOf("次")), columns(result))
        assertEquals(2, result.usedRows)
    }

    @Test
    fun openingBracketTravelsWithTheNextGlyph() {
        val result = VerticalLineBreaker.breakText("テスト「甲", maxRows = 2)
        assertKinsoku(result)
        assertEquals(
            listOf(listOf("テ", "ス"), listOf("ト"), listOf("﹁", "甲")),
            columns(result)
        )
    }

    @Test
    fun openingBracketRunStaysWhole() {
        // '「『甲' is one indivisible cluster: it hangs as a whole rather than
        // leaving '『' or '「' at the end of the previous column.
        val result = VerticalLineBreaker.breakText("「『甲", maxRows = 2)
        assertKinsoku(result)
        assertEquals(listOf(listOf("﹁", "﹃", "甲")), columns(result))
        assertEquals(3, result.usedRows)
    }

    @Test
    fun latinWordStaysInsideOneColumn() {
        val result = VerticalLineBreaker.breakText("甲ABC", maxRows = 3)
        assertEquals(listOf(listOf("甲"), listOf("A", "B", "C")), columns(result))
    }

    @Test
    fun numberStaysInsideOneColumn() {
        // "1,200" is one term: the connector and the digits may not be split.
        val result = VerticalLineBreaker.breakText("価格は1,200円", maxRows = 3)
        assertKinsoku(result)
        assertEquals(
            listOf(listOf("価", "格", "は"), listOf("1", "︐", "2", "0", "0"), listOf("円")),
            columns(result)
        )
        assertEquals(
            VerticalTextSymbolConverter.convert("価格は1,200円"),
            result.glyphs.map { it.text }.joinToString("")
        )
    }

    @Test
    fun wordSuffixStaysAttachedToTheWord() {
        assertEquals(
            listOf(listOf("甲", "1", "2", "%"), listOf("増")),
            columns(VerticalLineBreaker.breakText("甲12%増", maxRows = 4))
        )
        // Same text with the suffix exactly at the column limit: '12' must not be
        // left alone while '%' opens the next column.
        assertEquals(
            listOf(listOf("甲"), listOf("1", "2", "%"), listOf("増")),
            columns(VerticalLineBreaker.breakText("甲12%増", maxRows = 3))
        )
        assertEquals(
            listOf(listOf("甲"), listOf("C", "+", "+"), listOf("増")),
            columns(VerticalLineBreaker.breakText("甲C++増", maxRows = 3))
        )
        assertEquals(
            listOf(listOf("甲", "C", "#"), listOf("増")),
            columns(VerticalLineBreaker.breakText("甲C#増", maxRows = 3))
        )
        assertEquals(
            listOf(listOf("甲", "R", "&", "D"), listOf("増")),
            columns(VerticalLineBreaker.breakText("甲R&D増", maxRows = 4))
        )
    }

    @Test
    fun halfWidthAndFullWidthMinusKeepTheNumberWhole() {
        assertEquals(
            listOf(listOf("甲"), listOf("1", "2", "-", "3", "4")),
            columns(VerticalLineBreaker.breakText("甲12-34", maxRows = 3))
        )
        // The full-width '－' becomes the vertical form '︱'; it still joins the digits.
        val result = VerticalLineBreaker.breakText("甲12－34", maxRows = 3)
        assertEquals(listOf(listOf("甲"), listOf("1", "2", "︱", "3", "4")), columns(result))
        assertEquals(5, result.usedRows)
    }

    @Test
    fun closingPunctuationNeverAnchorsOnWhitespace() {
        val result = VerticalLineBreaker.breakText("甲 。乙", maxRows = 2)
        assertKinsoku(result)
        assertEquals(listOf(listOf("甲", "︒"), listOf("乙")), columns(result))
    }

    @Test
    fun openingBracketSwallowsTheFollowingSpace() {
        val result = VerticalLineBreaker.breakText("「 甲", maxRows = 2)
        assertKinsoku(result)
        assertEquals(listOf(listOf("﹁", "甲")), columns(result))
    }

    @Test
    fun longVowelMarkDoesNotOpenAColumn() {
        // 'ー' converts to '︱', which belongs to 行頭禁則 and must follow 'ラ'.
        val result = VerticalLineBreaker.breakText("ラーメン", maxRows = 1)
        assertKinsoku(result)
        assertEquals(listOf(listOf("ラ", "︱"), listOf("メ"), listOf("ン")), columns(result))
    }

    @Test
    fun longVowelAndMinusStayDistinct() {
        // Both 'ー' and '－' render as '︱', so the breaker works on the original text:
        // a long vowel follows its kana, a minus opens its number's column.
        val longVowel = VerticalLineBreaker.breakText("ラー12", maxRows = 2)
        assertKinsoku(longVowel)
        assertEquals(listOf(listOf("ラ", "︱"), listOf("1", "2")), columns(longVowel))
        val minus = VerticalLineBreaker.breakText("ラ－12", maxRows = 2)
        assertEquals(listOf(listOf("ラ"), listOf("︱", "1", "2")), columns(minus))
        assertEquals(3, minus.usedRows)
    }

    @Test
    fun combiningMarkFollowsANonLatinBase() {
        // The mark has to attach before tokens are formed: "и" is not a word code point,
        // so a token-level merge would have put U+0301 into the next column on its own.
        val cyrillic = VerticalLineBreaker.breakText("и\u0301あ", maxRows = 1)
        assertEquals(listOf(listOf("и\u0301"), listOf("あ")), columns(cyrillic))
        val kana = VerticalLineBreaker.breakText("か\u3099き", maxRows = 1)
        assertEquals(listOf(listOf("か\u3099"), listOf("き")), columns(kana))
    }

    @Test
    fun emDashNeverOpensAColumn() {
        // '—' converts to '︱' just like a minus, but it is punctuation and must stay
        // with the preceding glyph; only a signed number may open a column with '︱'.
        val result = VerticalLineBreaker.breakText("甲—乙", maxRows = 1)
        assertKinsoku(result)
        assertEquals(listOf(listOf("甲", "︱"), listOf("乙")), columns(result))
    }

    @Test
    fun convertedPunctuationStillFollowsKinsokuRules() {
        // Unambiguous presentation forms still obey 行頭禁則 if a caller hands them in
        // converted. A converted minus ("︱") is deliberately not treated as a sign: it
        // is indistinguishable from a long vowel, so the breaker needs the original text.
        assertEquals(
            listOf(listOf("テ", "ス"), listOf("ト", "︑", "次")),
            columns(VerticalLineBreaker.breakText("テスト︑次", maxRows = 3))
        )
    }

    @Test
    fun trailingSpacesDoNotOpenAnExtraColumn() {
        assertEquals(1, VerticalLineBreaker.breakText("甲乙 ", maxRows = 2).columns)
    }

    @Test
    fun whitespaceBeforeAHardNewlineDoesNotOpenAnExtraColumn() {
        // The space used to bump the column counter before being dropped, so the
        // paragraph break started from a column that held nothing at all.
        val result = VerticalLineBreaker.breakText("甲乙 \n丙", maxRows = 2)
        assertEquals(listOf(listOf("甲", "乙"), listOf("丙")), columns(result))
    }

    @Test
    fun whitespaceBetweenWordsKeepsItsRow() {
        // Dots in drawn columns would hide a swallowed space: check the row indexes.
        val result = VerticalLineBreaker.breakText("甲 乙", maxRows = 3)
        assertEquals(listOf(listOf("甲", "乙")), columns(result))
        val byText = result.glyphs.associateBy { it.text }
        assertEquals(0, byText.getValue("甲").row)
        assertEquals(2, byText.getValue("乙").row)
        assertEquals(3, result.usedRows)
    }

    @Test
    fun aWordIsNeverSplitSoSmallerFontsCannotOverflow() {
        // Regression: chunking words by maxRows made the text fit at a large font and
        // overflow at a smaller one, because the chunks changed with the row count.
        // The size search assumes shrinking only ever helps.
        for (rows in 1..8) {
            val result = VerticalLineBreaker.breakText("ABCDEF。", maxRows = rows)
            assertEquals("rows=$rows", 1, result.columns)
            assertEquals("rows=$rows", 7, result.usedRows)
        }
    }

    @Test
    fun overlongWordHangsInsteadOfBeingSplit() {
        val text = "supercalifragilistic、"
        val result = VerticalLineBreaker.breakText(text, maxRows = 5)
        assertEquals(1, result.columns)
        assertEquals(text.length, result.usedRows)
        // Glyphs carry the vertical presentation form, so compare against the conversion.
        assertEquals(
            VerticalTextSymbolConverter.convert(text),
            result.glyphs.map { it.text }.joinToString("")
        )
    }

    @Test
    fun surrogatePairCountsAsOneGlyph() {
        val result = VerticalLineBreaker.breakText("𠮷野家", maxRows = 2)
        assertEquals(listOf("𠮷", "野", "家"), result.glyphs.map { it.text })
        assertEquals(listOf(listOf("𠮷", "野"), listOf("家")), columns(result))
    }

    @Test
    fun explicitNewlineStillStartsANewColumn() {
        val result = VerticalLineBreaker.breakText("甲\n乙\n丙", maxRows = 100)
        assertEquals(3, result.columns)
        assertEquals(1, result.usedRows)
    }

    @Test
    fun paragraphGapKeepsEmptyColumn() {
        val result = VerticalLineBreaker.breakText("春夏\n\n秋冬", maxRows = 100)
        assertEquals(3, result.columns)
        assertTrue(columns(result)[1].isEmpty())
        assertEquals(listOf("秋", "冬"), columns(result)[2])
    }

    @Test
    fun rowsAreContiguousFromZeroInEveryColumn() {
        val result = VerticalLineBreaker.breakText(
            "これはテストです、しかし」と言った（重要）。次",
            maxRows = 6
        )
        assertKinsoku(result)
        assertEquals(
            listOf(
                listOf("こ", "れ", "は", "テ", "ス", "ト"),
                listOf("で", "す", "︑", "し", "か"),
                listOf("し", "﹂", "と", "言", "っ", "た"),
                listOf("︵", "重", "要", "︶", "︒", "次")
            ),
            columns(result)
        )
        for (column in result.glyphs.groupBy { it.column }.values) {
            assertEquals(
                (0 until column.size).toList(),
                column.sortedBy { it.row }.map { it.row }
            )
        }
    }

    @Test
    fun paragraphStartingWithPunctuationOpensItsColumn() {
        // Nothing precedes the punctuation, so it has to start the column.
        val result = VerticalLineBreaker.breakText("。甲", maxRows = 3)
        assertEquals(listOf(listOf("︒", "甲")), columns(result))
    }

    @Test
    fun openingBracketBeforeAHardNewlineClosesTheColumn() {
        // An explicit paragraph break cannot be crossed, so the bracket stays.
        val result = VerticalLineBreaker.breakText("甲「\n乙", maxRows = 4)
        assertEquals(listOf(listOf("甲", "﹁"), listOf("乙")), columns(result))
    }

    @Test
    fun minusKeepsTheNumberInOneColumn() {
        assertEquals(
            listOf(listOf("甲"), listOf("︱", "1", "2"), listOf("乙")),
            columns(VerticalLineBreaker.breakText("甲－12乙", maxRows = 3))
        )
        assertEquals(
            listOf(listOf("︱", "1", "2"), listOf("円")),
            columns(VerticalLineBreaker.breakText("－12円", maxRows = 2))
        )
        assertEquals(
            listOf(listOf("甲"), listOf("-", "1", "2"), listOf("乙")),
            columns(VerticalLineBreaker.breakText("甲-12乙", maxRows = 2))
        )
        assertEquals(3, VerticalLineBreaker.breakText("甲-12乙", maxRows = 2).usedRows)
    }

    @Test
    fun accentedLatinWordStaysInsideOneColumn() {
        val precomposed = VerticalLineBreaker.breakText("甲café乙", maxRows = 4)
        assertEquals(
            listOf(listOf("甲"), listOf("c", "a", "f", "é"), listOf("乙")),
            columns(precomposed)
        )
        // Decomposed form: 'e' + U+0301 is one drawable glyph, so it takes the same
        // four rows as the precomposed letter instead of adding a fifth row.
        val decomposed = VerticalLineBreaker.breakText("甲cafe\u0301乙", maxRows = 4)
        assertEquals(
            listOf(listOf("甲"), listOf("c", "a", "f", "e\u0301"), listOf("乙")),
            columns(decomposed)
        )
        assertEquals(4, decomposed.usedRows)
        assertEquals(precomposed.usedRows, decomposed.usedRows)
    }

    @Test
    fun openingBracketHangsWithItsGlyphEvenAtOneRow() {
        val result = VerticalLineBreaker.breakText("「甲", maxRows = 1)
        assertKinsoku(result)
        assertEquals(listOf(listOf("﹁", "甲")), columns(result))
        assertEquals(2, result.usedRows)
    }

    @Test
    fun openingBracketAtTheEndOfTheTextHasNoFollowingGlyph() {
        // Last resort: nothing follows, so the bracket can only close the column.
        val result = VerticalLineBreaker.breakText("甲乙「", maxRows = 2)
        assertEquals(listOf(listOf("甲", "乙"), listOf("﹁")), columns(result))
    }

    @Test
    fun explicitNewlinesAtTheEdgesKeepTheirColumns() {
        assertEquals(listOf(listOf<String>(), listOf("甲"), listOf()), columns(
            VerticalLineBreaker.breakText("\n甲\n", maxRows = 2)
        ))
        val blank = VerticalLineBreaker.breakText("\n", maxRows = 2)
        assertEquals(2, blank.columns)
        assertTrue(blank.glyphs.isEmpty())
    }

    @Test
    fun emptyAndBlankTextStayEmpty() {
        for (text in listOf("", "   ", "\n")) {
            val result = VerticalLineBreaker.breakText(text, maxRows = 3)
            assertTrue("text=$text", result.glyphs.isEmpty())
        }
        assertEquals(1, VerticalLineBreaker.breakText("", maxRows = 3).columns)
        assertEquals(0, VerticalLineBreaker.breakText("   ", maxRows = 3).usedRows)
    }
}
