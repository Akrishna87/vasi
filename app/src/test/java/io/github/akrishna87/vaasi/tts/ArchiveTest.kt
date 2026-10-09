package io.github.akrishna87.vaasi.tts

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun stripsThePacksTopFolder() {
        assertEquals("model.onnx", Archive.relativePath("kokoro-en-v0_19/model.onnx"))
        assertEquals("espeak-ng-data/en_dict", Archive.relativePath("./kokoro-en-v0_19/espeak-ng-data/en_dict"))
        assertNull(Archive.relativePath("kokoro-en-v0_19/"))
        assertNull(Archive.relativePath("kokoro-en-v0_19/../../evil"))
    }

    private fun pack(entries: Map<String, String>): File {
        val file = tmp.newFile("pack.tar.bz2")
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.putArchiveEntry(TarArchiveEntry("kokoro/"))
            tar.closeArchiveEntry()
            for ((name, text) in entries) {
                val bytes = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    @Test
    fun unpacksIntoPlaceAndReportsProgress() {
        val archive = pack(
            mapOf(
                "kokoro/model.int8.onnx" to "model",
                "kokoro/voices.bin" to "voices",
                "kokoro/espeak-ng-data/en_dict" to "dict",
            ),
        )
        val target = File(tmp.root, "voices/compact")
        target.parentFile.mkdirs()
        val progress = mutableListOf<Float>()
        Archive.extractTarBz2(archive, target) { progress += it }

        assertEquals("model", File(target, "model.int8.onnx").readText())
        assertEquals("dict", File(target, "espeak-ng-data/en_dict").readText())
        assertEquals("model.int8.onnx", VoicePacks.modelFile(target)?.name)
        assertTrue(progress.isNotEmpty())
        assertFalse(File(tmp.root, "voices/compact.partial").exists())
    }

    @Test
    fun replacesAnOlderCopy() {
        val target = File(tmp.root, "voices/compact").apply { mkdirs() }
        File(target, "stale.txt").writeText("old")
        Archive.extractTarBz2(pack(mapOf("kokoro/model.onnx" to "new")), target)
        assertFalse(File(target, "stale.txt").exists())
        assertEquals("new", File(target, "model.onnx").readText())
    }
}
