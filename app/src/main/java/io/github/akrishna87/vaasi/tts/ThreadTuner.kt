package io.github.akrishna87.vaasi.tts

/**
 * Finds how many threads make the voice fastest on this phone, by timing real conversion
 * work with each candidate in turn. Phones mix fast and slow cores, and each step of the
 * model waits for its slowest thread, so more threads isn't always faster.
 */
class ThreadTuner(val candidates: List<Int>) {
    private val work = HashMap<Int, Double>()
    private val audio = HashMap<Int, Double>()

    private fun timed(threads: Int) = (audio[threads] ?: 0.0) >= ENOUGH_AUDIO_SECONDS

    /** The thread count to time next, or null once every candidate has been timed. */
    fun next(): Int? = candidates.firstOrNull { !timed(it) }

    /** Records that [threads] took [workSeconds] to make [audioSeconds] of speech. */
    fun record(threads: Int, workSeconds: Double, audioSeconds: Double) {
        if (threads !in candidates || audioSeconds <= 0) return
        work[threads] = (work[threads] ?: 0.0) + workSeconds
        audio[threads] = (audio[threads] ?: 0.0) + audioSeconds
    }

    /** Seconds of work per second of speech made with [threads] (lower is faster). */
    fun cost(threads: Int): Double? = audio[threads]?.let { a -> work.getValue(threads) / a }

    /**
     * The fastest once all are timed; of ones within a few percent of it, the fewest threads,
     * which leaves cores free and runs cooler.
     */
    val best: Int?
        get() {
            if (next() != null) return null
            val fastest = candidates.minOf { cost(it)!! }
            return candidates.filter { cost(it)!! <= fastest * 1.05 }.min()
        }

    fun summary() = candidates.joinToString { "$it threads: ${cost(it)?.let { c -> "%.2f".format(c) } ?: "-"}" }

    companion object {
        /** Speech to make with each candidate before trusting its timing. */
        const val ENOUGH_AUDIO_SECONDS = 20.0

        /** What to try on a phone with [cores] cores, starting with the usual [default]. */
        fun candidates(cores: Int, default: Int): List<Int> =
            (listOf(default, 2, 4) + if (cores >= 8) listOf(6) else emptyList())
                .filter { it in 1..cores }
                .distinct()
    }
}
