package io.github.akrishna87.vaasi.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Speech made by the voice: mono samples in [-1, 1]. */
class Speech(val samples: FloatArray, val sampleRate: Int)

/**
 * The Kokoro voice, running on the phone through sherpa-onnx. One model is kept loaded;
 * calls take turns, since reading, previews and saving to a file all share it.
 */
object Tts {
    private const val TAG = "VaasiTts"

    private val lock = Mutex()
    private var engine: OfflineTts? = null
    private var loadedPack: String? = null

    private val _loading = MutableStateFlow(false)
    /** True while a voice model is being loaded into memory (a few seconds). */
    val loading: StateFlow<Boolean> = _loading

    suspend fun speak(dir: File, pack: VoicePack, voice: Voice, text: String, speed: Float): Speech =
        lock.withLock {
            withContext(Dispatchers.Default) {
                val tts = load(dir, pack)
                val config = GenerationConfig(
                    sid = voice.sid,
                    speed = speed,
                    silenceScale = 0.2f,
                    extra = voice.espeakLanguage?.let { mapOf("lang" to it) },
                )
                val audio = tts.generateWithConfig(text, config)
                Speech(audio.samples, audio.sampleRate)
            }
        }

    fun unload(pack: VoicePack) {
        if (loadedPack == pack.id && lock.tryLock()) {
            try {
                engine?.release()
                engine = null
                loadedPack = null
            } finally {
                lock.unlock()
            }
        }
    }

    private fun load(dir: File, pack: VoicePack): OfflineTts {
        engine?.let { if (loadedPack == pack.id) return it }
        engine?.release()
        engine = null
        _loading.value = true
        try {
            val model = VoicePacks.modelFile(dir) ?: error("The ${pack.title} voices aren't installed")
            val kokoro = OfflineTtsKokoroModelConfig(
                model = model.path,
                voices = File(dir, "voices.bin").path,
                tokens = File(dir, "tokens.txt").path,
                dataDir = File(dir, "espeak-ng-data").path,
                lexicon = pack.lexicon?.let { File(dir, it).path } ?: "",
            )
            val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(kokoro = kokoro, numThreads = threads, debug = false, provider = "cpu"),
            )
            val started = System.currentTimeMillis()
            val tts = OfflineTts(assetManager = null, config = config)
            Log.i(TAG, "Loaded ${pack.id} in ${System.currentTimeMillis() - started} ms with $threads threads")
            engine = tts
            loadedPack = pack.id
            return tts
        } finally {
            _loading.value = false
        }
    }
}
