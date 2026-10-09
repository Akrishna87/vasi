package io.github.akrishna87.vaasi.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.akrishna87.vaasi.Settings
import io.github.akrishna87.vaasi.VaasiApp
import io.github.akrishna87.vaasi.VoiceChoice
import io.github.akrishna87.vaasi.play.Preview
import io.github.akrishna87.vaasi.play.Reader
import io.github.akrishna87.vaasi.text.Book
import io.github.akrishna87.vaasi.tts.Tts
import io.github.akrishna87.vaasi.tts.Voice
import io.github.akrishna87.vaasi.tts.VoicePack
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ImportState {
    data class Reading(val name: String, val page: Int, val pages: Int) : ImportState
    data class Failed(val message: String) : ImportState
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VaasiApp

    val books = app.library.books
    val packStates = app.packs.states
    val settings = app.settings.settings
    val modelLoading: StateFlow<Boolean> = Tts.loading
    val previewing: StateFlow<String?> = Preview.playing

    /** The voices in use, or null until a voice pack is installed. */
    val choice: StateFlow<VoiceChoice?> = combine(settings, packStates) { s, _ -> app.voiceChoice(s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.voiceChoice())

    private val _importing = MutableStateFlow<ImportState?>(null)
    val importing: StateFlow<ImportState?> = _importing

    private val _book = MutableStateFlow<Book?>(null)
    /** The book open on screen (not necessarily the one being read). */
    val book: StateFlow<Book?> = _book

    private var previewJob: Job? = null

    fun import(uri: Uri, opened: (String) -> Unit) {
        if (_importing.value is ImportState.Reading) return
        _importing.value = ImportState.Reading("PDF", 0, 0)
        viewModelScope.launch {
            try {
                val entry = app.library.import(uri) { page, pages ->
                    _importing.value = ImportState.Reading("PDF", page, pages)
                }
                _importing.value = null
                opened(entry.id)
            } catch (e: Exception) {
                _importing.value = ImportState.Failed(e.message ?: "Couldn't read that PDF")
            } catch (e: OutOfMemoryError) {
                _importing.value = ImportState.Failed("That PDF is too big for this phone's memory")
            }
        }
    }

    fun dismissImportError() {
        if (_importing.value is ImportState.Failed) _importing.value = null
    }

    fun open(id: String) {
        if (_book.value?.id == id) return
        _book.value = null
        viewModelScope.launch {
            _book.value = try {
                app.library.load(id)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun deleteBook(id: String) {
        if (Reader.state.value.bookId == id) Reader.stop()
        if (_book.value?.id == id) _book.value = null
        app.library.delete(id)
    }

    fun download(pack: VoicePack) = app.packs.download(pack)
    fun cancelDownload(pack: VoicePack) = app.packs.cancel(pack)

    fun deletePack(pack: VoicePack) {
        if (choice.value?.pack == pack) Reader.stop()
        app.packs.delete(pack)
    }

    fun usePack(pack: VoicePack) = changeSettings { it.copy(packId = pack.id) }

    fun chooseVoice(voice: Voice) = changeSettings { it.copy(voiceId = voice.id) }

    fun chooseDialogueVoice(voice: Voice?) = changeSettings { it.copy(dialogueVoiceId = voice?.id) }

    fun setSpeed(speed: Float) = changeSettings { it.copy(speed = speed) }

    private fun changeSettings(change: (Settings) -> Settings) {
        val before = app.settings.settings.value
        app.settings.update(change)
        if (app.settings.settings.value != before) Reader.settingsChanged()
    }

    fun preview(voice: Voice) {
        val pack = choice.value?.pack ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            try {
                Preview.play(getApplication(), pack, voice, settings.value.speed)
            } catch (e: Exception) {
                // A failed preview just doesn't play.
            }
        }
    }
}
