package io.github.akrishna87.vaasi.tts

import java.io.File

enum class Accent(val label: String) { American("American"), British("British") }

enum class Gender { Female, Male }

/**
 * One Kokoro voice. [sid] is its speaker number inside the pack; [best] marks the voices
 * Kokoro's authors rate highest.
 */
data class Voice(
    val id: String,
    val name: String,
    val sid: Int,
    val accent: Accent,
    val gender: Gender,
    val best: Boolean = false,
) {
    val description: String
        get() = "${accent.label} " + if (gender == Gender.Female) "woman" else "man"

    /**
     * espeak-ng's British English for British voices; American voices use the pack's default
     * (its pronunciation dictionary, or American espeak).
     */
    val espeakLanguage: String? get() = if (accent == Accent.British) "en" else null
}

/** A downloadable Kokoro model with its voices, as packaged by sherpa-onnx. */
data class VoicePack(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val sizeMb: Int,
    val voices: List<Voice>,
    val defaultVoice: String,
    /** Pronunciation dictionary for multi-language Kokoro (v1.0+); null for v0.19. */
    val lexicon: String? = null,
) {
    fun voice(id: String?): Voice = voices.firstOrNull { it.id == id } ?: voices.first { it.id == defaultVoice }
}

object VoicePacks {
    private fun v(id: String, name: String, sid: Int, best: Boolean = false): Voice {
        val accent = if (id[0] == 'b') Accent.British else Accent.American
        val gender = if (id[1] == 'f') Gender.Female else Gender.Male
        return Voice(id, name, sid, accent, gender, best)
    }

    val compact = VoicePack(
        id = "compact",
        title = "Compact",
        summary = "11 voices · quick to download and fast on any phone",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2",
        sizeMb = 99,
        defaultVoice = "af_bella",
        voices = listOf(
            v("af", "Aria", 0),
            v("af_bella", "Bella", 1, best = true),
            v("af_nicole", "Nicole", 2),
            v("af_sarah", "Sarah", 3),
            v("af_sky", "Sky", 4),
            v("am_adam", "Adam", 5),
            v("am_michael", "Michael", 6),
            v("bf_emma", "Emma", 7),
            v("bf_isabella", "Isabella", 8),
            v("bm_george", "George", 9),
            v("bm_lewis", "Lewis", 10),
        ),
    )

    // Kokoro v1.0 also has Spanish, French, Hindi, Italian, Japanese, Portuguese and Chinese
    // voices (speakers 28-52); they come with other languages later.
    val studio = VoicePack(
        id = "studio",
        title = "Studio",
        summary = "28 voices · Kokoro's newest, most natural model · best on recent phones",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
        sizeMb = 330,
        defaultVoice = "af_heart",
        lexicon = "lexicon-us-en.txt",
        voices = listOf(
            v("af_alloy", "Alloy", 0),
            v("af_aoede", "Aoede", 1),
            v("af_bella", "Bella", 2, best = true),
            v("af_heart", "Heart", 3, best = true),
            v("af_jessica", "Jessica", 4),
            v("af_kore", "Kore", 5),
            v("af_nicole", "Nicole", 6, best = true),
            v("af_nova", "Nova", 7),
            v("af_river", "River", 8),
            v("af_sarah", "Sarah", 9),
            v("af_sky", "Sky", 10),
            v("am_adam", "Adam", 11),
            v("am_echo", "Echo", 12),
            v("am_eric", "Eric", 13),
            v("am_fenrir", "Fenrir", 14, best = true),
            v("am_liam", "Liam", 15),
            v("am_michael", "Michael", 16, best = true),
            v("am_onyx", "Onyx", 17),
            v("am_puck", "Puck", 18, best = true),
            v("am_santa", "Santa", 19),
            v("bf_alice", "Alice", 20),
            v("bf_emma", "Emma", 21, best = true),
            v("bf_isabella", "Isabella", 22),
            v("bf_lily", "Lily", 23),
            v("bm_daniel", "Daniel", 24),
            v("bm_fable", "Fable", 25),
            v("bm_george", "George", 26, best = true),
            v("bm_lewis", "Lewis", 27),
        ),
    )

    val all = listOf(compact, studio)

    fun byId(id: String?): VoicePack? = all.firstOrNull { it.id == id }

    /** An installed pack's .onnx model; its file name differs between the plain and int8 packs. */
    fun modelFile(dir: File): File? {
        val models = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }.orEmpty()
        return models.firstOrNull { "int8" in it.name } ?: models.minByOrNull { it.name.length }
    }
}
