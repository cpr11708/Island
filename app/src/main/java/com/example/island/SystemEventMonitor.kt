package com.example.island

import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(clearWake)
                    IslandBus.wakeSnap = true
                }
                Intent.ACTION_SCREEN_ON -> {
                    handler.removeCallbacks(clearWake)
                    handler.postDelayed(clearWake, 800)
                }
                "android.net.wifi.WIFI_AP_STATE_CHANGED" ->
                    IslandBus.hotspotAp = intent.getIntExtra("wifi_state", 11) == 13 // 13 = hotspot enabled
                "android.net.conn.TETHER_STATE_CHANGED" ->
                    IslandBus.hotspotAp = intent.getStringArrayListExtra("tetherArray")?.isNotEmpty() == true
                "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                    // Earbuds report their battery a little after connecting (or when it changes).
                    val level = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                    if (level in 0..100) IslandBus.alertAddBattery(btAlertKey, level)
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    // The name needs the Bluetooth permission. Without it, just say a device connected.
                    val name = runCatching { device?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Connected"
                    btAlertKey = -1
                    if (IslandBus.alert("🎧", name.take(16), MiniGreenColor)) {
                        btAlertKey = IslandBus.alertKey
                        // The battery level (earbuds, headphones) is not always ready at once: look again a moment later.
                        val key = btAlertKey
                        handler.postDelayed({
                            val level = btLevel(device)
                            if (level in 0..100) IslandBus.alertAddBattery(key, level)
                        }, 1400)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> IslandBus.alert("🎧", "Disconnected", Gray)
            }
        }
    }

    private val clearWake = Runnable { IslandBus.wakeSnap = false }

    /** The key of the Bluetooth pop-up that is waiting for a battery level. */
    private var btAlertKey = -1

    /** The battery of a connected Bluetooth device. Android hides this call from apps, so it may not exist on every phone. */
    private fun btLevel(d: BluetoothDevice?): Int =
        runCatching { BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as Int }.getOrDefault(-1)

    // Camera: Android tells every app when a camera becomes busy or free. Microphone: it reports active recordings.
    private val handler = Handler(Looper.getMainLooper())
    private val busyCameras = mutableSetOf<String>()
    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            busyCameras += cameraId
            updateCam()
        }
        override fun onCameraAvailable(cameraId: String) {
            busyCameras -= cameraId
            updateCam()
        }
    }

    // The flashlight can make a camera look busy: that is not a photo being taken, so no green dot for it.
    private var torchOn = false
    private var torchIgnoreUntil = 0L
    private fun updateCam() {
        IslandBus.camActive = busyCameras.isNotEmpty() && !torchOn
    }
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            val was = torchOn
            torchOn = enabled
            updateCam()
            // Android reports the current state when we start listening: that is not a change.
            if (enabled && !was && SystemClock.elapsedRealtime() > torchIgnoreUntil) {
                IslandBus.alert("🔦", "Flashlight", Yellow)
            }
        }
    }
    private val micCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            IslandBus.micActive = configs.any { !it.isClientSilenced }
        }
    }

    fun start() {
        IslandBus.privacyEnabled = context.getSharedPreferences("island", Context.MODE_PRIVATE).getBoolean("privacy_dots", true)
        IslandBus.lockPlayer = context.getSharedPreferences("island", Context.MODE_PRIVATE).getBoolean("lock_player", false)
        IslandBus.statusPills = context.getSharedPreferences("island", Context.MODE_PRIVATE).getBoolean("status_pills", false)
        torchIgnoreUntil = SystemClock.elapsedRealtime() + 1500
        runCatching {
            context.getSystemService(CameraManager::class.java).registerTorchCallback(torchCallback, handler)
        }
        // Is the hotspot already on? (A hidden call: it may not exist on every phone.)
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            IslandBus.hotspotAp = android.net.wifi.WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wm) as Boolean
        }
        runCatching {
            context.getSystemService(CameraManager::class.java).registerAvailabilityCallback(cameraCallback, handler)
        }
        runCatching {
            val am = context.getSystemService(AudioManager::class.java)
            am.registerAudioRecordingCallback(micCallback, handler)
            IslandBus.micActive = am.activeRecordingConfigurations.any { !it.isClientSilenced }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction("android.net.conn.TETHER_STATE_CHANGED")
            addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop() {
        runCatching { context.getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(cameraCallback) }
        runCatching { context.getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(micCallback) }
        runCatching { context.getSystemService(CameraManager::class.java).unregisterTorchCallback(torchCallback) }
        handler.removeCallbacksAndMessages(null)
        busyCameras.clear()
        IslandBus.camActive = false
        IslandBus.micActive = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    private companion object {
        val Orange = Color(0xFFFF9F0A)
        val Gray = Color(0xFF8E8E93)
        val Purple = Color(0xFF7D7AFF)
        val Yellow = Color(0xFFFFD60A)
        val MiniGreenColor = Color(0xFF30D158)
    }
}