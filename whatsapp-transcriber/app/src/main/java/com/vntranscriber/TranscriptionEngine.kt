package com.vntranscriber

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns one whisper.cpp context. The model stays loaded between notes
 * (loading small-q5_1 takes ~1-2 s; you do not want that per message).
 *
 * Threading: file decode runs on Dispatchers.IO (blocking I/O).
 * Inference runs on Dispatchers.Default: it is pure CPU work, and whisper.cpp
 * spawns its own worker threads anyway. A Mutex serializes access because a
 * whisper context is not thread-safe.
 */
class TranscriptionEngine(private val modelManager: ModelManager) {

    private val mutex = Mutex()
    private var ctx = 0L
    private var loadedModel: File? = null

    // Phones have 2-4 big cores. Going wider puts work on little cores and gets slower.
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    class ModelMissingException : IllegalStateException("No model installed. Open the app and get a model.")

    suspend fun transcribe(file: File, language: String): Transcript {
        val pcm = withContext(Dispatchers.IO) { AudioDecoder.decodeToWhisperPcm(file) }
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                val c = ensureLoaded()
                val started = SystemClock.elapsedRealtime()
                val bytes = WhisperLib.fullTranscribe(c, pcm, language, threads)
                    ?: error("whisper_full failed for ${file.name}")
                val elapsed = SystemClock.elapsedRealtime() - started
                Transcript(
                    id = file.name,
                    text = bytes.toString(Charsets.UTF_8).trim(),
                    language = WhisperLib.detectedLanguage(c),
                    audioSeconds = pcm.size / AudioDecoder.WHISPER_RATE.toFloat(),
                    processingMs = elapsed,
                ).also { Log.i(TAG, "${file.name}: ${it.metaLine()}") }
            }
        }
    }

    suspend fun release() = mutex.withLock { freeLocked() }

    private fun ensureLoaded(): Long {
        val model = modelManager.modelFile()
        if (ctx != 0L && model == loadedModel) return ctx
        freeLocked() // model was switched in settings
        if (!modelManager.isReady()) throw ModelMissingException()
        ctx = WhisperLib.initContext(model.absolutePath)
        check(ctx != 0L) { "whisper failed to load ${model.name} (corrupt download?)" }
        loadedModel = model
        Log.i(TAG, "Loaded ${model.name}. ${WhisperLib.systemInfo()}")
        return ctx
    }

    private fun freeLocked() {
        if (ctx != 0L) WhisperLib.freeContext(ctx)
        ctx = 0L
        loadedModel = null
    }

    private companion object { const val TAG = "TranscriptionEngine" }
}
