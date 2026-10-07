package com.vntranscriber

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object Permissions {

    fun hasStorage(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            granted(c, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** Android 11+: system "All files access" screen. Android 10 uses a runtime prompt instead. */
    fun storageSettingsIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(c))

    fun canDrawOverlays(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun overlayIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(c))

    fun hasNotifications(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(c, Manifest.permission.POST_NOTIFICATIONS)

    fun ignoresBatteryOptimizations(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    @android.annotation.SuppressLint("BatteryLife") // sideload-only app, this is the point
    fun batteryIntent(c: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(c))

    fun accessibilityEnabled(c: Context): Boolean {
        val me = ComponentName(c, WhatsAppAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun accessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private fun granted(c: Context, permission: String) =
        ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED

    private fun packageUri(c: Context) = Uri.parse("package:${c.packageName}")
}
