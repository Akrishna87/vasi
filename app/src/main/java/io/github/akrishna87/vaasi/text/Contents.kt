package io.github.akrishna87.vaasi.text

/** A place to jump to: a chapter or section heading, and the sentence it starts at. */
data class Chapter(val title: String, val sentence: Int)

/** A bookmark from the PDF's own outline (its "table of contents" panel). */
data class OutlineEntry(val title: String, val page: Int)

/**
 * Finds a book's chapters, and where its main text starts, so reading begins at the
 * Introduction (or Chapter 1) rather than at the title page, copyright notice and contents.
 */
object Contents {

    /** Headings that come before the main text. */
    private val frontMatter = Regex(
        "^(?:(?:table of )?contents|copyright|dedication|acknowledg(?:e)?ments?|list of (?:figures|tables|illustrations)|" +
            "about the author|also by|praise for|title page|half title|epigraph|abbreviations|foreword|preface)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Headings that open the main text: the first of these is where reading starts. */
    private val openers = listOf(
        Regex(
            "^(?:(?:\\d+\\.?\\s+)?introduction\\b|prologue\\b|chapter\\s+(?:1|one|i)\\b|part\\s+(?:1|one|i)\\b|" +
                "1\\.?\\s+\\p{Lu}|abstract\\b|executive summary\\b)",
            RegexOption.IGNORE_CASE,
        ),
        // Only when there's no introduction or first chapter to start at.
        Regex("^(?:preface|foreword)\\b", RegexOption.IGNORE_CASE),
    )

    /** Headings worth listing as chapters when the PDF has no bookmarks. */
    private val headingWords = Regex(
        "^(?:chapter|part|section|book|introduction|preface|foreword|prologue|epilogue|afterword|conclusions?|" +
            "appendix|abstract|summary|executive summary|background|references|bibliography|glossary|index|notes|" +
            "contents|table of contents|acknowledg(?:e)?ments?)\\b|^(?:\\d{1,2}(?:\\.\\d{1,2})*\\.?|[IVXLC]{1,6}\\.)\\s+\\p{Lu}",
        RegexOption.IGNORE_CASE,
    )

    private val copyright = Regex("copyright|©|all rights reserved|isbn|first published|printed in", RegexOption.IGNORE_CASE)

    /** A contents line: "4 Introduction", "Introduction 4", "Chapter 1 ..... 12". */
    private val contentsLine = Regex("^\\d{1,4}\\s*\\p{L}.{0,80}$|^.{1,80}\\D\\s\\d{1,4}$")

    /** The chapters, and the sentence to start reading at. */
    fun find(paragraphs: List<Paragraph>, outline: List<OutlineEntry> = emptyList()): Pair<List<Chapter>, Int> {
        val firstSentence = IntArray(paragraphs.size + 1).also { s ->
            paragraphs.forEachIndexed { i, p -> s[i + 1] = s[i] + p.sentences.size }
        }
        val contentsPages = contentsPages(paragraphs)

        val headings: List<Pair<String, Int>> = // (title, paragraph)
            fromOutline(paragraphs, outline).ifEmpty {
                fromContentsList(paragraphs, contentsPages).takeIf { it.size >= 2 } ?: fromHeadings(paragraphs, contentsPages)
            }
        val chapters = headings.map { (title, p) -> Chapter(title, firstSentence[p]) }
            .distinctBy { it.sentence }

        val startParagraph = startParagraph(paragraphs, headings, contentsPages)
        return chapters to firstSentence[startParagraph]
    }

    private fun text(p: Paragraph) = p.sentences.joinToString(" ")

    private fun isHeadingLike(p: Paragraph): Boolean {
        val t = text(p)
        return p.sentences.size == 1 && t.length <= 80 && t.split(' ').size <= 10 &&
            !t.trimEnd().endsWith('.') && (t.first().isUpperCase() || t.first().isDigit())
    }

    private fun isProse(p: Paragraph) = text(p).length >= 120 || p.sentences.size >= 2

    private val contentsTitle = Regex("^(?:table of )?contents$", RegexOption.IGNORE_CASE)

    /**
     * Pages holding a table of contents: a "Contents" heading, or mostly contents lines, and
     * the pages straight after one that carry on listing titles (ebooks' contents have no
     * page numbers).
     */
    private fun contentsPages(paragraphs: List<Paragraph>): Set<Int> {
        val pages = mutableSetOf<Int>()
        paragraphs.groupBy { it.page }.toSortedMap().forEach { (page, ps) ->
            val titled = ps.any { contentsTitle.matches(text(it).trim()) }
            val lines = ps.count { contentsLine.matches(text(it).trim()) && text(it).length <= 90 }
            val carriesOn = page - 1 in pages && ps.size >= 3 && ps.all(::isEntryLike)
            if (titled || (ps.size >= 4 && lines * 2 >= ps.size) || carriesOn) pages += page
        }
        return pages
    }

    private fun isEntryLike(p: Paragraph): Boolean {
        val t = text(p).trim()
        return p.sentences.size == 1 && t.length <= 90 && !t.endsWith('.')
    }

    /**
     * Finds each title listed in the book's own contents page in the text after it, in order,
     * as the chapters. Works when headings don't look like headings, such as when one runs
     * into its first paragraph.
     */
    private fun fromContentsList(paragraphs: List<Paragraph>, contentsPages: Set<Int>): List<Pair<String, Int>> {
        if (contentsPages.isEmpty()) return emptyList()
        val lastContents = paragraphs.indexOfLast { it.page in contentsPages }
        // Only the first contents page and those carrying on from it: not a list of figures later on.
        val firstPage = contentsPages.min()
        var lastPage = firstPage
        while (lastPage + 1 in contentsPages) lastPage++
        val lines = paragraphs.filter { it.page in firstPage..lastPage && it.sentences.size <= 2 }
            .map { text(it).trim() }
            .filter { !contentsTitle.matches(it) && it.length <= 120 }
        // Strip page numbers only when the contents has them, so "Chapter 1" keeps its number.
        val numbered = lines.count { it.last().isDigit() } * 2 >= lines.size
        val entries = lines
            .map { if (numbered) it.replace(Regex("[\\s.·…_-]*\\d{1,4}$"), "").trim() else it }
            .filter { e -> e.count { it.isLetter() } >= 3 }
        // Where each title first appears after the contents.
        val at = entries.map { entry ->
            (lastContents + 1 until paragraphs.size).firstOrNull { i ->
                paragraphs[i].page !in contentsPages && opensWith(paragraphs[i], entry)
            }
        }
        // Keep the longest run that's in the contents' order, so one title found in the wrong
        // place (a copyright page listed first but printed at the back) doesn't lose the rest.
        return inOrder(at).map { entries[it] to at[it]!! }
    }

    /** Indexes of the longest strictly increasing run of the non-null [values]. */
    private fun inOrder(values: List<Int?>): List<Int> {
        val best = IntArray(values.size)
        val before = IntArray(values.size) { -1 }
        var end = -1
        for (i in values.indices) {
            val v = values[i] ?: continue
            best[i] = 1
            for (j in 0 until i) {
                val w = values[j] ?: continue
                if (w < v && best[j] + 1 > best[i]) {
                    best[i] = best[j] + 1
                    before[i] = j
                }
            }
            if (end < 0 || best[i] > best[end]) end = i
        }
        val run = ArrayList<Int>()
        var i = end
        while (i >= 0) {
            run += i
            i = before[i]
        }
        return run.reversed()
    }

    /** Whether paragraph [p] is the heading [title], or starts with it. */
    private fun opensWith(p: Paragraph, title: String): Boolean {
        val have = normalise(text(p))
        val keys = listOf(title, title.replace(Regex("^\\d{1,4}\\s+(?=\\p{L})"), "")).map(::normalise).distinct()
        return keys.any { want ->
            want.length >= 4 && (
                have == want || have.startsWith("$want ") ||
                    // A heading split over two lines: "THE FIRST NIGHT" then "Deny Trauma".
                    (isHeadingLike(p) && have.length >= 6 && want.startsWith("$have "))
                )
        }
    }

    /** Matches each bookmark to the paragraph on its page that best fits its title. */
    private fun fromOutline(paragraphs: List<Paragraph>, outline: List<OutlineEntry>): List<Pair<String, Int>> {
        if (outline.isEmpty()) return emptyList()
        return outline.mapNotNull { entry ->
            val onPage = paragraphs.indices.filter { paragraphs[it].page == entry.page }
            val candidates = onPage.ifEmpty { paragraphs.indices.filter { paragraphs[it].page > entry.page }.take(1) }
            if (candidates.isEmpty()) return@mapNotNull null
            val want = normalise(entry.title)
            val match = candidates.firstOrNull { normalise(text(paragraphs[it])).startsWith(want.take(30)) }
                ?: candidates.first()
            entry.title.trim() to match
        }.sortedBy { it.second }
    }

    /** Short title-like paragraphs ("Chapter 2: The Plateau", "1. Introduction") that open some prose. */
    private fun fromHeadings(paragraphs: List<Paragraph>, contentsPages: Set<Int>): List<Pair<String, Int>> =
        paragraphs.indices.filter { i ->
            val p = paragraphs[i]
            p.page !in contentsPages && isHeadingLike(p) && headingWords.containsMatchIn(text(p)) &&
                paragraphs.drop(i + 1).take(2).any(::isProse)
        }.map { text(paragraphs[it]).trim() to it }

    private fun startParagraph(
        paragraphs: List<Paragraph>,
        headings: List<Pair<String, Int>>,
        contentsPages: Set<Int>,
    ): Int {
        if (paragraphs.isEmpty()) return 0
        // Only look in the first part of the book: front matter is never most of it.
        val lastPage = paragraphs.last().page
        val horizon = maxOf(12, lastPage / 4)
        val early = headings.filter { (_, p) -> paragraphs[p].page <= horizon && paragraphs[p].page !in contentsPages }
        for (opener in openers) {
            early.firstOrNull { (title, _) -> opener.containsMatchIn(title) }?.let { return it.second }
        }
        early.firstOrNull { (title, _) -> !frontMatter.containsMatchIn(title) }?.let { (_, p) ->
            // A heading after obvious front matter (copyright, contents) opens the main text.
            if (hasFrontMatterBefore(paragraphs, p, contentsPages)) return p
        }
        // No headings: skip a leading title page, copyright notice or contents, if there is one.
        if (!hasFrontMatterBefore(paragraphs, paragraphs.size, contentsPages)) return 0
        // Front matter only: some ebooks keep their copyright page at the back.
        val after = paragraphs.indexOfLast {
            it.page <= horizon && (it.page in contentsPages || copyright.containsMatchIn(text(it)))
        }
        val firstProse = (after + 1 until paragraphs.size).firstOrNull { isProse(paragraphs[it]) } ?: return 0
        // Start at the heading just above that prose, if it has one.
        return if (firstProse > after + 1 && isHeadingLike(paragraphs[firstProse - 1])) firstProse - 1 else firstProse
    }

    private fun hasFrontMatterBefore(paragraphs: List<Paragraph>, end: Int, contentsPages: Set<Int>): Boolean {
        val firstPages = maxOf(6, (paragraphs.lastOrNull()?.page ?: 0) / 10)
        return (0 until end).any { i ->
            val p = paragraphs[i]
            p.page <= firstPages && (p.page in contentsPages || copyright.containsMatchIn(text(p)))
        }
    }

    private fun normalise(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
