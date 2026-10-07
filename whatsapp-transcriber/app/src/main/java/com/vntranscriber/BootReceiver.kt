package com.vntranscriber

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the watcher after reboot or app update, if the user had it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (Prefs.watcherEnabled(context) && Permissions.hasStorage(context) && context.app.modelManager.isReady()) {
            WhatsAppFileWatcher.start(context)
        }
    }
}
