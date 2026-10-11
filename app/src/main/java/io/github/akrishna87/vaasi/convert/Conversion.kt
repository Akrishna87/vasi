package io.github.akrishna87.vaasi.convert

import org.json.JSONArray
import org.json.JSONObject

/**
 * One stretch of a book already turned into audio: an .m4a file holding sentences
 * [first] onwards, with [starts] giving where each sentence begins (in samples).
 */
class Segment(val file: String, val first: Int, val starts: LongArray, val samples: Long) {
    val count: Int get() = starts.size
    val last: Int get() = first + count - 1

    fun contains(sentence: Int) = sentence in first..last

    /** Where [sentence] starts in this segment, in samples. */
    fun startOf(sentence: Int): Long = starts[sentence - first]

    /** The sentence playing at sample [position] of this segment. */
    fun sentenceAt(position: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= position) lo = mid else hi = mid - 1
        }
        return first + lo
    }
}

/**
 * A book's audio, as far as it has been made: the segments converted so far, and the
 * voice settings they were made with ([signature]).
 */
class Conversion(
    val sentences: Int,
    val sampleRate: Int,
    val signature: String,
    /** The narrator's name, to show which voice the audio is in. */
    val voiceName: String,
    segments: List<Segment>,
) {
    val segments: List<Segment> = segments.sortedBy { it.first }

    val converted: Int = this.segments.sumOf { it.count }

    val complete: Boolean get() = converted >= sentences

    val fraction: Float get() = if (sentences == 0) 1f else converted.toFloat() / sentences

    fun segmentFor(sentence: Int): Segment? {
        var lo = 0
        var hi = segments.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val s = segments[mid]
            when {
                sentence < s.first -> hi = mid - 1
                sentence > s.last -> lo = mid + 1
                else -> return s
            }
        }
        return null
    }

    fun isConverted(sentence: Int) = segmentFor(sentence) != null

    /**
     * The next sentence to convert: the first not yet converted at or after [anchor] (where
     * the listener is, or where the book starts), then whatever is left before it.
     */
    fun nextToConvert(anchor: Int): Int? {
        val from = anchor.coerceIn(0, maxOf(sentences - 1, 0))
        return firstGap(from, sentences) ?: firstGap(0, from)
    }

    private fun firstGap(from: Int, until: Int): Int? {
        var i = from
        while (i < until) {
            val s = segmentFor(i) ?: return i
            i = s.last + 1
        }
        return null
    }

    /** The end (exclusive) of a new segment starting at [from]: at most [max] sentences, up to the next converted one. */
    fun runEnd(from: Int, max: Int): Int {
        val next = segments.firstOrNull { it.first > from }?.first ?: sentences
        return minOf(from + max, next, sentences)
    }

    fun with(segment: Segment) = Conversion(sentences, sampleRate, signature, voiceName, segments + segment)

    fun toJson(): String = JSONObject().apply {
        put("sentences", sentences)
        put("sampleRate", sampleRate)
        put("signature", signature)
        put("voiceName", voiceName)
        put("segments", JSONArray().apply {
            for (s in segments) {
                put(JSONObject().apply {
                    put("file", s.file)
                    put("first", s.first)
                    put("samples", s.samples)
                    put("starts", JSONArray().apply { s.starts.forEach { put(it) } })
                })
            }
        })
    }.toString()

    companion object {
        fun fromJson(json: String): Conversion {
            val o = JSONObject(json)
            val a = o.getJSONArray("segments")
            val segments = (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                val st = s.getJSONArray("starts")
                Segment(s.getString("file"), s.getInt("first"), LongArray(st.length()) { st.getLong(it) }, s.getLong("samples"))
            }
            return Conversion(o.getInt("sentences"), o.getInt("sampleRate"), o.getString("signature"), o.getString("voiceName"), segments)
        }
    }
}
