package com.vntranscriber

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Tracks one bit: is WhatsApp the app in front? Reads no screen content.
 *
 * Window-state events also fire for the keyboard, the notification shade and
 * our own overlay. Those must not count as "left WhatsApp", so they are ignored.
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    private val ignored by lazy {
        val imes = getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.map { it.packageName }
        setOf(packageName, "com.android.systemui") + imes
    }

    override fun onServiceConnected() {
        ForegroundAppState.accessibilityConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignored) return
        ForegroundAppState.whatsAppInForeground.value = pkg in WHATSAPP_PACKAGES
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ForegroundAppState.accessibilityConnected.value = false
        ForegroundAppState.whatsAppInForeground.value = false
        super.onDestroy()
    }

    private companion object {
        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
