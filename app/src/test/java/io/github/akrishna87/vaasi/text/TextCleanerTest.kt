package io.github.akrishna87.vaasi.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {

    private fun page(n: Int, body: String) = "The Long Walk Home\n\n$body\n\n$n"

    @Test
    fun dropsRunningHeadersAndPageNumbers() {
        val pages = (1..6).map { page(it, "Paragraph on page $it ends here.") }
        val text = TextCleaner.clean(pages).map { it.text }
        assertEquals((1..6).map { "Paragraph on page $it ends here." }, text)
    }

    @Test
    fun keepsTheTitleOfAShortDocument() {
        // With only two pages there's no telling a header from content, so nothing is dropped
        // except the page numbers.
        val text = TextCleaner.clean(listOf(page(1, "One."), page(2, "Two."))).map { it.text }
        assertEquals(listOf("The Long Walk Home", "One.", "The Long Walk Home", "Two."), text)
    }

    @Test
    fun joinsLinesAndMendsHyphenatedWords() {
        val pages = listOf("The quick brown fox jumped over the exam-\nple of a lazy dog, who did not\nmind at all.")
        assertEquals("The quick brown fox jumped over the example of a lazy dog, who did not mind at all.", TextCleaner.clean(pages).single().text)
    }

    @Test
    fun keepsRealHyphens() {
        val pages = listOf("It was a well-\nKnown fact.")
        assertEquals("It was a well- Known fact.", TextCleaner.clean(pages).single().text)
    }

    @Test
    fun carriesAParagraphOverAPageBreak() {
        val pages = listOf("It was the best of times, it was the\n", "worst of times.\n\nA new paragraph.")
        val paragraphs = TextCleaner.clean(pages)
        assertEquals(listOf("It was the best of times, it was the worst of times.", "A new paragraph."), paragraphs.map { it.text })
        assertEquals(listOf(1, 2), paragraphs.map { it.page })
    }

    @Test
    fun startsANewParagraphAfterAFinishedSentenceAtAPageBreak() {
        val paragraphs = TextCleaner.clean(listOf("The end of a thought.", "Something else entirely."))
        assertEquals(2, paragraphs.size)
    }

    @Test
    fun guessesParagraphsFromShortLastLines() {
        val lines = listOf(
            "This line is about as long as all the other lines in the body",
            "of the text, and so is this one, which carries on and on until",
            "it ends.",
            "Then a new paragraph begins with a line of about the usual size",
            "and finishes here.",
        )
        val text = TextCleaner.clean(listOf(lines.joinToString("\n"))).map { it.text }
        assertEquals(2, text.size)
        assertTrue(text[0].endsWith("until it ends."))
        assertTrue(text[1].startsWith("Then a new paragraph"))
    }

    @Test
    fun fixesLigaturesAndDropsCitations() {
        val text = TextCleaner.clean(listOf("The ﬁrst ﬂight was eﬃcient [12]. Really [3, 4].")).single().text
        assertEquals("The first flight was efficient. Really.", text)
    }

    @Test
    fun recognisesPageNumbers() {
        listOf("12", "- 12 -", "Page 3", "page 3 of 40", "iv", "(7)", "12 / 300").forEach {
            assertTrue(it, TextCleaner.isPageNumber(it))
        }
        listOf("Chapter 12", "12 Angry Men", "In 1999 we", "Hello").forEach {
            assertFalse(it, TextCleaner.isPageNumber(it))
        }
    }

    @Test
    fun dropsParagraphsWithoutWords() {
        assertTrue(TextCleaner.clean(listOf("1 . . . . . . . 12\n\n— — —")).isEmpty())
    }
}
