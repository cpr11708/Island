package com.example.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.view.WindowManager

/**
 * The fallback way to show the island, as a normal overlay ("Display over other apps"). Android keeps the status bar
 * above this kind of overlay, so touches near the top of the screen can be swallowed in some apps. The accessibility
 * service ([IslandAccessibilityService]) does not have that problem.
 */
class IslandService : Service() {
    private var overlay: IslandOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        overlay = IslandOverlay(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.rebuild()
    }

    override fun onDestroy() {
        overlay?.stop()
        overlay = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Island", NotificationManager.IMPORTANCE_MIN),
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Island is running")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private companion object {
        const val CHANNEL = "island"
    }
}