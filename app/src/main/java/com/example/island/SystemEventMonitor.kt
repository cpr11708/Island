package com.example.island

import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

/**
 * Short pop-ups, like the iPhone's: silent mode, Do Not Disturb, airplane mode, a Bluetooth device connecting.
 * Each shows for a moment and never interrupts a call or an open island.
 */
class SystemEventMonitor(private val context: Context) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // Some of these are "sticky": registering gives the current value at once, which is not a change.
            if (isInitialStickyBroadcast) return
            when (intent.action) {
                AudioManager.RINGER_MODE_CHANGED_ACTION -> when (intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
                    AudioManager.RINGER_MODE_SILENT -> IslandBus.alert("🔕", "Silent", Orange)
                    AudioManager.RINGER_MODE_VIBRATE -> IslandBus.alert("📳", "Vibrate", Orange)
                    AudioManager.RINGER_MODE_NORMAL -> IslandBus.alert("🔔", "Ring", Color.White)
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    val nm = c.getSystemService(NotificationManager::class.java)
                    if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                        IslandBus.alert("🌙", "Focus off", Gray)
                    } else {
                        IslandBus.alert("🌙", "Do Not Disturb", Purple)
                    }
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val on = intent.getBooleanExtra("state", false)
                    IslandBus.alert("✈️", if (on) "Airplane" else "Airplane off", if (on) Orange else Gray)
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    // The name needs the Bluetooth permission. Without it, just say a device connected.
                    val name = runCatching { device?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Connected"
                    IslandBus.alert("🎧", name.take(16), MiniGreenColor)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> IslandBus.alert("🎧", "Disconnected", Gray)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private companion object {
        val Orange = Color(0xFFFF9F0A)
        val Gray = Color(0xFF8E8E93)
        val Purple = Color(0xFF7D7AFF)
        val MiniGreenColor = Color(0xFF30D158)
    }
}