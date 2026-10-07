package com.vntranscriber

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that finds new WhatsApp voice notes and transcribes them.
 *
 * Detection uses two paths that both just request a scan:
 *  - FileObserver (inotify) on the voice-notes root + newest week folders. Low latency.
 *  - A 15 s poll. Safety net, because inotify on /sdcard (a FUSE mount) is not
 *    guaranteed to see writes from other apps on every Android/One UI build.
 * The scan itself is idempotent, so double triggers cost nothing.
 *
 * Why not WorkManager: periodic work has a 15 minute floor. Why not ContentObserver
 * on MediaStore: WhatsApp's .nomedia files keep voice notes out of MediaStore.
 */
class WhatsAppFileWatcher : LifecycleService() {

    private val state by lazy { getSharedPreferences("watcher_state", MODE_PRIVATE) }
    // Conflated: a burst of inotify events collapses into one pending scan.
    private val scanRequests = Channel<Unit>(Channel.CONFLATED)
    private val queue = Channel<File>(Channel.UNLIMITED)
    private val inFlight = mutableSetOf<String>() // guarded by synchronized(inFlight)

    private var observer: FileObserver? = null
    private var watchedDirs: List<File> = emptyList()

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, Notifications.WATCHER_ID, Notifications.watcher(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )

        // First run: do not transcribe years of old voice notes. Only new ones.
        if (!state.contains(KEY_CUTOFF)) state.edit { putLong(KEY_CUTOFF, System.currentTimeMillis()) }

        lifecycleScope.launch(Dispatchers.IO) { for (r in scanRequests) scan() }
        lifecycleScope.launch {
            while (isActive) {
                scanRequests.trySend(Unit)
                delay(POLL_MS)
            }
        }
        lifecycleScope.launch { for (file in queue) process(file) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.stopWatching()
        // Free ~200-300 MB of model RAM. Not on the main thread: release() waits
        // for any transcription still running in native code.
        val engine = app.engine
        CoroutineScope(Dispatchers.Default).launch { engine.release() }
        super.onDestroy()
    }

    private fun scan() {
        refreshObserver()
        val cutoff = state.getLong(KEY_CUTOFF, 0)
        val processed = processedNames()
        watchedDirs.asSequence()
            .flatMap { it.listFiles().orEmpty().asSequence() }
            .filter { VoiceNoteDirs.isVoiceNote(it) && it.lastModified() > cutoff && it.name !in processed }
            .sortedBy { it.lastModified() }
            .filter { synchronized(inFlight) { inFlight.add(it.absolutePath) } }
            .forEach { queue.trySend(it) }
    }

    /** Re-arms inotify when a new week folder shows up (or WhatsApp is installed later). */
    private fun refreshObserver() {
        val dirs = VoiceNoteDirs.activeDirs()
        if (dirs == watchedDirs) return
        observer?.stopWatching()
        watchedDirs = dirs
        observer = if (dirs.isEmpty()) null else object : FileObserver(
            dirs, CREATE or CLOSE_WRITE or MOVED_TO,
        ) {
            override fun onEvent(event: Int, path: String?) { scanRequests.trySend(Unit) }
        }.also { it.startWatching() }
        Log.i(TAG, "Watching ${dirs.map { it.name }}")
    }

    private suspend fun process(file: File) {
        try {
            if (!awaitComplete(file)) return
            val transcript = app.engine.transcribe(file, Prefs.language(this))
            if (transcript.text.isBlank()) return
            Notifications.transcript(this, transcript)
            OverlayService.show(this, transcript)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed on ${file.name}", e)
            Notifications.error(this, file.name, e.message ?: e.javaClass.simpleName)
        } finally {
            markProcessed(file.name) // also on failure: never retry-loop a broken file
            synchronized(inFlight) { inFlight.remove(file.absolutePath) }
        }
    }

    /**
     * WhatsApp may still be writing when we first see the file. Wait until the
     * size stops changing. Returns false if the file vanished (deleted / renamed).
     */
    private suspend fun awaitComplete(file: File): Boolean = withContext(Dispatchers.IO) {
        var last = -1L
        repeat(40) {
            if (!file.exists()) return@withContext false
            val size = file.length()
            if (size > 0 && size == last) return@withContext true
            last = size
            delay(500)
        }
        file.exists() && file.length() > 0
    }

    private fun processedNames(): Set<String> =
        state.getString(KEY_PROCESSED, "")!!.split('\n').filterTo(HashSet()) { it.isNotEmpty() }

    @Synchronized
    private fun markProcessed(name: String) {
        val names = state.getString(KEY_PROCESSED, "")!!.split('\n').filter { it.isNotEmpty() }
        val trimmed = (names + name).takeLast(MAX_REMEMBERED)
        state.edit { putString(KEY_PROCESSED, trimmed.joinToString("\n")) }
    }

    companion object {
        private const val TAG = "WhatsAppFileWatcher"
        private const val POLL_MS = 15_000L
        private const val KEY_CUTOFF = "cutoff_ms"
        private const val KEY_PROCESSED = "processed"
        private const val MAX_REMEMBERED = 500

        fun start(c: Context) {
            Prefs.setWatcherEnabled(c, true)
            ContextCompat.startForegroundService(c, Intent(c, WhatsAppFileWatcher::class.java))
        }

        fun stop(c: Context) {
            Prefs.setWatcherEnabled(c, false)
            c.stopService(Intent(c, WhatsAppFileWatcher::class.java))
        }
    }
}
