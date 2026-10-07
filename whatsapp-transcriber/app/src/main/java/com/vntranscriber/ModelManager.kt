package com.vntranscriber

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Multilingual GGML models from the official whisper.cpp Hugging Face repo. Sizes are approximate. */
enum class WhisperModel(val fileName: String, val approxMb: Int, val label: String) {
    BASE_Q5_1("ggml-base-q5_1.bin", 60, "base q5_1 (60 MB, fast, weak Hebrew)"),
    SMALL_Q5_1("ggml-small-q5_1.bin", 190, "small q5_1 (190 MB, recommended for Hebrew)"),
    SMALL_Q8_0("ggml-small-q8_0.bin", 265, "small q8_0 (265 MB, a bit better, slower)");

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

/**
 * Gets the model onto the device. Three sources, tried in this order:
 *  1. Already in filesDir/models.
 *  2. Bundled in the APK under assets/models/ (fully offline install).
 *  3. Dropped into /sdcard/Download/ via adb or a file manager (fully offline install).
 *  4. Downloaded once over HTTPS (needs INTERNET, only for this step).
 */
class ModelManager(private val context: Context) {

    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(model: WhisperModel = Prefs.model(context)) = File(dir, model.fileName)

    fun isReady(model: WhisperModel = Prefs.model(context)): Boolean =
        modelFile(model).length() > model.approxMb * 1_000_000L / 2

    /** Tries the offline sources. Returns true when the model is ready. */
    suspend fun installOffline(onProgress: (Int) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val model = Prefs.model(context)
        if (isReady(model)) return@withContext true

        val assetPath = "models/${model.fileName}"
        if (context.assets.list("models")?.contains(model.fileName) == true) {
            context.assets.openFd(assetPath).use { fd ->
                writeModel(fd.createInputStream(), fd.length, model, onProgress)
            }
            return@withContext isReady(model)
        }

        val sideloaded = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            model.fileName,
        )
        if (sideloaded.canRead() && sideloaded.length() > 0) {
            writeModel(sideloaded.inputStream(), sideloaded.length(), model, onProgress)
            return@withContext isReady(model)
        }
        false
    }

    suspend fun download(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val model = Prefs.model(context)
        val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true // HF redirects to its CDN
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode} for ${model.url}" }
            writeModel(conn.inputStream, conn.contentLengthLong, model, onProgress)
        } finally {
            conn.disconnect()
        }
        check(isReady(model)) { "Downloaded file looks truncated" }
    }

    /** Write to .part first so a killed copy never leaves a half model that looks valid. */
    private suspend fun writeModel(input: InputStream, total: Long, model: WhisperModel, onProgress: (Int) -> Unit) {
        val part = File(dir, "${model.fileName}.part")
        input.use { src ->
            part.outputStream().use { dst ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var lastPct = -1
                while (true) {
                    coroutineContext.ensureActive()
                    val n = src.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
        }
        check(part.renameTo(modelFile(model))) { "Could not move model into place" }
    }
}
