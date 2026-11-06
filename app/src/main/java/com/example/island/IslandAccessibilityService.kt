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
        if (!IslandBus.timerReal || IslandBus.timerKnown || peekedKey == IslandBus.timerKey) return
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
                if (done) handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 400)
            } else handler.postDelayed(this, 300)
        }
    }
    private var sameCount = 0
    private val poll = object : Runnable {
        override fun run() {
            maybePeek()
            readTimerChip()
            if (IslandBus.timerCmd != null && cmdStart == 0L) handler.post(cmdLoop)
            handler.postDelayed(this, 1000)
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
    }

    // Only used to learn which app is in front, so the music player can stay hidden while you are in the music app.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // Ask Android which window is really in front. Some phones only report the app launcher or recents in the event.
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            ?: event.packageName?.toString() ?: return
        // Ignore Island itself, system pop-ups (shade, volume) and keyboards: none of them mean you changed app.
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android" ||
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
        overlay?.stop()
        overlay = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
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
        if (!IslandBus.timerReal) return false
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
        val want = if (cmd == "pause") Regex("pause|resume|start|play|continue", RegexOption.IGNORE_CASE)
        else Regex("cancel|delete|stop|reset|close|dismiss", RegexOption.IGNORE_CASE)
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
                            IslandBus.timerEndMs = System.currentTimeMillis() + IslandBus.timerPausedMs
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