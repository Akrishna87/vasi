package io.github.akrishna87.vaasi.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.akrishna87.vaasi.MainActivity
import io.github.akrishna87.vaasi.R
import io.github.akrishna87.vaasi.app
import io.github.akrishna87.vaasi.text.Segment
import io.github.akrishna87.vaasi.tts.Tts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Saving a book as an audio file, for the screens to show. */
object Export {
    sealed interface State {
        data object Idle : State
        data class Working(val bookId: String, val title: String, val done: Int, val total: Int) : State
        data class Done(val bookId: String, val title: String, val location: String) : State
        data class Failed(val bookId: String, val message: String) : State
    }

    internal val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun start(context: Context, bookId: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ExportService::class.java).setAction(ExportService.ACTION_START).putExtra(ExportService.EXTRA_BOOK, bookId),
        )
    }

    fun cancel(context: Context) {
        context.startService(Intent(context, ExportService::class.java).setAction(ExportService.ACTION_CANCEL))
    }

    fun dismiss() {
        if (_state.value !is State.Working) _state.value = State.Idle
    }
}

/**
 * Has the voice read a whole book into an .m4a file in Music/Vaasi. It runs as a foreground
 * service because a long book takes a while, and the phone may be locked meanwhile.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Saving audio files", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while Vaasi saves a PDF as an audio file"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION, progressNotification("Getting ready…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_BOOK)
                if (id != null && job?.isActive != true) job = scope.launch { export(id) } else if (job?.isActive != true) finish()
            }
            ACTION_CANCEL -> {
                job?.cancel()
                Export._state.value = Export.State.Idle
                finish()
            }
            else -> if (job?.isActive != true) finish()
        }
        return START_NOT_STICKY
    }

    private suspend fun export(bookId: String) {
        val title = app.library.entry(bookId)?.title ?: "Book"
        val choice = app.voiceChoice()
        if (choice == null) {
            Export._state.value = Export.State.Failed(bookId, "Download a voice first")
            finish()
            return
        }
        val speed = app.settings.settings.value.speed
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vaasi:export")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        val temp = File(cacheDir, "export.m4a")
        try {
            val book = app.library.load(bookId)
            val dir = app.packs.dir(choice.pack)
            Export._state.value = Export.State.Working(bookId, title, 0, book.sentenceCount)
            var writer: AacWriter? = null
            try {
                for (i in 0 until book.sentenceCount) {
                    val parts = if (choice.dialogue != null) book.segments[i] else listOf(Segment(book.sentence(i), false))
                    for (part in parts) {
                        val voice = if (part.quoted) choice.dialogue ?: choice.narrator else choice.narrator
                        val speech = Tts.speak(dir, choice.pack, voice, part.text, speed)
                        if (speech.samples.isEmpty()) continue
                        withContext(Dispatchers.IO) {
                            val w = writer ?: AacWriter(temp, speech.sampleRate).also { writer = it }
                            w.write(speech.samples)
                        }
                    }
                    if (book.isLastInParagraph(i)) withContext(Dispatchers.IO) { writer?.silence(450) }
                    Export._state.value = Export.State.Working(bookId, title, i + 1, book.sentenceCount)
                    if (i % 5 == 0) notifyProgress(title, i + 1, book.sentenceCount)
                    currentCoroutineContext().ensureActive()
                }
                withContext(Dispatchers.IO) { writer?.finish() }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { writer?.close() }
            }
            val location = withContext(Dispatchers.IO) { publish(temp, title) }
            Export._state.value = Export.State.Done(bookId, title, location)
            doneNotification(title, location)
        } catch (e: CancellationException) {
            Export._state.value = Export.State.Idle
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Export failed", e)
            Export._state.value = Export.State.Failed(bookId, e.message ?: "Saving the audio failed")
        } finally {
            temp.delete()
            wakeLock?.takeIf { it.isHeld }?.release()
            finish()
        }
    }

    /** Copies the finished file into the shared Music/Vaasi folder. */
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

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(title: String, done: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vaasi)
            .setContentTitle("Saving “$title” as audio")
            .setContentText(if (total > 0) "${done * 100 / total}% done" else "Getting ready…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(R.drawable.ic_close, "Cancel", cancel)
            .build()
    }

    private fun notifyProgress(title: String, done: Int, total: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, progressNotification(title, done, total))
    }

    private fun doneNotification(title: String, location: String) {
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VaasiExport"
        const val ACTION_START = "io.github.akrishna87.vaasi.EXPORT"
        const val ACTION_CANCEL = "io.github.akrishna87.vaasi.EXPORT_CANCEL"
        const val EXTRA_BOOK = "book"
        private const val CHANNEL = "export"
        private const val NOTIFICATION = 2
        private const val DONE_NOTIFICATION = 3
    }
}
