package com.example.island

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------
// Tuning knobs
// ---------------------------------------------------------------------------------------------

/** Height of the compact island. */
/** How tall the compact island is (the person can change it; 36 dp is the standard). */
val COMPACT: Dp get() = IslandBus.compactH.dp

/** Idle pill diameter as a fraction of the camera hole. Below 1 keeps it inside the hole. */
const val IDLE_SCALE = 0.8f

/** Slide the idle pill down (+) or up (-) if it is not centred on the hole. Use the outline to tune. */
val HOLE_NUDGE = (-1.5).dp

/** Corner radius of an open island. Smaller = squarer. */
val OPEN_CORNER = 40.dp

/** Extra touch area around the island on every side. */
val TOUCH_PAD = 14.dp

/** The faint edge drawn around a showing island (see [IslandBus.edgeOutline]). */
private val EdgeColor = Color(0xFF3A3A3C)

/** How long to hold a finger on the island before it opens or closes. */
const val HOLD_MS = 450L

/** How much of its usual press squish the island keeps while a button inside it is pressed (0 = none, 1 = all). */
const val BUTTON_ISLAND_SHARE = 1f

// ---------------------------------------------------------------------------------------------
// Shared state between the app screen and the overlay service
// ---------------------------------------------------------------------------------------------

enum class IslandState {
    Idle, Charging, ChargingOpen, Timer, TimerOpen, Call, CallOpen, Music, MusicOpen, Nav, NavOpen, Alert, Device, Torch, TorchOpen;

    /** Whether the island needs the large overlay window. */
    fun isTall(): Boolean = this == ChargingOpen || this == TimerOpen || this == MusicOpen || this == CallOpen || this == NavOpen || this == Device || this == TorchOpen

    fun tapped(): IslandState = when (this) {
        Charging -> ChargingOpen
        ChargingOpen -> Charging
        Timer -> TimerOpen
        TimerOpen -> Timer
        Call -> CallOpen
        CallOpen -> Call
        Music -> MusicOpen
        MusicOpen -> Music
        Nav -> NavOpen
        NavOpen -> Nav
        Torch -> TorchOpen
        TorchOpen -> Torch
        else -> this
    }
}

data class CallAction(val label: String, val accept: Boolean, val intent: android.app.PendingIntent?)

object IslandBus {
    var state by mutableStateOf(IslandState.Idle)
    /** Draws a red outline on the idle pill so you can see where it sits relative to the camera. */
    var showOutline by mutableStateOf(false)

    /** True after an outside touch shrank the island: if nothing else happens, it goes idle after [IDLE_AFTER_MS]. */
    var autoIdle by mutableStateOf(false)

    /** How long the auto-idle waits. */
    var autoIdleMs = 1500L

    /** Live battery reading, filled in by BatteryMonitor. [minutesToFull] is -1 when unknown. */
    var batteryPercent by mutableIntStateOf(0)
    var minutesToFull by mutableIntStateOf(-1)

    /** Now playing, filled in by MediaMonitor. [mediaStatus] explains what the listener is doing (shown in the app). */
    var mediaStatus by mutableStateOf("Not started")
    var mediaTitle by mutableStateOf("")
    var mediaArtist by mutableStateOf("")
    var mediaArt by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var mediaPlaying by mutableStateOf(false)
    var mediaDuration by mutableLongStateOf(0L)
    var mediaPosBase by mutableLongStateOf(0L)
    var mediaPosTime by mutableLongStateOf(0L)
    var mediaSpeed by mutableFloatStateOf(1f)
    /** Colour of the visualizer, taken from the current album cover. */
    var mediaAccent by mutableStateOf(androidx.compose.ui.graphics.Color(0xFFD96C9B))
    var mediaSeek: (Long) -> Unit = {}
    var mediaPrev: () -> Unit = {}
    var mediaPlayPause: () -> Unit = {}
    var mediaNext: () -> Unit = {}

    /** The app being used right now (needs the accessibility service) and the app the music comes from. */
    var foregroundPackage by mutableStateOf("")
    var mediaPackage by mutableStateOf("")

    /** The current phone call, filled in by MediaListenerService (or by [demoCall]). */
    var callName by mutableStateOf("")
    var callIncoming by mutableStateOf(false)
    var callStartMs by mutableLongStateOf(0L)
    var callTimeDebug by mutableStateOf("")
    /** The last call that just ended: if its notification comes straight back, it is the same call. */
    var lastCallStart = 0L
    var lastCallName = ""
    var lastCallEndedAt = 0L
    var callActions by mutableStateOf<List<CallAction>>(emptyList())
    var callKey = ""
    var callDismissedKey: String? = null
    /** False for the demo button, so a real notification never clears the demo. */
    var callReal = false
    /** What the listener saw, shown in the app to help diagnose calls. */
    var callDebug by mutableStateOf("No call notifications seen")

    /** The current timer, filled in by MediaListenerService (or by [demoTimer]). */
    var timerEndMs by mutableLongStateOf(0L)
    var timerPausedMs by mutableLongStateOf(0L)
    var timerPaused by mutableStateOf(false)
    var timerLabel by mutableStateOf("Timer")
    var timerActions by mutableStateOf<List<CallAction>>(emptyList())
    var timerKey = ""
    var timerDismissedKey: String? = null
    /** False for the demo button, so a real notification never clears the demo. */
    var timerReal = false
    /** False until the real time left is known (Samsung's Clock leaves it out of its notification). */
    var timerKnown by mutableStateOf(false)
    /** True when the notification itself gave the time, so the screen reader should stay out of it. */
    var timerFromNotification = false
    /** "pause" or "cancel": a button was pressed on a real timer; the accessibility service carries it out in the Clock app. */
    var timerCmd by mutableStateOf<String?>(null)
    var timerCmdDebug by mutableStateOf("")
    /** What the last tap on a compact pill tried to open, and what happened (shown in the app). */
    var tapDebug by mutableStateOf("")
    /** Why the island last closed, or that a hold registered (shown in the app, never overwritten by taps). */
    var closeDebug by mutableStateOf("")
    private val closeLog = ArrayDeque<String>()
    /** Keeps the last few reasons, newest first (shown in the app). */
    fun logClose(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        closeLog.addFirst("$t $msg")
        while (closeLog.size > 5) closeLog.removeLast()
        closeDebug = closeLog.joinToString("\n")
    }
    /** Set by the notification listener: presses the timer notification's own button ("pause" or "cancel"). True if one was found and pressed. */
    var timerRemotePress: ((String) -> Boolean)? = null
    /** What the accessibility service saw when it read the timer from the screen. */
    var timerChipDebug by mutableStateOf("not looked yet")
    /** What the Clock notification contained, shown in the app to help diagnose timers. */
    var timerDebug by mutableStateOf("No timer seen")

    /** True when the Clock's stopwatch is showing: [timerEndMs] is then the moment it started, and the time counts up. */
    var timerStopwatch by mutableStateOf(false)

    fun timerRemainingMs(now: Long = System.currentTimeMillis()): Long = when {
        timerPaused -> timerPausedMs
        timerStopwatch -> (now - timerEndMs).coerceAtLeast(0L)
        else -> (timerEndMs - now).coerceAtLeast(0L)
    }

    fun demoStopwatch() {
        timerReal = false
        timerKey = "demo"
        timerDismissedKey = null
        timerLabel = "Stopwatch"
        timerStopwatch = true
        timerPaused = false
        timerKnown = true
        timerEndMs = System.currentTimeMillis() - 5_000
        timerActions = listOf(CallAction("Pause", false, null), CallAction("Stop", false, null))
        state = IslandState.Timer
    }

    fun demoTimer() {
        timerReal = false
        timerKey = "demo"
        timerDismissedKey = null
        timerLabel = "Timer"
        timerStopwatch = false
        timerPaused = false
        timerKnown = true
        timerEndMs = System.currentTimeMillis() + 60_000
        timerActions = listOf(CallAction("Pause", false, null), CallAction("Stop", false, null))
        state = IslandState.Timer
    }

    /** The demo timer's buttons have no Clock app behind them, so they act here. */
    fun demoTimerPress(action: CallAction) {
        when (action.label) {
            "Pause" -> {
                timerPausedMs = timerRemainingMs()
                timerPaused = true
                timerActions = listOf(CallAction("Resume", false, null), CallAction("Stop", false, null))
            }
            "Resume" -> {
                timerEndMs = if (timerStopwatch) System.currentTimeMillis() - timerPausedMs else System.currentTimeMillis() + timerPausedMs
                timerPaused = false
                timerActions = listOf(CallAction("Pause", false, null), CallAction("Stop", false, null))
            }
            else -> state = IslandState.Idle
        }
    }

    fun demoCall(incoming: Boolean = false) {
        callReal = false
        callName = "Jordan"
        callIncoming = incoming
        callStartMs = if (incoming) 0L else System.currentTimeMillis() - 8_000
        callActions = if (incoming) listOf(CallAction("Decline", false, null), CallAction("Answer", true, null))
        else listOf(CallAction("Hang up", false, null))
        state = if (incoming) IslandState.CallOpen else IslandState.Call
    }

    /** Buttons of the demo call have no real phone app behind them, so they act here. */
    fun demoPress(action: CallAction) {
        if (action.accept) {
            callIncoming = false
            callStartMs = System.currentTimeMillis()
            callActions = listOf(CallAction("Hang up", false, null))
            state = IslandState.Call
        } else {
            state = IslandState.Idle
        }
    }

    /** Gap the camera leaves between the two halves of an open island, in dp (set by the island). */
    var camGap by mutableStateOf(46.dp)
    /** The screen the island is drawn on and its own camera (what Auto uses), in dp, for the settings screen's preview. */
    var screenWdp by androidx.compose.runtime.mutableFloatStateOf(0f)
    var autoCamX by androidx.compose.runtime.mutableFloatStateOf(0f)
    var autoCamY by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** The screen's own corner radius in dp, as Android reports it (0 when the screen has square corners or says nothing). */
    var screenCorner by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** True: an expanded island's corners match the screen's. False: they use [cornerCustom] (the "corners" setting). */
    var cornerAuto by mutableStateOf(true)
    var cornerCustom by androidx.compose.runtime.mutableFloatStateOf(OPEN_CORNER.value)
    /**
     * The corner radius (dp) of an expanded island that sits [gap] dp in from the screen's edges. Matching the screen, it
     * is the screen's corner less that gap, so the two curves run side by side. Never under half a compact pill's
     * height, so the pill stays round.
     */
    fun openCorner(gap: Float): Float =
        (if (!cornerAuto) cornerCustom else if (screenCorner > 0f) screenCorner - gap else OPEN_CORNER.value)
            .coerceAtLeast(compactH / 2f)
    /** Where the middle of the island is, in dp from the camera's centre (not zero when the camera is off to one side). */
    var pillCentre by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** Where the middle of the small touch window is, in dp from the camera's centre. */
    var padCentre = 0f
    /** Extra width (dp) a compact call island needs once its time has more than four characters ("13:28"). */
    var callExtra by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** How many characters the compact timer's time has ("9:59" is 4), so a pill near a screen end can fit it. */
    var timerChars by mutableStateOf(5)
    /** How far (dp) the camera's status dots reach past the camera's usual gap, and whether that is on its left. */
    var dotsReach by androidx.compose.runtime.mutableFloatStateOf(0f)
    var dotsOnLeft by mutableStateOf(false)
    var compactH by androidx.compose.runtime.mutableFloatStateOf(36f)
    /** True while the camera position editor is open: the real island hides so only the editor's copy shows. */
    var editingCamera by mutableStateOf(false)
    var startCameraEdit: (() -> Unit)? = null
    /** Goes up whenever the camera position is changed from somewhere other than the settings screen. */
    var camVersion by androidx.compose.runtime.mutableIntStateOf(0)
    /** Set just before the island is rebuilt after the position editor: the new island drops in smoothly. */
    var introNext = false
    /** Set just before a rebuild: where the old camera was (x, y, idle circle size, screen width, all dp), so the new island glides from there. */
    var moveFrom: FloatArray? = null
    /** The idle circle's size the last time the island was drawn (dp). */
    var lastIdleD = 36f
    /** Set by the overlay: builds the island again (after the camera position setting changes). */
    var rebuildOverlay: (() -> Unit)? = null
    /** Where the middle of the island's touch box is, in dp from the camera's centre. */
    var mainCentre = 0f

    /** True while a finger is on the island: it squishes a little. */
    var pressed by mutableStateOf(false)
    /** While split: true if the finger is on the small second pill rather than the main one. */
    var pressedBubble by mutableStateOf(false)
    /** The finger is on a button inside the island (not on the island itself). */
    var pressedOnButton by mutableStateOf(false)

    /** A short message pill (silent mode, a Bluetooth device...). Shown for a moment, then the island goes back. */
    var alertIcon by mutableStateOf("")
    var alertText by mutableStateOf("")
    var alertColor by mutableStateOf(Color.White)
    var alertExpand = 0
    /** What the expanded pop-up shows: 1 = Bluetooth device, 2 = battery. */
    var expandMode by mutableIntStateOf(1)
    var alertKey by mutableIntStateOf(0)
    /** 0..100 when the pop-up shows a battery (low battery, fully charged, earbuds), otherwise -1. */
    var alertBattery by mutableIntStateOf(-1)
    var alertReturn = IslandState.Idle

    /** The camera / microphone is in use by some app (shows the green / orange dots). */
    /** The user's switch (in the app): open the music player by itself on the lock screen. On by default. */
    var lockPlayer by mutableStateOf(false)
    /** The lock screen player is on screen right now (the island does not show music then), and whether it is shrunk. */
    var lockPlayerShowing by mutableStateOf(false)
    /** The screen is off, or has only just come on: the lock screen player is not animated in then. */
    var wakeSnap by mutableStateOf(false)
    /** The notification shade is pulled down over the lock screen (worked out from which windows are on screen). */
    var lockShadeOpen by mutableStateOf(false)
    var lockMinimized by mutableStateOf(false)
    /** True while the lock screen player is being held and moved. */
    var lockDragging by mutableStateOf(false)
    /** The windows on screen while locked, for working out how to tell that the notification shade is open. */
    var lockWindowsDebug by mutableStateOf("")
    /** While moving it: is the player clinging to the middle of the screen / to its usual height. */
    var lockSnapX by mutableStateOf(false)
    var lockSnapY by mutableStateOf(false)
    /** The lock screen player's sizes (dp): the big one is as wide as [lockFullWidthDp], set when the overlay is built. */
    var lockFullWidthDp by mutableStateOf(360f)
    const val LOCK_MIN_W_DP = 250f
    const val LOCK_MIN_H_DP = 60f
    const val LOCK_BIG_H_DP = 160f

    /** Nothing has been on the island for 30 s or more: touching the camera then brings nothing back. */
    var restoreStale by mutableStateOf(false)
    var lastPresentMs = 0L

    /** Something was swiped away or timed out, and can be brought back. */
    val hasHidden: Boolean
        get() = !restoreStale && (callReal && callDismissedKey == callKey) ||
                (timerKey.isNotEmpty() && timerDismissedKey == timerKey) ||
                (navKey.isNotEmpty() && navDismissedKey == navKey) ||
                (torchOn && torchDismissed) ||
                (mediaPackage.isNotEmpty() && mediaTitle.isNotEmpty() && (mediaHiddenTitle == mediaTitle || !mediaLive))

    /** Brings back everything that was swiped away (and paused music that had timed out). */
    fun restoreDismissed() {
        torchDismissed = false
        lastPresentMs = android.os.SystemClock.elapsedRealtime()
        restoreStale = false
        callDismissedKey = null
        timerDismissedKey = null
        navDismissedKey = null
        mediaHiddenTitle = ""
        if (mediaPackage.isNotEmpty() && mediaTitle.isNotEmpty()) mediaLive = true
        autoIdle = false
        val r = restingState()
        if (!state.isTall()) state = r
    }

    /** The user's switch (in the app) for the dots. On by default. */
    var privacyEnabled by mutableStateOf(true)

    /** How close (dp) the island may come to the screen's sides: 8 normally, 0 for a Custom position (like the top). */
    var sideGap = 8f

    /** The user's switch for the faint edge around a showing island. On by default. */
    var edgeOutline by mutableStateOf(true)
    /** True while everything just around the island is black (checked by the accessibility service from a screenshot). */
    var behindBlack by mutableStateOf(false)
    /** The edge only shows where it is needed: a black island on a black screen. */
    val edgeShown: Boolean get() = edgeOutline && behindBlack
    /** Where the visible island is on the screen, in pixels (null when nothing is showing). */
    @Volatile var islandOnScreen: android.graphics.Rect? = null
    var camActive by mutableStateOf(false)
    var micActive by mutableStateOf(false)
    /** Screen recording, and hotspot / tethering (from a broadcast, or from the phone's own notification). */
    var recActive by mutableStateOf(false)
    var recDebug by mutableStateOf("")
    /** The flashlight is on: shown as its own blue pill for as long as it stays on (swipe it away to hide it). */
    var torchOn by mutableStateOf(false)
    /** Every notification the Clock app has posted (for diagnosing a stopwatch or timer that does not show). */
    var clockDebug by mutableStateOf("")
    var torchDismissed by mutableStateOf(false)
    /** Set by the system monitor: switches the real flashlight off. */
    var torchSwitch: ((Boolean) -> Unit)? = null
    fun turnTorchOff() {
        runCatching { torchSwitch?.invoke(false) }
        torchOn = false
        torchDismissed = false
        state = restingState()
    }
    var hotspotAp by mutableStateOf(false)
    var hotspotNote by mutableStateOf(false)
    val hotspotActive: Boolean get() = hotspotAp || hotspotNote
    var statusPills by mutableStateOf(false)

    // Navigation (Google Maps).
    var navKey by mutableStateOf("")
    var navDismissedKey: String? = null
    var navReal = false
    var navTitle by mutableStateOf("")
    var navText by mutableStateOf("")
    var navSub by mutableStateOf("")
    var navMore by mutableStateOf("")
    var navDistance by mutableStateOf("")
    var navIcon by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var navActions by mutableStateOf<List<CallAction>>(emptyList())
    var navOpen: android.app.PendingIntent? = null
    var navDebug by mutableStateOf("")
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    fun demoNav() {
        navReal = false
        navKey = "demo"
        navDismissedKey = null
        navTitle = "Turn left onto Main St"
        navText = "Then turn right in 0.4 mi"
        navSub = "Arrive 3:47 PM"
        navMore = "14 min · 5.2 mi"
        navDistance = "800 ft"
        navIcon = null
        navActions = listOf(CallAction("End", false, null))
        state = IslandState.Nav
    }

    fun demoNavPress() {
        navKey = ""
        if (state == IslandState.Nav || state == IslandState.NavOpen) state = restingState()
    }

    /** Opens a pop-up's expanded view straight away (used by the demo buttons). 1 = Bluetooth device, 2 = battery, 3 = charging. */
    fun expandPopup(mode: Int) {
        autoIdle = false
        expandMode = mode
        state = IslandState.Device
        autoIdleMs = 4500L
        autoIdle = true
    }

    /** Shows a short pop-up. Returns false when something open (a call, an expanded island) is in the way. */
    fun alert(icon: String, text: String, color: Color, battery: Int = -1, expand: Int = 0): Boolean {
        val s = state
        val ok = s == IslandState.Idle || s == IslandState.Charging || s == IslandState.Music ||
                s == IslandState.Timer || s == IslandState.Call || s == IslandState.Nav || s == IslandState.Torch || s == IslandState.Alert ||
                s == IslandState.Device
        if (!ok) return false
        if (s != IslandState.Alert) alertReturn = s
        logClose("pop-up shown: $text")
        alertIcon = icon
        alertText = text
        alertColor = color
        alertBattery = battery
        // Touch and hold on a pop-up opens more: 1 = the Bluetooth device, 2 = the battery (any pop-up with a battery).
        alertExpand = if (expand != 0) expand else if (battery >= 0) 2 else 0
        alertKey++
        // Pop-ups that open into more stay a little longer, so there is time to touch and hold.
        autoIdleMs = if (alertExpand != 0) 4000L else 2500L
        autoIdle = true
        state = IslandState.Alert
        return true
    }

    /** The Bluetooth device pop-up (an expanded island that stays clear of the camera). */
    var deviceName by mutableStateOf("")
    var deviceConnected by mutableStateOf(true)
    var deviceBattery by mutableIntStateOf(-1)
    var deviceKey by mutableIntStateOf(0)

    /** Shows the Bluetooth device pop-up. Returns false when something open (a call, an expanded island) is in the way. */
    fun showDevice(name: String, connected: Boolean, battery: Int = -1): Boolean {
        val s = state
        val ok = s == IslandState.Idle || s == IslandState.Charging || s == IslandState.Music ||
                s == IslandState.Timer || s == IslandState.Call || s == IslandState.Nav || s == IslandState.Torch || s == IslandState.Alert ||
                s == IslandState.Device
        if (!ok) return false
        deviceName = name
        deviceConnected = connected
        deviceBattery = battery
        deviceKey++
        // It starts small, with no device name (long names end up behind the camera). Touch and hold shows the details.
        if (s == IslandState.Device) state = IslandState.Idle
        val shown = alert("headphones", if (connected) "Connected" else "Disconnected", if (connected) Color(0xFF30D158) else Color(0xFF8E8E93), -1, expand = 1)
        // Opens straight into the full view.
        if (shown) expandPopup(1)
        return shown
    }

    /** Earbuds report their battery a moment after connecting: add it to the pop-up if it is still showing. */
    fun deviceAddBattery(forKey: Int, level: Int) {
        if ((state != IslandState.Device && state != IslandState.Alert) || deviceKey != forKey || !deviceConnected) return
        deviceBattery = level
    }

    /** The battery level of a Bluetooth device arrives a moment after it connects: add it to its pop-up if still showing. */
    fun alertAddBattery(forKey: Int, level: Int) {
        if (state != IslandState.Alert || alertKey != forKey) return
        alertBattery = level
        alertKey++
        autoIdleMs = 2500L
        autoIdle = true
    }

    /** While split: the kind on the left (the camera pill) and on the right (the small pill), and where the right one is. */
    var splitPrimary: IslandState? = null
    var splitSecondary: IslandState? = null
    var splitBubbleStartDp = 0f
    var splitBubbleEndDp = 0f
    /** Music the user swiped away; it stays hidden until the song changes. */
    var mediaHiddenTitle = ""
    /** True while music is playing, or was paused only a moment ago (so its pill stays a while). */
    var mediaLive by mutableStateOf(false)

    fun onBubble(xDp: Float) = splitSecondary != null && xDp in splitBubbleStartDp..splitBubbleEndDp

    private fun kindAt(xDp: Float): IslandState? = if (splitSecondary != null) {
        if (onBubble(xDp)) splitSecondary else splitPrimary
    } else null

    private fun openOf(kind: IslandState): IslandState = when (kind) {
        IslandState.Call -> IslandState.CallOpen
        IslandState.Timer -> IslandState.TimerOpen
        IslandState.Music -> IslandState.MusicOpen
        IslandState.Nav -> IslandState.NavOpen
        IslandState.Torch -> IslandState.TorchOpen
        else -> state
    }

    /** Hold on the island: opens whichever of the two split pills was touched (each keeps its side). */
    fun toggleAt(xDp: Float) {
        val kind = kindAt(xDp)
        if (kind != null && !state.isTall()) {
            autoIdle = false
            state = openOf(kind)
        } else toggle()
    }

    /** Swipe up on the island: dismisses whichever of the two split pills was touched. */
    fun dismissAt(xDp: Float) {
        val kind = kindAt(xDp)
        if (kind == null || state.isTall()) {
            dismiss()
            return
        }
        when (kind) {
            IslandState.Call -> callDismissedKey = callKey
            IslandState.Timer -> timerDismissedKey = timerKey
            IslandState.Music -> mediaHiddenTitle = mediaTitle
            IslandState.Nav -> navDismissedKey = navKey
            IslandState.Torch -> torchDismissed = true
            else -> Unit
        }
        // If the dismissed one was the focused one, the other takes over; otherwise nothing else changes.
        val other = if (kind == splitPrimary) splitSecondary else splitPrimary
        if (kind == state.base() && other != null) state = other
        splitPrimary = null
        splitSecondary = null
    }

    var callPackage = ""
    var timerPackage = ""

    private fun dialerish(p: String) = listOf("incallui", "dialer", "telecom").any { p.contains(it) }
    fun isPhoneApp(p: String) = dialerish(p)

    /** The notification's own Mute button, for call apps that have one (WhatsApp, say). */
    var callMuteIntent: android.app.PendingIntent? = null

    /** True while the island's Mute has the system microphone off, so it can be turned back on when the call ends. */
    var micMutedByIsland = false

    /**
     * Answer / hang up. For the phone app the system call is used directly (needs the "answer calls" permission), because
     * the phone app's own button only opens the call screen. Otherwise (or without the permission) the button's own
     * intent is sent.
     */
    fun pressCallAction(a: CallAction) {
        val ctx = appContext
        if (ctx != null && isPhoneApp(callPackage)) {
            val tm = ctx.getSystemService(android.telecom.TelecomManager::class.java)
            val done = runCatching {
                if (a.accept) { tm.acceptRingingCall(); true } else tm.endCall()
            }.getOrDefault(false)
            if (done) return
        }
        a.intent?.let { sendPI(it) }
    }

    /** True while you are inside the app a pill belongs to (the music app, Maps, Clock, the phone app): it stays out of the way. */
    fun hiddenByApp(kind: IslandState): Boolean {
        // Never two music players at once: while the lock screen one is showing, the island stays out of its way.
        if (kind == IslandState.Music && lockPlayerShowing) return true
        val fg = foregroundPackage
        if (fg.isEmpty()) return false
        return when (kind) {
            IslandState.Music -> mediaPackage.isNotEmpty() && fg == mediaPackage
            IslandState.Nav -> fg == MAPS_PACKAGE
            IslandState.Timer -> fg == timerPackage || fg == "com.sec.android.app.clockpackage" ||
                    fg == "com.google.android.deskclock" || fg == "com.android.deskclock"
            IslandState.Call -> (callPackage.isNotEmpty() && fg == callPackage) || (dialerish(fg) && dialerish(callPackage))
            else -> false
        }
    }

    /** Persistent activities that are going, highest priority first: Navigation > Call > Music > Timer > Flashlight. */
    fun activeList(): List<IslandState> = listOf(
        IslandState.Nav to (navKey.isNotEmpty() && navDismissedKey != navKey),
        IslandState.Call to (callReal && callDismissedKey != callKey),
        IslandState.Music to (mediaLive && mediaTitle.isNotEmpty() && mediaHiddenTitle != mediaTitle),
        IslandState.Timer to (timerKey.isNotEmpty() && timerDismissedKey != timerKey),
        IslandState.Torch to (torchOn && !torchDismissed),
    ).filter { it.second && !hiddenByApp(it.first) }.map { it.first }

    /** What the island rests on once a short pop-up (charging, an alert) is over. */
    fun restingState(): IslandState = activeList().firstOrNull() ?: IslandState.Idle

    /** Another activity going besides [base] (highest priority first), or null. */
    fun otherActive(base: IslandState): IslandState? = activeList().firstOrNull { it != base }

    /** Two activities side by side as (left, right): fixed priority, the higher one on the left. */
    fun splitPair(focus: IslandState): Pair<IslandState, IslandState>? {
        val base = when (focus) {
            IslandState.Call, IslandState.CallOpen -> IslandState.Call
            IslandState.Timer, IslandState.TimerOpen -> IslandState.Timer
            IslandState.Music, IslandState.MusicOpen -> IslandState.Music
            IslandState.Nav, IslandState.NavOpen -> IslandState.Nav
            IslandState.Torch, IslandState.TorchOpen -> IslandState.Torch
            else -> return null
        }
        val order = listOf(IslandState.Nav, IslandState.Call, IslandState.Music, IslandState.Timer, IslandState.Torch)
        var list = activeList()
        if (base !in list) list = (list + base).sortedBy { order.indexOf(it) }
        if (list.size < 2) return null
        var two = list.take(2)
        if (base !in two) two = listOf(two[0], base)
        return two[0] to two[1]
    }

    /** What a single tap on a compact island opens (set by the monitors; null when there is nothing to open). */
    var mediaOpen: android.app.PendingIntent? = null
    var callOpen: android.app.PendingIntent? = null
    var timerOpen: android.app.PendingIntent? = null
    var appContext: android.content.Context? = null

    /** A quick tap on a compact island opens the app behind that part of it: the music app, the phone app, the Clock. */
    /** Opens the app the music is coming from (on the lock screen Android asks to unlock first). */
    fun openMusicApp() {
        val ctx = appContext
        val locked = ctx?.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (ctx != null && locked) {
            // Locked: ask to unlock first, then open it (see UnlockOpenActivity).
            if (runCatching { UnlockOpenActivity.start(ctx) }.isSuccess) return
        }
        openMusicAppNow()
    }

    fun openMusicAppNow() {
        val ok = mediaOpen?.let { sendPI(it) } ?: false
        if (!ok) {
            val ctx = appContext
            val launch = ctx?.packageManager?.getLaunchIntentForPackage(mediaPackage)
            if (ctx != null && launch != null) {
                runCatching { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    fun openAppAt(xDp: Float) {
        if (state.isTall()) return
        val kind = (kindAt(xDp) ?: state).base()
        tapDebug = "tap on $kind: ctx=${appContext != null} timerIntent=${timerOpen != null} callIntent=${callOpen != null} mediaIntent=${mediaOpen != null}"
        when (kind) {
            IslandState.Music -> openMusicApp()
            IslandState.Call -> {
                val ok = callOpen?.let { sendPI(it) } ?: false
                if (!ok) {
                    // No notification intent: bring up the in-call screen (or the phone app) instead.
                    val ctx = appContext
                    if (ctx != null) {
                        val shown = runCatching {
                            (ctx.getSystemService(android.content.Context.TELECOM_SERVICE) as android.telecom.TelecomManager)
                                .showInCallScreen(false)
                        }.isSuccess
                        if (!shown) {
                            ctx.packageManager.getLaunchIntentForPackage("com.samsung.android.dialer")?.let {
                                runCatching { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }
                    }
                }
            }
            IslandState.Nav -> {
                val ok = navOpen?.let { sendPI(it) } ?: false
                val ctx = appContext
                if (!ok && ctx != null) {
                    ctx.packageManager.getLaunchIntentForPackage(MAPS_PACKAGE)?.let {
                        runCatching { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
            IslandState.Timer -> openClock()
            else -> Unit
        }
    }

    /**
     * Sends a notification's PendingIntent. Newer Android blocks an app that is not in front from starting another app's
     * screen unless it says so, which is why a plain send() reported success and nothing opened.
     */
    fun sendPI(pi: android.app.PendingIntent): Boolean = runCatching {
        val opts = if (android.os.Build.VERSION.SDK_INT >= 34) {
            android.app.ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        pi.send(appContext, 0, null, null, null, null, opts)
    }.isSuccess

    /** Opens the Clock app (the notification's own intent first, then the Clock app itself). */
    fun openClock() {
        val sent = timerOpen?.let { sendPI(it) }
        if (sent == true) { tapDebug += " | notification intent sent (background launch allowed)"; return }
        tapDebug += " | notification intent: ${if (sent == null) "none" else "failed"}"
        val ctx = appContext
        if (ctx == null) { tapDebug += " | no context"; return }
        val pm = ctx.packageManager
        val launch = listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock")
            .firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        if (launch == null) { tapDebug += " | no Clock app found"; return }
        val r = runCatching { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        tapDebug += " | launch ${launch.component?.packageName}: ${if (r.isSuccess) "started" else r.exceptionOrNull()?.javaClass?.simpleName}"
    }

    /** Opens a compact island, or closes an open one. */
    fun toggle() {
        logClose("hold registered (state was $state, expandable=$alertExpand)")
        autoIdle = false
        when {
            // A pop-up: hold to see more (the Bluetooth device, the battery).
            state == IslandState.Alert && alertExpand != 0 -> {
                expandMode = alertExpand
                state = IslandState.Device
                autoIdleMs = 4500L
                autoIdle = true
            }
            // Hold again to close it.
            state == IslandState.Device -> state = restingState()
            else -> state = state.tapped()
        }
    }

    /** Swiped away. A real call or timer stays hidden for the rest of that call or timer. */
    fun dismiss() {
        logClose("closed by a swipe (state was $state)")
        if (state == IslandState.Call || state == IslandState.CallOpen) callDismissedKey = callKey
        if (state == IslandState.Timer || state == IslandState.TimerOpen) timerDismissedKey = timerKey
        if (state == IslandState.Music || state == IslandState.MusicOpen) mediaHiddenTitle = mediaTitle
        if (state == IslandState.Nav || state == IslandState.NavOpen) navDismissedKey = navKey
        if (state == IslandState.Torch || state == IslandState.TorchOpen) torchDismissed = true
        state = IslandState.Idle
    }

    /** A touch landed anywhere outside the island: go idle. */
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** When a finger last went down on the island itself. */
    var lastIslandDownMs = 0L

    /**
     * Some other window of ours reported a touch "outside" it. When a finger lands on the island, the island's own window
     * gets the touch while the others (the camera pad, the main window...) are told the touch was outside them, at the
     * same moment. So this waits a beat: if a finger went down on the island at about that time, it was not outside.
     */
    fun outsideTouch() {
        val t = android.os.SystemClock.elapsedRealtime()
        uiHandler.postDelayed({
            if (lastIslandDownMs >= t - 150) logClose("ignored an outside signal: the touch was on the island")
            else outsideTouchNow()
        }, 70)
    }

    private fun outsideTouchNow() {
        logClose("closed by a touch outside the island (state was $state)")
        autoIdle = false
        when (state) {
            IslandState.Idle -> Unit
            // Music and calls stay until you swipe them away. An outside touch only closes the expanded player.
            IslandState.Music, IslandState.Call, IslandState.Timer, IslandState.Nav, IslandState.Torch -> Unit
            IslandState.NavOpen -> state = IslandState.Nav
            IslandState.TorchOpen -> state = IslandState.Torch
            // An open timer closes to the compact timer, unless it has finished (you need the Stop button).
            IslandState.TimerOpen -> if (timerRemainingMs() > 0 || timerPaused || timerStopwatch) state = IslandState.Timer
            // An open call closes to the compact call, except while it is still ringing (you need the buttons).
            IslandState.CallOpen -> if (!callIncoming) state = IslandState.Call
            IslandState.MusicOpen -> state = IslandState.Music
            IslandState.Alert, IslandState.Device -> state = restingState()
            else -> state = IslandState.Idle
        }
    }
}

/** The one shrink feel used everywhere (split or single): width has no overshoot, height a tiny bounce. */
private val SHRINK_W_SPRING = spring<Float>(dampingRatio = 1f, stiffness = 380f)
private val SHRINK_H_SPRING = spring<Float>(dampingRatio = 0.8f, stiffness = 340f)

/** How long a shrunken island waits for a touch before going idle. */
const val IDLE_AFTER_MS = 1500L

/**
 * The camera hole as a circle: [centerX] is measured from the screen's left edge, [centerY] from the screen's top,
 * [diameter] is the visible hole. [real] is false when Android reports no cutout.
 */
data class Hole(val centerX: Dp, val centerY: Dp, val diameter: Dp, val real: Boolean, val screenW: Dp = 0.dp)

/** The island's size, never wider than the screen (with a little room at each side). */
/**
 * How far an expanded island sits from the top of the screen (it hangs from the camera like a compact one). It keeps
 * the same distance from the sides, so it sits evenly inside the screen's corners.
 */
internal fun Hole.edgeGap(): Float = (centerY.value - IslandBus.compactH / 2f).coerceAtLeast(0f)

/** The range of the island height setting (dp). */
const val ISLAND_H_MIN = 28f
const val ISLAND_H_MAX = 56f

/** The widest an expanded island gets, for screens much wider than a phone's. */
private const val OPEN_MAX_W = 480f

private fun IslandState.dims(hole: Hole): DpSize {
    var d = rawDims(hole)
    // An expanded island fills the screen's width less the same gap it has at the top.
    if (isTall() && hole.screenW.value > 0f) {
        return DpSize(minOf(hole.screenW.value - 2 * hole.edgeGap(), OPEN_MAX_W).dp, d.height)
    }
    // The camera's dots take room next to the camera: a compact island grows by that much.
    if (this != IslandState.Idle && !isTall()) d = DpSize(d.width + IslandBus.dotsReach.dp, d.height)
    return if (hole.screenW.value > 0f && this != IslandState.Idle) DpSize(minOf(d.width, hole.screenW - (IslandBus.sideGap * 2).dp), d.height) else d
}

/** Roughly how wide one character of the compact pills' 14 sp text is, in dp. */
private const val PILL_CHAR_DP = 8.6f

/**
 * The width a compact pill needs when the camera is near a screen end and [content] all sits on the roomy side,
 * or [full] when the camera is not near an end. Never wider than [full].
 */
private fun hugWidth(hole: Hole, content: Dp, full: Dp): Dp {
    val edge = minOf(hole.centerX.value, hole.screenW.value - hole.centerX.value)
    if (hole.screenW.value <= 0f || edge >= 60f) return full
    // The pill reaches past the camera towards the screen edge (it stops 8 dp short), and always covers the camera.
    val pastCamera = maxOf(edge - IslandBus.sideGap, IslandBus.camGap.value / 2 - 4f)
    return (pastCamera.dp + IslandBus.camGap / 2 + content + 14.dp).coerceAtMost(full)
}

private fun IslandState.rawDims(hole: Hole): DpSize = when (this) {
    IslandState.Idle -> {
        // The idle circle also follows the island height setting (36 dp is the standard size).
        val d = if (hole.real) hole.diameter * IDLE_SCALE * (COMPACT.value / 36f) else 2.dp
        DpSize(d, d)
    }
    // Battery icon, a space, and "100%".
    IslandState.Charging -> DpSize(hugWidth(hole, 74.dp, 190.dp), COMPACT)
    // Timer icon, a space, and the time.
    IslandState.Timer -> DpSize(hugWidth(hole, (26f + IslandBus.timerChars * PILL_CHAR_DP).dp, 190.dp), COMPACT)
    IslandState.Music -> {
        // With the camera near a screen end both pieces sit on the one roomy side, so hug them instead of leaving a gap.
        val nearEnd = hole.screenW.value > 0f &&
                minOf(hole.centerX.value, hole.screenW.value - hole.centerX.value) < 60f
        DpSize(if (nearEnd) (78.dp + IslandBus.camGap).coerceAtMost(140.dp) else 140.dp, COMPACT)
    }
    IslandState.MusicOpen -> {
        // Halfway between the old fixed width (340 dp) and almost the full screen width.
        val wide = (hole.screenW - 28.dp).coerceIn(340.dp, 480.dp)
        DpSize((340.dp + wide) / 2, 164.dp)
    }
    IslandState.ChargingOpen -> DpSize(350.dp, 84.dp)
    IslandState.TimerOpen -> DpSize(340.dp, 104.dp)
    IslandState.Call -> {
        // Phone icon, the words or time, a space, and the waveform. "Incoming" and "On call" are longer than a short time.
        val chars = if (IslandBus.callIncoming || IslandBus.callStartMs <= 0L) 8f else 4f + IslandBus.callExtra / 17f
        val full = 190.dp + IslandBus.callExtra.dp
        DpSize(hugWidth(hole, (86f + chars * PILL_CHAR_DP).dp, full), COMPACT)
    }
    // As wide as the message needs, with the camera's gap kept clear in the middle (the text lives on the right half).
    IslandState.Alert -> DpSize((IslandBus.camGap + (IslandBus.alertText.length * 7 + 30).dp * 2).coerceIn(172.dp, 320.dp), COMPACT)
    // The distance sits on one side of the camera, so the pill grows (both sides alike) when it is long.
    IslandState.Nav -> {
        val text = IslandBus.navDistance.ifEmpty { IslandBus.navTitle.take(9) }
        DpSize((IslandBus.camGap + ((text.length * PILL_CHAR_DP) + 28f).dp * 2).coerceIn(190.dp, 300.dp), COMPACT)
    }
    // Tall enough for the big distance, up to three lines of instruction, the trip summary and the Exit button.
    IslandState.NavOpen -> DpSize(350.dp, 196.dp)
    // Hugs the content: the camera's gap in the middle plus just enough on each side, not the full width.
    IslandState.Torch -> DpSize(172.dp, COMPACT)
    IslandState.TorchOpen -> DpSize(350.dp, 84.dp)
    IslandState.Device -> DpSize(350.dp, 84.dp)
    IslandState.CallOpen -> DpSize(360.dp, 165.dp)
}

// ---------------------------------------------------------------------------------------------
// Root: what the overlay window shows
// ---------------------------------------------------------------------------------------------

/**
 * [xShift] is how far the camera is from the horizontal middle of the screen. The overlay window is centred on the
 * screen, so the island is shifted by that much.
 */
@Composable
fun IslandRoot(
    hole: Hole,
    xShift: Dp,
    onRestoreWanted: (Boolean) -> Unit = {},
    onIslandBounds: (Rect) -> Unit = {},
    onLayoutWanted: (touchable: Boolean, tall: Boolean) -> Unit,
) {
    val view = LocalView.current
    // While you are inside the app the music comes from (Spotify, say), the player stays out of the way. It is only
    // hidden from view: it comes back by itself when you leave that app.
    val state = IslandBus.state.let {
        if (IslandBus.hiddenByApp(it.base())) IslandState.Idle else it
    }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        onLayoutWanted(state != IslandState.Idle, state.isTall())
        if (first) first = false
        else view.performHapticFeedback(
            if (state == IslandState.Idle) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.CONFIRM,
        )
    }
    // After an outside touch shrinks the island, give it a moment, then go idle (unless it was touched again).
    // The countdown does not run while a finger is on the island, so a pop-up cannot vanish under a touch and hold.
    LaunchedEffect(state, IslandBus.autoIdle, IslandBus.alertKey, IslandBus.pressed) {
        if (IslandBus.autoIdle && !IslandBus.pressed) {
            if (state == IslandState.Charging || state == IslandState.Timer || state == IslandState.Alert || state == IslandState.Device) {
                delay(IslandBus.autoIdleMs)
                IslandBus.logClose("timer ran out (state was $state)")
                // Short pop-ups hand the island back to the highest-priority persistent activity (or idle).
                IslandBus.state = IslandBus.restingState()
            }
            IslandBus.autoIdle = false
            IslandBus.autoIdleMs = IDLE_AFTER_MS
        }
    }
    // Two things going at once (music and a timer, say) show as two pills, one on each side of the camera.
    val stateNow by rememberUpdatedState(state)
    var split by remember { mutableStateOf<Pair<IslandState, IslandState>?>(null) }
    // Look again right away whenever anything that decides the layout changes (not just on the 0.4 s timer).
    LaunchedEffect(state, IslandBus.mediaPlaying, IslandBus.mediaLive, IslandBus.mediaTitle, IslandBus.timerKnown, IslandBus.callStartMs) {
        split = IslandBus.splitPair(state)
    }
    // A small touch area over the camera, only while something is hidden: touch it to get the island back.
    LaunchedEffect(state, IslandBus.hasHidden) {
        onRestoreWanted(state == IslandState.Idle && IslandBus.hasHidden)
    }
    LaunchedEffect(Unit) {
        var navWasHidden = false
        var timerWasHidden = false
        while (true) {
            // Leaving Maps: the navigation island appears now (it was kept out of sight the whole time).
            val navHidden = IslandBus.hiddenByApp(IslandState.Nav)
            if (navWasHidden && !navHidden && IslandBus.activeList().contains(IslandState.Nav) && !IslandBus.state.isTall()) {
                IslandBus.state = IslandState.Nav
            }
            navWasHidden = navHidden
            // Leaving the Clock: a stopwatch's notification never updates by itself, so nothing else would bring it up.
            val timerHidden = IslandBus.hiddenByApp(IslandState.Timer)
            if (timerWasHidden && !timerHidden && IslandBus.state == IslandState.Idle && IslandBus.activeList().contains(IslandState.Timer)) {
                IslandBus.autoIdle = false
                IslandBus.state = IslandState.Timer
            }
            timerWasHidden = timerHidden
            // Inside the app of the pill that is showing: if something else is going, that takes the island.
            val st = IslandBus.state
            if (IslandBus.hiddenByApp(st.base())) {
                val r = IslandBus.restingState()
                if (r != IslandState.Idle) IslandBus.state = r
            }
            split = IslandBus.splitPair(stateNow)
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (IslandBus.state != IslandState.Idle || IslandBus.activeList().isNotEmpty() || IslandBus.lastPresentMs == 0L) {
                IslandBus.lastPresentMs = nowMs
                IslandBus.restoreStale = false
            } else if (nowMs - IslandBus.lastPresentMs > 30_000) {
                IslandBus.restoreStale = true
            }
            delay(400)
        }
    }
    var islandRect by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.fillMaxSize()) {
        // While expanded, a full-window layer behind the island catches touches anywhere else in the window.
        if (state != IslandState.Idle) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Only a touch that is not on the island itself counts as "outside".
                        if (!islandRect.contains(down.position)) IslandBus.outsideTouch()
                    }
                },
            )
        }
        Island(
            state = state,
            split = split?.second,
            splitLeft = split?.first,
            hole = hole,
            xShift = xShift,
            outline = IslandBus.showOutline,
            onTap = {
                IslandBus.autoIdle = false
                IslandBus.state = IslandBus.state.tapped()
            },
            onDismiss = {
                // Swiping a real call away hides it for the rest of that call.
                if (IslandBus.state == IslandState.Call || IslandBus.state == IslandState.CallOpen) {
                    IslandBus.callDismissedKey = IslandBus.callKey
                }
                if (IslandBus.state == IslandState.Nav || IslandBus.state == IslandState.NavOpen) {
                    IslandBus.navDismissedKey = IslandBus.navKey
                }
                if (IslandBus.state == IslandState.Timer || IslandBus.state == IslandState.TimerOpen) {
                    IslandBus.timerDismissedKey = IslandBus.timerKey
                }
                IslandBus.state = IslandState.Idle
            },
            onBounds = { islandRect = it; onIslandBounds(it) },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The island itself
// ---------------------------------------------------------------------------------------------

/**
 * Hold ~450 ms to open or close the island (it fires while the finger is still down), or swipe up, or in from a side towards the camera, to dismiss it. A
 * finger may wobble up to 20 dp during the hold. A quick tap does nothing.
 */
internal fun Modifier.islandGestures(centre: () -> Float = { 0f }): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // A button (or the scrub bar) inside the island already took this touch: only that button reacts, not the island.
        // Which side of the camera was touched, in dp from the camera's centre (the touch area is centred on it).
        val xDp = (down.position.x - size.width / 2f) / density + centre()
        IslandBus.lastIslandDownMs = android.os.SystemClock.elapsedRealtime()
        if (down.isConsumed) {
            // On a button: the button does most of the reacting, and the island only a little.
            IslandBus.pressedBubble = IslandBus.onBubble(xDp)
            IslandBus.pressedOnButton = true
            IslandBus.pressed = true
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.all { !it.pressed }) break
                }
            } finally {
                IslandBus.pressed = false
                IslandBus.pressedOnButton = false
                IslandBus.pressedBubble = false
            }
            return@awaitEachGesture
        }
        IslandBus.pressedBubble = IslandBus.onBubble(xDp)
        IslandBus.pressed = true
        try {
            val wobble = 20.dp.toPx()
            val swipe = 24.dp.toPx()
            val inward = 14.dp.toPx()
            // 0 = let go early or wandered off, 1 = swiped up, null = the hold time ran out.
            val early = withTimeoutOrNull(HOLD_MS) {
                var out = 0
                while (true) {
                    val event = awaitPointerEvent()
                    val c = event.changes.firstOrNull { it.id == down.id }
                    if (c == null || c.changedToUp() || !c.pressed) {
                        // Let go in time without moving: a tap.
                        if (c != null && c.changedToUp()) out = 2
                        break
                    }
                    val dy = c.position.y - down.position.y
                    val dx = c.position.x - down.position.x
                    if (dy < -swipe) { out = 1; break }
                    // Swiping in from a side of the compact island towards the camera dismisses that part as well.
                    // Starting right over the camera does not count: there is no side to swipe in from.
                    if (!IslandBus.state.isTall() && abs(xDp) > 10f && abs(dx) > inward && abs(dx) > abs(dy) * 1.5f &&
                        (if (xDp < 0f) dx > 0f else dx < 0f)
                    ) { out = 1; break }
                    if (abs(dx) > wobble || abs(dy) > wobble) break
                }
                out
            }
            when (early) {
                null -> {
                    IslandBus.toggleAt(xDp)
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.all { !it.pressed }) break
                    }
                }
                1 -> {
                    IslandBus.dismissAt(xDp)
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.all { !it.pressed }) break
                    }
                }
                2 -> IslandBus.openAppAt(xDp)
                else -> Unit
            }
        } finally {
            IslandBus.pressed = false
            IslandBus.pressedBubble = false
        }
    }
}

/**
 * A row laid out around the camera, wherever it is: [left] goes on the camera's left, [right] on its right, with the
 * camera's gap kept clear between them. When the camera is too near one end for that, both go on the roomy side.
 * (With the camera in the middle this is the same as a row with the two at the ends.)
 */
@Composable
internal fun CamRow(
    modifier: Modifier = Modifier,
    pad: Dp = 14.dp,
    gap: Dp = IslandBus.camGap,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val total = maxWidth
        val cx = maxWidth / 2 - IslandBus.pillCentre.dp          // the camera, from this row's left edge
        // The camera's status dots are part of the camera: they take room on one side.
        val dots = IslandBus.dotsReach.dp
        val leftAvail = (cx - gap / 2 - (if (IslandBus.dotsOnLeft) dots else 0.dp) - pad).coerceAtLeast(0.dp)
        val rightAvail = (maxWidth - cx - gap / 2 - (if (IslandBus.dotsOnLeft) 0.dp else dots) - pad).coerceAtLeast(0.dp)
        val small = minOf(leftAvail, rightAvail)
        val big = maxOf(leftAvail, rightAvail)
        when {
            small >= big * 0.5f -> Row(Modifier.fillMaxSize().padding(start = pad), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(leftAvail), contentAlignment = Alignment.CenterStart) { left() }
                Spacer(Modifier.width((total - pad * 2 - leftAvail - rightAvail).coerceAtLeast(0.dp)))
                Box(Modifier.width(rightAvail), contentAlignment = Alignment.CenterEnd) { right() }
            }
            rightAvail >= leftAvail -> Row(
                Modifier.fillMaxSize().padding(start = (cx + gap / 2 + (if (IslandBus.dotsOnLeft) 0.dp else dots)).coerceAtLeast(0.dp), end = pad),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) { left(); right() }
            else -> Row(
                Modifier.fillMaxSize().padding(start = pad, end = (maxWidth - cx + gap / 2 + (if (IslandBus.dotsOnLeft) dots else 0.dp)).coerceAtLeast(0.dp)),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) { left(); right() }
        }
    }
}

/** An invisible touch area. While the island is compact it lives in its own small window (see IslandOverlay). */
/**
 * The lock screen music player, like the iPhone's: a card near the bottom of the lock screen with the cover, the song,
 * the progress bar and the play / skip buttons. Only there while the phone is locked and some music is open.
 */
@Composable
fun LockPlayerRoot(onTouchable: (Boolean, Boolean) -> Unit) {
    val view = LocalView.current
    val keyguard = remember { view.context.getSystemService(android.app.KeyguardManager::class.java) }
    var locked by remember { mutableStateOf(false) }
    var eligible by remember { mutableStateOf(false) }
    // This loop does not need the screen to be on, so what the player should be is already worked out when it wakes.
    // It only appears if music is playing while the phone is locked, and goes away 30 s after the music stops.
    LaunchedEffect(Unit) {
        var pausedSince = 0L
        while (true) {
            val l = runCatching { keyguard.isKeyguardLocked }.getOrDefault(false)
            locked = l
            val now = android.os.SystemClock.elapsedRealtime()
            if (!l) {
                eligible = false
                pausedSince = 0L
            } else if (IslandBus.mediaPlaying) {
                eligible = true
                pausedSince = 0L
            } else if (eligible) {
                if (pausedSince == 0L) pausedSince = now else if (now - pausedSince > 30_000) eligible = false
            }
            IslandBus.lockPlayerShowing = l && eligible && IslandBus.lockPlayer &&
                    IslandBus.mediaPackage.isNotEmpty() && IslandBus.mediaTitle.isNotEmpty()
            delay(60)
        }
    }
    // With the shade open the window itself slides off the bottom edge (see IslandOverlay), so the card stays solid.
    val want = IslandBus.lockPlayerShowing && !IslandBus.lockShadeOpen
    val wantDrawn = IslandBus.lockPlayerShowing
    // Slides in gently, but leaves almost at once when you unlock. While the screen is off, and for a moment after it
    // comes on, it is simply there or not: no sliding in at the moment you wake the phone.
    val animated by animateFloatAsState(if (wantDrawn) 1f else 0f, tween(if (wantDrawn) 300 else 90), label = "lock player")
    val shown = if (IslandBus.wakeSnap) (if (wantDrawn) 1f else 0f) else animated

    // Big or small, and remembered: it stays the way you left it when the phone locks and unlocks (and after a restart).
    val prefs = remember { view.context.getSharedPreferences("island", android.content.Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) { IslandBus.lockMinimized = prefs.getBoolean("lock_minimized", false) }
    fun setMinimized(v: Boolean) {
        IslandBus.lockMinimized = v
        prefs.edit().putBoolean("lock_minimized", v).apply()
    }
    val minimized = IslandBus.lockMinimized
    // The window itself grows and shrinks (the overlay animates it), so the card simply fills it.
    LaunchedEffect(want, minimized) { onTouchable(want, minimized) }
    // While it is being moved the rim lights up (growing it would be cut off by its own window).
    val held by animateFloatAsState(if (IslandBus.lockDragging) 1f else 0f, tween(160), label = "lock held")
    // The glow takes its colour from the album art, and fades to the next song's colour.
    val artColor = remember(IslandBus.mediaArt) { artGlowColor(IslandBus.mediaArt) }
    val glowColor by androidx.compose.animation.animateColorAsState(artColor, tween(700), label = "lock glow")
    if (shown > 0.01f) {
        Box(Modifier.fillMaxSize()) {
            var drag = 0f
            // Fully rounded ends on the small pill, rounded corners on the big player, following the window's size
            // as it changes (a plain shape, so nothing has to be measured twice while it animates).
            val shape = remember {
                object : androidx.compose.ui.graphics.Shape {
                    override fun createOutline(
                        size: androidx.compose.ui.geometry.Size,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        density: androidx.compose.ui.unit.Density,
                    ): androidx.compose.ui.graphics.Outline {
                        val r = minOf(36.dp.value * density.density, size.height / 2f)
                        return androidx.compose.ui.graphics.Outline.Rounded(
                            androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(r, r)),
                        )
                    }
                }
            }
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha = shown
                        translationY = (1f - shown) * 40.dp.toPx()
                    }
                    .clip(shape)
                    // Black like the island, with a soft glow of the album's colour spilling in from the top left.
                    .drawBehind {
                        drawRect(Color.Black)
                        drawRect(
                            Brush.radialGradient(
                                0f to glowColor.copy(alpha = 0.80f),
                                0.55f to glowColor.copy(alpha = 0.22f),
                                1f to Color.Transparent,
                                center = Offset(size.width * 0.12f, 0f),
                                radius = size.width * 1.05f,
                            ),
                        )
                    }
                    .border(1.dp + (1.5f * held).dp, Color.White.copy(alpha = 0.10f + 0.40f * held), shape)
                    // Swipe down to shrink, swipe up to open it again.
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = {
                                if (drag > 40f) setMinimized(true) else if (drag < -40f) setMinimized(false)
                            },
                        ) { _, dy -> drag += dy }
                    },
            ) {
                // Each version keeps its own full size and is simply revealed or cut off by the shrinking window.
                // The old content fades out quickly while the window moves, then the new one fades in.
                // It fills the whole card, and does not animate its own size: otherwise it starts small in the top left
                // corner of the growing window and the content seems to fly in from there.
                androidx.compose.animation.AnimatedContent(
                    minimized,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        (fadeIn(tween(170, delayMillis = 110)) togetherWith fadeOut(tween(110)))
                            .using(SizeTransform(clip = false) { _, _ -> snap() })
                    },
                    contentAlignment = Alignment.Center,
                    label = "lock content",
                ) { small ->
                    if (small) {
                        Box(Modifier.requiredSize(IslandBus.LOCK_MIN_W_DP.dp, IslandBus.LOCK_MIN_H_DP.dp)) {
                            LockMusicMini { setMinimized(false) }
                        }
                    } else {
                        Box(Modifier.requiredSize(IslandBus.lockFullWidthDp.dp, IslandBus.LOCK_BIG_H_DP.dp)) {
                            LockMusicCard { setMinimized(true) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Faint dotted guide lines, drawn only while the lock screen player is being moved: one down the middle of the screen
 * and one where its bottom edge sits at its usual height. A line brightens while the player clings to it.
 */
@Composable
fun LockGuides(homeYDp: Float) {
    val show by animateFloatAsState(if (IslandBus.lockDragging) 1f else 0f, tween(180), label = "guides")
    val nearX by animateFloatAsState(if (IslandBus.lockSnapX) 1f else 0f, tween(120), label = "guide x")
    val nearY by animateFloatAsState(if (IslandBus.lockSnapY) 1f else 0f, tween(120), label = "guide y")
    // The horizontal line runs through the middle of the player, whether it is big or small.
    val half by animateFloatAsState(
        (if (IslandBus.lockMinimized) IslandBus.LOCK_MIN_H_DP else IslandBus.LOCK_BIG_H_DP) / 2f, tween(300), label = "guide half",
    )
    if (show < 0.01f) return
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 9.dp.toPx()))
        val x = size.width / 2f
        val y = size.height - (homeYDp + half).dp.toPx()
        drawLine(
            Color.White.copy(alpha = (0.22f + 0.55f * nearX) * show), Offset(x, 0f), Offset(x, size.height),
            strokeWidth = (1.5f + nearX).dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, pathEffect = dash,
        )
        drawLine(
            Color.White.copy(alpha = (0.22f + 0.55f * nearY) * show), Offset(0f, y), Offset(size.width, y),
            strokeWidth = (1.5f + nearY).dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, pathEffect = dash,
        )
    }
}

/** Touch area over the camera while something is hidden: tap, hold or swipe down brings the island back. */
@Composable
fun RestorePad() {
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { IslandBus.restoreDismissed() }, onLongPress = { IslandBus.restoreDismissed() })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, drag -> if (drag > 4f) IslandBus.restoreDismissed() }
            },
    )
}

@Composable
fun IslandTouchPad() {
    Box(Modifier.fillMaxSize().islandGestures { IslandBus.padCentre })
}

/** A button's own press reaction: it shrinks a little while a finger is on it, and springs back. */
@Composable
internal fun Modifier.pressScale(source: androidx.compose.foundation.interaction.InteractionSource, to: Float = 1f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) to else 1f,
        spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "button press",
    )
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

private fun IslandState.base(): IslandState = when (this) {
    IslandState.Call, IslandState.CallOpen -> IslandState.Call
    IslandState.Timer, IslandState.TimerOpen -> IslandState.Timer
    IslandState.Music, IslandState.MusicOpen -> IslandState.Music
    IslandState.Nav, IslandState.NavOpen -> IslandState.Nav
    IslandState.Torch, IslandState.TorchOpen -> IslandState.Torch
    else -> this
}

/** What the island looked like the last time its shape changed (used to know which part is being opened). */
private class IslandPrev {
    var splitOn = false
    var secondary: IslandState? = null
    var main: IslandState = IslandState.Idle
    /** Set while an island that was opened from the small right pill is open: closing it returns to that pill. */
    var openFromBubble = false
    var openBase: IslandState? = null
}

@Composable
fun Island(
    state: IslandState,
    split: IslandState? = null,
    splitLeft: IslandState? = null,
    hole: Hole,
    xShift: Dp,
    outline: Boolean,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onBounds: (Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Split: the main pill hugs the camera (its content on the left, the camera at its right end), and the second
    // activity is a small separate pill on the right. So it reads as two items, not three.
    val camD = if (hole.real) hole.diameter else 24.dp
    IslandBus.camGap = camD + 16.dp
    val camX = hole.centerX.value
    val screenWv = hole.screenW.value
    // A camera near the right edge: the dots grow out to its left instead.
    val dotsLeft = screenWv > 0f && screenWv - camX < 70f
    // Camera / microphone dots. They stay a moment after the app stops, so a quick flicker is still readable.
    val camShow = IslandBus.camActive && IslandBus.privacyEnabled
    val micShow = IslandBus.micActive && IslandBus.privacyEnabled
    val recShow = IslandBus.recActive && IslandBus.statusPills
    val hotShow = IslandBus.hotspotActive && IslandBus.statusPills
    // They start out the way things are right now, so a rebuilt island has its dots at once (nothing fades or grows in).
    var privCam by remember { mutableStateOf(camShow) }
    var privMic by remember { mutableStateOf(micShow) }
    var privRec by remember { mutableStateOf(recShow) }
    var privHot by remember { mutableStateOf(hotShow) }
    LaunchedEffect(recShow) { if (recShow) privRec = true else { delay(600); privRec = false } }
    LaunchedEffect(hotShow) { if (hotShow) privHot = true else { delay(600); privHot = false } }
    LaunchedEffect(camShow) {
        if (camShow) privCam = true else { if (IslandBus.privacyEnabled) delay(1500); privCam = false }
    }
    LaunchedEffect(micShow) {
        if (micShow) privMic = true else { if (IslandBus.privacyEnabled) delay(1500); privMic = false }
    }
    val privAnim by animateFloatAsState(
        if (privCam || privMic || privRec || privHot) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 380f), label = "privacy",
    )
    // dp the dots pill reaches past the camera's right edge: a second column of dots (recording, hotspot) widens it.
    val privCols = (if (privCam || privMic) 1 else 0) + (if (privRec || privHot) 1 else 0)
    val privExtra by animateFloatAsState(20f + 11f * (privCols - 1).coerceAtLeast(0), tween(250), label = "privacy width")
    // The dots are part of the camera: everything else is laid out around them (never over or under them).
    val dotsReach = if (privCam || privMic || privRec || privHot) 20f + 11f * (privCols - 1).coerceAtLeast(0) else 0f
    IslandBus.dotsReach = dotsReach
    IslandBus.dotsOnLeft = dotsLeft
    // A clock that moves on every second: the width of the call time (and of the little pills) follows its length.
    var tickMs by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tickMs = System.currentTimeMillis() } }
    val callChars by remember {
        androidx.compose.runtime.derivedStateOf {
            if (IslandBus.callIncoming || IslandBus.callStartMs <= 0L) 4 else miniClock(tickMs - IslandBus.callStartMs).length
        }
    }
    IslandBus.callExtra = (callChars - 4).coerceAtLeast(0) * 17f
    val timerChars by remember {
        androidx.compose.runtime.derivedStateOf {
            if (!IslandBus.timerKnown) 5 else {
                val ms = IslandBus.timerRemainingMs(tickMs)
                val t = (if (IslandBus.timerStopwatch) ms else ms + 999L) / 1000
                if (t >= 3600) 7 else if (t >= 600) 5 else 4
            }
        }
    }
    IslandBus.timerChars = timerChars
    // The left pill of a split fits what it shows (an icon and a short time) instead of a fixed width.
    val leftKind = splitLeft ?: state
    val miniChars by remember(leftKind) {
        androidx.compose.runtime.derivedStateOf {
            when (leftKind) {
                IslandState.Call, IslandState.CallOpen -> callChars
                IslandState.Timer, IslandState.TimerOpen ->
                    if (IslandBus.timerKnown) miniClock(IslandBus.timerRemainingMs(tickMs) + (if (IslandBus.timerStopwatch) 0L else 999L)).length else 5
                else -> 0
            }
        }
    }
    val pillW = if (miniChars == 0) 52.dp else (33f + miniChars * 7.6f).dp
    val bubbleW = 74.dp
    val baseRight = camD / 2 + 6.dp
    val mainRight = baseRight + (if (dotsLeft) 0f else dotsReach).dp   // from the camera's centre to the main pill's right edge
    val leftExt = baseRight + pillW + (if (dotsLeft) dotsReach else 0f).dp   // ... to the main pill's left edge
    val bubbleStart = mainRight + 8.dp                     // ... to the small pill's left edge
    val rightExt = bubbleStart + bubbleW
    // Two pills side by side need room on both sides of the camera; near a screen edge only one activity shows.
    val fitsSplit = screenWv <= 0f || (camX >= leftExt.value + 8f && screenWv - camX >= rightExt.value + 8f)
    val splitOn = fitsSplit && split != null && splitLeft != null &&
            (state == IslandState.Timer || state == IslandState.Music || state == IslandState.Call || state == IslandState.Nav)
    IslandBus.splitSecondary = if (splitOn) split else null
    IslandBus.splitPrimary = if (splitOn) splitLeft else null
    IslandBus.splitBubbleStartDp = bubbleStart.value - 4f
    IslandBus.splitBubbleEndDp = rightExt.value + 10f

    val dims = state.dims(hole)
    // A compact island hangs with the camera at its middle, but never higher than the top of the screen (a tall island
    // over a camera near the top just starts at the top).
    val compactTop = (hole.centerY - COMPACT / 2).coerceAtLeast(0.dp)
    // Where the main pill wants to be, as its left and right edges (dp from the camera's centre), height and top.
    // A camera away from the middle of the screen: the island slides over so it stays on the screen, still covering the camera.
    val shift = if (state == IslandState.Idle) 0f else {
        val w = dims.width.value
        // Whatever the dots take on one side pushes the island's middle that way (so the room each side keeps its size).
        val want = if (state.isTall() || splitOn) 0f else if (dotsLeft) -dotsReach / 2 else dotsReach / 2
        val lim = (w / 2 - (camD.value / 2 + 4f)).coerceAtLeast(0f)
        if (screenWv <= 0f) want.coerceIn(-lim, lim) else {
            // The furthest left and right the middle can go: the edges stay sideGap dp from the screen's sides
            // (an expanded island keeps the same gap as at its top).
            val g = if (state.isTall()) hole.edgeGap() else IslandBus.sideGap
            val low = g - camX + w / 2
            val high = (screenWv - g - camX) - w / 2
            val fit = if (low > high) low else want.coerceIn(low, high)
            fit.coerceIn(-lim, lim)
        }
    }
    val targetLeft = if (splitOn) -leftExt.value else -dims.width.value / 2 + shift
    val targetRight = if (splitOn) mainRight.value else dims.width.value / 2 + shift
    val targetHeight = if (splitOn) COMPACT.value else dims.height.value
    // Idle sits centred on the hole. Every other state hangs so the hole is at the middle of a compact island.
    val targetTop = if (state == IslandState.Idle) (hole.centerY - dims.height / 2 + HOLE_NUDGE).value else compactTop.value

    // The feel lives in these two springs. Lower damping = more bounce, higher stiffness = snappier.
    val spec = spring<Float>(dampingRatio = 0.58f, stiffness = 340f)
    val widthSpec = spring<Float>(dampingRatio = 0.55f, stiffness = 300f)
    // Right after the position editor closes, the island starts as the idle circle and grows into whatever is going on.
    val introStart = remember { IslandBus.introNext }
    val idleStart = IslandState.Idle.dims(hole).width.value
    IslandBus.lastIdleD = idleStart
    // After a position change (not a fold, not the editor) the island starts where the camera was and glides over.
    val moveFrom = remember {
        IslandBus.moveFrom.also { IslandBus.moveFrom = null }?.takeIf {
            !introStart && abs(it[3] - hole.screenW.value) < 1f && (abs(it[0] - hole.centerX.value) > 0.5f || abs(it[1] - hole.centerY.value) > 0.5f)
        }
    }
    val glideOnce = remember { booleanArrayOf(true) }
    val moveDx = if (moveFrom != null) moveFrom[0] - hole.centerX.value else 0f
    val moveDy = if (moveFrom != null) moveFrom[1] - hole.centerY.value else 0f
    val left = remember {
        Animatable(if (introStart) -idleStart / 2 else if (moveFrom != null) (if (state == IslandState.Idle) -moveFrom[2] / 2 else targetLeft) + moveDx else targetLeft)
    }
    val right = remember {
        Animatable(if (introStart) idleStart / 2 else if (moveFrom != null) (if (state == IslandState.Idle) moveFrom[2] / 2 else targetRight) + moveDx else targetRight)
    }
    val height = remember {
        Animatable(if (introStart) idleStart else if (moveFrom != null && state == IslandState.Idle) moveFrom[2] else targetHeight)
    }
    val top = remember {
        Animatable(if (introStart) hole.centerY.value - idleStart / 2 + HOLE_NUDGE.value else if (moveFrom != null) targetTop + moveDy else targetTop)
    }
    // While it glides, it is not held over the new camera spot.
    // (Judged by how far it still is from its spot, not by whether the animation has started, so the very first frame is right too.)
    val gliding = moveFrom != null &&
            (abs(left.value - targetLeft) > 0.3f || abs(right.value - targetRight) > 0.3f || abs(top.value - targetTop) > 0.3f)
    // Which side of the camera the dots pill is on: 0 = right, 1 = left. It slides across (under the island) instead of jumping.
    val dotsSide = remember {
        Animatable(
            if (moveFrom != null) (if (screenWv - moveFrom[0] < 70f) 1f else 0f) else (if (dotsLeft) 1f else 0f),
        )
    }
    LaunchedEffect(dotsLeft) {
        dotsSide.animateTo(
            if (dotsLeft) 1f else 0f,
            tween(480, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        )
    }
    val bubbleAlpha = remember { Animatable(0f) }
    val bubbleLeft = remember { Animatable(bubbleStart.value) }
    val bubbleRight = remember { Animatable(rightExt.value) }
    var bubbleKind by remember { mutableStateOf<IslandState?>(null) }
    var ghostKind by remember { mutableStateOf<IslandState?>(null) }
    var bubblePhase by remember { mutableStateOf(false) }
    val phaseScope = rememberCoroutineScope()

    val prev = remember { IslandPrev() }
    // After the position editor closes, the new island eases in instead of popping up.
    val intro = remember { Animatable(if (introStart) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (introStart) {
            IslandBus.introNext = false
            // What is inside the island fades in once it has started to grow.
            intro.animateTo(1f, tween(260, delayMillis = 160))
        }
    }
    // When a split ends (the other activity went away), the pill's old small content fades out while the full compact
    // content fades in, once the pill has begun to settle: no jump between the two layouts.
    var lastSplitLeft by remember { mutableStateOf<IslandState?>(null) }
    var endGhost by remember { mutableStateOf<IslandState?>(null) }
    val endAlpha = remember { Animatable(0f) }
    val contentFade = remember { Animatable(1f) }
    LaunchedEffect(splitOn, splitLeft, state) {
        if (splitOn) {
            lastSplitLeft = splitLeft
            contentFade.snapTo(1f)
        } else if (lastSplitLeft != null && !state.isTall()) {
            endGhost = lastSplitLeft
            lastSplitLeft = null
            try {
                contentFade.snapTo(0f)
                endAlpha.snapTo(1f)
                coroutineScope {
                    launch { endAlpha.animateTo(0f, tween(200)) }
                    launch { delay(130); contentFade.animateTo(1f, tween(220)) }
                }
            } finally {
                // However this ends (even if something else changes halfway), nothing is left faded out or drawn twice.
                withContext(NonCancellable) {
                    endGhost = null
                    endAlpha.snapTo(0f)
                    contentFade.snapTo(1f)
                }
            }
        } else if (!splitOn) {
            lastSplitLeft = null
        }
    }
    val splitNow by rememberUpdatedState(split)
    val splitLeftNow by rememberUpdatedState(splitLeft)

    // Opening something: the part being opened is the one that grows. If it was the small pill, the island starts
    // from there. The other part simply fades away.
    // (The target size is a key too: one alert replacing another, Silent then Do Not Disturb say, keeps the same state
    // but needs a different width.)
    LaunchedEffect(state, splitOn, bubblePhase, IslandBus.compactH, dotsReach, IslandBus.callExtra, pillW.value, dims.width.value, dims.height.value) {
        val open = state.isTall()
        val fromBubble = open && prev.splitOn && prev.secondary == state.base()
        val fromMain = open && prev.splitOn && !fromBubble
        val oldMain = prev.main
        // Closing something that was opened from the small right pill: it shrinks back into that pill.
        val toBubble = splitOn && !open && prev.openFromBubble && prev.openBase == splitNow
        if (open) {
            prev.openFromBubble = fromBubble || (prev.openFromBubble && prev.openBase == state.base())
            prev.openBase = state.base()
        } else {
            prev.openFromBubble = false
            prev.openBase = null
        }
        // Music stepping aside (paused): its pill shrinks and slides to the small pill on the right.
        val demote = splitOn && !open && oldMain == IslandState.Music && splitLeftNow != IslandState.Music && splitNow == IslandState.Music
        val oldLeft = left.value
        val oldRight = right.value
        prev.splitOn = splitOn
        prev.secondary = if (splitOn) splitNow else null
        // A short alert is never something the camera pill or the small pill falls back to.
        prev.main = (splitLeftNow ?: state).takeIf { it != IslandState.Alert } ?: prev.main
        if (splitOn) bubbleKind = splitNow
        // Shrinking back into the small pill runs on its own coroutine, so nothing else can cut it short halfway.
        if (bubblePhase) return@LaunchedEffect
        if (toBubble) {
            bubblePhase = true
            ghostKind = splitLeftNow
            val tl = targetLeft; val tr = targetRight; val th = targetHeight; val tt = targetTop
            val bs0 = bubbleStart.value; val re = rightExt.value
            phaseScope.launch {
                try {
                    bubbleAlpha.snapTo(0f)
                    // Width is critically damped (no overshoot, so the countdown is never covered); height keeps a tiny bounce.
                    val bw = SHRINK_W_SPRING
                    val bh = SHRINK_H_SPRING
                    coroutineScope {
                        launch { left.animateTo(bs0, bw) }
                        launch { right.animateTo(re, bw) }
                        launch { height.animateTo(COMPACT.value, bh) }
                        launch { top.animateTo(tt, spec) }
                        delay(300)
                    }
                } finally {
                    withContext(NonCancellable) {
                        // Hand over to the real small pill; the camera pill goes back to being the main one.
                        left.snapTo(tl)
                        right.snapTo(tr)
                        height.snapTo(th)
                        bubbleLeft.snapTo(bs0)
                        bubbleRight.snapTo(re)
                        bubbleAlpha.snapTo(1f)
                        ghostKind = null
                        bubblePhase = false
                    }
                }
            }
            return@LaunchedEffect
        }
        try {
            coroutineScope {
                if (fromBubble) {
                    // The camera pill stays put for a moment while the small pill grows into the open island.
                    ghostKind = oldMain
                    left.snapTo(bubbleStart.value)
                    right.snapTo(rightExt.value)
                    height.snapTo(COMPACT.value)
                    bubbleAlpha.snapTo(0f)
                }
                // Growing bounces a lot; shrinking bounces a little (the limit below keeps the camera covered anyway).
                val shrinking = (targetRight - targetLeft) < (right.value - left.value)
                val wSpec = if (shrinking) SHRINK_W_SPRING else widthSpec
                val hSpec = if (targetHeight < height.value) SHRINK_H_SPRING else spec
                if (demote) {
                    left.snapTo(targetLeft)
                    right.snapTo(targetRight)
                    height.snapTo(targetHeight)
                    bubbleLeft.snapTo(oldLeft)
                    bubbleRight.snapTo(oldRight)
                    bubbleAlpha.snapTo(1f)
                }
                launch { bubbleLeft.animateTo(bubbleStart.value, widthSpec) }
                launch { bubbleRight.animateTo(rightExt.value, widthSpec) }
                // Gliding to a new camera spot: everything moves together on one smooth curve (no bounce, so what is inside stays calm).
                val glide = if (moveFrom != null && glideOnce[0]) {
                    tween<Float>(480, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                } else null
                glideOnce[0] = false
                launch { left.animateTo(targetLeft, glide ?: wSpec) }
                launch { right.animateTo(targetRight, glide ?: wSpec) }
                launch { height.animateTo(targetHeight, glide ?: hSpec) }
                launch { top.animateTo(targetTop, glide ?: spec) }
                when {
                    splitOn -> launch { bubbleAlpha.animateTo(1f, tween(200)) }
                    fromBubble -> Unit
                    fromMain -> launch { bubbleAlpha.animateTo(0f, tween(120)) }
                    else -> launch { bubbleAlpha.animateTo(0f, tween(150)) }
                }
                if (fromBubble) {
                    delay(320)
                    ghostKind = null
                }
            }
        } finally {
            if (!fromBubble) ghostKind = null
        }
    }

    val visibility by animateFloatAsState(
        if (IslandBus.editingCamera || (state == IslandState.Idle && !hole.real)) 0f else 1f,
        label = "visibility",
    )
    val pressSpring = spring<Float>(dampingRatio = 0.45f, stiffness = 500f)
    val pressing = IslandBus.pressed && state != IslandState.Idle
    // While split, only the pill being touched squishes.
    // On a button the island reacts at only BUTTON_ISLAND_SHARE of its usual squish (the button does the rest).
    val pressedScale = if (IslandBus.pressedOnButton) 1f - 0.07f * BUTTON_ISLAND_SHARE else 0.93f
    val squish by animateFloatAsState(
        if (pressing && !(splitOn && IslandBus.pressedBubble)) pressedScale else 1f, pressSpring, label = "squish",
    )
    val squishBubble by animateFloatAsState(
        if (pressing && splitOn && IslandBus.pressedBubble) pressedScale else 1f, pressSpring, label = "squishBubble",
    )
    // A coloured glow flashes around an island when it opens, then settles.
    val glow = remember { Animatable(0f) }
    LaunchedEffect(state.isTall()) {
        if (state.isTall()) {
            glow.animateTo(1f, tween(160))
            glow.animateTo(0.3f, tween(900))
        } else glow.animateTo(0f, tween(220))
    }
    val glowColor = when (state) {
        IslandState.Music, IslandState.MusicOpen -> IslandBus.mediaAccent
        IslandState.Timer, IslandState.TimerOpen -> Color(0xFFFF9F0A)
        IslandState.Nav, IslandState.NavOpen -> Color(0xFF0A84FF)
        IslandState.Call, IslandState.CallOpen, IslandState.Charging, IslandState.ChargingOpen -> Color(0xFF30D158)
        IslandState.Torch, IslandState.TorchOpen -> Color(0xFF0A84FF)
        IslandState.Device -> if (IslandBus.deviceConnected) Color(0xFF30D158) else Color(0xFF8E8E93)
        else -> Color.White
    }

    // Never let the pill get smaller than the camera while something is showing.
    // The very first frame of opening the small pill already starts from the small pill's place (the springs are moved
    // there right after), so nothing flashes at the old size.
    val fromBubbleNow = state.isTall() && prev.splitOn && prev.secondary == state.base()
    val demoteNow = splitOn && !state.isTall() && prev.main == IslandState.Music && splitLeft != IslandState.Music && split == IslandState.Music
    val leftNow = if (fromBubbleNow) bubbleStart.value else if (demoteNow) targetLeft else left.value
    val rightNow = if (fromBubbleNow) rightExt.value else if (demoteNow) targetRight else right.value
    val heightNow = if (fromBubbleNow) COMPACT.value else if (demoteNow) targetHeight else height.value
    val h = if (state == IslandState.Idle) heightNow else maxOf(heightNow, COMPACT.value)
    // Whatever the springs do, the pill always keeps covering the camera: it never ends closer than 4 dp to its edge.
    val minHalf = camD.value / 2 + 4f
    // (Not while the camera pill is left behind: it is the one covering the camera then.)
    val toBubbleNow = splitOn && !state.isTall() &&
            (bubblePhase || (prev.openFromBubble && prev.openBase == split))
    val cameraCovered = fromBubbleNow || toBubbleNow || ghostKind != null || gliding
    val l = if (state == IslandState.Idle || cameraCovered) leftNow else minOf(leftNow, -minHalf)
    val r = if (state == IslandState.Idle || cameraCovered) rightNow else maxOf(rightNow, minHalf)
    val widthDp = (r - l).coerceAtLeast(1f)
    val centre = (l + r) / 2
    // While gliding to a new camera spot, the content is laid out as if the island were already there (it just rides along).
    IslandBus.pillCentre = if (gliding) centre - (leftNow - targetLeft) else centre
    val corner = minOf(h / 2, IslandBus.openCorner(hole.edgeGap())).dp
    val privOn = privAnim > 0.01f
    // The touch box reaches from the island's left end to its right end (the dots pill and the small pill included), so it
    // can sit off-centre when the camera is not in the middle. Its middle is [boxCentre] dp from the camera.
    val privReach = if (privOn) camD.value / 2 + 4f + privExtra else 0f
    val extR = maxOf(r, if (bubbleAlpha.value > 0.01f) rightExt.value else 0f, if (!dotsLeft) privReach else 0f)
    val extL = maxOf(-l, if (dotsLeft) privReach else 0f)
    val boxCentre = (extR - extL) / 2f
    IslandBus.mainCentre = boxCentre
    // Squishing keeps the top edge and the camera covered: it pivots on the camera and the top, and never gets
    // shorter than what is needed to hide the camera.
    val neededH = COMPACT.value / 2 + camD.value / 2 + 2f
    val minScaleY = (neededH / h).coerceAtMost(1f)
    val pivotX = (-l / widthDp).coerceIn(0f, 1f)

    // The touch area is a box TOUCH_PAD bigger than the island on every side. The visible island is drawn inside it.
    Box(
        modifier
            .offset { IntOffset((xShift + boxCentre.dp).roundToPx(), (top.value.dp - TOUCH_PAD).roundToPx()) }
            .size((extR + extL).dp + TOUCH_PAD * 2, (if (privOn) maxOf(h, COMPACT.value) else h).dp + TOUCH_PAD * 2)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .islandGestures { IslandBus.mainCentre },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Everything inside is placed in dp from the camera's centre, so this layer is pulled back onto the camera.
        Box(Modifier.fillMaxSize().offset(x = (-boxCentre).dp), contentAlignment = Alignment.TopCenter) {
            // The camera pill, left behind while the small pill opens.
            val ghostShown = (if (fromBubbleNow) prev.main else if (toBubbleNow) splitLeft else ghostKind)?.takeIf { it != IslandState.Alert }
            if (ghostShown != null) {
                Box(
                    Modifier.padding(top = TOUCH_PAD).offset(x = ((-leftExt + mainRight) / 2).value.dp)
                        .size(leftExt + mainRight, COMPACT)
                        .clip(RoundedCornerShape(COMPACT / 2)).background(Color.Black),
                ) {
                    Box(Modifier.size(pillW, COMPACT).align(Alignment.CenterStart)) { MiniActivity(ghostShown, towardCamera = true) }
                }
            }
            // The main pill: the whole island when nothing is split.
            Box(
                Modifier
                    .padding(top = TOUCH_PAD)
                    .offset(x = centre.dp)
                    .size(widthDp.dp, h.dp)
                    .graphicsLayer {
                        scaleX = squish
                        scaleY = maxOf(squish, minScaleY)
                        transformOrigin = TransformOrigin(pivotX, 0f)
                    }
                    .alpha(visibility)
                    .shadow(
                        (12f * glow.value).dp, RoundedCornerShape(corner), clip = false,
                        ambientColor = glowColor, spotColor = glowColor,
                    )
                    .clip(RoundedCornerShape(corner))
                    .background(Color.Black)
                    .then(
                        if (outline && state == IslandState.Idle)
                            Modifier.border(BorderStroke(1.dp, Color.Red), RoundedCornerShape(corner))
                        else if (IslandBus.edgeShown && state != IslandState.Idle)
                            Modifier.border(BorderStroke(1.dp, EdgeColor), RoundedCornerShape(corner))
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (toBubbleNow) {
                    // The closing island is the small pill's content on its way back into place.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MiniActivity(split!!) }
                } else if (splitOn) {
                    Box(Modifier.size(pillW, COMPACT).align(Alignment.CenterStart)) { MiniActivity(splitLeft!!, towardCamera = true) }
                } else {
                    AnimatedContent(
                        modifier = Modifier.graphicsLayer { alpha = intro.value * contentFade.value },
                        targetState = state,
                        transitionSpec = {
                            (fadeIn(tween(durationMillis = 220, delayMillis = 120)) togetherWith
                                    fadeOut(tween(durationMillis = 80)))
                                .using(SizeTransform(clip = false) { _, _ -> snap() })
                        },
                        contentAlignment = Alignment.Center,
                        label = "content",
                    ) { s ->
                        val d = s.dims(hole)
                        Box(Modifier.requiredSize(d.width, d.height)) {
                            when (s) {
                                IslandState.Idle -> Unit
                                IslandState.Charging -> ChargingCompact()
                                IslandState.ChargingOpen -> ChargingOpen()
                                IslandState.Timer -> TimerCompact()
                                IslandState.TimerOpen -> TimerOpenContent()
                                IslandState.Call -> CallCompact()
                                IslandState.CallOpen -> CallOpenContent()
                                IslandState.Nav -> NavCompact()
                                IslandState.NavOpen -> NavOpenContent()
                                IslandState.Music -> MusicCompact()
                                IslandState.MusicOpen -> MusicOpen()
                                IslandState.Alert -> AlertCompact()
                                IslandState.Torch -> TorchCompact()
                                IslandState.TorchOpen -> TorchOpenContent()
                                IslandState.Device -> when (IslandBus.expandMode) {
                                    2 -> BatteryAlertOpen()
                                    3 -> ChargingOpen()
                                    else -> DeviceOpen()
                                }
                            }
                        }
                    }
                }
                val ghostEnd = endGhost
                if (ghostEnd != null && !splitOn && !state.isTall() && endAlpha.value > 0.01f) {
                    Box(Modifier.size(pillW, COMPACT).align(Alignment.CenterStart).graphicsLayer { alpha = endAlpha.value }) {
                        MiniActivity(ghostEnd, towardCamera = true)
                    }
                }
            }
            // The camera / microphone pill: grows out of the hole to the right, green dot on top (camera), orange below (mic).
            // (Not while the position editor is open.)
            if (privOn && !IslandBus.editingCamera) {
                // It grows from, and shrinks back into, the idle circle over the camera: width, height and position all
                // follow the same 0..1 progress, so closing is one smooth motion with no cut at the end.
                val f = privAnim.coerceAtLeast(0f)
                val idleD = IslandState.Idle.dims(hole).width.value
                val wide = camD.value / 2 + 4f
                val pl0 = -(idleD / 2 + (wide - idleD / 2) * f)
                val pr0 = idleD / 2 + (wide - idleD / 2) * f + privExtra * f
                val side = dotsSide.value.coerceIn(0f, 1f)
                val pl = pl0 + (-pr0 - pl0) * side
                val pr = pr0 + (-pl0 - pr0) * side
                val pillH = idleD + (COMPACT.value - idleD) * f
                val centre = hole.centerY.value + HOLE_NUDGE.value * (1f - f)
                val dy = (centre - pillH / 2) - top.value
                Box(
                    // (While gliding to a new camera spot it glides along with the island, from where the camera was.)
                    Modifier.padding(top = TOUCH_PAD)
                        .offset(
                            x = ((pl + pr) / 2 + (if (gliding) left.value - targetLeft else 0f)).dp,
                            y = (dy + (if (gliding) top.value - targetTop else 0f)).dp,
                        )
                        // requiredSize: exactly this size, whatever space the layout around it offers.
                        .requiredSize((pr - pl).dp, pillH.dp)
                        .clip(RoundedCornerShape((pillH / 2).dp)).background(Color.Black),
                ) {
                    PrivacyDots(
                        privCam, privMic, privRec, privHot,
                        // The dots slide along inside the pill as it crosses over (bias +1 = right end, -1 = left end).
                        Modifier.align(androidx.compose.ui.BiasAlignment(1f - 2f * side, 0f))
                            .padding(start = (7f * side).dp, end = (7f * (1f - side)).dp),
                    )
                }
            }
            // The small second pill.
            // While split, always show what is current; the remembered one is only for the fade-out.
            val bubbleShown = (if (splitOn) split else bubbleKind)?.takeIf { it != IslandState.Alert }
            if ((bubbleAlpha.value > 0.01f || demoteNow) && bubbleShown != null) {
                val bl = if (demoteNow) left.value else bubbleLeft.value
                val br = if (demoteNow) right.value else bubbleRight.value
                Box(
                    Modifier.padding(top = TOUCH_PAD).offset(x = ((bl + br) / 2).dp)
                        .size((br - bl).coerceAtLeast(1f).dp, COMPACT)
                        .graphicsLayer {
                            val k = squishBubble * (0.85f + 0.15f * bubbleAlpha.value)
                            scaleX = k
                            scaleY = k
                        }
                        .alpha(if (demoteNow) 1f else bubbleAlpha.value)
                        .clip(RoundedCornerShape(COMPACT / 2)).background(Color.Black)
                        .then(if (IslandBus.edgeShown) Modifier.border(BorderStroke(1.dp, EdgeColor), RoundedCornerShape(COMPACT / 2)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) { MiniActivity(bubbleShown) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Contents
// ---------------------------------------------------------------------------------------------

/**
 * The status dots. First column: green for the camera (top), orange for the microphone (below). Second column, when
 * needed: red for screen recording (top), green for the hotspot (below). One alone sits in the middle of its column.
 */
@Composable
private fun PrivacyDots(cam: Boolean, mic: Boolean, rec: Boolean, hot: Boolean, modifier: Modifier) {
    val v1 = cam || mic
    val v2 = rec || hot
    val w1 by animateDpAsState(if (v1) (if (v2) 11.dp else 7.dp) else 0.dp, tween(250), label = "dots 1")
    val w2 by animateDpAsState(if (v2) 7.dp else 0.dp, tween(250), label = "dots 2")
    Row(modifier.height(24.dp)) {
        Box(Modifier.width(w1).fillMaxHeight()) {
            DotPair(cam, Color(0xFF30D158), mic, Color(0xFFFF9F0A), Modifier.align(Alignment.CenterStart))
        }
        Box(Modifier.width(w2).fillMaxHeight()) {
            DotPair(rec, Color(0xFFFF453A), hot, Color(0xFFBF5AF2), Modifier.align(Alignment.CenterStart))
        }
    }
}

/** Two dots, one above the other. When the second switches on it splits off the first; when one of two goes off, they merge. */
@Composable
private fun DotPair(a: Boolean, colorA: Color, b: Boolean, colorB: Color, modifier: Modifier) {
    val yA = remember { Animatable(if (a && b) -5f else 0f) }
    val yB = remember { Animatable(if (a && b) 5f else 0f) }
    val alphaA = remember { Animatable(if (a) 1f else 0f) }
    val alphaB = remember { Animatable(if (b) 1f else 0f) }
    var prevA by remember { mutableStateOf(a) }
    var prevB by remember { mutableStateOf(b) }
    LaunchedEffect(a, b) {
        val spec = spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
        // A dot that switches on starts where the other one is, then they move apart.
        if (a && !prevA) yA.snapTo(if (prevB) yB.value else 0f)
        if (b && !prevB) yB.snapTo(if (prevA) yA.value else 0f)
        prevA = a
        prevB = b
        val both = a && b
        coroutineScope {
            // A dot is simply there or not: no fading in or out.
            alphaA.snapTo(if (a) 1f else 0f)
            alphaB.snapTo(if (b) 1f else 0f)
            // A dot that switches off slides to the middle (where the remaining one is going).
            launch { yA.animateTo(if (both) -5f else 0f, spec) }
            launch { yB.animateTo(if (both) 5f else 0f, spec) }
        }
    }
    Box(modifier.requiredSize(7.dp, 24.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.offset(y = yA.value.dp).graphicsLayer { alpha = alphaA.value }
                .size(7.dp).clip(CircleShape).background(colorA),
        )
        Box(
            Modifier.offset(y = yB.value.dp).graphicsLayer { alpha = alphaB.value }
                .size(7.dp).clip(CircleShape).background(colorB),
        )
    }
}

private val Green = Color(0xFF30D158)
private val Red = Color(0xFFFF453A)
private val Gray = Color(0xFF8E8E93)

/** A battery shape that fills up to [percent]. */
@Composable
internal fun BatteryIcon(percent: Int, modifier: Modifier, color: Color = Green) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF3A3A3C))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth((percent.coerceIn(4, 100)) / 100f).background(color),
        )
    }
}

private fun fullIn(minutes: Int, percent: Int): String = when {
    percent >= 100 -> "Fully charged"
    minutes < 0 -> "Charging"
    minutes < 60 -> "Full in $minutes min"
    else -> "Full in ${minutes / 60} h ${minutes % 60} min"
}

@Composable
private fun ChargingCompact() {
    CamRow(
        Modifier.fillMaxSize(),
        left = { BatteryIcon(IslandBus.batteryPercent, Modifier.size(24.dp, 12.dp)) },
        right = { Text("${IslandBus.batteryPercent}%", color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
    )
}

/** The shared look of the wide pop-ups: an icon, a title with a line under it, and one big value; the camera sits in between. */
@Composable
internal fun InfoOpen(
    title: String,
    subtitle: String,
    subtitleColor: Color,
    icon: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    // Exactly the Charging pop-up's layout (icon and words, then one big value); the camera is kept clear.
    CamRow(
        Modifier.fillMaxSize(), pad = 22.dp, gap = IslandBus.camGap - 12.dp,
        left = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(subtitle, color = subtitleColor, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        },
        right = { trailing() },
    )
}

/** The expanded battery pop-up (low battery, fully charged). */
@Composable
private fun BatteryAlertOpen() {
    val tint = IslandBus.alertColor
    val pct = IslandBus.alertBattery.coerceIn(0, 100)
    val full = pct >= 100
    val fill = remember(IslandBus.alertKey) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(IslandBus.alertKey) { fill.animateTo(pct.toFloat(), tween(900)) }
    InfoOpen(
        title = if (full) "Charging" else IslandBus.alertText,
        subtitle = if (full) "Fully charged" else "Charge soon",
        subtitleColor = if (full) Gray else tint,
        icon = { BatteryIcon(fill.value.toInt(), Modifier.size(34.dp, 16.dp), tint) },
        trailing = { Text("$pct%", color = tint, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
    )
}

@Composable
private fun DeviceOpen() {
    val up = IslandBus.deviceConnected
    val tint = if (up) Green else Gray
    InfoOpen(
        title = IslandBus.deviceName,
        subtitle = if (up) "Connected" else "Disconnected",
        subtitleColor = tint,
        icon = {
            androidx.compose.foundation.layout.Box(
                Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                AlertGlyph("headphones", tint, Modifier.size(20.dp))
            }
        },
        trailing = {
            if (up && IslandBus.deviceBattery >= 0) {
                val fill = remember(IslandBus.deviceKey) { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(IslandBus.deviceBattery, IslandBus.deviceKey) { fill.animateTo(IslandBus.deviceBattery.toFloat(), tween(900)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BatteryIcon(fill.value.toInt(), Modifier.size(26.dp, 13.dp), tint)
                    Spacer(Modifier.width(6.dp))
                    Text("${IslandBus.deviceBattery}%", color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        },
    )
}

/** The flashlight, expanded: a button to switch it off. */
@Composable
internal fun TorchOpenContent() {
    val blue = Color(0xFF0A84FF)
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    InfoOpen(
        title = "Flashlight",
        subtitle = "On",
        subtitleColor = blue,
        icon = { AlertGlyph("flashlight", blue, Modifier.size(26.dp)) },
        trailing = {
            androidx.compose.foundation.layout.Box(
                Modifier.height(40.dp).pressScale(source).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(blue)
                    .clickable(interactionSource = source, indication = null) { IslandBus.turnTorchOff() }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Turn off", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        },
    )
}

@Composable
private fun ChargingOpen() {
    InfoOpen(
        title = "Charging",
        subtitle = fullIn(IslandBus.minutesToFull, IslandBus.batteryPercent),
        subtitleColor = Gray,
        icon = { BatteryIcon(IslandBus.batteryPercent, Modifier.size(34.dp, 16.dp)) },
        trailing = { Text("${IslandBus.batteryPercent}%", color = Green, fontSize = 24.sp, fontWeight = FontWeight.Bold) },
    )
}