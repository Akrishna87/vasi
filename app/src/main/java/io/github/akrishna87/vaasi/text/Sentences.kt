package io.github.akrishna87.vaasi.text

/** A stretch of a sentence, and whether it is inside quotation marks (someone speaking). */
data class Segment(val text: String, val quoted: Boolean)

object Sentences {

    /** Sentences longer than this are split at a comma or semicolon so the voice never rushes. */
    const val MAX_CHARS = 280

    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "ft", "vs", "etc", "e.g", "i.e", "cf",
        "fig", "figs", "no", "nos", "vol", "vols", "pp", "p", "ch", "chap", "sec", "ed", "eds", "inc",
        "ltd", "co", "corp", "dept", "est", "approx", "jan", "feb", "mar", "apr", "jun", "jul", "aug",
        "sep", "sept", "oct", "nov", "dec", "a.m", "p.m", "u.s", "u.k", "gen", "col", "capt", "lt", "rev",
        "hon", "gov", "sen", "rep", "al",
    )

    private val closers = setOf('"', '\'', '”', '’', ')', ']', '»')

    /** Splits a paragraph into sentences, each at most [MAX_CHARS] long where possible. */
    fun split(paragraph: String): List<String> {
        val text = paragraph.trim()
        if (text.isEmpty()) return emptyList()
        val sentences = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var end = i + 1
                while (end < text.length && (text[end] == '.' || text[end] == '!' || text[end] == '?' || text[end] == '…')) end++
                while (end < text.length && text[end] in closers) end++
                if (end >= text.length || (text[end].isWhitespace() && startsSentence(text, end) && !isAbbreviation(text, start, i, c))) {
                    val sentence = text.substring(start, end).trim()
                    if (sentence.isNotEmpty()) sentences += sentence
                    start = end
                }
                i = end
            } else {
                i++
            }
        }
        val rest = text.substring(start).trim()
        if (rest.isNotEmpty()) sentences += rest
        return sentences.flatMap(::shorten)
    }

    /** True when what follows position [at] (after spaces) can begin a new sentence. */
    private fun startsSentence(text: String, at: Int): Boolean {
        var j = at
        while (j < text.length && text[j].isWhitespace()) j++
        if (j >= text.length) return true
        val next = text[j]
        return next.isUpperCase() || next.isDigit() || next in "\"'“‘([¿¡"
    }

    private fun isAbbreviation(text: String, sentenceStart: Int, dot: Int, mark: Char): Boolean {
        if (mark != '.') return false
        var j = dot
        while (j > sentenceStart && !text[j - 1].isWhitespace() && text[j - 1] != '(' && text[j - 1] != '"' && text[j - 1] != '“') j--
        val word = text.substring(j, dot)
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isUpperCase()) return true // an initial: "J. R. R. Tolkien"
        return word.lowercase() in abbreviations
    }

    private fun shorten(sentence: String): List<String> {
        if (sentence.length <= MAX_CHARS) return listOf(sentence)
        // Prefer the break nearest the middle: a semicolon or dash, else a comma, else a space.
        val middle = sentence.length / 2
        val cut = listOf(Regex("[;:—–] ?"), Regex(", "), Regex(" "))
            .firstNotNullOfOrNull { re ->
                re.findAll(sentence)
                    .map { it.range.last + 1 }
                    .filter { it in 40..(sentence.length - 40) }
                    .minByOrNull { kotlin.math.abs(it - middle) }
            } ?: return listOf(sentence)
        return shorten(sentence.substring(0, cut).trim()) + shorten(sentence.substring(cut).trim())
    }

    /**
     * Splits each sentence of a paragraph into narrated and quoted stretches. Quotes may run
     * across sentences ("Wait. Don't go," she said.), so the state carries over between them.
     */
    fun segments(sentences: List<String>): List<List<Segment>> {
        var quoted = false
        return sentences.map { sentence ->
            val parts = mutableListOf<Segment>()
            val buf = StringBuilder()
            fun cut(nowQuoted: Boolean) {
                val s = buf.toString().trim()
                if (s.isNotEmpty()) {
                    if (s.any { it.isLetterOrDigit() } || parts.isEmpty()) {
                        parts += Segment(s, quoted)
                    } else {
                        // Bare punctuation ("," or "—") rides along with the stretch before it.
                        val last = parts.removeAt(parts.size - 1)
                        parts += last.copy(text = last.text + s)
                    }
                }
                buf.setLength(0)
                quoted = nowQuoted
            }
            for ((i, c) in sentence.withIndex()) {
                when {
                    c == '“' -> { cut(true); buf.append(c) }
                    c == '”' -> { buf.append(c); cut(false) }
                    c == '"' -> {
                        // Straight quotes: opening if at the start or after a space or bracket.
                        val opening = i == 0 || sentence[i - 1].isWhitespace() || sentence[i - 1] in "([—–-"
                        if (opening && !quoted) { cut(true); buf.append(c) } else if (quoted) { buf.append(c); cut(false) } else buf.append(c)
                    }
                    else -> buf.append(c)
                }
            }
            cut(quoted)
            parts.ifEmpty { listOf(Segment(sentence, false)) }
        }
    }
}
