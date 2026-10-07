package com.vntranscriber

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Draws a stack of transcript cards at the top of the screen.
 *
 * If the accessibility service is on, the stack is only visible while WhatsApp
 * is in front, and cards that arrived while you were elsewhere wait for you.
 * Without it, cards show immediately over whatever app is open.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var themed: Context
    private var stack: LinearLayout? = null
    private val scope = MainScope()

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Never steal focus or the keyboard from WhatsApp; touches outside the cards pass through.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        y = 200
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        themed = ContextThemeWrapper(this, R.style.Theme_VNT)
        scope.launch {
            ForegroundAppState.accessibilityConnected
                .combine(ForegroundAppState.whatsAppInForeground) { _, _ -> }
                .collect { updateVisibility() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getBundleExtra(EXTRA)?.let { addCard(Transcript.fromBundle(it)) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stack?.let { wm.removeView(it) }
        stack = null
        super.onDestroy()
    }

    private fun addCard(t: Transcript) {
        val container = stack ?: createStack()
        val card = LayoutInflater.from(themed).inflate(R.layout.overlay_card, container, false)
        card.findViewById<TextView>(R.id.meta).text = "🎙 ${t.metaLine()}"
        val text = card.findViewById<TextView>(R.id.text)
        text.text = t.text
        text.setOnClickListener { text.maxLines = if (text.maxLines == 6) 40 else 6 }
        card.findViewById<View>(R.id.copy).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("transcript", t.text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        card.findViewById<View>(R.id.close).setOnClickListener { removeCard(card) }
        enableDrag(card.findViewById(R.id.header))

        container.addView(card, 0) // newest on top
        while (container.childCount > MAX_CARDS) container.removeViewAt(container.childCount - 1)
        updateVisibility()
    }

    private fun removeCard(card: View) {
        val container = stack ?: return
        container.removeView(card)
        if (container.childCount == 0) stopSelf()
    }

    private fun createStack(): LinearLayout =
        LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }.also {
            wm.addView(it, params)
            stack = it
        }

    private fun updateVisibility() {
        val container = stack ?: return
        val gate = ForegroundAppState.accessibilityConnected.value && Prefs.onlyOverWhatsApp(this)
        val visible = !gate || ForegroundAppState.whatsAppInForeground.value
        container.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Drag the whole stack up/down by its card header. */
    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(handle: View) {
        var startY = 0
        var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = params.y; touchY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.y = (startY + (e.rawY - touchY)).toInt().coerceAtLeast(0)
                    stack?.let { wm.updateViewLayout(it, params) }
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val EXTRA = "transcript"
        private const val MAX_CARDS = 3

        fun show(c: Context, t: Transcript) {
            if (!Permissions.canDrawOverlays(c)) return // notification still carries the text
            c.startService(Intent(c, OverlayService::class.java).putExtra(EXTRA, t.toBundle()))
        }
    }
}
