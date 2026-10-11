package io.github.akrishna87.vaasi.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadTunerTest {
    @Test
    fun triesTheUsualCountFirstAndOnlyWhatThePhoneHas() {
        assertEquals(listOf(4, 2, 6), ThreadTuner.candidates(cores = 8, default = 4))
        assertEquals(listOf(3, 2, 4), ThreadTuner.candidates(cores = 4, default = 3))
        assertEquals(listOf(1, 2), ThreadTuner.candidates(cores = 2, default = 1))
        assertEquals(listOf(1), ThreadTuner.candidates(cores = 1, default = 1))
    }

    @Test
    fun picksTheFastestOnceEachHasMadeEnoughSpeech() {
        val t = ThreadTuner(listOf(4, 2, 6))
        assertEquals(4, t.next())
        t.record(4, workSeconds = 12.0, audioSeconds = 10.0) // not enough yet
        assertEquals(4, t.next())
        t.record(4, workSeconds = 12.0, audioSeconds = 10.0)
        assertEquals(2, t.next())
        assertNull(t.best)
        t.record(2, workSeconds = 16.0, audioSeconds = 25.0)
        t.record(6, workSeconds = 40.0, audioSeconds = 25.0)
        assertNull(t.next())
        assertEquals(1.2, t.cost(4)!!, 1e-9)
        assertEquals(2, t.best) // a phone with two fast cores
    }

    @Test
    fun prefersFewerThreadsWhenItsNearlyAsFast() {
        val t = ThreadTuner(listOf(4, 2))
        t.record(4, workSeconds = 10.0, audioSeconds = 20.0)
        t.record(2, workSeconds = 10.3, audioSeconds = 20.0)
        assertEquals(2, t.best)
        val u = ThreadTuner(listOf(4, 2))
        u.record(4, workSeconds = 10.0, audioSeconds = 20.0)
        u.record(2, workSeconds = 15.0, audioSeconds = 20.0)
        assertEquals(4, u.best)
    }
}
