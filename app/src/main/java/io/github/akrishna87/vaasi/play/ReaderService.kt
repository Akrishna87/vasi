package io.github.akrishna87.vaasi.play

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media.session.MediaButtonReceiver
import io.github.akrishna87.vaasi.MainActivity
import io.github.akrishna87.vaasi.R
import io.github.akrishna87.vaasi.VoiceChoice
import io.github.akrishna87.vaasi.app
import io.github.akrishna87.vaasi.convert.AacReader
import io.github.akrishna87.vaasi.convert.Convert
import io.github.akrishna87.vaasi.text.Book
import io.github.akrishna87.vaasi.text.Segment
import io.github.akrishna87.vaasi.text.Sentences
import io.github.akrishna87.vaasi.tts.Expression
import io.github.akrishna87.vaasi.tts.Prosody
import io.github.akrishna87.vaasi.tts.Speech
import io.github.akrishna87.vaasi.tts.Tts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads a book aloud in the background. A producer has the voice speak the coming sentences
 * a few ahead while a player streams them to an [AudioTrack]; which sentence is audible is
 * worked out from the track's playback position, so the highlight follows the voice.
 */
class ReaderService : Service() {

    private class Clip(val sentence: Int, val speech: Speech, val pauseMs: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var wakeLock: PowerManager.WakeLock? = null

    private var book: Book? = null
    private var job: Job? = null
    private val trackLock = Any()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var paused = false
    /** Settings changed while paused: start afresh with them on resume. */
    private var stale = false
    private var resumeOnFocusGain = false
    private var noisyRegistered = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(::onFocusChange, Handler(Looper.getMainLooper()))
            .build()
        session = MediaSessionCompat(this, "Vaasi").apply {
            setCallback(callback)
            setSessionActivity(openApp())
            isActive = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows what Vaasi is reading, with play and pause"
                setShowBadge(false)
            },
        )
        scope.launch {
            Reader.state.collect { s ->
                updateSession(s)
                if (s.bookId != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(s))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Whatever started us expects a foreground service within seconds.
        ServiceCompat.startForeground(
            this, NOTIFICATION, notification(Reader.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        when (intent?.action) {
            ACTION_PLAY -> {
                val id = intent.getStringExtra(EXTRA_BOOK)
                if (id != null) open(id, intent.getIntExtra(EXTRA_SENTENCE, -1)) else stopReading()
            }
            Intent.ACTION_MEDIA_BUTTON -> {
                if (book != null) {
                    MediaButtonReceiver.handleIntent(session, intent)
                } else {
                    // A headset button after the app was closed: pick up the last book.
                    val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    val wantsPlay = key != null && key.keyCode in setOf(
                        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
                    )
                    val last = getSharedPreferences("reader", MODE_PRIVATE).getString("lastBook", null)
                    if (wantsPlay && last != null && app.library.entry(last) != null) open(last, -1) else stopReading()
                }
            }
            else -> if (book == null) stopReading()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away stops a paused book but lets a playing one carry on.
        if (!Reader.state.value.playing) stopReading()
    }

    override fun onDestroy() {
        instance = null
        job?.cancel()
        releaseTrack()
        abandonFocus()
        releaseWakeLock()
        unregisterNoisy()
        session.isActive = false
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Controls -------------------------------------------------------------------------

    private fun open(id: String, sentence: Int) {
        scope.launch {
            val b = book?.takeIf { it.id == id } ?: try {
                app.library.load(id)
            } catch (e: Exception) {
                Reader.publish { it.copy(error = "Couldn't open that PDF", playing = false) }
                stopReading()
                return@launch
            }
            book = b
            getSharedPreferences("reader", MODE_PRIVATE).edit().putString("lastBook", id).apply()
            val start = if (sentence >= 0) sentence else app.library.entry(id)?.position ?: 0
            Reader.publish {
                it.copy(bookId = b.id, title = b.title, total = b.sentenceCount, error = null)
            }
            startFrom(start.coerceIn(0, b.sentenceCount - 1))
        }
    }

    fun toggle() = if (Reader.state.value.playing) pause() else resume()

    fun pause() {
        paused = true
        synchronized(trackLock) { track?.pause() }
        releaseWakeLock()
        Reader.publish { it.copy(playing = false, waiting = false) }
    }

    fun resume() {
        val b = book ?: return
        if (stale || job?.isActive != true) {
            // Play again from the top once the book has been read to the end.
            val at = Reader.state.value.sentence
            startFrom(if (at >= b.sentenceCount - 1) 0 else at.coerceAtLeast(0))
            return
        }
        if (!requestFocus()) return
        paused = false
        synchronized(trackLock) { track?.play() }
        acquireWakeLock()
        Reader.publish { it.copy(playing = true, error = null) }
    }

    fun seek(sentence: Int) {
        val b = book ?: return
        startFrom(sentence.coerceIn(0, b.sentenceCount - 1))
    }

    fun settingsChanged() {
        if (book == null) return
        if (Reader.state.value.playing) seek(Reader.state.value.sentence) else stale = true
    }

    fun stopReading() {
        job?.cancel()
        job = null
        releaseTrack()
        abandonFocus()
        releaseWakeLock()
        unregisterNoisy()
        book = null
        Reader.publish { Reader.State(error = it.error, slow = it.slow) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Reading --------------------------------------------------------------------------

    private fun startFrom(index: Int) {
        val b = book ?: return
        val choice = app.voiceChoice()
        if (choice == null && !Convert.of(b.id).hasAudio) {
            Reader.publish { it.copy(playing = false, waiting = false, error = "Download a voice first") }
            return
        }
        val speed = app.settings.settings.value.speed
        val expressive = app.settings.settings.value.expressive
        stale = false
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            if (!requestFocus()) {
                Reader.publish { it.copy(sentence = index, playing = false, waiting = false) }
                return@launch
            }
            paused = false
            registerNoisy()
            acquireWakeLock()
            Reader.publish { it.copy(sentence = index, playing = true, waiting = true, error = null) }
            app.library.savePosition(b.id, index)
            try {
                read(b, choice, speed, expressive, index)
                // The end of the book.
                app.library.savePosition(b.id, b.sentenceCount - 1)
                releaseWakeLock()
                Reader.publish { it.copy(sentence = b.sentenceCount - 1, playing = false, waiting = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Reading failed", e)
                releaseWakeLock()
                val message = if (e is OutOfMemoryError) {
                    "This phone ran out of memory for these voices. Try the Compact voices."
                } else {
                    "The voice stopped: ${e.message ?: e.javaClass.simpleName}"
                }
                Reader.publish { it.copy(playing = false, waiting = false, error = message) }
            }
        }
    }

    private suspend fun read(b: Book, choice: VoiceChoice?, speed: Float, expressive: Boolean, from: Int) = coroutineScope {
        val audio = Convert.of(b.id)
        // A book converted to audio (or being converted) plays from its audio files.
        val converted = audio.hasAudio || audio.active
        if (audio.active && Convert.next() != b.id) Convert.start(this@ReaderService, b.id, first = true)
        // Several sentences ahead, so a slow moment doesn't leave a gap.
        val clips = Channel<Clip>(capacity = 6)
        launch(Dispatchers.Default) {
            try {
                if (converted) {
                    playConverted(b, choice, expressive, from, clips)
                } else {
                    for (i in from until b.sentenceCount) speak(b, choice!!, expressive, i, speed, clips, firstBreath = i == from)
                }
                clips.close()
            } catch (e: Throwable) {
                clips.close(e)
                if (e is CancellationException) throw e
            }
        }
        // Converted audio is made at normal speed; the speaker speeds it up without changing pitch.
        withContext(Dispatchers.IO) { play(b, clips, trackSpeed = if (converted) speed else 1f) }
    }

    /** Has the voice speak sentence [i] now. */
    private suspend fun speak(
        b: Book, choice: VoiceChoice, expressive: Boolean, i: Int, speed: Float, clips: SendChannel<Clip>, firstBreath: Boolean,
    ) {
        val dir = app.packs.dir(choice.pack)
        var parts = if (choice.dialogue != null || expressive) b.segments[i] else listOf(Segment(b.sentence(i), false))
        var moods = if (expressive) Expression.prosody(parts) else parts.map { Prosody.Plain }
        if (firstBreath) {
            // Start talking sooner: say the first clause of a long opening sentence on its own.
            val first = Sentences.firstBreath(parts[0].text)
            if (first.size > 1) {
                parts = first.map { Segment(it, parts[0].quoted) } + parts.drop(1)
                moods = first.map { moods[0] } + moods.drop(1)
            }
        }
        for ((k, part) in parts.withIndex()) {
            val voice = if (part.quoted) choice.dialogue ?: choice.narrator else choice.narrator
            val mood = moods[k]
            val speech = Tts.speak(dir, choice.pack, voice, part.text, Expression.voiceSpeed(mood, speed))
            if (speech.samples.isEmpty()) continue
            val samples = Expression.apply(speech.samples, speech.sampleRate, mood)
            val endOfParagraph = k == parts.lastIndex && b.isLastInParagraph(i)
            clips.send(Clip(i, Speech(samples, speech.sampleRate), if (endOfParagraph) 450 else 0))
        }
    }

    /**
     * Plays the book's converted audio from sentence [from]. Where it isn't converted yet:
     * if converting is under way, wait for it (it works just ahead of the listener);
     * otherwise have the voice speak those sentences now.
     */
    private suspend fun playConverted(b: Book, choice: VoiceChoice?, expressive: Boolean, from: Int, clips: SendChannel<Clip>) {
        val dir = Convert.dir(this, b.id)
        var i = from
        while (i < b.sentenceCount) {
            val status = Convert.of(b.id)
            val segment = status.conversion?.segmentFor(i)
            when {
                segment != null -> {
                    val pieces = withContext(Dispatchers.IO) { sentencesOf(File(dir, segment.file), segment, i) }
                    for ((sentence, samples) in pieces) clips.send(Clip(sentence, Speech(samples, Convert.SAMPLE_RATE), 0))
                    i = segment.last + 1
                }
                status.active -> {
                    val wanted = i
                    Convert.status.first { m ->
                        val s = m[b.id]
                        s == null || s.conversion?.isConverted(wanted) == true || !s.active
                    }
                }
                else -> {
                    val c = choice ?: error("This part isn't converted yet. Download a voice to hear it.")
                    speak(b, c, expressive, i, 1f, clips, firstBreath = false)
                    i++
                }
            }
        }
    }

    /** Decodes a segment from sentence [from] on, cut into one run of samples per sentence. */
    private fun sentencesOf(file: File, segment: io.github.akrishna87.vaasi.convert.Segment, from: Int): List<Pair<Int, FloatArray>> {
        val out = ArrayList<Pair<Int, FloatArray>>()
        var sentence = from
        val pending = ArrayList<FloatArray>()
        fun flush() {
            val size = pending.sumOf { it.size }
            if (size > 0) {
                val joined = FloatArray(size)
                var at = 0
                for (p in pending) {
                    p.copyInto(joined, at)
                    at += p.size
                }
                out += sentence to joined
            }
            pending.clear()
        }
        AacReader.decode(file, segment.startOf(from)) { chunk, position ->
            var offset = 0
            while (offset < chunk.size) {
                val next = if (sentence < segment.last) segment.startOf(sentence + 1) else Long.MAX_VALUE
                val room = next - (position + offset)
                if (room <= 0) {
                    flush()
                    sentence++
                    continue
                }
                val take = if (room >= chunk.size - offset) chunk.size - offset else room.toInt()
                pending += chunk.copyOfRange(offset, offset + take)
                offset += take
            }
            true
        }
        flush()
        return out
    }

    /** Streams the clips to the speaker, keeping the shown sentence in step with the voice. */
    private suspend fun play(b: Book, clips: ReceiveChannel<Clip>, trackSpeed: Float) = coroutineScope {
        val marks = ArrayList<Pair<Long, Int>>() // (first frame, sentence), in order
        // Frames handed to the track so far, counted as each chunk goes in (not per clip), so
        // the track is only "waiting for the voice" once it has really played all of them.
        val written = AtomicLong(0)
        var audio: AudioTrack? = null
        var ticker: Job? = null
        try {
            for (clip in clips) {
                val t = audio ?: newTrack(clip.speech.sampleRate).also { t ->
                    audio = t
                    if (trackSpeed != 1f) {
                        try {
                            t.playbackParams = PlaybackParams().setSpeed(trackSpeed).setPitch(1f)
                        } catch (e: Exception) {
                            Log.w(TAG, "This phone can't change the playback speed", e)
                        }
                    }
                    synchronized(trackLock) {
                        track = t
                        if (!paused) t.play()
                    }
                    ticker = launch(Dispatchers.Main) { follow(b, t, marks) { written.get() } }
                }
                synchronized(marks) {
                    if (marks.isEmpty() || marks.last().second != clip.sentence) marks += written.get() to clip.sentence
                }
                writeAll(t, clip.speech.samples, written)
                if (clip.pauseMs > 0) writeAll(t, FloatArray(clip.speech.sampleRate * clip.pauseMs / 1000), written)
            }
            val t = audio ?: return@coroutineScope
            // Let the last words play out. A buffer of silence after them makes sure the track
            // starts even when the whole book was shorter than its start threshold.
            val end = written.get()
            writeAll(t, FloatArray(t.bufferSizeInFrames), AtomicLong())
            while (head(t) < end) delay(100)
        } finally {
            ticker?.cancel()
            releaseTrack()
        }
    }

    private suspend fun follow(b: Book, t: AudioTrack, marks: List<Pair<Long, Int>>, written: () -> Long) {
        var shown = -1
        var playingTicks = 0
        var starvedTicks = 0
        while (currentCoroutineContext().isActive) {
            val head = head(t)
            val sentence = synchronized(marks) { marks.lastOrNull { it.first <= head }?.second }
            if (sentence != null && sentence != shown) {
                shown = sentence
                Reader.publish { it.copy(sentence = sentence) }
                app.library.savePosition(b.id, sentence)
            }
            val starved = !paused && head >= written() - 1
            if (Reader.state.value.waiting != starved && Reader.state.value.playing) {
                Reader.publish { it.copy(waiting = starved) }
            }
            // After half a minute of listening, see whether the voice keeps up on this phone.
            if (!paused && head > 0) {
                playingTicks++
                if (starved) starvedTicks++
                if (playingTicks > 250 && starvedTicks * 4 > playingTicks && !Reader.state.value.slow) {
                    Log.i(TAG, "The voice can't keep up: waited $starvedTicks of $playingTicks ticks")
                    Reader.publish { it.copy(slow = true) }
                }
            }
            delay(120)
        }
    }

    private fun head(t: AudioTrack): Long = synchronized(trackLock) {
        if (t.state == AudioTrack.STATE_UNINITIALIZED) 0L else t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    }

    private fun newTrack(sampleRate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min * 4, sampleRate * 4 / 4)) // about a quarter second
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Writes without blocking, so pausing or skipping never leaves a thread stuck in the track. */
    private suspend fun writeAll(t: AudioTrack, samples: FloatArray, written: AtomicLong) {
        var offset = 0
        while (offset < samples.size) {
            currentCoroutineContext().ensureActive()
            val n = synchronized(trackLock) {
                if (track !== t) throw CancellationException("track replaced")
                t.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            if (n < 0) throw IOException("The speaker stopped working (error $n)")
            offset += n
            written.addAndGet(n.toLong())
            if (n == 0) delay(25)
        }
    }

    private fun releaseTrack() = synchronized(trackLock) {
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
    }

    // ---- Audio focus, headphones and wake lock ---------------------------------------------

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnFocusGain = Reader.state.value.playing
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                resume()
            }
        }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() // headphones unplugged
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (noisyRegistered) unregisterReceiver(noisy)
        noisyRegistered = false
    }

    private fun acquireWakeLock() {
        // Keeps the voice speaking ahead while the screen is off.
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vaasi:reading")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(6 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    // ---- Media session and notification -----------------------------------------------------

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = resume()
        override fun onPause() = pause()
        override fun onStop() = stopReading()
        override fun onSkipToNext() = seek(Reader.state.value.sentence + 1)
        override fun onSkipToPrevious() = seek(Reader.state.value.sentence - 1)
    }

    private fun updateSession(s: Reader.State) {
        val state = when {
            s.bookId == null -> PlaybackStateCompat.STATE_STOPPED
            s.playing && s.waiting -> PlaybackStateCompat.STATE_BUFFERING
            s.playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (s.playing) 1f else 0f)
                .build(),
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle(s))
                .build(),
        )
    }

    private fun subtitle(s: Reader.State): String {
        val b = book ?: return "Vaasi"
        if (s.total == 0) return "Vaasi"
        return "Page ${b.pageOf(s.sentence)} of ${b.pages} · ${s.sentence * 100 / s.total.coerceAtLeast(1)}%"
    }

    private fun notification(s: Reader.State): Notification {
        val playPause = if (s.playing) {
            NotificationCompat.Action(R.drawable.ic_pause, "Pause", button(PlaybackStateCompat.ACTION_PAUSE))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, "Play", button(PlaybackStateCompat.ACTION_PLAY))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vaasi)
            .setContentTitle(s.title.ifEmpty { "Vaasi" })
            .setContentText(
                when {
                    s.error != null -> s.error
                    s.playing && s.waiting -> "Getting the voice ready…"
                    else -> subtitle(s)
                },
            )
            .setContentIntent(openApp())
            .setDeleteIntent(button(PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(R.drawable.ic_previous, "Previous sentence", button(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(playPause)
            .addAction(R.drawable.ic_next, "Next sentence", button(PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .addAction(R.drawable.ic_close, "Stop", button(PlaybackStateCompat.ACTION_STOP))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun button(action: Long): PendingIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYING, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "VaasiReader"
        const val ACTION_PLAY = "io.github.akrishna87.vaasi.PLAY"
        const val EXTRA_BOOK = "book"
        const val EXTRA_SENTENCE = "sentence"
        private const val CHANNEL = "reading"
        private const val NOTIFICATION = 1

        @Volatile
        var instance: ReaderService? = null
            private set
    }
}
