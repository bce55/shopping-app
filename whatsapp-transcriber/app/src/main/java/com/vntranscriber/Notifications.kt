package com.vntranscriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val WATCHER_ID = 1
    private const val CH_WATCHER = "watcher"
    private const val CH_TRANSCRIPTS = "transcripts"

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCHER, "Watcher running", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TRANSCRIPTS, "Transcripts", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setSound(null, null) }
        )
    }

    fun watcher(c: Context): Notification =
        NotificationCompat.Builder(c, CH_WATCHER)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Watching for voice notes")
            .setContentIntent(openApp(c))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** Backup channel: readable on the lock screen and when the overlay is hidden. */
    fun transcript(c: Context, t: Transcript) = post(c, t.id.hashCode(), "🎙 ${t.metaLine()}", t.text)

    fun error(c: Context, fileName: String, message: String) =
        post(c, fileName.hashCode(), "Could not transcribe $fileName", message)

    private fun post(c: Context, id: Int, title: String, text: String) {
        if (!Permissions.hasNotifications(c)) return
        val n = NotificationCompat.Builder(c, CH_TRANSCRIPTS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(c))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(c).notify(id, n)
    }

    private fun openApp(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )
}
