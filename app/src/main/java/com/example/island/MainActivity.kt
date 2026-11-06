package com.example.island

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { ControlScreen() }
            }
        }
    }
}

@Composable
private fun ControlScreen() {
    val context = LocalContext.current
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var listenerOn by remember { mutableStateOf(isListenerOn(context)) }
    var a11yOn by remember { mutableStateOf(isAccessibilityOn(context)) }
    val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    var batteryFree by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

    // Re-check the permission when coming back from the settings screen.
    val lifecycle = (context as ComponentActivity).lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canOverlay = Settings.canDrawOverlays(context)
                listenerOn = isListenerOn(context)
                a11yOn = isAccessibilityOn(context)
                batteryFree = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(listenerOn) {
        // After a reinstall Android sometimes forgets to reconnect the listener, so ask it to.
        if (listenerOn) runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(context, MediaListenerService::class.java),
            )
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val btGranted = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Island", style = MaterialTheme.typography.headlineMedium)

        if (a11yOn) {
            Text("Island is running (accessibility service). It starts by itself, so there is nothing to start here.")
        } else {
            Text("Recommended: turn on Island in Accessibility settings, under Installed apps. That lets you touch the island in every app, not just on the home screen. (If the switch is greyed out, open Island's App info, tap the three dots, and choose \"Allow restricted settings\" first.)")
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("Open accessibility settings") }
            Text("Or use the basic overlay below (touches near the top of some apps will not reach it):", style = MaterialTheme.typography.bodySmall)
        }

        if (a11yOn) {
            // Nothing else to set up for the island itself.
        } else if (!canOverlay) {
            Text("Step 1: allow Island to display over other apps.")
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("Grant overlay permission") }
        } else {
            Text("Overlay permission granted.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    context.startForegroundService(Intent(context, IslandService::class.java))
                }) { Text("Start island") }
                OutlinedButton(onClick = {
                    context.stopService(Intent(context, IslandService::class.java))
                }) { Text("Stop") }
            }
        }

        if (!listenerOn) {
            Text("For now-playing: allow notification access for Island. (If it is greyed out, open Island's App info, tap the three dots, and choose \"Allow restricted settings\" first.)")
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { Text("Grant notification access") }
        } else {
            Text("Notification access granted. Music will show up on its own.")
            Text("Media status: ${IslandBus.mediaStatus}", style = MaterialTheme.typography.bodySmall)
            Text("Calls: ${IslandBus.callDebug}", style = MaterialTheme.typography.bodySmall)
            Text("Timer notification: ${IslandBus.timerDebug}", style = MaterialTheme.typography.bodySmall)
            Text("Timer chip: ${IslandBus.timerChipDebug}", style = MaterialTheme.typography.bodySmall)
            Text("Timer buttons: ${IslandBus.timerCmdDebug}", style = MaterialTheme.typography.bodySmall)
            Text("Last tap: ${IslandBus.tapDebug}", style = MaterialTheme.typography.bodySmall)
            Text("App in front: ${IslandBus.foregroundPackage.ifEmpty { "(none seen yet)" }}, music app: ${IslandBus.mediaPackage.ifEmpty { "(none)" }}", style = MaterialTheme.typography.bodySmall)
        }

        if (!batteryFree) {
            Text("Keep Island alive: allow it to run in the background without battery limits. On Samsung phones also open Settings, Battery, Background usage limits, and make sure Island is in \"Never auto sleeping apps\".")
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("Allow background running") }
        } else {
            Text("Background running allowed.", style = MaterialTheme.typography.bodySmall)
        }
        if (!btGranted) {
            Text("To show the name of a Bluetooth device when it connects, allow Bluetooth access.")
            Button(onClick = { btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow Bluetooth names") }
        }

        Text("Try it", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { IslandBus.state = IslandState.Charging }) { Text("Charging") }
            Button(onClick = { IslandBus.demoTimer() }) { Text("Timer") }
            Button(onClick = { IslandBus.state = IslandState.Music }) { Text("Music") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { IslandBus.demoCall() }) { Text("Call") }
            Button(onClick = { IslandBus.demoCall(incoming = true) }) { Text("Incoming") }
            OutlinedButton(onClick = { IslandBus.state = IslandState.Idle }) { Text("Idle") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                IslandBus.alert("\uD83D\uDD15", "Silent", androidx.compose.ui.graphics.Color(0xFFFF9F0A))
            }) { Text("Alert") }
            Button(onClick = {
                // Two things at once: a timer plus music.
                IslandBus.demoTimer()
                IslandBus.mediaTitle = "Demo song"
                IslandBus.mediaPlaying = true
                IslandBus.state = IslandState.Music
            }) { Text("Split") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = IslandBus.showOutline, onCheckedChange = { IslandBus.showOutline = it })
            Text("Red outline on idle pill (to see where it sits)")
        }
        Text(
            "Tap a compact island to open it, tap again to close it, swipe it up to dismiss.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun isListenerOn(context: android.content.Context): Boolean =
    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun isAccessibilityOn(context: android.content.Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val me = android.content.ComponentName(context, IslandAccessibilityService::class.java).flattenToString()
    return enabled.split(':').any { it.equals(me, ignoreCase = true) }
}