package io.github.akrishna87.vaasi.text

/** A paragraph of readable text and the (1-based) PDF page it starts on. */
data class RawParagraph(val page: Int, val text: String)

/**
 * Turns the text of a PDF's pages into paragraphs worth reading aloud: drops running
 * headers, footers and page numbers, mends words hyphenated across lines, and joins the
 * lines of each paragraph (and paragraphs split across a page break) back together.
 *
 * Pages are expected the way [PdfText] extracts them: lines separated by '\n', with a blank
 * line where the PDF's layout suggests a new paragraph.
 */
object TextCleaner {

    fun clean(pages: List<String>): List<RawParagraph> {
        val pageLines = pages.map { page -> page.replace("\r\n", "\n").replace('\r', '\n').split('\n').map(::tidyLine) }
        val boilerplate = repeatedEdgeLines(pageLines)

        val paragraphs = mutableListOf<RawParagraph>()
        var current = StringBuilder()
        var currentPage = 1

        fun flush() {
            val text = finishParagraph(current.toString())
            if (text.isNotEmpty()) paragraphs += RawParagraph(currentPage, text)
            current = StringBuilder()
        }

        pageLines.forEachIndexed { index, rawLines ->
            val page = index + 1
            val lines = stripEdges(rawLines, boilerplate)
            val typical = typicalLength(lines)
            for ((i, line) in lines.withIndex()) {
                if (line.isEmpty()) {
                    flush()
                    continue
                }
                if (current.isEmpty()) currentPage = page
                appendLine(current, line)

                val next = lines.getOrNull(i + 1)
                if (next != null && next.isNotEmpty() && looksLikeParagraphEnd(line, next, typical)) flush()
            }
            // A paragraph carries over to the next page only if it stops mid-sentence.
            if (current.isNotEmpty() && endsParagraph(current)) flush()
        }
        flush()
        return paragraphs
    }

    private val ligatures = mapOf(
        "ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl", "ﬃ" to "ffi", "ﬄ" to "ffl",
        "ﬅ" to "st", "ﬆ" to "st",
    )

    internal fun tidyLine(line: String): String {
        var s = line
        for ((from, to) in ligatures) s = s.replace(from, to)
        return s.replace("­", "") // soft hyphens
            .replace(' ', ' ')
            .replace(Regex("[\\t\\u2000-\\u200B\\u202F\\u205F\\u3000]"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }

    private val pageNumber = Regex(
        "^(?:page\\s*)?[-–—(\\[]?\\s*(?:\\d{1,4}|[ivxlcdm]{1,7})\\s*[-–—)\\]]?(?:\\s*(?:of|/)\\s*\\d{1,4})?$",
        RegexOption.IGNORE_CASE,
    )

    internal fun isPageNumber(line: String) = pageNumber.matches(line.trim())

    /** The same line with its numbers blanked out, so "Chapter 3 · 41" matches "Chapter 3 · 42". */
    private fun signature(line: String) = line.lowercase().replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").trim()

    /** The first and last lines of a page, where running heads, feet and page numbers sit. */
    private fun edgeIndices(lines: List<String>): List<Int> {
        val nonBlank = lines.indices.filter { lines[it].isNotEmpty() }
        val edge = if (nonBlank.size >= 6) 2 else 1
        return (nonBlank.take(edge) + nonBlank.takeLast(edge)).distinct()
    }

    /** Signatures of lines that sit at the top or bottom of many pages: running heads and feet. */
    private fun repeatedEdgeLines(pages: List<List<String>>): Set<String> {
        if (pages.size < 3) return emptySet()
        val counts = HashMap<String, Int>()
        for (lines in pages) {
            edgeIndices(lines).map { signature(lines[it]) }.filter { it.isNotEmpty() }.toSet()
                .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val threshold = maxOf(2, kotlin.math.ceil(pages.size * 0.4).toInt())
        return counts.filter { it.value >= threshold }.keys
    }

    private fun stripEdges(lines: List<String>, boilerplate: Set<String>): List<String> {
        val drop = edgeIndices(lines).filter { i ->
            val line = lines[i]
            isPageNumber(line) || signature(line) in boilerplate
        }.toSet()
        val kept = lines.filterIndexed { i, _ -> i !in drop }
        return kept.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
    }

    private fun typicalLength(lines: List<String>): Int {
        val lengths = lines.map { it.length }.filter { it >= 20 }.sorted()
        return if (lengths.isEmpty()) 0 else lengths[lengths.size / 2]
    }

    private val sentenceEnd = Regex("[.!?:…]['\"”’)\\]]*$")

    private fun endsParagraph(text: CharSequence) = sentenceEnd.containsMatchIn(text.trimEnd())

    /**
     * Many PDFs mark no paragraphs at all. Guess one ends where a line finishes a sentence
     * well short of the usual line length, or where a short title-like line stands alone.
     */
    private fun looksLikeParagraphEnd(line: String, next: String, typical: Int): Boolean {
        if (typical == 0) return false
        val short = line.length < typical * 0.75
        if (short && endsParagraph(line)) return true
        val heading = line.length < typical * 0.6 && line.last().isLetterOrDigit() &&
            line.first().isUpperCase() && next.first().isUpperCase() && line.split(' ').size <= 8
        return heading
    }

    private fun appendLine(paragraph: StringBuilder, line: String) {
        if (paragraph.isEmpty()) {
            paragraph.append(line)
            return
        }
        val last = paragraph.length - 1
        val hyphenated = paragraph[last] == '-' && last > 0 && paragraph[last - 1].isLetter() &&
            line.first().isLowerCase()
        if (hyphenated) {
            paragraph.setLength(last) // "exam-" + "ple" -> "example"
            paragraph.append(line)
        } else {
            paragraph.append(' ').append(line)
        }
    }

    private val citation = Regex("\\s*\\[\\d+(?:[,–-]\\s*\\d+)*]")
    private val bullets = Regex("^[•●▪■◦‣∙·*-]\\s+")

    internal fun finishParagraph(text: String): String {
        val s = text.replace(citation, "").replace(bullets, "").replace(Regex("\\s{2,}"), " ").trim()
        // Lines of only numbers, dots or symbols (tables of contents, rulers) aren't worth reading.
        return if (s.none { it.isLetter() }) "" else s
    }
}
