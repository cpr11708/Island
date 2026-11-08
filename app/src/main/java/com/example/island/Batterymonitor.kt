package com.example.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

/**
 * Watches the real battery. When a charger is plugged in, the island pops out as a compact charging pill for a few
 * seconds. Unplugging clears it. It never interrupts something else that is already showing (a call, a timer).
 */
class BatteryMonitor(private val context: Context) {

    private var lastPlugged: Boolean? = null
    private var lastLevel = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = handle(intent)
    }

    fun start() {
        // ACTION_BATTERY_CHANGED is "sticky": registering gives us the current reading immediately.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun handle(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) IslandBus.batteryPercent = level * 100 / scale

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val ms = bm.computeChargeTimeRemaining()
        IslandBus.minutesToFull = if (plugged && ms > 0) (ms / 60_000L).toInt() else -1

        // Low battery (20 % and 10 %) and fully charged: a short pop-up with a battery that fills up.
        val pct = IslandBus.batteryPercent
        val prevLevel = lastLevel
        lastLevel = pct
        if (prevLevel >= 0 && pct != prevLevel) {
            if (!plugged && prevLevel > 20 && pct <= 20 || !plugged && prevLevel > 10 && pct <= 10) {
                if (IslandBus.alert("battery", "Low battery", RED, pct)) IslandBus.expandPopup(2)
            } else if (plugged && prevLevel < 100 && pct >= 100) {
                if (IslandBus.alert("battery", "Fully charged", GREEN, pct)) IslandBus.expandPopup(2)
            }
        }

        val before = lastPlugged
        lastPlugged = plugged
        if (before == null || before == plugged) return // first reading, or nothing changed about the charger

        if (plugged && pct >= 100) {
            // Plugged in while already full: say so, rather than showing a charging pill that has nothing to charge.
            if (IslandBus.alert("battery", "Fully charged", GREEN, pct)) IslandBus.expandPopup(2)
        } else if (plugged) {
            if (IslandBus.state == IslandState.Idle) {
                IslandBus.autoIdleMs = CHARGE_SHOW_MS
                IslandBus.autoIdle = true
                IslandBus.state = IslandState.Charging
            }
        } else if (IslandBus.state == IslandState.Charging || IslandBus.state == IslandState.ChargingOpen) {
            IslandBus.autoIdle = false
            IslandBus.state = IslandState.Idle
        }
    }

    companion object {
        private val RED = androidx.compose.ui.graphics.Color(0xFFFF453A)
        private val GREEN = androidx.compose.ui.graphics.Color(0xFF30D158)

        /** How long the compact charging pill stays before the island goes idle again. */
        const val CHARGE_SHOW_MS = 4000L
    }
}