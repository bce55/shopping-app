package com.vntranscriber

import kotlinx.coroutines.flow.MutableStateFlow

/** Written by the accessibility service, read by the overlay. Same process, so a plain singleton works. */
object ForegroundAppState {
    val accessibilityConnected = MutableStateFlow(false)
    val whatsAppInForeground = MutableStateFlow(false)
}
