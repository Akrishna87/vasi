package io.github.akrishna87.vaasi.play

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What is being read aloud, for the screens to show. The [ReaderService] does the reading. */
object Reader {
    data class State(
        val bookId: String? = null,
        val title: String = "",
        val sentence: Int = 0,
        val total: Int = 0,
        /** The listener wants it playing (it may still be [waiting] for the voice). */
        val playing: Boolean = false,
        /** Playing, but the voice hasn't caught up yet (loading the model or a slow phone). */
        val waiting: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    internal fun publish(change: (State) -> State) = _state.update(change)

    /** Starts reading [bookId], from [sentence] or else from where the listener left off. */
    fun play(context: Context, bookId: String, sentence: Int = -1) {
        val intent = Intent(context, ReaderService::class.java)
            .setAction(ReaderService.ACTION_PLAY)
            .putExtra(ReaderService.EXTRA_BOOK, bookId)
            .putExtra(ReaderService.EXTRA_SENTENCE, sentence)
        ContextCompat.startForegroundService(context, intent)
    }

    fun toggle(context: Context) {
        val service = ReaderService.instance
        val s = state.value
        when {
            service != null -> service.toggle()
            s.bookId != null -> play(context, s.bookId, s.sentence)
        }
    }

    fun pause() {
        ReaderService.instance?.pause()
    }

    fun seek(context: Context, bookId: String, sentence: Int) {
        val service = ReaderService.instance
        if (service != null && state.value.bookId == bookId) service.seek(sentence) else play(context, bookId, sentence)
    }

    fun skip(context: Context, by: Int) {
        val s = state.value
        val id = s.bookId ?: return
        seek(context, id, (s.sentence + by).coerceIn(0, (s.total - 1).coerceAtLeast(0)))
    }

    /** The voice or speed changed: carry on from the same sentence with the new settings. */
    fun settingsChanged() {
        ReaderService.instance?.settingsChanged()
    }

    fun stop() {
        ReaderService.instance?.stopReading()
    }
}
