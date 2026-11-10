package com.example.island

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import kotlin.math.roundToInt
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SetupScreen() }
            }
        }
    }
}

private val Good = Color(0xFF30D158)
private val Warn = Color(0xFFFF9F0A)

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var listenerOn by remember { mutableStateOf(isListenerOn(context)) }
    var a11yOn by remember { mutableStateOf(isAccessibilityOn(context)) }
    val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    var batteryFree by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    var callGranted by remember { mutableStateOf(has(context, Manifest.permission.ANSWER_PHONE_CALLS)) }
    var btGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 31 || has(context, Manifest.permission.BLUETOOTH_CONNECT))
    }
    var devTools by remember { mutableStateOf(false) }

    // Check everything again when coming back from a settings screen.
    val lifecycle = (context as ComponentActivity).lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canOverlay = Settings.canDrawOverlays(context)
                listenerOn = isListenerOn(context)
                a11yOn = isAccessibilityOn(context)
                batteryFree = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                callGranted = has(context, Manifest.permission.ANSWER_PHONE_CALLS)
                btGranted = Build.VERSION.SDK_INT < 31 || has(context, Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(listenerOn) {
        // After a reinstall Android sometimes forgets to reconnect the listener, so ask it to.
        if (listenerOn) runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(context, MediaListenerService::class.java),
            )
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        btGranted = Build.VERSION.SDK_INT < 31 || has(context, Manifest.permission.BLUETOOTH_CONNECT)
    }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        callGranted = has(context, Manifest.permission.ANSWER_PHONE_CALLS)
    }

    val islandReady = a11yOn || canOverlay
    val allGood = islandReady && listenerOn && batteryFree

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- Header
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Island", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                "A Dynamic Island for your cover screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp,
            )
        }

        // ---- Status
        Card(color = (if (allGood) Good else Warn).copy(alpha = 0.16f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Dot(if (allGood) Good else Warn)
                Column {
                    Text(
                        if (allGood) "Island is ready" else "A little setup is left",
                        fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                    )
                    Text(
                        if (allGood) "It starts by itself. There is nothing to start here."
                        else "Finish the steps below. Each one turns on part of the island.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    )
                }
            }
        }

        // ---- Setup
        Heading("Setup")
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (a11yOn) {
                    Step(
                        done = true,
                        title = "Show the island",
                        text = "On, using Accessibility. You can touch the island in every app.",
                        button = null,
                    ) { }
                } else {
                    // Two ways to show the island: only one is needed, so they sit together in their own box.
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Show the island: pick one", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Column(
                            Modifier.fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Step(
                                done = false,
                                title = "Accessibility (recommended)",
                                text = "Turn on Island under Accessibility, in Installed apps. That lets you touch it in every app.",
                                button = "Open Accessibility",
                            ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)))
                                Text("  or  ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)))
                            }
                            if (!canOverlay) {
                                Step(
                                    done = false,
                                    title = "Basic overlay",
                                    text = "A simpler option. Touches near the top of some apps will not reach it.",
                                    button = "Allow overlay",
                                ) {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                                    )
                                }
                            } else {
                                Step(
                                    done = true,
                                    title = "Basic overlay allowed",
                                    text = "Start it here if you are not using Accessibility.",
                                    button = "Start island",
                                ) {
                                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    context.startForegroundService(Intent(context, IslandService::class.java))
                                }
                            }
                        }
                    }
                }
                if (!a11yOn || !listenerOn) {
                    // Android blocks these two switches for apps installed from a file until this is allowed.
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Warn.copy(alpha = 0.14f)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Switch greyed out?", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            "Android blocks Accessibility and Notification access for apps installed from a file. Open Island's " +
                                    "App info, tap the three dots at the top right (or More), and choose \"Allow restricted settings\". " +
                                    "Then come back and try again.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                        )
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                                )
                            }
                        }) { Text("Open App info") }
                    }
                }
                Step(
                    done = listenerOn,
                    title = "Notification access",
                    text = "Lets the island see music, calls, timers and navigation.",
                    button = if (listenerOn) null else "Allow access",
                ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                Step(
                    done = batteryFree,
                    title = "Run in the background",
                    text = "Stops Android from putting the island to sleep. On Samsung also add Island to \"Never auto sleeping apps\" in Settings, Battery.",
                    button = if (batteryFree) null else "Allow",
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                }
                Step(
                    done = callGranted,
                    title = "Call control (optional)",
                    text = "Lets you answer and hang up from the island.",
                    button = if (callGranted) null else "Allow",
                ) { callPermission.launch(Manifest.permission.ANSWER_PHONE_CALLS) }
                Step(
                    done = btGranted,
                    title = "Bluetooth names (optional)",
                    text = "Shows the name of a device when it connects.",
                    button = if (btGranted) null else "Allow",
                ) { btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
            }
        }

        // ---- Features
        Heading("Features")
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PrefSwitch(context, "Camera and microphone dots", "A green or orange dot beside the camera while one is in use.", "privacy_dots", IslandBus.privacyEnabled) { IslandBus.privacyEnabled = it }
                PrefSwitch(context, "Recording and hotspot dots", "Red while the screen is recorded, green while the hotspot is on.", "status_pills", IslandBus.statusPills) { IslandBus.statusPills = it }
                PrefSwitch(context, "Outline", "A faint grey edge around the island, only while everything around it is black, so it never disappears into the screen.", "island_outline", IslandBus.edgeOutline) { IslandBus.edgeOutline = it }
                PrefSwitch(context, "Music on the lock screen", "A music player near the bottom while locked and playing.", "lock_player", IslandBus.lockPlayer) { IslandBus.lockPlayer = it }
                Text(
                    "Touch and hold the lock screen player, then drag, to move it. Tap the handle at its top, or swipe it down, to shrink it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                )
                TextButton(onClick = {
                    context.getSharedPreferences("island", Context.MODE_PRIVATE).edit().remove("lock_x_dp").remove("lock_y_dp").apply()
                }) { Text("Put the player back in its usual place") }
            }
        }
        // ---- Where the island sits and how round it is
        Heading("Position and shape")
        Card { PositionAndShape(context) }
        Text(
            "Tap a compact island to open it, tap again to close it, and swipe it up to dismiss it. " +
                    "Touch the camera to bring a dismissed one back.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )

        // ---- Things that overlap with the island
        Heading("Avoid doubled-up pop-ups")
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Some phone features show the same thing the island does. If you see two of something, turn the other one off here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                )
                // Samsung's own screens vary between phones and updates, so each button falls back to a nearby page.
                fun open(vararg actions: String) {
                    for (a in actions) {
                        val ok = runCatching {
                            context.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                        if (ok) return
                    }
                }
                // Android never lets one app switch off another app's pop-ups, so each button goes straight to that
                // app's own notification page, where its "Pop on screen" (or "Pop-up") switch is.
                val apps = remember { doubledApps(context) }
                if (apps.isNotEmpty()) {
                    Text(
                        "Turn off pop-ups (and on Android 16 or later, \"Live updates\", the chip in the status bar) for the apps the island already shows. Android only allows this from each app's own page:",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    )
                    apps.forEach { (label, pkg) ->
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("$label notifications") }
                    }
                }
                TextButton(onClick = { open("com.samsung.android.settings.lockscreen.NOW_BAR", "android.settings.LOCK_SCREEN_SETTINGS", Settings.ACTION_SETTINGS) }) {
                    Text("Now Bar and lock screen settings")
                }
                TextButton(onClick = { open("android.settings.NOTIFICATION_SETTINGS", Settings.ACTION_SETTINGS) }) {
                    Text("Notification settings (status bar icons, pop-ups)")
                }
                TextButton(onClick = { open("android.settings.SOUND_SETTINGS", Settings.ACTION_SETTINGS) }) {
                    Text("Sound settings (silent and vibrate)")
                }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Island's App info") }
            }
        }

        // ---- Developer tools, tucked away
        Row(verticalAlignment = Alignment.CenterVertically) {
            Heading("Developer tools", Modifier.weight(1f))
            TextButton(onClick = { devTools = !devTools }) { Text(if (devTools) "Hide" else "Show") }
        }
        if (devTools) DeveloperTools(listenerOn)
    }
}

@Composable
private fun DeveloperTools(listenerOn: Boolean) {
    val small = MaterialTheme.typography.bodySmall
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Try it", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { IslandBus.state = IslandState.Charging }) { Text("Charging") }
                Button(onClick = { IslandBus.demoTimer() }) { Text("Timer") }
                Button(onClick = { IslandBus.state = IslandState.Music }) { Text("Music") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { IslandBus.demoNav() }) { Text("Nav") }
                Button(onClick = { IslandBus.camActive = !IslandBus.camActive }) { Text("Cam") }
                Button(onClick = { IslandBus.micActive = !IslandBus.micActive }) { Text("Mic") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { IslandBus.recActive = !IslandBus.recActive }) { Text("Rec") }
                Button(onClick = { IslandBus.hotspotAp = !IslandBus.hotspotAp }) { Text("Hotspot") }
                Button(onClick = { IslandBus.demoStopwatch() }) { Text("Stopwatch") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    IslandBus.torchOn = !IslandBus.torchOn
                    if (!IslandBus.torchOn) IslandBus.torchDismissed = false
                    IslandBus.state = if (IslandBus.torchOn) IslandState.TorchOpen else IslandBus.restingState()
                }) { Text("Flashlight") }
                Button(onClick = { IslandBus.alert("silent", "Silent", Color(0xFFFF9F0A)) }) { Text("Alert") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { IslandBus.showDevice("AirPods Pro", true, 80); IslandBus.expandPopup(1) }) { Text("BT connect") }
                Button(onClick = { IslandBus.showDevice("AirPods Pro", false); IslandBus.expandPopup(1) }) { Text("BT disconnect") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { IslandBus.alert("battery", "Low battery", Color(0xFFFF9F0A), 20); IslandBus.expandPopup(2) }) { Text("Battery 20%") }
                Button(onClick = { IslandBus.alert("battery", "Low battery", Color(0xFFFF453A), 10); IslandBus.expandPopup(2) }) { Text("Battery 10%") }
                Button(onClick = { IslandBus.alert("battery", "Charged", Color(0xFF30D158), 100); IslandBus.expandPopup(2) }) { Text("Full") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { IslandBus.demoCall() }) { Text("Call") }
                Button(onClick = { IslandBus.demoCall(incoming = true) }) { Text("Incoming") }
                OutlinedButton(onClick = { IslandBus.state = IslandState.Idle }) { Text("Idle") }
            }
            Button(onClick = {
                // Two things at once: a timer plus music.
                IslandBus.demoTimer()
                IslandBus.mediaTitle = "Demo song"
                IslandBus.mediaPlaying = true
                IslandBus.state = IslandState.Music
            }) { Text("Split (timer + music)") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = IslandBus.showOutline, onCheckedChange = { IslandBus.showOutline = it })
                Text("Red outline on the idle pill")
            }
        }
    }
    if (listenerOn) {
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What Island sees", fontWeight = FontWeight.SemiBold)
                Text("Media: ${IslandBus.mediaStatus}", style = small)
                Text("Calls: ${IslandBus.callDebug}", style = small)
                Text("Call time: ${IslandBus.callTimeDebug}", style = small)
                Text("Timer notification: ${IslandBus.timerDebug}", style = small)
                Text("Timer chip: ${IslandBus.timerChipDebug}", style = small)
                Text("Timer buttons: ${IslandBus.timerCmdDebug}", style = small)
                Text("Clock notifications: ${IslandBus.clockDebug}", style = small)
                Text("Last tap: ${IslandBus.tapDebug}", style = small)
                Text("Last close: ${IslandBus.closeDebug}", style = small)
                Text("Maps: ${IslandBus.navDebug}", style = small)
                Text("Camera in use: ${IslandBus.camActive}, microphone: ${IslandBus.micActive}", style = small)
                Text("Recording: ${IslandBus.recActive}, hotspot: ${IslandBus.hotspotActive}", style = small)
                if (IslandBus.recDebug.isNotEmpty()) Text("Recording because: ${IslandBus.recDebug}", style = small)
                Text("Windows seen while locked:\n${IslandBus.lockWindowsDebug.ifEmpty { "(lock the phone, then open this screen again)" }}", style = small)
                Text(
                    "App in front: ${IslandBus.foregroundPackage.ifEmpty { "(none seen yet)" }}, music app: ${IslandBus.mediaPackage.ifEmpty { "(none)" }}",
                    style = small,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.padding(start = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun Card(color: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color).padding(18.dp)) { content() }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(14.dp).clip(CircleShape).background(color))
}

/** One line of the checklist: a tick or a ring, what it is, and a button while it is still to do. */
@Composable
private fun Step(done: Boolean, title: String, text: String, button: String?, onClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 2.dp).size(22.dp).clip(CircleShape)
                .background(if (done) Good else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) { if (done) Text("✓", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            if (button != null) {
                Spacer(Modifier.height(6.dp))
                Button(onClick = onClick) { Text(button) }
            }
        }
    }
}

/** The ready-made spots, as (preference value, name, where the camera is in dp on a screen [w] dp wide). */
private fun presetSpots(w: Float) = listOf(
    Triple("left_corner", "Left corner", 30f),
    Triple("left", "Left", w * 0.25f),
    Triple("center", "Centre", w * 0.5f),
    Triple("right", "Right", w * 0.75f),
    Triple("right_corner", "Right corner", w - 30f),
)

/**
 * Where the island sits and how round an expanded island is, around one to-scale preview of the top of the screen:
 * its rounded corners, the camera, and an expanded island drawn around it. Tapping the preview picks the nearest
 * ready-made spot; the corner slider redraws it as it moves.
 */
@Composable
private fun PositionAndShape(context: Context) {
    val prefs = context.getSharedPreferences("island", Context.MODE_PRIVATE)
    var mode by remember(IslandBus.camVersion) { mutableStateOf(prefs.getString("cam_pos", "auto") ?: "auto") }
    fun choose(m: String) {
        mode = m
        prefs.edit().putString("cam_pos", m).apply()
        IslandBus.rebuildOverlay?.invoke()
    }

    // The screen the island is on. Until the island has started, this screen's own size and camera stand in.
    val view = androidx.compose.ui.platform.LocalView.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val ownCutout = remember(view) { view.rootWindowInsets?.displayCutout?.boundingRectTop }
    val ownCorner = remember(view) {
        (view.rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0) / density
    }
    val running = IslandBus.screenWdp > 0f
    val screenW = if (running) IslandBus.screenWdp else config.screenWidthDp.toFloat()
    val autoX = if (running) IslandBus.autoCamX else ownCutout?.let { it.exactCenterX() / density } ?: (screenW / 2f)
    val autoY = if (running) IslandBus.autoCamY else ownCutout?.let { (it.bottom - minOf(it.width(), it.height()) / 2f) / density } ?: 20f
    val screenCorner = if (running) IslandBus.screenCorner else ownCorner

    @Suppress("UNUSED_VARIABLE") val camVersion = IslandBus.camVersion // re-read the Custom spot after the editor saves it
    val spots = presetSpots(screenW)
    val camX = when (mode) {
        "auto" -> autoX
        "custom" -> screenW * prefs.getFloat("cam_x_pct", 50f) / 100f
        else -> spots.firstOrNull { it.first == mode }?.third ?: autoX
    }
    val camY = if (mode == "custom" && prefs.getBoolean("cam_y_on", false)) prefs.getFloat("cam_y_dp", 20f) else autoY
    val compactH = if (mode == "custom") prefs.getFloat("island_h", 36f) else 36f
    // The expanded island sits as far from the sides as from the top, with the corners the island will use
    // (the same rules as the island itself: Hole.edgeGap and IslandBus.openCorner).
    val edgeGap = (camY - compactH / 2f).coerceAtLeast(0f)
    val corner = (if (!IslandBus.cornerAuto) IslandBus.cornerCustom else if (screenCorner > 0f) screenCorner - edgeGap else OPEN_CORNER.value)
        .coerceAtLeast(compactH / 2f)
    val corners = androidx.compose.animation.core.animateFloatAsState(corner, label = "preview corner").value
    val islandX = androidx.compose.animation.core.animateFloatAsState(camX, label = "preview spot").value

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IslandPreview(
            screenW = screenW, screenCorner = screenCorner, camX = islandX, camY = camY, compactH = compactH, gap = edgeGap,
            corner = corners, spots = spots.map { it.third }, chosen = spots.indexOfFirst { it.first == mode },
            onTap = { x -> choose(spots.minBy { kotlin.math.abs(it.third - x) }.first) },
        )
        Text(
            when (mode) {
                "auto" -> "Auto: the island sits over your phone's own camera. Tap a spot above to move it somewhere else."
                "custom" -> "Custom: you set where the camera is and how tall the island is."
                else -> "${spots.first { it.first == mode }.second}. Tap another spot to move it, or pick Auto or Custom."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Segmented(
            options = listOf("Auto", spots.firstOrNull { it.first == mode }?.second ?: "Spot", "Custom"),
            selected = when (mode) { "auto" -> 0; "custom" -> 2; else -> 1 },
        ) { i ->
            when (i) {
                0 -> choose("auto")
                1 -> if (mode == "auto" || mode == "custom") choose("center")
                else -> choose("custom")
            }
        }
        if (mode == "custom") {
            OutlinedButton(onClick = { IslandBus.startCameraEdit?.invoke() }, modifier = Modifier.fillMaxWidth()) {
                Text("Edit position and height")
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)))

        PrefSwitch(
            context, "Match the screen's corners",
            when {
                !IslandBus.cornerAuto -> "Off: pick the corner size below." +
                        (if (screenCorner > 0f) " Your screen's corners are ${screenCorner.roundToInt()} dp." else "")
                screenCorner > 0f -> "An expanded island's corners follow your screen's rounded corners (${screenCorner.roundToInt()} dp)."
                else -> "Your screen reports square corners, so an expanded island uses ${OPEN_CORNER.value.roundToInt()} dp. Turn this off to pick your own."
            },
            "corner_auto", IslandBus.cornerAuto,
        ) { IslandBus.cornerAuto = it }
        if (!IslandBus.cornerAuto) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Corner size", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${IslandBus.cornerCustom.roundToInt()} dp", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                Slider(
                    value = IslandBus.cornerCustom,
                    onValueChange = { IslandBus.cornerCustom = it.roundToInt().toFloat() },
                    onValueChangeFinished = { prefs.edit().putFloat("corner_dp", IslandBus.cornerCustom).apply() },
                    valueRange = 18f..64f,
                )
            }
        }
    }
}

/**
 * The top of the screen, to scale: its outline with its own rounded corners, faint dots at the ready-made spots, and an
 * expanded island over the camera with the corners it will have. It fades out downwards, as the screen goes on.
 */
@Composable
private fun IslandPreview(
    screenW: Float, screenCorner: Float, camX: Float, camY: Float, compactH: Float, gap: Float, corner: Float,
    spots: List<Float>, chosen: Int, onTap: (Float) -> Unit,
) {
    // A representative expanded island (about the size of the music player), [gap] dp in from the top and the sides.
    val islandW = minOf(480f, screenW - 2 * gap)
    val islandH = 130f
    val top = (camY - compactH / 2f).coerceAtLeast(0f)
    val left = (camX - islandW / 2f).coerceIn(gap, screenW - gap - islandW)
    val shownH = top + islandH + 40f // how much of the screen's top the preview shows
    val screenColor = MaterialTheme.colorScheme.surface
    val edgeColor = MaterialTheme.colorScheme.outline
    val spotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chosenColor = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().aspectRatio(screenW / shownH)
            // Drawn on its own layer so the bottom can fade out to nothing (the screen carries on below).
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen; clip = true }
            .pointerInput(screenW) { detectTapGestures { p -> onTap(p.x / size.width * screenW) } },
    ) {
        val k = size.width / screenW // pixels per dp of the real screen
        val r = screenCorner * k
        // The screen: a rounded top, straight sides that run off the bottom.
        val screen = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    0.75.dp.toPx(), 0.75.dp.toPx(), size.width - 0.75.dp.toPx(), size.height + r + 4.dp.toPx(),
                    androidx.compose.ui.geometry.CornerRadius(r, r),
                ),
            )
        }
        drawPath(screen, screenColor)
        drawPath(screen, edgeColor.copy(alpha = 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        // The expanded island, with a faint edge as on the phone.
        val cr = minOf(corner, islandH / 2f) * k
        drawRoundRect(
            Color.Black, topLeft = androidx.compose.ui.geometry.Offset(left * k, top * k),
            size = androidx.compose.ui.geometry.Size(islandW * k, islandH * k),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cr, cr),
        )
        drawRoundRect(
            Color(0xFF3A3A3C), topLeft = androidx.compose.ui.geometry.Offset(left * k, top * k),
            size = androidx.compose.ui.geometry.Size(islandW * k, islandH * k),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cr, cr),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
        )
        // The camera, inside the island.
        drawCircle(Color(0xFF1E1E22), radius = 6.dp.toPx(), center = androidx.compose.ui.geometry.Offset(camX * k, camY * k))
        // The ready-made spots (where the camera would be for each), on top so they show over the island too:
        // rings, and a filled dot for the chosen one.
        spots.forEachIndexed { i, x ->
            val c = androidx.compose.ui.geometry.Offset(x * k, camY * k)
            if (i == chosen) drawCircle(chosenColor, radius = 5.dp.toPx(), center = c)
            else drawCircle(
                spotColor.copy(alpha = 0.6f), radius = 4.dp.toPx(), center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
            )
        }
        // Fade the bottom out, as the screen carries on below.
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color.Black, Color.Transparent), startY = size.height * 0.7f, endY = size.height,
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }
}

/** A row of joined buttons, one of them chosen. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50))
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 14.sp, maxLines = 1,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PrefSwitch(context: Context, title: String, text: String, key: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = value,
            onCheckedChange = { on ->
                onChange(on)
                context.getSharedPreferences("island", Context.MODE_PRIVATE).edit().putBoolean(key, on).apply()
            },
        )
    }
}

/** The installed apps whose pop-ups repeat what the island shows: the phone app, WhatsApp, Maps and the Clock. */
private fun doubledApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    fun installed(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
    val dialer = runCatching { context.getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage }.getOrNull()
    return listOfNotNull(
        dialer?.let { "Phone" to it },
        "WhatsApp" to "com.whatsapp",
        "Google Maps" to "com.google.android.apps.maps",
        "Clock" to "com.google.android.deskclock",
        "Clock" to "com.sec.android.app.clockpackage",
    ).filter { installed(it.second) }.distinctBy { it.second }
}

private fun has(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun isListenerOn(context: Context): Boolean =
    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun isAccessibilityOn(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val me = ComponentName(context, IslandAccessibilityService::class.java).flattenToString()
    return enabled.split(':').any { it.equals(me, ignoreCase = true) }
}