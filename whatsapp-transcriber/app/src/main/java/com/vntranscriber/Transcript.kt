package com.vntranscriber

import android.os.Bundle

data class Transcript(
    val id: String,          // voice note file name, e.g. PTT-20261007-WA0012.opus
    val text: String,
    val language: String,
    val audioSeconds: Float,
    val processingMs: Long,
) {
    fun metaLine(): String {
        val s = audioSeconds.toInt()
        return "%d:%02d · %s · %.1fs".format(s / 60, s % 60, language, processingMs / 1000f)
    }

    fun toBundle() = Bundle().apply {
        putString("id", id); putString("text", text); putString("lang", language)
        putFloat("secs", audioSeconds); putLong("ms", processingMs)
    }

    companion object {
        fun fromBundle(b: Bundle) = Transcript(
            b.getString("id")!!, b.getString("text")!!, b.getString("lang")!!,
            b.getFloat("secs"), b.getLong("ms"),
        )
    }
}
