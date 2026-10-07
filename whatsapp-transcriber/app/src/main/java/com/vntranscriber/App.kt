package com.vntranscriber

import android.app.Application

class App : Application() {
    val modelManager by lazy { ModelManager(this) }
    val engine by lazy { TranscriptionEngine(modelManager) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

val android.content.Context.app: App get() = applicationContext as App
