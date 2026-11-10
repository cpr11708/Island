package com.example.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Shows the island from an accessibility service. Its windows sit above the status bar, so the island can be touched
 * in every app. Island does not read anything on screen: it only draws and listens to touches on its own window.
 * Android starts this service by itself once it is switched on, and keeps it running.
 */
class IslandAccessibilityService : AccessibilityService() {
    private var overlay: IslandOverlay? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastChip = ""
    private var peekedKey = ""

    /**
     * A new timer whose time is not known yet: pull the notification shade down for a moment, copy the countdown from
     * it, and close it again. Done once per timer, and never on the lock screen or with the screen off.
     */
    private fun maybePeek() {
        if (!IslandBus.timerReal || IslandBus.timerStopwatch || IslandBus.timerKnown || peekedKey == IslandBus.timerKey) return
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!pm.isInteractive || km.isKeyguardLocked) return
        peekedKey = IslandBus.timerKey
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        handler.postDelayed({
            if (readTimerChip()) performGlobalAction(GLOBAL_ACTION_BACK)
            else handler.postDelayed({ if (readTimerChip()) performGlobalAction(GLOBAL_ACTION_BACK) }, 700)
        }, 700)
    }
    private var cmdStart = 0L
    private val cmdLoop = object : Runnable {
        override fun run() {
            val cmd = IslandBus.timerCmd ?: return
            if (cmdStart == 0L) cmdStart = System.currentTimeMillis()
            val done = runCatching { tryTimerCommand(cmd) }.getOrDefault(false)
            val timedOut = System.currentTimeMillis() - cmdStart > 5000
            if (done || timedOut) {
                IslandBus.timerCmdDebug = if (done) "$cmd done" else "$cmd: button not found in Clock"
                IslandBus.timerCmd = null
                cmdStart = 0L
                if (done) handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 150)
            } else handler.postDelayed(this, 120)
        }
    }

    // A button pressed in the island starts the Clock automation within a tenth of a second, not at the next slow poll,
    // so the Clock is on screen for as short a time as possible.
    private val cmdWatch = object : Runnable {
        override fun run() {
            if (IslandBus.timerCmd != null && cmdStart == 0L) handler.post(cmdLoop)
            handler.postDelayed(this, 100)
        }
    }
    /**
     * For finding out how the notification shade looks to Android on the lock screen: a list of the windows that are
     * on screen while the phone is locked. Shown under Developer tools in the app.
     */
    private val lockWindowHistory = ArrayDeque<String>()
    private var lastLockWindows = ""
    private fun describeLockWindows() {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return
        val now = runCatching {
            windows.joinToString("\n") { w ->
                val r = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                "type=${w.type} layer=${w.layer} focus=${w.isFocused} active=${w.isActive} " +
                        "title=\"${w.title}\" pkg=${w.root?.packageName} ${r.width()}x${r.height()}"
            }
        }.getOrDefault("could not read windows")
        // Only a change is worth keeping: the last few are shown, newest first (open the shade, then unlock, then look).
        if (now == lastLockWindows) return
        lastLockWindows = now
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lockWindowHistory.addFirst("[$time]\n$now")
        while (lockWindowHistory.size > 8) lockWindowHistory.removeLast()
        IslandBus.lockWindowsDebug = lockWindowHistory.joinToString("\n---\n")
    }

    /** Window events from the system while locked (opening the shade may only show up here, not in the window list). */
    private fun logLockEvent(event: AccessibilityEvent) {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return
        if (event.packageName?.toString() == packageName) return
        val kind = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) "state" else "windows(change=${event.windowChanges})"
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lockWindowHistory.addFirst("[$time] event $kind pkg=${event.packageName} class=${event.className} text=${event.text}")
        while (lockWindowHistory.size > 8) lockWindowHistory.removeLast()
        IslandBus.lockWindowsDebug = lockWindowHistory.joinToString("\n---\n")
    }

    /**
     * On the lock screen Samsung keeps a small "Samsung Pay" swipe handle window on screen. It goes away while the
     * notification shade is pulled down and comes back when it closes. So once that window has been seen during this
     * lock, its absence means the shade is open.
     */
    private var spaySeen = false
    private var spayGoneSince = 0L
    private val shadeWatch = object : Runnable {
        override fun run() {
            val km = getSystemService(android.app.KeyguardManager::class.java)
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!km.isKeyguardLocked || !pm.isInteractive) {
                // Screen off, or unlocked: forget everything. When the screen comes on, the lock screen's windows take a
                // moment to appear, and that must not look like the shade being opened.
                spaySeen = false
                spayGoneSince = 0L
                if (IslandBus.lockShadeOpen) IslandBus.lockShadeOpen = false
            } else {
                val list = runCatching { windows }.getOrNull()
                if (list != null && list.isNotEmpty()) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val spay = list.any { w -> runCatching { w.root?.packageName?.toString() }.getOrNull()?.contains("spay") == true }
                    if (spay) {
                        spaySeen = true
                        spayGoneSince = 0L
                    } else if (spaySeen && spayGoneSince == 0L) spayGoneSince = now
                    // The handle has to be gone for a moment before it counts as the shade (not a flicker as the screen wakes).
                    val open = spaySeen && !spay && spayGoneSince != 0L && now - spayGoneSince > 350
                    if (open != IslandBus.lockShadeOpen) IslandBus.lockShadeOpen = open
                }
            }
            handler.postDelayed(this, 200)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            maybePeek()
            readTimerChip()
            if (IslandBus.timerCmd != null && cmdStart == 0L) handler.post(cmdLoop)
            describeLockWindows()
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * The outline only shows when the island would otherwise vanish: a screenshot is checked along a thin ring just
     * outside the island's edge, and if every point there is black, the outline is drawn. Checked about twice a second
     * while the island shows something (and the outline is switched on); nothing is kept or sent anywhere.
     */
    private var blackBusy = false
    private val blackWatch = object : Runnable {
        override fun run() {
            handler.postDelayed(this, 600)
            val pm = getSystemService(android.os.PowerManager::class.java)
            val rect = IslandBus.islandOnScreen
            if (!IslandBus.edgeOutline || IslandBus.state == IslandState.Idle || rect == null || !pm.isInteractive) {
                if (IslandBus.behindBlack) IslandBus.behindBlack = false
                return
            }
            if (blackBusy) return
            blackBusy = true
            runCatching {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(shot: ScreenshotResult) {
                        blackBusy = false
                        val black = runCatching { ringIsBlack(shot, rect) }.getOrDefault(false)
                        runCatching { shot.hardwareBuffer.close() }
                        if (IslandBus.behindBlack != black) IslandBus.behindBlack = black
                    }
                    override fun onFailure(errorCode: Int) { blackBusy = false }
                })
            }.onFailure { blackBusy = false }
        }
    }

    /**
     * True when the ring 7 dp outside [r] is black: at least 80% of its points (very dark grey counts as black), so
     * small things like status bar icons beside the island do not count against it.
     */
    private fun ringIsBlack(shot: ScreenshotResult, r: android.graphics.Rect): Boolean {
        val hw = android.graphics.Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace) ?: return false
        val d = resources.displayMetrics.density
        // A little way out, past most of the island's own soft glow (a pop-up has one).
        val out = (7 * d).toInt()
        // Only the strip of the screen around the island is copied out of the screenshot.
        val left = (r.left - out).coerceIn(0, hw.width - 1)
        val right = (r.right + out).coerceIn(left + 1, hw.width)
        val bottom = (r.bottom + out).coerceIn(1, hw.height)
        val strip = android.graphics.Bitmap.createBitmap(hw, left, 0, right - left, bottom)
            .copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        hw.recycle()
        fun dark(x: Int, y: Int): Boolean {
            val c = strip.getPixel((x - left).coerceIn(0, strip.width - 1), y.coerceIn(0, strip.height - 1))
            // Very dark grey (like the faint end of that glow) still counts as black.
            return maxOf(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)) <= 20
        }
        val step = (5 * d).toInt().coerceAtLeast(1)
        var seen = 0
        var darkCount = 0
        fun look(x: Int, y: Int) { seen++; if (dark(x, y)) darkCount++ }
        try {
            // The two sides, from the top of the screen down past the island's bottom.
            for (y in 0..bottom - 1 step step) {
                if (r.left - out >= 0) look(r.left - out, y)
                if (r.right + out < hw.width) look(r.right + out, y)
            }
            // Below it, and above it when there is room between it and the top of the screen.
            for (x in (r.left - out)..(r.right + out) step step) {
                if (x < 0 || x >= hw.width) continue
                look(x, bottom - 1)
                if (r.top - out >= 0) look(x, r.top - out)
            }
            // Mostly black counts: the status bar's small white icons beside the island do not make the screen light.
            return seen > 0 && darkCount >= seen * 0.8f
        } finally {
            strip.recycle()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        IslandBus.appContext = applicationContext
        // If the fallback overlay is running, stop it so there is only one island.
        stopService(Intent(this, IslandService::class.java))
        overlay?.stop()
        overlay = IslandOverlay(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also { it.start() }
        handler.removeCallbacks(poll)
        handler.post(poll)
        handler.removeCallbacks(shadeWatch)
        handler.post(shadeWatch)
        handler.removeCallbacks(cmdWatch)
        handler.post(cmdWatch)
        handler.removeCallbacks(blackWatch)
        handler.post(blackWatch)
    }

    // Only used to learn which app is in front, so the music player can stay hidden while you are in the music app.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        logLockEvent(event)
        // Ask Android which window is really in front. Some phones only report the app launcher or recents in the event.
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            ?: event.packageName?.toString() ?: return
        // Island's own settings screen is an app like any other (so leaving the Clock for it shows the timer again),
        // but the island's own windows are not.
        if (pkg == packageName) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.className?.toString()?.endsWith("Activity") == true) {
                IslandBus.foregroundPackage = pkg
            }
            return
        }
        // Ignore system pop-ups (shade, volume) and keyboards: none of them mean you changed app.
        if (pkg == "com.android.systemui" || pkg == "android" ||
            pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("honeyboard")
        ) return
        IslandBus.foregroundPackage = pkg
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.rebuild()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(shadeWatch)
        handler.removeCallbacks(cmdWatch)
        handler.removeCallbacks(blackWatch)
        overlay?.stop()
        overlay = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(blackWatch)
        handler.removeCallbacks(cmdWatch)
        overlay?.stop()
        overlay = null
        super.onDestroy()
    }

    /**
     * Samsung's timer notification has no time in it, so the island keeps its own countdown and only needs to be told
     * the time left once. Whenever the Clock app's timer screen or the open notification shade is on screen, copy the
     * countdown from it. (The status bar chip is not used.) After that the island counts down by itself.
     */
    private fun readTimerChip(): Boolean {
        if (!IslandBus.timerReal || IslandBus.timerStopwatch) return false
        val list = try { windows } catch (_: Exception) { return false }
        for (w in list) {
            val root = w.root ?: continue
            val pkg = root.packageName?.toString()
            if (pkg != "com.android.systemui" && pkg != "com.sec.android.app.clockpackage") continue
            val t = ArrayList<String>()
            collect(root, t, 0)
            val shown = findCountdown(t, pkg == "com.sec.android.app.clockpackage") ?: continue
            val (idx, ms) = shown
            val paused = t.subList((idx - 6).coerceAtLeast(0), (idx + 7).coerceAtMost(t.size)).any { it.equals("Resume", true) }
            if (paused) {
                IslandBus.timerPausedMs = ms
                IslandBus.timerPaused = true
            } else {
                IslandBus.timerEndMs = System.currentTimeMillis() + ms
                IslandBus.timerPaused = false
            }
            IslandBus.timerKnown = true
            IslandBus.timerChipDebug = "read from ${pkg?.substringAfterLast('.')}: ${ms / 1000}s left${if (paused) " (paused)" else ""}"
            return true
        }
        return false
    }

    private val durationRe = Regex("""^(\d+\s*h)?\s*(\d+\s*m)?\s*(\d+\s*s)?$""")
    private val slashRe = Regex("""^.*\d+\s*[hm].*/\s*\d{1,2}:\d{2}$""")

    /** Finds the countdown among the texts on screen, in screen order. Returns its position and the time in ms. */
    private fun findCountdown(t: List<String>, inClockApp: Boolean): Pair<Int, Long>? {
        for (i in t.indices) {
            // Notification: "1:19:56", then "1 h 20 m / 14:25".
            if (i > 0 && slashRe.matches(t[i]) && timeRegex.matches(t[i - 1])) return (i - 1) to (parseClock(t[i - 1]) ?: continue)
            // Clock app: "1 h 20 m", then "1:19:57".
            if (inClockApp && i + 1 < t.size && t[i].isNotBlank() && durationRe.matches(t[i]) && timeRegex.matches(t[i + 1])) {
                return (i + 1) to (parseClock(t[i + 1]) ?: continue)
            }
        }
        return null
    }

    private fun collect(node: android.view.accessibility.AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 25) return
        (node.text ?: node.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    private val timeRegex = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")

    private fun parseClock(text: String): Long? {
        val parts = text.split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            2 -> (parts[0] * 60 + parts[1]) * 1000
            else -> null
        }
    }

    /** Looks for the right button in the Clock app's window and presses it. Returns true once pressed. */
    private fun tryTimerCommand(cmd: String): Boolean {
        // The stopwatch's "pause" is its Stop / Start button, and its "cancel" is Reset.
        val sw = IslandBus.timerStopwatch
        val want = if (cmd == "pause") Regex(if (sw) "stop|start|pause|resume|continue" else "pause|resume|start|play|continue", RegexOption.IGNORE_CASE)
        else Regex(if (sw) "reset|cancel|delete" else "cancel|delete|stop|reset|close|dismiss", RegexOption.IGNORE_CASE)
        val seen = StringBuilder()
        for (w in windows) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != "com.sec.android.app.clockpackage") continue
            val hit = findButton(root, want, seen, 0)
            if (hit != null) {
                var n: android.view.accessibility.AccessibilityNodeInfo? = hit
                while (n != null && !n.isClickable) n = n.parent
                if (n != null && n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                    if (cmd == "pause") {
                        if (IslandBus.timerPaused) {
                            val resumed = System.currentTimeMillis()
                            IslandBus.timerEndMs = if (sw) resumed - IslandBus.timerPausedMs else resumed + IslandBus.timerPausedMs
                            IslandBus.timerPaused = false
                        } else {
                            IslandBus.timerPausedMs = IslandBus.timerRemainingMs()
                            IslandBus.timerPaused = true
                        }
                    }
                    return true
                }
            }
        }
        if (seen.isNotEmpty()) IslandBus.timerCmdDebug = "looking, Clock shows: $seen"
        return false
    }

    private fun findButton(
        node: android.view.accessibility.AccessibilityNodeInfo,
        want: Regex,
        seen: StringBuilder,
        depth: Int,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (depth > 15) return null
        val label = (node.contentDescription ?: node.text)?.toString()?.trim().orEmpty()
        if (label.isNotEmpty() && seen.length < 300) seen.append(label.take(20)).append(" | ")
        if (label.isNotEmpty() && label.length <= 20 && want.containsMatchIn(label)) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findButton(c, want, seen, depth + 1)?.let { return it }
        }
        return null
    }

}