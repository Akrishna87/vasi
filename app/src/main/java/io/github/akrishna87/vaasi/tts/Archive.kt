package io.github.akrishna87.vaasi.tts

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Unpacks the .tar.bz2 voice packs that sherpa-onnx publishes. */
object Archive {

    /**
     * The path of a tar entry inside the pack, without the pack's own top folder
     * ("kokoro-en-v0_19/espeak-ng-data/en_dict" -> "espeak-ng-data/en_dict"), or null for the
     * top folder itself and for anything trying to climb out of it.
     */
    fun relativePath(entryName: String): String? {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.size < 2 || parts.any { it == ".." }) return null
        return parts.drop(1).joinToString("/")
    }

    /**
     * Unpacks [archive] into [target], replacing what was there. Works in a sibling folder
     * and swaps it in at the end so a half-finished unpack is never mistaken for a pack.
     */
    fun extractTarBz2(archive: File, target: File, onProgress: (Float) -> Unit = {}) {
        val work = File(target.parentFile, target.name + ".partial")
        work.deleteRecursively()
        if (!work.mkdirs()) throw IOException("Couldn't create $work")
        val total = archive.length().coerceAtLeast(1)
        val counter = CountingStream(FileInputStream(archive))
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counter, 1 shl 16))).use { tar ->
            var lastReported = -1
            while (true) {
                val entry = tar.nextEntry ?: break
                val rel = relativePath(entry.name) ?: continue
                val out = File(work, rel)
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                    }
                    // Symlinks and the like aren't used by the voice packs.
                }
                val percent = (counter.count * 100 / total).toInt()
                if (percent != lastReported) {
                    lastReported = percent
                    onProgress(percent / 100f)
                }
            }
        }
        target.deleteRecursively()
        if (!work.renameTo(target)) throw IOException("Couldn't move the voice pack into place")
    }

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
