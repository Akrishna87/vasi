package io.github.akrishna87.vaasi.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTest {
    private val book = Book(
        "id", "Title", 3,
        listOf(
            Paragraph(1, listOf("A.", "B.")),
            Paragraph(2, listOf("C.")),
            Paragraph(3, listOf("D.", "E.", "F.")),
        ),
    )

    @Test
    fun numbersSentencesThroughTheBook() {
        assertEquals(6, book.sentenceCount)
        assertEquals(listOf("A.", "B.", "C.", "D.", "E.", "F."), (0 until 6).map(book::sentence))
        assertEquals(listOf(0, 0, 1, 2, 2, 2), (0 until 6).map(book::paragraphOf))
        assertEquals(listOf(1, 1, 2, 3, 3, 3), (0 until 6).map(book::pageOf))
        assertEquals(3, book.firstSentenceOf(2))
    }

    @Test
    fun knowsParagraphEnds() {
        assertFalse(book.isLastInParagraph(0))
        assertTrue(book.isLastInParagraph(1))
        assertTrue(book.isLastInParagraph(2))
        assertTrue(book.isLastInParagraph(5))
    }

    @Test
    fun survivesJson() {
        val copy = Book.fromJson(book.toJson())
        assertEquals(book.title, copy.title)
        assertEquals(book.pages, copy.pages)
        assertEquals(book.paragraphs, copy.paragraphs)
    }

    @Test
    fun buildsFromPages() {
        val b = Book.fromPages("x", "T", listOf("Hello there. General Kenobi!", "", "The end."))
        assertEquals(3, b.pages)
        assertEquals(listOf("Hello there.", "General Kenobi!", "The end."), (0 until b.sentenceCount).map(b::sentence))
        assertEquals(3, b.pageOf(2))
    }

    @Test
    fun segmentsLineUpWithSentences() {
        val b = Book("x", "T", 1, listOf(Paragraph(1, listOf("“Hi,” she said.", "Fine."))))
        assertEquals(b.sentenceCount, b.segments.size)
        assertEquals(2, b.segments[0].size)
    }
}
