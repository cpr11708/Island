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
val COMPACT = 36.dp

/** Idle pill diameter as a fraction of the camera hole. Below 1 keeps it inside the hole. */
const val IDLE_SCALE = 0.8f

/** Slide the idle pill down (+) or up (-) if it is not centred on the hole. Use the outline to tune. */
val HOLE_NUDGE = (-1.5).dp

/** Corner radius of an open island. Smaller = squarer. */
val OPEN_CORNER = 40.dp

/** Extra touch area around the island on every side. */
val TOUCH_PAD = 14.dp

/** How long to hold a finger on the island before it opens or closes. */
const val HOLD_MS = 600L

// ---------------------------------------------------------------------------------------------
// Shared state between the app screen and the overlay service
// ---------------------------------------------------------------------------------------------

enum class IslandState {
    Idle, Charging, ChargingOpen, Timer, TimerOpen, Call, CallOpen, Music, MusicOpen, Nav, NavOpen, Alert;

    /** Whether the island needs the large overlay window. */
    fun isTall(): Boolean = this == ChargingOpen || this == TimerOpen || this == MusicOpen || this == CallOpen || this == NavOpen

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

    /** True while a finger is on the island: it squishes a little. */
    var pressed by mutableStateOf(false)
    /** While split: true if the finger is on the small second pill rather than the main one. */
    var pressedBubble by mutableStateOf(false)

    /** A short message pill (silent mode, a Bluetooth device...). Shown for a moment, then the island goes back. */
    var alertIcon by mutableStateOf("")
    var alertText by mutableStateOf("")
    var alertColor by mutableStateOf(Color.White)
    var alertKey by mutableIntStateOf(0)
    /** 0..100 when the pop-up shows a battery (low battery, fully charged, earbuds), otherwise -1. */
    var alertBattery by mutableIntStateOf(-1)
    var alertReturn = IslandState.Idle

    /** The camera / microphone is in use by some app (shows the green / orange dots). */
    /** The user's switch (in the app): open the music player by itself on the lock screen. On by default. */
    var lockPlayer by mutableStateOf(true)

    /** Something was swiped away or timed out, and can be brought back. */
    val hasHidden: Boolean
        get() = (callReal && callDismissedKey == callKey) ||
                (timerKey.isNotEmpty() && timerDismissedKey == timerKey) ||
                (navKey.isNotEmpty() && navDismissedKey == navKey) ||
                (mediaPackage.isNotEmpty() && mediaTitle.isNotEmpty() && (mediaHiddenTitle == mediaTitle || !mediaLive))

    /** Brings back everything that was swiped away (and paused music that had timed out). */
    fun restoreDismissed() {
        callDismissedKey = null
        timerDismissedKey = null
        navDismissedKey = null
        mediaHiddenTitle = ""
        if (mediaPackage.isNotEmpty() && mediaTitle.isNotEmpty()) mediaLive = true
        autoIdle = false
        val r = restingState()
        if (!state.isTall()) state = r
    }

    /** The user's switch (in the app) for the dots. Off by default. */
    var privacyEnabled by mutableStateOf(false)
    var camActive by mutableStateOf(false)
    var micActive by mutableStateOf(false)
    /** Screen recording, and hotspot / tethering (from a broadcast, or from the phone's own notification). */
    var recActive by mutableStateOf(false)
    var hotspotAp by mutableStateOf(false)
    var hotspotNote by mutableStateOf(false)
    val hotspotActive: Boolean get() = hotspotAp || hotspotNote
    var statusPills by mutableStateOf(true)

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

    /** Shows a short pop-up. Returns false when something open (a call, an expanded island) is in the way. */
    fun alert(icon: String, text: String, color: Color, battery: Int = -1): Boolean {
        val s = state
        val ok = s == IslandState.Idle || s == IslandState.Charging || s == IslandState.Music ||
                s == IslandState.Timer || s == IslandState.Call || s == IslandState.Nav || s == IslandState.Alert
        if (!ok) return false
        if (s != IslandState.Alert) alertReturn = s
        alertIcon = icon
        alertText = text
        alertColor = color
        alertBattery = battery
        alertKey++
        autoIdleMs = 2500L
        autoIdle = true
        state = IslandState.Alert
        return true
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

    /** Persistent activities that are going, highest priority first: Navigation > Call > Music > Timer. */
    fun activeList(): List<IslandState> = listOf(
        IslandState.Nav to (navKey.isNotEmpty() && navDismissedKey != navKey),
        IslandState.Call to (callReal && callDismissedKey != callKey),
        IslandState.Music to (mediaLive && mediaTitle.isNotEmpty() && mediaHiddenTitle != mediaTitle),
        IslandState.Timer to (timerKey.isNotEmpty() && timerDismissedKey != timerKey),
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
            else -> return null
        }
        val order = listOf(IslandState.Nav, IslandState.Call, IslandState.Music, IslandState.Timer)
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
    fun openAppAt(xDp: Float) {
        if (state.isTall()) return
        val kind = (kindAt(xDp) ?: state).base()
        tapDebug = "tap on $kind: ctx=${appContext != null} timerIntent=${timerOpen != null} callIntent=${callOpen != null} mediaIntent=${mediaOpen != null}"
        when (kind) {
            IslandState.Music -> {
                val ok = mediaOpen?.let { sendPI(it) } ?: false
                if (!ok) {
                    val ctx = appContext
                    val launch = ctx?.packageManager?.getLaunchIntentForPackage(mediaPackage)
                    if (ctx != null && launch != null) {
                        runCatching { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
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
        autoIdle = false
        state = state.tapped()
    }

    /** Swiped away. A real call or timer stays hidden for the rest of that call or timer. */
    fun dismiss() {
        if (state == IslandState.Call || state == IslandState.CallOpen) callDismissedKey = callKey
        if (state == IslandState.Timer || state == IslandState.TimerOpen) timerDismissedKey = timerKey
        if (state == IslandState.Music || state == IslandState.MusicOpen) mediaHiddenTitle = mediaTitle
        if (state == IslandState.Nav || state == IslandState.NavOpen) navDismissedKey = navKey
        state = IslandState.Idle
    }

    /** A touch landed anywhere outside the island: go idle. */
    fun outsideTouch() {
        autoIdle = false
        when (state) {
            IslandState.Idle -> Unit
            // Music and calls stay until you swipe them away. An outside touch only closes the expanded player.
            IslandState.Music, IslandState.Call, IslandState.Timer, IslandState.Nav -> Unit
            IslandState.NavOpen -> state = IslandState.Nav
            // An open timer closes to the compact timer, unless it has finished (you need the Stop button).
            IslandState.TimerOpen -> if (timerRemainingMs() > 0 || timerPaused || timerStopwatch) state = IslandState.Timer
            // An open call closes to the compact call, except while it is still ringing (you need the buttons).
            IslandState.CallOpen -> if (!callIncoming) state = IslandState.Call
            IslandState.MusicOpen -> state = IslandState.Music
            IslandState.Alert -> state = restingState()
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

private fun IslandState.dims(hole: Hole): DpSize = when (this) {
    IslandState.Idle -> {
        val d = if (hole.real) hole.diameter * IDLE_SCALE else 2.dp
        DpSize(d, d)
    }
    IslandState.Charging -> DpSize(190.dp, COMPACT)
    IslandState.Timer -> DpSize(190.dp, COMPACT)
    IslandState.Music -> DpSize(140.dp, COMPACT)
    IslandState.MusicOpen -> {
        // Halfway between the old fixed width (340 dp) and almost the full screen width.
        val wide = (hole.screenW - 28.dp).coerceIn(340.dp, 480.dp)
        DpSize((340.dp + wide) / 2, 164.dp)
    }
    IslandState.ChargingOpen -> DpSize(350.dp, 84.dp)
    IslandState.TimerOpen -> DpSize(340.dp, 104.dp)
    IslandState.Call -> DpSize(190.dp, COMPACT)
    // As wide as the message needs, with the camera's gap kept clear in the middle (the text lives on the right half).
    IslandState.Alert -> DpSize((IslandBus.camGap + (IslandBus.alertText.length * 8 + 36).dp * 2).coerceIn(190.dp, 340.dp), COMPACT)
    IslandState.Nav -> DpSize(190.dp, COMPACT)
    IslandState.NavOpen -> DpSize(350.dp, 150.dp)
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
    LaunchedEffect(state, IslandBus.autoIdle, IslandBus.alertKey) {
        if (IslandBus.autoIdle) {
            if (state == IslandState.Charging || state == IslandState.Timer || state == IslandState.Alert) {
                delay(IslandBus.autoIdleMs)
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
        while (true) {
            // Leaving Maps: the navigation island appears now (it was kept out of sight the whole time).
            val navHidden = IslandBus.hiddenByApp(IslandState.Nav)
            if (navWasHidden && !navHidden && IslandBus.activeList().contains(IslandState.Nav) && !IslandBus.state.isTall()) {
                IslandBus.state = IslandState.Nav
            }
            navWasHidden = navHidden
            // Inside the app of the pill that is showing: if something else is going, that takes the island.
            val st = IslandBus.state
            if (IslandBus.hiddenByApp(st.base())) {
                val r = IslandBus.restingState()
                if (r != IslandState.Idle) IslandBus.state = r
            }
            split = IslandBus.splitPair(stateNow)
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
            onBounds = { islandRect = it },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The island itself
// ---------------------------------------------------------------------------------------------

/**
 * Hold ~600 ms to open or close the island (it fires while the finger is still down), or swipe up to dismiss it. A
 * finger may wobble up to 20 dp during the hold. A quick tap does nothing.
 */
internal fun Modifier.islandGestures(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // Which side of the camera was touched, in dp from the camera's centre (the touch area is centred on it).
        val xDp = (down.position.x - size.width / 2f) / density
        IslandBus.pressedBubble = IslandBus.onBubble(xDp)
        IslandBus.pressed = true
        try {
            val wobble = 20.dp.toPx()
            val swipe = 24.dp.toPx()
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

/** An invisible touch area. While the island is compact it lives in its own small window (see IslandOverlay). */
/**
 * The lock screen music player, like the iPhone's: a card near the bottom of the lock screen with the cover, the song,
 * the progress bar and the play / skip buttons. Only there while the phone is locked and some music is open.
 */
@Composable
fun LockPlayerRoot(onTouchable: (Boolean) -> Unit) {
    val view = LocalView.current
    val keyguard = remember { view.context.getSystemService(android.app.KeyguardManager::class.java) }
    var locked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            locked = runCatching { keyguard.isKeyguardLocked }.getOrDefault(false)
            delay(60)
        }
    }
    // Only appears if music is playing while the phone is locked. After 30 s without playing, it goes away.
    var eligible by remember { mutableStateOf(false) }
    LaunchedEffect(locked, IslandBus.mediaPlaying) {
        if (!locked) {
            eligible = false
        } else if (IslandBus.mediaPlaying) {
            eligible = true
        } else if (eligible) {
            delay(30_000)
            eligible = false
        }
    }
    val want = locked && eligible && IslandBus.lockPlayer && IslandBus.mediaPackage.isNotEmpty() && IslandBus.mediaTitle.isNotEmpty()
    // Slides in gently, but leaves almost at once when you unlock.
    val shown by animateFloatAsState(if (want) 1f else 0f, tween(if (want) 300 else 90), label = "lock player")
    LaunchedEffect(want) { onTouchable(want) }
    val glass = RoundedCornerShape(28.dp)
    if (shown > 0.01f) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    alpha = shown
                    translationY = (1f - shown) * 40.dp.toPx()
                }
                // Like the home screen's widgets: smoky grey glass, a touch lighter at the top, with a thin pale rim.
                .clip(glass)
                .background(Brush.verticalGradient(listOf(Color(0xA3585D64), Color(0xA3343840))))
                .border(
                    1.6.dp,
                    Brush.verticalGradient(listOf(Color(0xCCD6DADF), Color(0x80B8BDC3))),
                    glass,
                ),
        ) { LockMusicCard() }
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
    Box(Modifier.fillMaxSize().islandGestures())
}

private fun IslandState.base(): IslandState = when (this) {
    IslandState.Call, IslandState.CallOpen -> IslandState.Call
    IslandState.Timer, IslandState.TimerOpen -> IslandState.Timer
    IslandState.Music, IslandState.MusicOpen -> IslandState.Music
    IslandState.Nav, IslandState.NavOpen -> IslandState.Nav
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
    val splitOn = split != null && splitLeft != null && (state == IslandState.Timer || state == IslandState.Music || state == IslandState.Call || state == IslandState.Nav)
    // Split: the main pill hugs the camera (its content on the left, the camera at its right end), and the second
    // activity is a small separate pill on the right. So it reads as two items, not three.
    val camD = if (hole.real) hole.diameter else 24.dp
    IslandBus.camGap = camD + 16.dp
    val pillW = 88.dp
    val bubbleW = 74.dp
    val mainRight = camD / 2 + 6.dp                        // from the camera's centre to the main pill's right edge
    val leftExt = mainRight + pillW                        // ... to the main pill's left edge
    val bubbleStart = mainRight + 8.dp                     // ... to the small pill's left edge
    val rightExt = bubbleStart + bubbleW
    IslandBus.splitSecondary = if (splitOn) split else null
    IslandBus.splitPrimary = if (splitOn) splitLeft else null
    IslandBus.splitBubbleStartDp = bubbleStart.value - 4f
    IslandBus.splitBubbleEndDp = rightExt.value + 10f

    val dims = state.dims(hole)
    val compactTop = hole.centerY - COMPACT / 2
    // Where the main pill wants to be, as its left and right edges (dp from the camera's centre), height and top.
    val targetLeft = if (splitOn) -leftExt.value else -dims.width.value / 2
    val targetRight = if (splitOn) mainRight.value else dims.width.value / 2
    val targetHeight = if (splitOn) COMPACT.value else dims.height.value
    // Idle sits centred on the hole. Every other state hangs so the hole is at the middle of a compact island.
    val targetTop = if (state == IslandState.Idle) (hole.centerY - dims.height / 2 + HOLE_NUDGE).value else compactTop.value

    // The feel lives in these two springs. Lower damping = more bounce, higher stiffness = snappier.
    val spec = spring<Float>(dampingRatio = 0.58f, stiffness = 340f)
    val widthSpec = spring<Float>(dampingRatio = 0.55f, stiffness = 300f)
    val left = remember { Animatable(targetLeft) }
    val right = remember { Animatable(targetRight) }
    val height = remember { Animatable(targetHeight) }
    val top = remember { Animatable(targetTop) }
    val bubbleAlpha = remember { Animatable(0f) }
    val bubbleLeft = remember { Animatable(bubbleStart.value) }
    val bubbleRight = remember { Animatable(rightExt.value) }
    var bubbleKind by remember { mutableStateOf<IslandState?>(null) }
    var ghostKind by remember { mutableStateOf<IslandState?>(null) }
    var bubblePhase by remember { mutableStateOf(false) }
    val phaseScope = rememberCoroutineScope()

    // Camera / microphone dots. They stay a moment after the app stops, so a quick flicker is still readable.
    var privCam by remember { mutableStateOf(false) }
    var privMic by remember { mutableStateOf(false) }
    var privRec by remember { mutableStateOf(false) }
    var privHot by remember { mutableStateOf(false) }
    val camShow = IslandBus.camActive && IslandBus.privacyEnabled
    val micShow = IslandBus.micActive && IslandBus.privacyEnabled
    val recShow = IslandBus.recActive && IslandBus.statusPills
    val hotShow = IslandBus.hotspotActive && IslandBus.statusPills
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
    val prev = remember { IslandPrev() }
    val splitNow by rememberUpdatedState(split)
    val splitLeftNow by rememberUpdatedState(splitLeft)

    // Opening something: the part being opened is the one that grows. If it was the small pill, the island starts
    // from there. The other part simply fades away.
    LaunchedEffect(state, splitOn, bubblePhase) {
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
                launch { left.animateTo(targetLeft, wSpec) }
                launch { right.animateTo(targetRight, wSpec) }
                launch { height.animateTo(targetHeight, hSpec) }
                launch { top.animateTo(targetTop, spec) }
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
        if (state == IslandState.Idle && !hole.real) 0f else 1f,
        label = "visibility",
    )
    val pressSpring = spring<Float>(dampingRatio = 0.45f, stiffness = 500f)
    val pressing = IslandBus.pressed && state != IslandState.Idle
    // While split, only the pill being touched squishes.
    val squish by animateFloatAsState(
        if (pressing && !(splitOn && IslandBus.pressedBubble)) 0.93f else 1f, pressSpring, label = "squish",
    )
    val squishBubble by animateFloatAsState(
        if (pressing && splitOn && IslandBus.pressedBubble) 0.93f else 1f, pressSpring, label = "squishBubble",
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
    val cameraCovered = fromBubbleNow || toBubbleNow || ghostKind != null
    val l = if (state == IslandState.Idle || cameraCovered) leftNow else minOf(leftNow, -minHalf)
    val r = if (state == IslandState.Idle || cameraCovered) rightNow else maxOf(rightNow, minHalf)
    val widthDp = (r - l).coerceAtLeast(1f)
    val centre = (l + r) / 2
    val corner = minOf(h / 2, OPEN_CORNER.value).dp
    val privOn = privAnim > 0.01f
    val ext = maxOf(-l, r, if (bubbleAlpha.value > 0.01f) rightExt.value else 0f, if (privOn) camD.value / 2 + 4f + privExtra else 0f)
    // Squishing keeps the top edge and the camera covered: it pivots on the camera and the top, and never gets
    // shorter than what is needed to hide the camera.
    val neededH = COMPACT.value / 2 + camD.value / 2 + 2f
    val minScaleY = (neededH / h).coerceAtMost(1f)
    val pivotX = (-l / widthDp).coerceIn(0f, 1f)

    // The touch area is a box TOUCH_PAD bigger than the island on every side. The visible island is drawn inside it.
    Box(
        modifier
            .offset { IntOffset(xShift.roundToPx(), (top.value.dp - TOUCH_PAD).roundToPx()) }
            .size((2 * ext).dp + TOUCH_PAD * 2, (if (privOn) maxOf(h, COMPACT.value) else h).dp + TOUCH_PAD * 2)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .islandGestures(),
        contentAlignment = Alignment.TopCenter,
    ) {
        // The camera pill, left behind while the small pill opens.
        val ghostShown = (if (fromBubbleNow) prev.main else if (toBubbleNow) splitLeft else ghostKind)?.takeIf { it != IslandState.Alert }
        if (ghostShown != null) {
            Box(
                Modifier.padding(top = TOUCH_PAD).offset(x = ((-leftExt + mainRight) / 2).value.dp)
                    .size(leftExt + mainRight, COMPACT)
                    .clip(RoundedCornerShape(COMPACT / 2)).background(Color.Black),
            ) {
                Box(Modifier.size(pillW, COMPACT).align(Alignment.CenterStart)) { MiniActivity(ghostShown) }
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
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (toBubbleNow) {
                // The closing island is the small pill's content on its way back into place.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MiniActivity(split!!) }
            } else if (splitOn) {
                Box(Modifier.size(pillW, COMPACT).align(Alignment.CenterStart)) { MiniActivity(splitLeft!!) }
            } else {
                AnimatedContent(
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
                        }
                    }
                }
            }
        }
        // The camera / microphone pill: grows out of the hole to the right, green dot on top (camera), orange below (mic).
        if (privOn) {
            // It grows from, and shrinks back into, the idle circle over the camera: width, height and position all
            // follow the same 0..1 progress, so closing is one smooth motion with no cut at the end.
            val f = privAnim.coerceAtLeast(0f)
            val idleD = IslandState.Idle.dims(hole).width.value
            val wide = camD.value / 2 + 4f
            val pl = -(idleD / 2 + (wide - idleD / 2) * f)
            val pr = idleD / 2 + (wide - idleD / 2) * f + privExtra * f
            val pillH = idleD + (COMPACT.value - idleD) * f
            val centre = hole.centerY.value + HOLE_NUDGE.value * (1f - f)
            val dy = (centre - pillH / 2) - top.value
            if (!state.isTall()) {
                Box(
                    Modifier.padding(top = TOUCH_PAD).offset(x = ((pl + pr) / 2).dp, y = dy.dp)
                        // requiredSize: exactly this size, whatever space the layout around it offers.
                        .requiredSize((pr - pl).dp, pillH.dp)
                        .clip(RoundedCornerShape((pillH / 2).dp)).background(Color.Black),
                ) {
                    PrivacyDots(privCam, privMic, privRec, privHot, Modifier.align(Alignment.CenterEnd).padding(end = 7.dp).graphicsLayer { alpha = f })
                }
            } else {
                // Open island: just the dots, in its top right corner.
                PrivacyDots(
                    privCam, privMic, privRec, privHot,
                    Modifier.padding(top = TOUCH_PAD + 14.dp).offset(x = (r - 16f).dp),
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
                    .clip(RoundedCornerShape(COMPACT / 2)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) { MiniActivity(bubbleShown) }
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
            DotPair(rec, Color(0xFFFF453A), hot, Color(0xFF30D158), Modifier.align(Alignment.CenterStart))
        }
    }
}

/** Two dots, one above the other. When the second switches on it splits off the first; when one of two goes off, they merge. */
@Composable
private fun DotPair(a: Boolean, colorA: Color, b: Boolean, colorB: Color, modifier: Modifier) {
    val yA = remember { Animatable(0f) }
    val yB = remember { Animatable(0f) }
    val alphaA = remember { Animatable(0f) }
    val alphaB = remember { Animatable(0f) }
    var prevA by remember { mutableStateOf(false) }
    var prevB by remember { mutableStateOf(false) }
    LaunchedEffect(a, b) {
        val spec = spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
        // A dot that switches on starts where the other one is, then they move apart.
        if (a && !prevA) yA.snapTo(if (prevB) yB.value else 0f)
        if (b && !prevB) yB.snapTo(if (prevA) yA.value else 0f)
        prevA = a
        prevB = b
        val both = a && b
        coroutineScope {
            launch { alphaA.animateTo(if (a) 1f else 0f, tween(200)) }
            launch { alphaB.animateTo(if (b) 1f else 0f, tween(200)) }
            // A dot that switches off slides to the middle (where the remaining one is going) while it fades.
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
    Row(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BatteryIcon(IslandBus.batteryPercent, Modifier.size(24.dp, 12.dp))
        Text("${IslandBus.batteryPercent}%", color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChargingOpen() {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BatteryIcon(IslandBus.batteryPercent, Modifier.size(34.dp, 16.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Charging", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(fullIn(IslandBus.minutesToFull, IslandBus.batteryPercent), color = Gray, fontSize = 13.sp)
        }
        Text("${IslandBus.batteryPercent}%", color = Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}