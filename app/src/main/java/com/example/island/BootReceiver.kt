package com.example.island

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * After a restart (or an app update) Android brings back the accessibility service and the notification listener by
 * itself, as long as they are turned on. This only covers the basic overlay: if the accessibility service is off but
 * "display over other apps" is allowed, it starts the overlay service again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(context, IslandAccessibilityService::class.java).flattenToString()
        val a11yOn = enabled.split(':').any { it.equals(me, ignoreCase = true) }
        if (!a11yOn && Settings.canDrawOverlays(context)) {
            runCatching { context.startForegroundService(Intent(context, IslandService::class.java)) }
        }
    }
}