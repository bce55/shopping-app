package com.vntranscriber

import android.os.Environment
import java.io.File

/**
 * WhatsApp layout (Android 11+, after WhatsApp moved under Android/media):
 *   /sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202641/PTT-20261007-WA0012.opus
 * The numbered subfolders are year + week number. A new one appears every week.
 */
object VoiceNoteDirs {
    val AUDIO_EXTENSIONS = setOf("opus", "m4a", "ogg", "aac")

    fun roots(): List<File> {
        val sd = Environment.getExternalStorageDirectory()
        return listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes",
            "WhatsApp/Media/WhatsApp Voice Notes", // pre-2021 installs / Android 10
        ).map { File(sd, it) }.filter { it.isDirectory }
    }

    /** Root plus its newest week folders. Watching all ~hundreds of week folders is pointless. */
    fun activeDirs(weeks: Int = 2): List<File> = roots().flatMap { root ->
        val subdirs = root.listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.name }
        listOf(root) + subdirs.take(weeks)
    }

    fun isVoiceNote(f: File) = f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS

    fun newest(): File? = activeDirs().asSequence()
        .flatMap { it.listFiles().orEmpty().asSequence() }
        .filter(::isVoiceNote)
        .maxByOrNull { it.lastModified() }
}
