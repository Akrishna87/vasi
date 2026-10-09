package io.github.akrishna87.vaasi.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encodes mono speech into an AAC .m4a file with the phone's own encoder. */
class AacWriter(file: File, private val sampleRate: Int) : Closeable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxing = false
    private var framesQueued = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Adds samples in [-1, 1]. */
    fun write(samples: FloatArray) {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcm.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        pcm.flip()
        while (pcm.hasRemaining()) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val input = codec.getInputBuffer(index)!!
                input.clear()
                val bytes = minOf(input.remaining(), pcm.remaining()) and 1.inv() // whole samples
                val slice = pcm.duplicate()
                slice.limit(pcm.position() + bytes)
                input.put(slice)
                pcm.position(pcm.position() + bytes)
                codec.queueInputBuffer(index, 0, bytes, framesQueued * 1_000_000L / sampleRate, 0)
                framesQueued += bytes / 2
            }
            drain(endOfStream = false)
        }
    }

    fun silence(ms: Int) = write(FloatArray(sampleRate * ms / 1000))

    /** Flushes the encoder and completes the file. */
    fun finish() {
        var queued = false
        var attempts = 0
        while (!queued && attempts++ < 500) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index, 0, 0, framesQueued * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                queued = true
            } else {
                drain(endOfStream = false)
            }
        }
        drain(endOfStream = true)
        if (muxing) muxer.stop()
        muxing = false
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idle > 300) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && muxing) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, output, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close() {
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
        }
        codec.release()
        try {
            if (muxing) muxer.stop()
        } catch (_: IllegalStateException) {
        }
        muxer.release()
    }
}
