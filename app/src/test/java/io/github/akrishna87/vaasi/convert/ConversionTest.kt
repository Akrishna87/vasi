package io.github.akrishna87.vaasi.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionTest {
    private fun seg(first: Int, count: Int) =
        Segment("s$first.m4a", first, LongArray(count) { it * 1000L }, count * 1000L)

    private val empty = Conversion(20, 24000, "sig", "Heart", emptyList())

    @Test
    fun convertsFromTheAnchorThenWhatIsBefore() {
        assertEquals(5, empty.nextToConvert(5))
        val c = empty.with(seg(5, 8)).with(seg(13, 7)) // 5..19 done
        assertEquals(0, c.nextToConvert(5))
        assertEquals(4, c.runEnd(0, 4))
        assertEquals(5, c.runEnd(0, 8)) // stops where converted audio begins
        val full = c.with(seg(0, 5))
        assertNull(full.nextToConvert(5))
        assertTrue(full.complete)
        assertEquals(1f, full.fraction)
    }

    @Test
    fun followsTheListener() {
        val c = empty.with(seg(0, 8))
        assertEquals(12, c.nextToConvert(12)) // the listener jumped ahead
        assertEquals(8, c.nextToConvert(3))
    }

    @Test
    fun findsSegmentsAndSentences() {
        val c = empty.with(seg(8, 4)).with(seg(0, 8))
        assertEquals(0, c.segmentFor(7)?.first)
        assertEquals(8, c.segmentFor(11)?.first)
        assertNull(c.segmentFor(12))
        assertFalse(c.isConverted(19))
        val s = c.segmentFor(9)!!
        assertEquals(1000L, s.startOf(9))
        assertEquals(9, s.sentenceAt(1500))
        assertEquals(8, s.sentenceAt(0))
        assertEquals(11, s.sentenceAt(99999))
        assertEquals(12, c.converted)
    }

    @Test
    fun survivesJson() {
        val c = empty.with(seg(0, 3)).with(seg(3, 2))
        val copy = Conversion.fromJson(c.toJson())
        assertEquals(c.signature, copy.signature)
        assertEquals("Heart", copy.voiceName)
        assertEquals(listOf(0, 3), copy.segments.map { it.first })
        assertEquals(c.segments[1].starts.toList(), copy.segments[1].starts.toList())
        assertEquals(5, copy.converted)
    }
}
