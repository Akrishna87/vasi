package io.github.akrishna87.vaasi.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencesTest {

    @Test
    fun splitsAtSentenceEnds() {
        assertEquals(
            listOf("It rained.", "Did it stop?", "No!", "It poured…", "Then it stopped."),
            Sentences.split("It rained. Did it stop? No! It poured… Then it stopped."),
        )
    }

    @Test
    fun keepsAbbreviationsAndInitialsTogether() {
        assertEquals(
            listOf("Dr. Watson met Mr. J. R. Holmes at 3 p.m. on Baker St. in London.", "They talked."),
            Sentences.split("Dr. Watson met Mr. J. R. Holmes at 3 p.m. on Baker St. in London. They talked."),
        )
        assertEquals(listOf("Prices rose 2.5 percent, e.g. bread."), Sentences.split("Prices rose 2.5 percent, e.g. bread."))
    }

    @Test
    fun keepsSectionNumbersWithTheirHeadings() {
        assertEquals(listOf("1. Introduction"), Sentences.split("1. Introduction"))
        assertEquals(listOf("2.3. Results and Discussion"), Sentences.split("2.3. Results and Discussion"))
        assertEquals(listOf("She was 5.", "Then she grew up."), Sentences.split("She was 5. Then she grew up."))
    }

    @Test
    fun keepsClosingQuotesWithTheirSentence() {
        assertEquals(
            listOf("“Stop!” she said.", "“Why?” he asked."),
            Sentences.split("“Stop!” she said. “Why?” he asked."),
        )
    }

    @Test
    fun doesNotSplitBeforeALowercaseWord() {
        assertEquals(listOf("He said wait... and then left."), Sentences.split("He said wait... and then left."))
    }

    @Test
    fun breaksVeryLongSentences() {
        val clause = "the river ran on past the old mill and the quiet fields"
        val long = (1..8).joinToString(", ") { "$clause $it" } + "."
        val parts = Sentences.split(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= Sentences.MAX_CHARS })
        assertEquals(long, parts.joinToString(" "))
    }

    @Test
    fun findsQuotedSpeech() {
        val segments = Sentences.segments(listOf("“Come here,” she said, “right now.”"))
        assertEquals(
            listOf(Segment("“Come here,”", true), Segment("she said,", false), Segment("“right now.”", true)),
            segments.single(),
        )
    }

    @Test
    fun quotesCanSpanSentences() {
        val segments = Sentences.segments(listOf("\"Wait.", "Don't go,\" he begged.", "She left."))
        assertEquals(listOf(Segment("\"Wait.", true)), segments[0])
        assertEquals(listOf(Segment("Don't go,\"", true), Segment("he begged.", false)), segments[1])
        assertEquals(listOf(Segment("She left.", false)), segments[2])
    }

    @Test
    fun plainTextIsOneNarratedSegment() {
        assertEquals(listOf(listOf(Segment("Nothing quoted here.", false))), Sentences.segments(listOf("Nothing quoted here.")))
    }
}
