package com.vntranscriber

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** One-screen setup checklist plus a manual "transcribe newest" test button. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnModel: Button
    private lateinit var btnToggle: Button

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        output = findViewById(R.id.output)
        progress = findViewById(R.id.progress)
        btnModel = findViewById(R.id.btnModel)
        btnToggle = findViewById(R.id.btnToggle)

        findViewById<Button>(R.id.btnStorage).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(Permissions.storageSettingsIntent(this))
            } else {
                askPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Permissions.overlayIntent(this))
        }
        findViewById<Button>(R.id.btnNotifications).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            startActivity(Permissions.batteryIntent(this))
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Permissions.accessibilityIntent())
        }

        setupModelPicker()
        setupLanguagePicker()
        btnModel.setOnClickListener { getModel() }
        btnToggle.setOnClickListener {
            if (Prefs.watcherEnabled(this)) WhatsAppFileWatcher.stop(this) else WhatsAppFileWatcher.start(this)
            refresh()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { transcribeNewest() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val storage = Permissions.hasStorage(this)
        val modelReady = app.modelManager.isReady()
        val dirs = if (storage) VoiceNoteDirs.roots() else emptyList()
        status.text = buildString {
            appendLine("${mark(storage)} All-files access")
            appendLine("${mark(Permissions.canDrawOverlays(this@MainActivity))} Overlay")
            appendLine("${mark(Permissions.hasNotifications(this@MainActivity))} Notifications")
            appendLine("${mark(Permissions.ignoresBatteryOptimizations(this@MainActivity))} Battery unrestricted")
            appendLine("${mark(Permissions.accessibilityEnabled(this@MainActivity))} Accessibility (optional)")
            appendLine("${mark(modelReady)} Model: ${Prefs.model(this@MainActivity).fileName}")
            appendLine("${mark(dirs.isNotEmpty())} WhatsApp voice notes folder found")
        }
        btnToggle.isEnabled = storage && modelReady
        btnToggle.text = if (Prefs.watcherEnabled(this)) "Stop watcher" else "Start watcher"
    }

    private fun setupModelPicker() {
        val group = findViewById<RadioGroup>(R.id.modelGroup)
        WhisperModel.entries.forEach { m ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = m.label
                isChecked = m == Prefs.model(this@MainActivity)
                setOnClickListener { Prefs.setModel(this@MainActivity, m); refresh() }
            })
        }
    }

    private fun setupLanguagePicker() {
        val group = findViewById<RadioGroup>(R.id.langGroup)
        listOf("auto" to "Auto", "he" to "עברית", "en" to "English").forEach { (code, label) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                isChecked = code == Prefs.language(this@MainActivity)
                setOnClickListener { Prefs.setLanguage(this@MainActivity, code) }
            })
        }
    }

    private fun getModel() {
        btnModel.isEnabled = false
        progress.visibility = View.VISIBLE
        val onProgress: (Int) -> Unit = { pct -> runOnUiThread { progress.progress = pct } }
        lifecycleScope.launch {
            try {
                val mm = app.modelManager
                if (!mm.installOffline(onProgress)) {
                    output.text = "Downloading ${Prefs.model(this@MainActivity).fileName}…"
                    mm.download(onProgress)
                }
                output.text = "Model ready."
            } catch (e: Exception) {
                output.text = "Model setup failed: ${e.message}\n\n" +
                    "Offline option: copy the .bin file into the phone's Download folder and tap again."
            } finally {
                btnModel.isEnabled = true
                progress.visibility = View.GONE
                refresh()
            }
        }
    }

    private fun transcribeNewest() {
        val file = VoiceNoteDirs.newest()
        if (file == null) {
            output.text = "No voice note found. Check all-files access, and that WhatsApp has received one."
            return
        }
        output.text = "Transcribing ${file.name}…"
        lifecycleScope.launch {
            output.text = try {
                val t = app.engine.transcribe(file, Prefs.language(this@MainActivity))
                OverlayService.show(this@MainActivity, t)
                "${t.metaLine()}\n\n${t.text}"
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
        }
    }
}
