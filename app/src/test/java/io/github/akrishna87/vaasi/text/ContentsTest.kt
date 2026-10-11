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

    /** An ebook laid out like a Simon & Schuster one: a mailing-list page first, contents without
     * page numbers, and the copyright page at the back. */
    private fun ebook(introduction: List<Paragraph>) = listOf(
        p(2, "Thank you for downloading this Simon & Schuster ebook."),
        p(2, "Get a FREE ebook when you join our mailing list.", "Plus, get updates on new releases, deals, and more."),
        p(2, "CLICK HERE TO SIGN UP"),
        p(3, "The Courage to Be Disliked"), p(3, "Ichiro Kishimi and Fumitake Koga"),
        p(4, "Contents"), p(4, "Authors’ Note"), p(4, "Introduction"),
        p(4, "THE FIRST NIGHT: Deny Trauma"), p(4, "THE SECOND NIGHT: All Problems Are Interpersonal Relationship Problems"),
        p(5, "THE THIRD NIGHT: Discard Other People’s Tasks"), p(5, "Afterword"), p(5, "About the Authors"), p(5, "Copyright"),
        p(6, "Authors’ Note"), body(6),
    ) + introduction + listOf(
        p(12, "THE FIRST NIGHT"), p(12, "Deny Trauma"), body(12), body(13),
        p(40, "THE SECOND NIGHT"), p(40, "All Problems Are Interpersonal Relationship Problems"), body(40),
        p(80, "THE THIRD NIGHT"), p(80, "Discard Other People’s Tasks"), body(80),
        p(240, "Afterword"), body(240),
        p(250, "About the Authors"), body(250),
        p(254, "Copyright © 2013 by Ichiro Kishimi and Fumitake Koga.", "All rights reserved."),
    )

    @Test
    fun findsTheIntroductionFromTheContentsListWhenItsHeadingRunsIntoTheText() {
        val paragraphs = ebook(listOf(Paragraph(8, Sentences.split("Introduction On the outskirts of the thousand-year-old city lived a philosopher. $prose"))))
        val b = Book("id", "T", 254, paragraphs)
        assertEquals(b.firstSentenceOf(paragraphs.indexOfFirst { it.page == 8 }), b.start)
        assertEquals(
            listOf(
                "Authors’ Note", "Introduction", "THE FIRST NIGHT: Deny Trauma",
                "THE SECOND NIGHT: All Problems Are Interpersonal Relationship Problems",
                "THE THIRD NIGHT: Discard Other People’s Tasks", "Afterword", "About the Authors", "Copyright",
            ),
            b.chapters.map { it.title },
        )
        assertEquals("THE FIRST NIGHT", b.sentence(b.chapters[2].sentence))
    }

    @Test
    fun startsAtTheIntroductionOfAnEbook() {
        val b = Book("id", "T", 254, ebook(listOf(p(8, "Introduction"), body(8))))
        assertEquals("Introduction", b.sentence(b.start))
    }

    @Test
    fun aCopyrightPageAtTheBackDoesNotHideTheStart() {
        // No contents and no headings to go on: start past the title page and copyright.
        val b = book(
            p(1, "A Short Book"), p(2, "Copyright 2020 Someone.", "All rights reserved."),
            body(3), body(4), body(5), body(30),
            p(60, "Copyright © 2020 Someone.", "Printed in India."),
        )
        assertEquals(b.firstSentenceOf(2), b.start)
    }

    @Test
    fun takesAnotherLookAtBooksSavedAsStartingAtTheTop() {
        val b = Book("id", "T", 254, ebook(listOf(p(8, "Introduction"), body(8))), chapters = emptyList(), start = 0)
        // As an older version saved it: no contentsVersion, start 0.
        val old = org.json.JSONObject(b.toJson()).apply { remove("contentsVersion") }.toString()
        val again = Book.fromJson(old)
        assertEquals(false, again.hasContents)
        assertEquals("Introduction", again.sentence(again.start))
        // Once saved by this version, it's left alone.
        assertEquals(0, Book.fromJson(b.toJson()).start)
    }
}
