package com.vntranscriber

import android.content.Context
import androidx.core.content.edit

object Prefs {
    private const val NAME = "settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_MODEL = "model"
    private const val KEY_ENABLED = "watcher_enabled"
    private const val KEY_ONLY_OVER_WHATSAPP = "only_over_whatsapp"

    private fun prefs(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** "auto", "he" or "en". Passed straight to whisper. */
    fun language(c: Context): String = prefs(c).getString(KEY_LANGUAGE, "auto")!!
    fun setLanguage(c: Context, value: String) = prefs(c).edit { putString(KEY_LANGUAGE, value) }

    fun model(c: Context): WhisperModel =
        runCatching { WhisperModel.valueOf(prefs(c).getString(KEY_MODEL, null)!!) }
            .getOrDefault(WhisperModel.SMALL_Q5_1)
    fun setModel(c: Context, value: WhisperModel) = prefs(c).edit { putString(KEY_MODEL, value.name) }

    fun watcherEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setWatcherEnabled(c: Context, value: Boolean) = prefs(c).edit { putBoolean(KEY_ENABLED, value) }

    /** Only matters when the accessibility service is on. */
    fun onlyOverWhatsApp(c: Context): Boolean = prefs(c).getBoolean(KEY_ONLY_OVER_WHATSAPP, true)
}
