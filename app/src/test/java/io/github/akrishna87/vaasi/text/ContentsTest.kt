package io.github.akrishna87.vaasi.text

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentsTest {
    private val prose = "This is ordinary prose that goes on for a while. It has more than one sentence in it, " +
        "so it reads like the body of a book rather than a heading or a line from the contents."

    private fun p(page: Int, vararg sentences: String) = Paragraph(page, sentences.toList())
    private fun body(page: Int) = Paragraph(page, Sentences.split(prose))

    private fun book(vararg paragraphs: Paragraph) = Book("id", "T", paragraphs.last().page, paragraphs.toList())

    @Test
    fun startsAtTheIntroductionAfterTheFrontMatter() {
        val b = book(
            p(1, "The Patient Learner"),
            p(2, "Copyright © 2024 A. Writer.", "All rights reserved."),
            p(3, "Contents"), p(3, "Introduction 4"), p(3, "Chapter 1: The First Week 6"), p(3, "Chapter 2: The Plateau 9"),
            p(4, "Introduction"), body(4),
            p(6, "Chapter 1: The First Week"), body(6),
            p(9, "Chapter 2: The Plateau"), body(9),
        )
        assertEquals("Introduction", b.sentence(b.start))
        assertEquals(listOf("Introduction", "Chapter 1: The First Week", "Chapter 2: The Plateau"), b.chapters.map { it.title })
        assertEquals("Chapter 2: The Plateau", b.chapterAt(b.sentenceCount - 1)?.title)
    }

    @Test
    fun prefersTheIntroductionToAPreface() {
        val b = book(
            p(1, "Contents"), p(1, "Preface 2"), p(1, "Introduction 3"), p(1, "Chapter 1 5"), p(1, "Chapter 2 7"),
            p(2, "Preface"), body(2),
            p(3, "Introduction"), body(3),
            p(5, "Chapter 1"), body(5),
        )
        assertEquals("Introduction", b.sentence(b.start))
        assertEquals(listOf("Preface", "Introduction", "Chapter 1"), b.chapters.map { it.title })
    }

    @Test
    fun startsAtChapterOneOrANumberedSection() {
        val novel = book(p(1, "A Novel"), p(2, "For my mother"), p(3, "Copyright 2020."), p(4, "Chapter One"), body(4), body(5))
        assertEquals("Chapter One", novel.sentence(novel.start))

        val report = book(p(1, "Annual Report"), p(2, "© 2025 Office."), p(3, "1. Introduction"), body(3), p(4, "2. Results"), body(4))
        assertEquals("1. Introduction", report.sentence(report.start))
    }

    @Test
    fun aPlainDocumentStartsAtTheTop() {
        val letter = book(p(1, "Dear Ravi,", "Thank you for your note."), body(1), p(1, "With love, Amma"))
        assertEquals(0, letter.start)
        assertEquals(emptyList<Chapter>(), letter.chapters)
    }

    @Test
    fun skipsFrontMatterEvenWithoutHeadings() {
        val b = book(p(1, "My Essay"), p(2, "Copyright 2021 Someone.", "All rights reserved."), body(3), body(3))
        assertEquals(b.firstSentenceOf(2), b.start)
    }

    @Test
    fun usesThePdfsBookmarks() {
        val paragraphs = listOf(
            p(1, "Title"), p(2, "Contents"), p(2, "Welcome 3"),
            p(3, "Welcome"), body(3), p(5, "The Middle Bit"), body(5),
        )
        val (chapters, start) = Contents.find(paragraphs, listOf(OutlineEntry("Welcome", 3), OutlineEntry("The Middle Bit", 5)))
        val b = Book("id", "T", 5, paragraphs, chapters, start)
        assertEquals(listOf("Welcome", "The Middle Bit"), b.chapters.map { it.title })
        assertEquals("Welcome", b.sentence(b.chapters[0].sentence))
        assertEquals("The Middle Bit", b.sentence(b.chapters[1].sentence))
        // "Welcome" isn't an obvious opener, but it's the first heading past the contents.
        assertEquals("Welcome", b.sentence(b.start))
    }

    @Test
    fun savesAndRestoresChapters() {
        val b = Book.fromPages(
            "x", "T",
            listOf("Copyright 2024 Me. All rights reserved.", "Introduction\n\n$prose", "Chapter 1\n\n$prose"),
        )
        val copy = Book.fromJson(b.toJson())
        assertEquals(b.start, copy.start)
        assertEquals(b.chapters, copy.chapters)
        assertEquals("Introduction", copy.sentence(copy.start))
    }
}
