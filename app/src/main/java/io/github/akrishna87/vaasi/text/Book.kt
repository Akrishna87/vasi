package io.github.akrishna87.vaasi.text

import org.json.JSONArray
import org.json.JSONObject

/** A paragraph split into the sentences the voice reads one at a time. */
data class Paragraph(val page: Int, val sentences: List<String>)

/**
 * The readable text of an imported PDF. Sentences are numbered through the whole book;
 * that number is how playback position and highlighting refer to them.
 */
class Book(
    val id: String,
    val title: String,
    val pages: Int,
    val paragraphs: List<Paragraph>,
    chapters: List<Chapter>? = null,
    start: Int? = null,
) {
    private val found by lazy { Contents.find(paragraphs) }

    /** Chapters and sections to jump to. */
    val chapters: List<Chapter> = chapters ?: found.first

    /** Where the main text begins (past the title page, copyright and contents). */
    val start: Int = start ?: found.second

    /** Whether [chapters] and [start] were worked out when the PDF was added (with its bookmarks). */
    val hasContents: Boolean = chapters != null && start != null

    /** Index of each paragraph's first sentence; one extra entry holds the total. */
    private val starts: IntArray = IntArray(paragraphs.size + 1).also { s ->
        paragraphs.forEachIndexed { i, p -> s[i + 1] = s[i] + p.sentences.size }
    }

    val sentenceCount: Int get() = starts.last()

    fun firstSentenceOf(paragraph: Int): Int = starts[paragraph]

    fun paragraphOf(sentence: Int): Int {
        var lo = 0
        var hi = paragraphs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= sentence) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun sentence(index: Int): String {
        val p = paragraphOf(index)
        return paragraphs[p].sentences[index - starts[p]]
    }

    fun pageOf(sentence: Int): Int = paragraphs.getOrNull(paragraphOf(sentence))?.page ?: 1

    fun isLastInParagraph(sentence: Int): Boolean = starts[paragraphOf(sentence) + 1] == sentence + 1

    /** Narrated and quoted stretches of every sentence, for reading dialogue in a second voice. */
    val segments: List<List<Segment>> by lazy {
        paragraphs.flatMap { Sentences.segments(it.sentences) }
    }

    /** The chapter that [sentence] is in, if any. */
    fun chapterAt(sentence: Int): Chapter? = chapters.lastOrNull { it.sentence <= sentence }

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("pages", pages)
        put("start", start)
        put("chapters", JSONArray().apply {
            for (c in chapters) put(JSONObject().apply { put("title", c.title); put("sentence", c.sentence) })
        })
        put("paragraphs", JSONArray().apply {
            for (p in paragraphs) {
                put(JSONObject().apply {
                    put("page", p.page)
                    put("sentences", JSONArray(p.sentences))
                })
            }
        })
    }.toString()

    companion object {
        fun fromJson(json: String): Book {
            val o = JSONObject(json)
            val ps = o.getJSONArray("paragraphs")
            val paragraphs = (0 until ps.length()).map { i ->
                val p = ps.getJSONObject(i)
                val s = p.getJSONArray("sentences")
                Paragraph(p.getInt("page"), (0 until s.length()).map { s.getString(it) })
            }
            // Books added before chapters existed get them worked out now.
            val chapters = o.optJSONArray("chapters")?.let { cs ->
                (0 until cs.length()).map { i -> cs.getJSONObject(i).let { Chapter(it.getString("title"), it.getInt("sentence")) } }
            }
            val start = if (o.has("start")) o.getInt("start") else null
            return Book(o.getString("id"), o.getString("title"), o.getInt("pages"), paragraphs, chapters, start)
        }

        /** Builds a book from the text of each PDF page and the PDF's bookmarks. */
        fun fromPages(id: String, title: String, pages: List<String>, outline: List<OutlineEntry> = emptyList()): Book {
            val paragraphs = TextCleaner.clean(pages)
                .map { Paragraph(it.page, Sentences.split(it.text)) }
                .filter { it.sentences.isNotEmpty() }
            val (chapters, start) = Contents.find(paragraphs, outline)
            return Book(id, title, pages.size, paragraphs, chapters, start)
        }
    }
}
