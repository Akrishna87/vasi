package io.github.akrishna87.vaasi.convert

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.akrishna87.vaasi.VoiceChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Where a book's conversion to audio stands, for the screens and the player. */
data class AudioStatus(
    val conversion: Conversion? = null,
    /** Being converted right now. */
    val running: Boolean = false,
    /** Waiting its turn behind another book. */
    val queued: Boolean = false,
    val error: String? = null,
    val etaSeconds: Int? = null,
    /** Save to Music once converting finishes. */
    val saveWhenDone: Boolean = false,
    val saving: Boolean = false,
    val savedTo: String? = null,
) {
    val active: Boolean get() = running || queued
    val hasAudio: Boolean get() = (conversion?.converted ?: 0) > 0
}

/**
 * Turns whole books into audio ahead of listening, so they play without pauses. The work
 * runs in [ConvertService]; this keeps the queue and each book's [AudioStatus].
 */
object Convert {
    /** Sentences per segment file: small, so a listener close behind never waits long. */
    const val SEGMENT_SENTENCES = 8
    const val SAMPLE_RATE = 24_000

    private val _status = MutableStateFlow<Map<String, AudioStatus>>(emptyMap())
    val status: StateFlow<Map<String, AudioStatus>> = _status

    private val queue = ArrayDeque<String>()

    fun dir(context: Context, bookId: String) = File(context.filesDir, "audio/$bookId")

    private fun indexFile(context: Context, bookId: String) = File(dir(context, bookId), "index.json")

    /** Marks a conversion the listener wants finished, so it resumes after a restart. */
    private fun pendingFile(context: Context, bookId: String) = File(dir(context, bookId), "pending")

    fun of(bookId: String): AudioStatus = _status.value[bookId] ?: AudioStatus()

    internal fun update(bookId: String, change: (AudioStatus) -> AudioStatus) =
        _status.update { it + (bookId to change(it[bookId] ?: AudioStatus())) }

    /** What the voice settings were, so audio made with other voices is recognised. */
    fun signature(choice: VoiceChoice, expressive: Boolean) =
        listOf(choice.pack.id, choice.narrator.id, choice.dialogue?.id ?: "-", if (expressive) "expressive" else "plain").joinToString("|")

    /** The voices a [signature] stands for, if that pack is still installed. */
    fun choiceFrom(signature: String, installed: List<String>): Triple<String, String, String?>? {
        val p = signature.split('|')
        if (p.size < 4 || p[0] !in installed) return null
        return Triple(p[0], p[1], p[2].takeIf { it != "-" })
    }

    fun expressiveIn(signature: String) = signature.split('|').getOrNull(3) == "expressive"

    /** Reads every book's saved progress when the app starts. */
    fun init(context: Context) {
        val root = File(context.filesDir, "audio")
        val found = root.listFiles().orEmpty().mapNotNull { d ->
            val index = File(d, "index.json")
            val conversion = try {
                if (index.exists()) Conversion.fromJson(index.readText()) else null
            } catch (e: Exception) {
                null
            }
            conversion?.let { d.name to AudioStatus(conversion = it) }
        }.toMap()
        _status.value = found
    }

    internal fun save(context: Context, bookId: String, conversion: Conversion) {
        val f = indexFile(context, bookId)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(conversion.toJson())
        tmp.renameTo(f)
        update(bookId) { it.copy(conversion = conversion) }
    }

    fun sizeBytes(context: Context, bookId: String): Long =
        dir(context, bookId).listFiles().orEmpty().sumOf { it.length() }

    // ---- Queue ------------------------------------------------------------------------------

    internal fun next(): String? = synchronized(queue) { queue.firstOrNull() }

    internal fun done(bookId: String) {
        synchronized(queue) { queue.remove(bookId) }
    }

    /** Converts [bookId]; with [first], ahead of any other book (the one being listened to). */
    fun start(context: Context, bookId: String, first: Boolean = false) {
        synchronized(queue) {
            if (first) {
                queue.remove(bookId)
                queue.addFirst(bookId)
            } else if (bookId !in queue) {
                queue.addLast(bookId)
            }
        }
        dir(context, bookId).mkdirs()
        pendingFile(context, bookId).writeText("1")
        update(bookId) { it.copy(queued = true, error = null) }
        ContextCompat.startForegroundService(
            context,
            Intent(context, ConvertService::class.java).setAction(ConvertService.ACTION_PUMP)
                .putExtra(ConvertService.EXTRA_SWITCH, first),
        )
    }

    /** Stops converting [bookId] for now; what's done is kept. */
    fun pause(context: Context, bookId: String) {
        synchronized(queue) { queue.remove(bookId) }
        pendingFile(context, bookId).delete()
        update(bookId) { it.copy(queued = false, saveWhenDone = false) }
        context.startService(
            Intent(context, ConvertService::class.java).setAction(ConvertService.ACTION_STOP).putExtra(ConvertService.EXTRA_BOOK, bookId),
        )
    }

    /** Throws the audio away (and stops converting it). */
    fun delete(context: Context, bookId: String) {
        if (of(bookId).active) pause(context, bookId)
        dir(context, bookId).deleteRecursively()
        _status.update { it - bookId }
    }

    /** Converts again from scratch, with the voices now chosen. */
    fun reconvert(context: Context, bookId: String) {
        delete(context, bookId)
        start(context, bookId, first = true)
    }

    /** Picks up conversions that were cut short (the app was closed or the phone restarted). */
    fun resumePending(context: Context) {
        File(context.filesDir, "audio").listFiles().orEmpty()
            .filter { File(it, "pending").exists() && of(it.name).conversion?.complete != true && !of(it.name).active }
            .forEach { start(context, it.name) }
    }

    /** Saves the book as one audio file in Music/Vaasi, converting the rest first if need be. */
    fun saveToMusic(context: Context, bookId: String) {
        update(bookId) { it.copy(savedTo = null, error = null) }
        if (of(bookId).conversion?.complete == true) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConvertService::class.java).setAction(ConvertService.ACTION_SAVE).putExtra(ConvertService.EXTRA_BOOK, bookId),
            )
        } else {
            update(bookId) { it.copy(saveWhenDone = true) }
            start(context, bookId)
        }
    }

    fun dismissSaved(bookId: String) = update(bookId) { it.copy(savedTo = null) }
}
