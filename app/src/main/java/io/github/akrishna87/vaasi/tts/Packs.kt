package io.github.akrishna87.vaasi.tts

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PackState {
    data object NotInstalled : PackState
    data class Downloading(val done: Long, val total: Long) : PackState {
        val fraction: Float? get() = if (total > 0) done.toFloat() / total else null
    }
    data class Installing(val fraction: Float) : PackState
    data object Installed : PackState
    data class Failed(val message: String) : PackState
}

/**
 * Downloads voice packs with Android's download manager (so a download carries on with the
 * app closed) and unpacks them into the app's own storage.
 */
class Packs(private val context: Context, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("packs", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(DownloadManager::class.java)

    private val _states = MutableStateFlow(VoicePacks.all.associate { it.id to initialState(it) })
    val states: StateFlow<Map<String, PackState>> = _states

    private var watcher: Job? = null

    init {
        if (VoicePacks.all.any { downloadId(it) != null }) watch()
    }

    fun dir(pack: VoicePack) = File(context.filesDir, "voices/${pack.id}")

    fun isInstalled(pack: VoicePack) = File(dir(pack), READY).exists()

    fun installed(): List<VoicePack> = VoicePacks.all.filter(::isInstalled)

    private fun archiveFile(pack: VoicePack) = File(context.getExternalFilesDir("downloads"), "${pack.id}.tar.bz2")

    private fun downloadId(pack: VoicePack): Long? = prefs.getLong("download_${pack.id}", -1).takeIf { it >= 0 }

    private fun initialState(pack: VoicePack): PackState =
        if (isInstalled(pack)) PackState.Installed else PackState.NotInstalled

    private fun set(pack: VoicePack, state: PackState) = _states.update { it + (pack.id to state) }

    fun download(pack: VoicePack) {
        if (downloadId(pack) != null || isInstalled(pack)) return
        archiveFile(pack).delete()
        val request = DownloadManager.Request(Uri.parse(pack.url))
            .setTitle("Vaasi ${pack.title} voices")
            .setDescription("${pack.sizeMb} MB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "downloads", "${pack.id}.tar.bz2")
        val id = downloads.enqueue(request)
        prefs.edit { putLong("download_${pack.id}", id) }
        set(pack, PackState.Downloading(0, pack.sizeMb * 1_000_000L))
        watch()
    }

    fun cancel(pack: VoicePack) {
        downloadId(pack)?.let { downloads.remove(it) }
        prefs.edit { remove("download_${pack.id}") }
        archiveFile(pack).delete()
        set(pack, PackState.NotInstalled)
    }

    fun delete(pack: VoicePack) {
        Tts.unload(pack)
        dir(pack).deleteRecursively()
        set(pack, PackState.NotInstalled)
    }

    /** Polls the download manager while any pack is downloading, then unpacks it. */
    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            while (isActive) {
                val pending = VoicePacks.all.filter { downloadId(it) != null }
                if (pending.isEmpty()) break
                for (pack in pending) poll(pack)
                delay(700)
            }
        }
    }

    private suspend fun poll(pack: VoicePack) {
        val id = downloadId(pack) ?: return
        val query = DownloadManager.Query().setFilterById(id)
        val row = downloads.query(query)?.use { c ->
            if (!c.moveToFirst()) null else Triple(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
        if (row == null) {
            // The download was cancelled from the notification or cleared by the system.
            prefs.edit { remove("download_${pack.id}") }
            set(pack, PackState.Failed("The download was cancelled"))
            return
        }
        val (status, done, total) = row
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                prefs.edit { remove("download_${pack.id}") }
                install(pack)
            }
            DownloadManager.STATUS_FAILED -> {
                prefs.edit { remove("download_${pack.id}") }
                downloads.remove(id)
                set(pack, PackState.Failed("The download failed. Check your connection and try again."))
            }
            else -> set(pack, PackState.Downloading(done, if (total > 0) total else pack.sizeMb * 1_000_000L))
        }
    }

    private suspend fun install(pack: VoicePack) {
        set(pack, PackState.Installing(0f))
        val archive = archiveFile(pack)
        try {
            withContext(Dispatchers.IO) {
                Archive.extractTarBz2(archive, dir(pack)) { set(pack, PackState.Installing(it)) }
                if (VoicePacks.modelFile(dir(pack)) == null) error("The voice pack has no model in it")
                File(dir(pack), READY).writeText(pack.url)
            }
            set(pack, PackState.Installed)
        } catch (e: Exception) {
            dir(pack).deleteRecursively()
            set(pack, PackState.Failed("Couldn't unpack the voices: ${e.message}"))
        } finally {
            archive.delete()
        }
    }

    companion object {
        private const val READY = ".ready"
    }
}
