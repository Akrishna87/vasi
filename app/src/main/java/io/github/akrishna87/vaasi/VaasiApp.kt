package io.github.akrishna87.vaasi

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import io.github.akrishna87.vaasi.tts.Packs
import io.github.akrishna87.vaasi.tts.Voice
import io.github.akrishna87.vaasi.tts.VoicePack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the listener has chosen: which voices, and how fast they read. */
data class Settings(
    val packId: String? = null,
    val voiceId: String? = null,
    /** Second voice for quoted speech, or null to read everything in one voice. */
    val dialogueVoiceId: String? = null,
    val speed: Float = 1.0f,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        Settings(
            packId = prefs.getString("pack", null),
            voiceId = prefs.getString("voice", null),
            dialogueVoiceId = prefs.getString("dialogueVoice", null),
            speed = prefs.getFloat("speed", 1.0f),
        ),
    )
    val settings: StateFlow<Settings> = _settings

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        _settings.value = s
        prefs.edit {
            putString("pack", s.packId)
            putString("voice", s.voiceId)
            putString("dialogueVoice", s.dialogueVoiceId)
            putFloat("speed", s.speed)
        }
    }
}

/** The voices to read with, worked out from the settings and the packs on the phone. */
data class VoiceChoice(val pack: VoicePack, val narrator: Voice, val dialogue: Voice?)

class VaasiApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var library: Library
        private set
    lateinit var packs: Packs
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        library = Library(this)
        packs = Packs(this, scope)
        settings = SettingsStore(this)
    }

    /** The voices to use now, or null if no voice pack is installed yet. */
    fun voiceChoice(s: Settings = settings.settings.value): VoiceChoice? {
        val installed = packs.installed()
        val pack = installed.firstOrNull { it.id == s.packId } ?: installed.lastOrNull() ?: return null
        val narrator = pack.voice(s.voiceId)
        val dialogue = s.dialogueVoiceId?.let { id -> pack.voices.firstOrNull { it.id == id } }
        return VoiceChoice(pack, narrator, dialogue)
    }

    companion object {
        lateinit var instance: VaasiApp
            private set
    }
}

val Context.app: VaasiApp get() = applicationContext as VaasiApp
