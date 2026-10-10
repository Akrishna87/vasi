package io.github.akrishna87.vaasi.tts

import io.github.akrishna87.vaasi.text.Sentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ExpressionTest {

    @Test
    fun readsTheMoodOfWords() {
        assertEquals(Mood.Neutral, Expression.moodOf("The meeting is at three o'clock in the main hall."))
        assertEquals(Mood.Neutral, Expression.moodOf("I will never forget the dark blue door of the house."))
        assertEquals(Mood.Sad, Expression.moodOf("She wept at the funeral, lonely and full of grief."))
        assertEquals(Mood.Happy, Expression.moodOf("They laughed and smiled, delighted by the wonderful news."))
        assertEquals(Mood.Excited, Expression.moodOf("We did it!"))
        assertEquals(Mood.Angry, Expression.moodOf("I hate you, you liar!"))
        assertEquals(Mood.Afraid, Expression.moodOf("Terrified, she heard a scream!"))
        assertEquals(Mood.Loud, Expression.moodOf("GET OUT OF MY HOUSE!"))
    }

    @Test
    fun dialogueTagsSetHowSpeechIsSaid() {
        fun prosodyOf(sentence: String) = Expression.prosody(Sentences.segments(listOf(sentence)).single())
        val whisper = prosodyOf("“Come closer,” she whispered.")
        assertEquals(Prosody.of(Mood.Soft), whisper[0])
        val shout = prosodyOf("“Run,” he shouted, “now.”")
        assertEquals(Prosody.of(Mood.Loud), shout[0])
        assertEquals(Prosody.of(Mood.Loud), shout[2])
        // Plain narration is left alone.
        assertEquals(Prosody.Plain, prosodyOf("He opened the door and walked in.").single())
    }

    @Test
    fun narrationIsColouredLightly() {
        val sad = Expression.prosody(Sentences.segments(listOf("She wept at the funeral, lonely and full of grief.")).single()).single()
        val full = Prosody.of(Mood.Sad)
        assertTrue(sad.speed < 1f && sad.speed > full.speed)
        assertEquals(full.pauseMs / 2, sad.pauseMs)
    }

    @Test
    fun voiceSpeedMakesUpForThePitchShift() {
        val p = Prosody(speed = 1.1f, pitch = 1.05f)
        // Kokoro speaks at speed / pitch; resampling by pitch then brings it to speed.
        assertEquals(1.1f / 1.05f, Expression.voiceSpeed(p, 1f), 1e-4f)
        assertEquals(2f, Expression.voiceSpeed(p, 2f), 1e-4f) // capped at what Kokoro does well
    }

    private fun tone(freq: Double, rate: Int = 24000, seconds: Double = 0.5) =
        FloatArray((rate * seconds).toInt()) { i -> (0.5 * sin(2 * PI * freq * i / rate)).toFloat() }

    /** Frequency by counting upward zero crossings. */
    private fun frequency(s: FloatArray, rate: Int = 24000): Double {
        var crossings = 0
        for (i in 1 until s.size) if (s[i - 1] < 0 && s[i] >= 0) crossings++
        return crossings * rate.toDouble() / s.size
    }

    @Test
    fun resamplingShiftsPitchCleanly() {
        val up = Expression.resample(tone(220.0), 1.05f)
        assertEquals(12000 / 1.05, up.size.toDouble(), 1.0)
        assertEquals(231.0, frequency(up), 3.0)
        val down = Expression.resample(tone(220.0), 0.95f)
        assertEquals(209.0, frequency(down), 3.0)
        // Same loudness, no clicks.
        assertEquals(0.5f, up.drop(100).dropLast(100).maxOf { abs(it) }, 0.02f)
    }

    @Test
    fun gainNeverClips() {
        val loud = Expression.gain(tone(220.0).map { it * 1.8f }.toFloatArray(), 6f)
        assertTrue(loud.all { abs(it) <= 1f })
        val soft = Expression.gain(tone(220.0), -6f)
        assertEquals(0.25f, soft.maxOf { abs(it) }, 0.01f)
    }

    @Test
    fun applyAddsThePause() {
        val out = Expression.apply(FloatArray(2400), 24000, Prosody(pauseMs = 250))
        assertEquals(2400 + 6000, out.size)
    }
}
