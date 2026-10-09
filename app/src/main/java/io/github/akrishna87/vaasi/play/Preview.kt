package io.github.akrishna87.vaasi.play

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.akrishna87.vaasi.app
import io.github.akrishna87.vaasi.tts.Tts
import io.github.akrishna87.vaasi.tts.Voice
import io.github.akrishna87.vaasi.tts.VoicePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Plays a short sample of a voice, so the listener can pick one by ear. */
object Preview {
    private val _playing = MutableStateFlow<String?>(null)
    /** The id of the voice being previewed (or prepared), if any. */
    val playing: StateFlow<String?> = _playing

    suspend fun play(context: Context, pack: VoicePack, voice: Voice, speed: Float) {
        Reader.pause()
        _playing.value = voice.id
        var track: AudioTrack? = null
        try {
            val text = "Hello, I'm ${voice.name}. I can read your PDFs aloud, from the first page to the last."
            val speech = Tts.speak(context.app.packs.dir(pack), pack, voice, text, speed)
            withContext(Dispatchers.IO) {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(speech.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(speech.samples.size * 4)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track = t
                t.write(speech.samples, 0, speech.samples.size, AudioTrack.WRITE_BLOCKING)
                t.play()
                val ms = speech.samples.size * 1000L / speech.sampleRate
                delay(ms + 150)
            }
        } finally {
            track?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                }
                it.release()
            }
            if (_playing.value == voice.id) _playing.value = null
        }
    }
}
