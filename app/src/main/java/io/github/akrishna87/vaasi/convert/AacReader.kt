package io.github.akrishna87.vaasi.convert

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/** Decodes a converted .m4a segment back into speech samples with the phone's own decoder. */
object AacReader {

    /**
     * Calls [onChunk] with consecutive runs of mono samples in [-1, 1] and the position (in
     * samples from the start of the file) of each run's first sample, skipping everything
     * before [from]. Stops early when [onChunk] returns false.
     */
    fun decode(file: File, from: Long = 0, onChunk: (FloatArray, Long) -> Boolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val format = extractor.getTrackFormat(0)
            extractor.selectTrack(0)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var position = 0L
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            while (true) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    out >= 0 -> {
                        val buffer = decoder.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val frames = info.size / ((if (floatPcm) 4 else 2) * channels)
                        val samples = FloatArray(frames) { i ->
                            var sum = 0f
                            for (c in 0 until channels) {
                                sum += if (floatPcm) buffer.getFloat() else buffer.getShort() / 32768f
                            }
                            sum / channels
                        }
                        decoder.releaseOutputBuffer(out, false)
                        val start = position
                        position += frames
                        if (position > from && frames > 0) {
                            val skip = (from - start).coerceAtLeast(0).toInt()
                            val chunk = if (skip > 0) samples.copyOfRange(skip, samples.size) else samples
                            if (!onChunk(chunk, start + skip)) return
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: IllegalStateException) {
            }
            codec?.release()
            extractor.release()
        }
    }

    /**
     * Joins segments end to end into one .m4a (copying the encoded audio, no re-encoding),
     * for saving a whole book to Music.
     */
    fun join(files: List<File>, out: File) {
        val muxer = android.media.MediaMuxer(out.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var offsetUs = 0L
        val buffer = java.nio.ByteBuffer.allocateDirect(1 shl 18)
        val info = MediaCodec.BufferInfo()
        try {
            for (file in files) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    val format = extractor.getTrackFormat(0)
                    extractor.selectTrack(0)
                    if (track < 0) {
                        track = muxer.addTrack(format)
                        muxer.start()
                    }
                    val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    var lastUs = 0L
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        lastUs = extractor.sampleTime
                        val sync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        info.set(0, size, offsetUs + lastUs, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, buffer, info)
                        extractor.advance()
                    }
                    offsetUs += lastUs + 1024L * 1_000_000L / rate // one AAC frame after the last
                } finally {
                    extractor.release()
                }
            }
            if (track >= 0) muxer.stop()
        } finally {
            muxer.release()
        }
    }
}
