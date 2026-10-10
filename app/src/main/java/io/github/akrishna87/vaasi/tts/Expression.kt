package io.github.akrishna87.vaasi.tts

import io.github.akrishna87.vaasi.text.Segment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** The feeling a stretch of text is read with. */
enum class Mood { Neutral, Happy, Excited, Sad, Angry, Afraid, Soft, Loud }

/**
 * How to deliver a stretch of speech: [speed] and [pitch] as factors (1 = unchanged),
 * [gainDb] for loudness, and [pauseMs] of silence afterwards.
 */
data class Prosody(val speed: Float = 1f, val pitch: Float = 1f, val gainDb: Float = 0f, val pauseMs: Int = 0) {
    /** The same delivery, toned down: narrators colour their reading less than characters act. */
    fun scaled(amount: Float) = Prosody(
        1f + (speed - 1f) * amount, 1f + (pitch - 1f) * amount, gainDb * amount, (pauseMs * amount).roundToInt(),
    )

    companion object {
        val Plain = Prosody()

        fun of(mood: Mood) = when (mood) {
            Mood.Neutral -> Plain
            Mood.Happy -> Prosody(speed = 1.05f, pitch = 1.03f, gainDb = 1f)
            Mood.Excited -> Prosody(speed = 1.08f, pitch = 1.04f, gainDb = 2f)
            Mood.Sad -> Prosody(speed = 0.88f, pitch = 0.97f, gainDb = -2f, pauseMs = 250)
            Mood.Angry -> Prosody(speed = 1.05f, pitch = 0.99f, gainDb = 3f)
            Mood.Afraid -> Prosody(speed = 1.10f, pitch = 1.03f, gainDb = -1f)
            Mood.Soft -> Prosody(speed = 0.92f, pitch = 0.99f, gainDb = -6f, pauseMs = 100)
            Mood.Loud -> Prosody(speed = 1.04f, pitch = 1.04f, gainDb = 4f)
        }
    }
}

/**
 * Guesses the mood of each stretch of a sentence from its words and punctuation, and from
 * dialogue tags around speech ("she whispered", "he shouted"). Kokoro has no emotion
 * control, so the mood is expressed through pace, pitch, loudness and pauses.
 */
object Expression {

    private fun words(vararg w: String) = w.toSet()

    private val happy = words(
        "happy", "happily", "glad", "joy", "joyful", "delighted", "delight", "wonderful", "lovely", "love", "loved",
        "beautiful", "smile", "smiled", "smiling", "laugh", "laughed", "laughing", "cheerful", "hope", "hopeful", "proud", "grateful", "thank", "thanks", "celebrate", "celebrated", "fun", "excellent",
        "sweet", "peace", "peaceful", "hooray", "yay", "congratulations", "enjoy", "enjoyed",
    )
    private val excited = words(
        "amazing", "incredible", "fantastic", "wow", "hurry", "victory", "thrilled", "exciting", "excited", "brilliant", "astonishing", "unbelievable", "yes",
    )
    private val sad = words(
        "sad", "sadly", "sorrow", "sorrowful", "grief", "grieve", "grieved", "tears", "wept", "weep",
        "cried", "crying", "lonely", "loss", "died", "dead", "death", "die", "dying", "funeral",
        "mourn", "mourned", "heartbroken", "painful", "regret",
        "farewell", "gloomy", "unhappy", "miserable", "despair", "hopeless", "sigh", "sighed",
    )
    private val angry = words(
        "angry", "angrily", "furious", "fury", "rage", "raged", "hate", "hated", "hatred", "damn",
        "liar", "outraged", "annoyed", "irritated", "cruel",
    )
    private val afraid = words(
        "afraid", "fear", "feared", "scared", "terrified", "terror", "frightened", "panic", "panicked", "danger",
        "trembled", "trembling", "scream", "screamed",
        "horror", "horrified", "dread",
    )

    /** Dialogue tags: how the words were said. */
    private val tags: List<Pair<Regex, Mood>> = listOf(
        "whisper(?:ed|ing|s)?|murmur(?:ed|ing|s)?|mutter(?:ed|ing|s)?|breath(?:ed)?|said softly|said quietly|said gently" to Mood.Soft,
        "shout(?:ed|ing|s)?|yell(?:ed|ing|s)?|scream(?:ed|ing|s)?|roar(?:ed|ing|s)?|bellow(?:ed|ing|s)?|called out|cried out" to Mood.Loud,
        "sob(?:bed|bing|s)?|wept|weep(?:ing|s)?|sniffl(?:ed|ing)|choked|said sadly|sighed" to Mood.Sad,
        "laugh(?:ed|ing|s)?|giggl(?:ed|ing|es)|chuckl(?:ed|ing|es)|beam(?:ed|ing)|grinn(?:ed|ing)|said happily|said cheerfully" to Mood.Happy,
        "snap(?:ped|ping|s)|growl(?:ed|ing|s)?|snarl(?:ed|ing|s)?|hiss(?:ed|ing|es)?|spat|barked|said angrily" to Mood.Angry,
        "exclaim(?:ed|ing|s)?|gasp(?:ed|ing|s)?|squeal(?:ed|ing|s)?" to Mood.Excited,
        "stammer(?:ed|ing|s)?|stutter(?:ed|ing|s)?|trembl(?:ed|ing)|whimper(?:ed|ing|s)?" to Mood.Afraid,
    ).map { (pattern, mood) -> Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE) to mood }

    /** The mood of a stretch of text on its own merits. */
    fun moodOf(text: String): Mood {
        val lower = text.lowercase()
        val tokens = Regex("[\\p{L}']+").findAll(lower).map { it.value }.toList()
        if (tokens.isEmpty()) return Mood.Neutral
        val counts = mapOf(
            Mood.Happy to tokens.count { it in happy },
            Mood.Excited to tokens.count { it in excited },
            Mood.Sad to tokens.count { it in sad },
            Mood.Angry to tokens.count { it in angry } + (if ("how dare" in lower) 2 else 0),
            Mood.Afraid to tokens.count { it in afraid },
        )
        val exclaims = text.count { it == '!' }
        val shouting = Regex("\\b\\p{Lu}{3,}\\b").findAll(text).count() // "STOP RIGHT THERE"
        val (best, score) = counts.maxBy { it.value }

        return when {
            shouting >= 2 || (shouting >= 1 && exclaims >= 1) -> if (best == Mood.Angry && score > 0) Mood.Angry else Mood.Loud
            exclaims > 0 && score > 0 && best in setOf(Mood.Angry, Mood.Afraid, Mood.Sad) -> best
            exclaims > 0 -> if (best == Mood.Happy && score > 0) Mood.Happy else Mood.Excited
            score >= 2 || (score == 1 && tokens.size <= 8) -> best
            else -> Mood.Neutral
        }
    }

    /**
     * The delivery of each stretch of one sentence. Speech takes its mood from a dialogue
     * tag in the same sentence if there is one ("…," she whispered), else from its own
     * words; narration is coloured only lightly.
     */
    fun prosody(parts: List<Segment>): List<Prosody> {
        val narration = parts.filterNot { it.quoted }.joinToString(" ") { it.text }
        val tag = tags.firstOrNull { (re, _) -> re.containsMatchIn(narration) }?.second
        return parts.map { part ->
            if (part.quoted) {
                Prosody.of(tag ?: moodOf(part.text))
            } else {
                Prosody.of(moodOf(part.text)).scaled(0.5f)
            }
        }
    }

    // ---- Applying a delivery to speech --------------------------------------------------------

    /**
     * The Kokoro speed to ask for, so that after [apply] shifts the pitch the result plays at
     * [prosody]'s speed times the listener's [speed].
     */
    fun voiceSpeed(prosody: Prosody, speed: Float): Float = (speed * prosody.speed / prosody.pitch).coerceIn(0.5f, 2.0f)

    /** Shifts pitch (by resampling), sets loudness, and adds the pause. */
    fun apply(samples: FloatArray, sampleRate: Int, prosody: Prosody): FloatArray {
        var out = if (abs(prosody.pitch - 1f) > 0.001f) resample(samples, prosody.pitch) else samples
        if (abs(prosody.gainDb) > 0.01f) out = gain(out, prosody.gainDb)
        if (prosody.pauseMs > 0) out = out + FloatArray(sampleRate * prosody.pauseMs / 1000)
        return out
    }

    private const val HALF = 8 // taps either side
    private const val PHASES = 512 // fractional positions in the kernel table

    /**
     * Plays the samples [factor] times faster, raising the pitch by the same factor. A
     * windowed-sinc resampler (from a precomputed table, so it's cheap on a phone) keeps a
     * few percent shift clean.
     */
    fun resample(samples: FloatArray, factor: Float): FloatArray {
        val n = floor(samples.size / factor).toInt()
        val out = FloatArray(n)
        // Going up in pitch drops samples: low-pass first so nothing folds back.
        val cutoff = if (factor > 1f) 1.0 / factor else 1.0
        val taps = 2 * HALF
        val table = FloatArray((PHASES + 1) * taps)
        for (ph in 0..PHASES) {
            val frac = ph.toDouble() / PHASES
            var norm = 0.0
            for (t in 0 until taps) {
                val x = frac + (HALF - 1 - t) // distance from the tap at base - HALF + 1 + t
                val w = 0.5 + 0.5 * cos(PI * x / HALF) // Hann window
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x * cutoff) / (PI * x * cutoff)
                table[ph * taps + t] = (sinc * w).toFloat()
                norm += sinc * w
            }
            for (t in 0 until taps) table[ph * taps + t] = (table[ph * taps + t] / norm).toFloat()
        }
        for (i in 0 until n) {
            val center = i * factor.toDouble()
            val base = floor(center).toInt()
            val row = ((center - base) * PHASES).roundToInt() * taps
            var acc = 0f
            val first = base - HALF + 1
            for (t in 0 until taps) {
                val k = first + t
                if (k >= 0 && k < samples.size) acc += samples[k] * table[row + t]
            }
            out[i] = acc
        }
        return out
    }

    /** Changes loudness, with a soft limit so louder speech never clips. */
    fun gain(samples: FloatArray, db: Float): FloatArray {
        val g = 10.0.pow(db / 20.0).toFloat()
        return FloatArray(samples.size) { i ->
            val v = samples[i] * g
            if (abs(v) <= 0.9f) v else (0.9f + 0.1f * tanh((abs(v) - 0.9f) / 0.1f)) * if (v < 0) -1 else 1
        }
    }
}
