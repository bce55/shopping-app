package com.vntranscriber

/** Thin JNI bridge to whisper.cpp. Not thread-safe: callers must serialize per context. */
object WhisperLib {
    init {
        System.loadLibrary("whisper_jni")
    }

    /** Returns a native pointer, or 0 on failure. */
    external fun initContext(modelPath: String): Long
    external fun freeContext(ctx: Long)

    /** [samples] must be 16 kHz mono float PCM in [-1, 1]. Returns UTF-8 bytes, or null on failure. */
    external fun fullTranscribe(ctx: Long, samples: FloatArray, language: String, threads: Int): ByteArray?
    external fun detectedLanguage(ctx: Long): String
    external fun systemInfo(): String
}
