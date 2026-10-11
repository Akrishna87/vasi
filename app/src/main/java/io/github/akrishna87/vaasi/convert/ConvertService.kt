package io.github.akrishna87.vaasi.convert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.akrishna87.vaasi.MainActivity
import io.github.akrishna87.vaasi.R
import io.github.akrishna87.vaasi.VoiceChoice
import io.github.akrishna87.vaasi.app
import io.github.akrishna87.vaasi.play.Reader
import io.github.akrishna87.vaasi.text.Book
import io.github.akrishna87.vaasi.text.Segment as TextSegment
import io.github.akrishna87.vaasi.tts.Expression
import io.github.akrishna87.vaasi.tts.Prosody
import io.github.akrishna87.vaasi.tts.Tts
import io.github.akrishna87.vaasi.tts.VoicePacks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Converts books to audio in the background, a few sentences at a time just ahead of where
 * the listener is, saving after each stretch so it can pick up where it stopped. Also saves
 * finished books to Music.
 */
class ConvertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var current: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Converting to audio", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while Vaasi turns a PDF into audio"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION, progress("Getting ready…", null, 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        when (intent?.action) {
            ACTION_PUMP -> {
                // The book being listened to jumps the queue, even if another is converting.
                val first = Convert.next()
                if (intent.getBooleanExtra(EXTRA_SWITCH, false) && current != null && current != first) job?.cancel()
                pump()
            }
            ACTION_STOP -> {
                val id = intent.getStringExtra(EXTRA_BOOK)
                if (id != null && id == current) job?.cancel()
                pump()
            }
            ACTION_PAUSE_FROM_NOTIFICATION -> intent.getStringExtra(EXTRA_BOOK)?.let { id -> Convert.pause(this, id) }
            ACTION_SAVE -> intent.getStringExtra(EXTRA_BOOK)?.let { id -> scope.launch { saveToMusic(id); pump() } }
            else -> pump()
        }
        return START_NOT_STICKY
    }

    /** Starts on the next book in the queue, or stops when there's none. */
    private fun pump() {
        if (job?.isActive == true) return
        val id = Convert.next()
        if (id == null) {
            if (Convert.status.value.values.none { it.saving }) finish()
            return
        }
        current = id
        acquireWakeLock()
        val j = scope.launch {
            try {
                convert(id)
                Convert.done(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Converting failed", e)
                Convert.done(id)
                val message = if (e is OutOfMemoryError) "The phone ran out of memory." else e.message ?: e.javaClass.simpleName
                Convert.update(id) { it.copy(error = "Converting stopped: $message", saveWhenDone = false) }
            } finally {
                Convert.update(id) { it.copy(running = false, queued = false, etaSeconds = null) }
                current = null
            }
        }
        job = j
        j.invokeOnCompletion { scope.launch { pump() } }
    }

    private fun voices(conversion: Conversion?): Pair<VoiceChoice, Boolean>? {
        // Carry on with the voices the book was started in, so it doesn't change voice halfway.
        if (conversion != null) {
            val installed = app.packs.installed().map { it.id }
            Convert.choiceFrom(conversion.signature, installed)?.let { (packId, narrator, dialogue) ->
                val pack = VoicePacks.byId(packId)!!
                val choice = VoiceChoice(pack, pack.voice(narrator), dialogue?.let { d -> pack.voices.firstOrNull { it.id == d } })
                return choice to Convert.expressiveIn(conversion.signature)
            }
        }
        val choice = app.voiceChoice() ?: return null
        return choice to app.settings.settings.value.expressive
    }

    private suspend fun convert(id: String) {
        val book = app.library.load(id)
        val dir = Convert.dir(this, id).apply { mkdirs() }
        var conversion = Convert.of(id).conversion?.takeIf { it.sentences == book.sentenceCount }
        val (choice, expressive) = voices(conversion) ?: error("Download a voice first")
        val signature = Convert.signature(choice, expressive)
        if (conversion == null || conversion.signature != signature) {
            dir.listFiles().orEmpty().filter { it.name.endsWith(".m4a") || it.name.endsWith(".part") }.forEach { it.delete() }
            conversion = Conversion(book.sentenceCount, Convert.SAMPLE_RATE, signature, choice.narrator.name, emptyList())
            Convert.save(this, id, conversion)
        }
        Convert.update(id) { it.copy(running = true, queued = false, error = null) }
        val title = app.library.entry(id)?.title ?: book.title
        val packDir = app.packs.dir(choice.pack)
        val began = System.currentTimeMillis()
        var doneHere = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val reader = Reader.state.value
            val anchor = if (reader.bookId == id) reader.sentence else book.start
            val from = conversion!!.nextToConvert(anchor) ?: break
            // When the listener is waiting right here, make a short piece so they hear it sooner.
            val end = conversion.runEnd(from, if (reader.bookId == id && from == anchor) 2 else Convert.SEGMENT_SENTENCES)
            val segment = convertRun(book, from, end, dir, packDir, choice, expressive)
            conversion = conversion.with(segment)
            Convert.save(this, id, conversion)
            doneHere += end - from
            val perSentence = (System.currentTimeMillis() - began) / 1000.0 / doneHere
            val eta = if (doneHere >= 16) ((conversion.sentences - conversion.converted) * perSentence).toInt() else null
            Convert.update(id) { it.copy(etaSeconds = eta) }
            notifyProgress(title, eta, conversion.fraction)
        }
        File(dir, "pending").delete()
        if (Convert.of(id).saveWhenDone) {
            Convert.update(id) { it.copy(saveWhenDone = false) }
            saveToMusic(id)
        }
    }

    /** Speaks sentences [from] until [end] into one segment file. */
    private suspend fun convertRun(
        book: Book, from: Int, end: Int, dir: File, packDir: File, choice: VoiceChoice, expressive: Boolean,
    ): Segment {
        val name = "s$from.m4a"
        val part = File(dir, "$name.part")
        val starts = LongArray(end - from)
        var position = 0L
        val writer = withContext(Dispatchers.IO) { AacWriter(part, Convert.SAMPLE_RATE) }
        try {
            for (i in from until end) {
                starts[i - from] = position
                val parts = if (choice.dialogue != null || expressive) book.segments[i] else listOf(TextSegment(book.sentence(i), false))
                val moods = if (expressive) Expression.prosody(parts) else parts.map { Prosody.Plain }
                var spoke = false
                for ((k, part2) in parts.withIndex()) {
                    val voice = if (part2.quoted) choice.dialogue ?: choice.narrator else choice.narrator
                    val speech = Tts.speak(packDir, choice.pack, voice, part2.text, Expression.voiceSpeed(moods[k], 1f))
                    if (speech.samples.isEmpty()) continue
                    val samples = Expression.apply(speech.samples, speech.sampleRate, moods[k])
                    withContext(Dispatchers.IO) { writer.write(samples) }
                    position += samples.size
                    spoke = true
                }
                // A paragraph's end gets a breath; a sentence the voice skipped still takes a moment.
                val pauseMs = if (book.isLastInParagraph(i)) 450 else if (!spoke) 60 else 0
                if (pauseMs > 0) {
                    withContext(Dispatchers.IO) { writer.silence(pauseMs) }
                    position += Convert.SAMPLE_RATE * pauseMs / 1000
                }
            }
            withContext(Dispatchers.IO) { writer.finish() }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { writer.close() }
        }
        val file = File(dir, name)
        if (!part.renameTo(file)) error("Couldn't save the audio")
        return Segment(name, from, starts, position)
    }

    /** Joins the book's audio (from where its main text starts) into one file in Music/Vaasi. */
    private suspend fun saveToMusic(id: String) {
        val conversion = Convert.of(id).conversion ?: return
        Convert.update(id) { it.copy(saving = true, error = null) }
        val title = app.library.entry(id)?.title ?: "Book"
        try {
            val start = app.library.load(id).start
            val files = conversion.segments.filter { it.first >= start }.ifEmpty { conversion.segments }
                .map { File(Convert.dir(this, id), it.file) }
            val joined = File(cacheDir, "save.m4a")
            val location = withContext(Dispatchers.IO) {
                AacReader.join(files, joined)
                try {
                    publish(joined, title)
                } finally {
                    joined.delete()
                }
            }
            Convert.update(id) { it.copy(saving = false, savedTo = location) }
            notifySaved(title, location)
        } catch (e: CancellationException) {
            Convert.update(id) { it.copy(saving = false) }
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Saving failed", e)
            Convert.update(id) { it.copy(saving = false, error = "Couldn't save to Music: ${e.message}") }
        }
    }

    /** Copies the file into the shared Music/Vaasi folder. */
    private fun publish(file: File, title: String): String {
        val name = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifEmpty { "Book" }
        val folder = Environment.DIRECTORY_MUSIC + "/Vaasi"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, "Vaasi")
            put(MediaStore.Audio.Media.ALBUM, title)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values) ?: error("Couldn't create the audio file")
        try {
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Couldn't write the audio file")
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        return "$folder/$name.m4a"
    }

    // Android 15 limits how long this kind of background work may run in a day; when it says
    // stop, stop cleanly. What's converted is kept, and Continue picks it up.
    override fun onTimeout(startId: Int, fgsType: Int) {
        current?.let { id -> Convert.update(id) { it.copy(error = "Android paused the conversion for now. Tap Continue converting to carry on.") } }
        job?.cancel()
        finish()
    }

    private fun finish() {
        wakeLock?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vaasi:convert")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(10 * 60 * 60 * 1000L)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progress(title: String, eta: Int?, fraction: Float): Notification {
        val pause = current?.let { id ->
            PendingIntent.getService(
                this, 2, Intent(this, ConvertService::class.java).setAction(ACTION_PAUSE_FROM_NOTIFICATION).putExtra(EXTRA_BOOK, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val percent = (fraction * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vaasi)
            .setContentTitle("Converting “$title” to audio")
            .setContentText(if (eta != null) "$percent% · ${timeLeft(eta)}" else "$percent%")
            .setProgress(100, percent, fraction == 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .apply { if (pause != null) addAction(R.drawable.ic_pause, "Pause converting", pause) }
            .build()
    }

    private fun notifyProgress(title: String, eta: Int?, fraction: Float) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, progress(title, eta, fraction))
    }

    private fun notifySaved(title: String, location: String) {
        getSystemService(NotificationManager::class.java).notify(
            DONE_NOTIFICATION,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_vaasi)
                .setContentTitle("Saved “$title”")
                .setContentText(location)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VaasiConvert"
        const val ACTION_PUMP = "io.github.akrishna87.vaasi.CONVERT"
        const val ACTION_STOP = "io.github.akrishna87.vaasi.CONVERT_STOP"
        const val ACTION_PAUSE_FROM_NOTIFICATION = "io.github.akrishna87.vaasi.CONVERT_PAUSE"
        const val ACTION_SAVE = "io.github.akrishna87.vaasi.SAVE_TO_MUSIC"
        const val EXTRA_BOOK = "book"
        const val EXTRA_SWITCH = "switch"
        private const val CHANNEL = "convert"
        private const val NOTIFICATION = 2
        private const val DONE_NOTIFICATION = 3

        fun timeLeft(seconds: Int): String = when {
            seconds < 90 -> "about a minute left"
            seconds < 3600 -> "about ${(seconds + 30) / 60} min left"
            else -> {
                val h = seconds / 3600
                val m = (seconds % 3600 + 30) / 60
                if (m == 0) "about $h h left" else "about $h h $m min left"
            }
        }
    }
}
