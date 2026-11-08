package com.example.island

import android.app.Notification
import android.os.Bundle
import android.app.PendingIntent
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * Having notification access lets Island do two things:
 *  - [MediaMonitor] sees which app is playing media.
 *  - This service reads the phone app's call notification, to show the real caller, the call time and its buttons.
 * Android starts and stops this service by itself.
 */
class MediaListenerService : NotificationListenerService() {
    private var monitor: MediaMonitor? = null

    override fun onListenerConnected() {
        IslandBus.mediaStatus = "Listener connected"
        monitor?.stop()
        monitor = MediaMonitor(applicationContext, ComponentName(this, MediaListenerService::class.java)).also { it.start() }
        checkCalls()
        checkTimers()
        checkNav()
        checkStatus()
    }

    override fun onListenerDisconnected() {
        IslandBus.mediaStatus = "Listener disconnected"
        monitor?.stop()
        monitor = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        checkCalls()
        checkTimers()
        checkNav()
        checkStatus()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        checkCalls()
        checkTimers()
        checkNav()
        checkStatus()
    }

    /** Screen recording and hotspot are shown by the phone as ongoing notifications: their presence is the signal. */
    private fun checkStatus() {
        val all = try { activeNotifications } catch (_: SecurityException) { null } ?: return
        var rec = false
        var hot = false
        var recWhy = ""
        for (sbn in all) {
            val n = sbn.notification
            val ex = n.extras
            val pkg = sbn.packageName
            val ch = (n.channelId ?: "").lowercase()
            val words = ((ex.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString() + " " +
                    (ex.getCharSequence(Notification.EXTRA_TEXT) ?: "")).lowercase()
            // Only an ongoing notification counts: a finished recording leaves a normal "saved" one behind.
            if (sbn.isOngoing && (
                        (pkg.contains("smartcapture") && ("recording" in words || "recording" in ch)) ||
                                (pkg == "com.android.systemui" && ("screenrecord" in ch || "screen_record" in ch)) ||
                                "screen record" in words)
            ) { rec = true; recWhy = "$pkg ch=$ch: ${words.take(60)}" }
            if ((pkg == "android" || pkg == "com.android.systemui" || pkg.contains("settings") || pkg.contains("wifi") || pkg.contains("tether")) &&
                ("hotspot" in words || "tethering" in words || "tether" in ch || "hotspot" in ch)
            ) hot = true
        }
        IslandBus.recActive = rec
        IslandBus.recDebug = if (rec) recWhy else ""
        IslandBus.hotspotNote = hot
    }

    private fun looksLikeCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val ex = n.extras
        val isCallCategory = n.category == Notification.CATEGORY_CALL
        val hasCallStyle = ex.containsKey("android.callType")
        val fromDialer = sbn.packageName.contains("dialer") || sbn.packageName.contains("incallui") ||
                sbn.packageName.contains("telecom")
        val ongoing = sbn.isOngoing || n.fullScreenIntent != null
        return (isCallCategory || hasCallStyle) && ongoing && (isCallCategory || fromDialer || hasCallStyle)
    }

    private fun checkCalls() {
        val all = try { activeNotifications } catch (_: SecurityException) { null } ?: return
        IslandBus.callDebug = all
            .filter { it.notification.category == Notification.CATEGORY_CALL || it.packageName.contains("dialer") }
            .joinToString("; ") { "${it.packageName} cat=${it.notification.category} ongoing=${it.isOngoing}" }
            .ifEmpty { "No call notifications seen" }

        val sbn = all.firstOrNull { looksLikeCall(it) }
        if (sbn == null) {
            callEnded()
            return
        }

        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        // android.callType: 1 = incoming, 2 = ongoing, 3 = screening. Not every phone app sets it, so also read the text.
        val type = ex.getInt("android.callType", 0)
        // Other call apps (WhatsApp, say) often set no call type: they are ringing if the text says so, or if one of
        // their buttons is Answer / Accept.
        val hasAnswer = (n.actions ?: emptyArray()).any { Regex("answer|accept", RegexOption.IGNORE_CASE).containsMatchIn(it.title ?: "") }
        val incoming = type == 1 || (type != 2 && (hasAnswer || Regex("incoming|ringing|calling", RegexOption.IGNORE_CASE).containsMatchIn(text)))
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        // Without a chronometer, the post time is all there is, but a repost (screen turning on, say) changes it.
        // So a call that is already being followed keeps the start time it had.
        val sameCall = IslandBus.callKey == sbn.key && IslandBus.callStartMs > 0L && !IslandBus.callIncoming
        // Best source first: a clock inside the notification's own layout, then the notification's chronometer, then the
        // moment a ringing call turned into a connected one, then the post time.
        val now = System.currentTimeMillis()
        val answeredJustNow = IslandBus.callKey == sbn.key && IslandBus.callIncoming && !incoming
        // Once a call has a start time, it keeps it: a notification's clock restarts whenever it is re-posted, which
        // made the island's timer start over every few seconds.
        // The phone app sometimes removes and re-posts its notification when it updates: the same call, a moment later.
        val resumeStart = if (IslandBus.lastCallStart > 0L && now - IslandBus.lastCallEndedAt < 10_000 &&
            IslandBus.lastCallName == title) IslandBus.lastCallStart else 0L
        // The phone app's own notification time ("when") is exactly what its status chip counts from.
        val whenOk = n.`when` > 0L && now - n.`when` in 0L..(12 * 3600_000L)
        val start = when {
            incoming -> 0L
            whenOk -> n.`when`
            sameCall -> IslandBus.callStartMs
            resumeStart > 0L -> resumeStart
            answeredJustNow -> now
            chrono -> n.`when`
            else -> sbn.postTime
        }
        val usedSource = when {
            incoming -> "ringing"
            whenOk -> "notification time (same as the phone's chip)"
            sameCall -> "kept from the first sighting"
            resumeStart > 0L -> "kept (notification was re-posted)"
            answeredJustNow -> "answered now"
            chrono -> "chronometer"
            else -> "post time"
        }
        IslandBus.callTimeDebug = "used=$usedSource | posted ${(now - sbn.postTime) / 1000}s ago, when ${(now - n.`when`) / 1000}s ago, chronometer=$chrono, type=$type"

        val wanted = Regex("answer|accept|decline|reject|hang|end", RegexOption.IGNORE_CASE)
        val actions = (n.actions ?: emptyArray())
            .filter { it.actionIntent != null && !it.title.isNullOrBlank() }
            .let { list -> list.filter { wanted.containsMatchIn(it.title) }.ifEmpty { list } }
            .take(2)
            .map { a ->
                CallAction(
                    label = a.title.toString(),
                    accept = Regex("answer|accept", RegexOption.IGNORE_CASE).containsMatchIn(a.title),
                    intent = a.actionIntent,
                )
            }

        val wasIncoming = IslandBus.callIncoming
        val isNewCall = IslandBus.callKey != sbn.key
        IslandBus.callReal = true
        IslandBus.callKey = sbn.key
        IslandBus.callPackage = sbn.packageName
        IslandBus.callName = title
        IslandBus.callIncoming = incoming
        IslandBus.callStartMs = start
        IslandBus.callActions = actions
        IslandBus.callMuteIntent = (n.actions ?: emptyArray())
            .firstOrNull { it.actionIntent != null && Regex("mute", RegexOption.IGNORE_CASE).containsMatchIn(it.title ?: "") }
            ?.actionIntent
        IslandBus.callOpen = n.contentIntent

        if (IslandBus.callDismissedKey == sbn.key) return // swiped away: stays hidden for this call
        val s = IslandBus.state
        when {
            // Ringing: open, so the buttons are right there.
            incoming -> if (s != IslandState.CallOpen) {
                IslandBus.autoIdle = false
                IslandBus.state = IslandState.CallOpen
            }
            // Answered: shrink to the compact call.
            wasIncoming && s == IslandState.CallOpen -> IslandBus.state = IslandState.Call
            // A new ongoing call, or one that took over from something else.
            isNewCall || (s != IslandState.Call && s != IslandState.CallOpen) -> {
                IslandBus.autoIdle = false
                IslandBus.state = IslandState.Call
            }
        }
    }

    private fun looksLikeTimer(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        if (!(pkg.contains("deskclock") || pkg.contains("clockpackage"))) return false
        val ex = sbn.notification.extras
        val words = ((ex.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString() + " " +
                (ex.getCharSequence(Notification.EXTRA_TEXT) ?: "")).lowercase()
        // A Clock notification with a running chronometer is a timer or a stopwatch even if its words are unexpected.
        return "stopwatch" in words || "timer" in words || ex.getBoolean("android.chronometerCountDown") ||
                ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
    }

    private fun isStopwatch(sbn: StatusBarNotification): Boolean {
        val ex = sbn.notification.extras
        val words = ((ex.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString() + " " +
                (ex.getCharSequence(Notification.EXTRA_TEXT) ?: "")).lowercase()
        if ("stopwatch" in words) return true
        // A chronometer that counts up (and is not called a timer) is a stopwatch.
        return ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && !ex.getBoolean("android.chronometerCountDown") && "timer" !in words
    }

    private fun checkTimers() {
        val all = try { activeNotifications } catch (_: SecurityException) { null } ?: return
        // Everything the Clock app has posted, whatever it says: shown in the app to help when a timer is not recognised.
        IslandBus.clockDebug = all.filter { it.packageName.contains("deskclock") || it.packageName.contains("clockpackage") }
            .joinToString("\n") { s ->
                val e = s.notification.extras
                "${s.packageName.substringAfterLast('.')}: title=[${e.getCharSequence(Notification.EXTRA_TITLE)}] " +
                        "text=[${e.getCharSequence(Notification.EXTRA_TEXT)}] chrono=${e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)} " +
                        "countDown=${e.getBoolean("android.chronometerCountDown")} timer=${looksLikeTimer(s)} stopwatch=${isStopwatch(s)}"
            }.ifEmpty { "no Clock notifications right now" }
        // A real timer wins over a stopwatch when both are running.
        val sbn = all.filter { looksLikeTimer(it) }.minByOrNull { if (isStopwatch(it)) 1 else 0 }
        if (sbn == null) {
            timerEnded()
            return
        }
        if (IslandBus.timerKey != sbn.key) IslandBus.timerKnown = false // a different timer: forget the old time
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val now = System.currentTimeMillis()
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countDownFlag = ex.getBoolean("android.chronometerCountDown")
        val remote = readRemote(n)
        // Samsung keeps the time and the Pause / Cancel buttons of its Clock notification in "ongoing activity" extras.
        val ongoingStrings = mutableListOf<String>()
        val ongoingActions = mutableListOf<CallAction>()
        for (k in ex.keySet()) if (k.contains("ongoingActivity", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            when (val v = ex.get(k)) {
                is Bundle -> walkBundle(v, k, ongoingStrings, ongoingActions, 0)
                is CharSequence -> ongoingStrings += "$k=$v"
                is PendingIntent -> ongoingActions += CallAction(k.substringAfterLast('.'), false, v)
                else -> ongoingStrings += "$k=${v?.toString()?.take(30)}"
            }
        }
        val timeRe = Regex("^\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*$")
        val ongoingMs: Long? = ongoingStrings.firstNotNullOfOrNull { e ->
            val m = timeRe.find(e.substringAfter('=', ""))
            m?.let {
                val a = it.groupValues[1].toLong(); val b = it.groupValues[2].toLong()
                val c = it.groupValues[3].takeIf { x -> x.isNotEmpty() }?.toLong()
                (if (c != null) a * 3600 + b * 60 + c else a * 60 + b) * 1000
            }
        }

        // Everything text-like in the notification, in case the time is only written out.
        // Android 16 "promoted" notifications can carry the time as short text shown in the status bar chip.
        val critical = runCatching {
            Notification::class.java.getMethod("getShortCriticalText").invoke(n)?.toString()
        }.getOrNull().orEmpty()
        @Suppress("DEPRECATION")
        val extrasDump = ex.keySet().sorted().joinToString(", ") { k -> "$k=${ex.get(k)?.toString()?.take(30)}" }

        val blob = listOf(
            title, text, critical,
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            ex.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty(),
            ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty(),
        ).plus(remote.texts).joinToString(" ")

        IslandBus.timerDebug = "chrono=$chrono countDown=$countDownFlag when-now=${(n.`when` - now) / 1000}s " +
                "customViews=${n.contentView != null}/${n.bigContentView != null} remoteEnd=${remote.countdownEndMs?.let { (it - now) / 1000 }} " +
                "ongoing=${ongoingStrings.joinToString(";").take(400)} ongoingActions=${ongoingActions.map { it.label }} critical=[$critical] flags=${n.flags} actions=${n.actions?.map { it.title }} " +
                "text=[${blob.replace('\n', ' ').take(160)}] extras={${extrasDump.take(700)}}"

        val timeInText = Regex("(\\d+):(\\d{2})(?::(\\d{2}))?").find(blob)
        // Samsung writes "1 h 20 m / 08:21": the timer's length and the time of day it goes off. The notification's own
        // time is when the timer started, so start + length is the exact end. The time of day checks it.
        val endFromText: Long? = Regex("(?:(\\d+)\\s*h)?\\s*(?:(\\d+)\\s*m)?\\s*(?:(\\d+)\\s*s)?\\s*/\\s*(\\d{1,2}):(\\d{2})")
            .find(blob)?.let { m ->
                val h = m.groupValues[1].toLongOrNull() ?: 0L
                val mi = m.groupValues[2].toLongOrNull() ?: 0L
                val se = m.groupValues[3].toLongOrNull() ?: 0L
                val lengthMs = ((h * 60 + mi) * 60 + se) * 1000
                val cal = java.util.Calendar.getInstance().apply {
                    timeInMillis = now
                    set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[4].toInt() % 24)
                    set(java.util.Calendar.MINUTE, m.groupValues[5].toInt())
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    if (timeInMillis < now - 60_000) add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                val goesOff = cal.timeInMillis
                val exact = n.`when` + lengthMs
                if (lengthMs > 0 && Math.abs(exact - (goesOff + 30_000)) <= 60_000) exact else goesOff + 30_000
            }
        val isSw = isStopwatch(sbn)
        IslandBus.timerStopwatch = isSw
        IslandBus.timerFromNotification = ongoingMs != null || endFromText != null
        if (isSw) {
            // A stopwatch counts up: the island remembers the moment it started.
            val paused = ongoingActions.any { it.label.contains("resume", true) || it.label.contains("start", true) } ||
                    (n.actions?.any { a -> a.title?.toString()?.let { t -> t.contains("resume", true) || t.contains("start", true) } == true } == true)
            val elapsed: Long? = when {
                chrono && !countDownFlag && n.`when` in 1..now -> now - n.`when`
                ongoingMs != null -> ongoingMs
                timeInText != null -> {
                    val a = timeInText.groupValues[1].toLong()
                    val b = timeInText.groupValues[2].toLong()
                    val c = timeInText.groupValues[3].takeIf { it.isNotEmpty() }?.toLong()
                    (if (c != null) a * 3600 + b * 60 + c else a * 60 + b) * 1000
                }
                else -> null
            }
            if (elapsed != null) {
                if (paused) {
                    IslandBus.timerPausedMs = elapsed
                    IslandBus.timerPaused = true
                } else {
                    val start = now - elapsed
                    if (!IslandBus.timerKnown || IslandBus.timerPaused || Math.abs(start - IslandBus.timerEndMs) > 2500) IslandBus.timerEndMs = start
                    IslandBus.timerPaused = false
                }
                IslandBus.timerKnown = true
            } else if (!IslandBus.timerKnown) {
                // The notification doesn't say how long it has run: count from when it was posted.
                IslandBus.timerEndMs = if (n.`when` in 1..now) n.`when` else now
                IslandBus.timerPaused = false
                IslandBus.timerKnown = true
            }
        } else when {
            ongoingMs != null -> {
                val paused = ongoingActions.any { it.label.contains("resume", true) || it.label.contains("start", true) }
                if (paused) {
                    IslandBus.timerPausedMs = ongoingMs
                    IslandBus.timerPaused = true
                } else {
                    IslandBus.timerEndMs = System.currentTimeMillis() + ongoingMs
                    IslandBus.timerPaused = false
                }
                IslandBus.timerKnown = true
            }
            endFromText != null -> {
                IslandBus.timerEndMs = endFromText
                IslandBus.timerPaused = false
                IslandBus.timerKnown = true
            }
            // The normal case: a countdown clock whose end time is the notification's time.
            chrono && (countDownFlag || n.`when` > now) -> {
                IslandBus.timerPaused = false
                IslandBus.timerEndMs = n.`when`
                IslandBus.timerKnown = true
            }
            // Phones whose Clock app draws its own notification layout with a countdown clock inside.
            remote.countdownEndMs != null -> {
                IslandBus.timerPaused = false
                IslandBus.timerEndMs = remote.countdownEndMs
                IslandBus.timerKnown = true
            }
            // Otherwise the time left is only written in the text, like "4:32". Treat it as the time left right now.
            timeInText != null -> {
                val a = timeInText.groupValues[1].toLong()
                val b = timeInText.groupValues[2].toLong()
                val c = timeInText.groupValues[3].takeIf { it.isNotEmpty() }?.toLong()
                IslandBus.timerPausedMs = (if (c != null) (a * 3600 + b * 60 + c) else (a * 60 + b)) * 1000
                IslandBus.timerPaused = true
                IslandBus.timerKnown = true
            }
            // Samsung's Clock leaves the time out of its notification. The accessibility service copies it from the
            // Clock app or the notification shade when either is on screen, and the island counts down from there.
            else -> Unit
        }

        val wanted = Regex("pause|resume|stop|cancel|delete|dismiss|add|reset|start", RegexOption.IGNORE_CASE)
        IslandBus.timerActions = (ongoingActions.filter { it.intent != null }.takeIf { it.isNotEmpty() }?.take(3)) ?: (n.actions ?: emptyArray())
            .filter { it.actionIntent != null && !it.title.isNullOrBlank() }
            .let { list -> list.filter { wanted.containsMatchIn(it.title) }.ifEmpty { list } }
            .take(3)
            .map { a -> CallAction(a.title.toString(), false, a.actionIntent) }
            // No buttons of its own: offer one that opens the Clock app.
            .ifEmpty { listOfNotNull(n.contentIntent?.let { CallAction("Open", false, it) }) }

        IslandBus.timerOpen = n.contentIntent
        IslandBus.timerRemotePress = { cmd -> pressRemoteButton(n, cmd) }
        val isNew = IslandBus.timerKey != sbn.key
        IslandBus.timerReal = true
        IslandBus.timerKey = sbn.key
        IslandBus.timerPackage = sbn.packageName
        IslandBus.timerLabel = if (isSw) "Stopwatch" else title.ifEmpty { "Timer" }

        if (IslandBus.timerDismissedKey == sbn.key) return // swiped away: stays hidden for this timer
        if (IslandBus.hiddenByApp(IslandState.Timer)) return // inside the Clock: nothing changes
        val s = IslandBus.state
        // A call comes first; otherwise a new timer takes over from music, charging and so on.
        // Priority: call > music > timer, so a timer never takes over a call or music (it shows beside them).
        val busy = s == IslandState.Call || s == IslandState.CallOpen || s == IslandState.Music || s == IslandState.MusicOpen
        val showing = s == IslandState.Timer || s == IslandState.TimerOpen
        if (!busy && (isNew || !showing)) {
            IslandBus.autoIdle = false
            IslandBus.state = IslandState.Timer
        }
    }

    /** Flattens a Bundle: collects its text values, and any button (a PendingIntent plus the text beside it). */
    private fun walkBundle(b: Bundle, path: String, strings: MutableList<String>, actions: MutableList<CallAction>, depth: Int) {
        if (depth > 5) return
        val intents = mutableListOf<Pair<String, PendingIntent>>()
        var label: String? = null
        for (k in b.keySet()) {
            @Suppress("DEPRECATION")
            val v = b.get(k)
            when (v) {
                is CharSequence -> {
                    strings += "$path.$k=$v"
                    if (label == null && Regex("title|text|label|name", RegexOption.IGNORE_CASE).containsMatchIn(k)) label = v.toString()
                }
                is PendingIntent -> intents += k to v
                is Bundle -> walkBundle(v, "$path.$k", strings, actions, depth + 1)
                is Array<*> -> v.forEachIndexed { i, x -> if (x is Bundle) walkBundle(x, "$path.$k[$i]", strings, actions, depth + 1) }
                is List<*> -> v.forEachIndexed { i, x -> if (x is Bundle) walkBundle(x, "$path.$k[$i]", strings, actions, depth + 1) }
                else -> if (v != null) strings += "$path.$k=${v.toString().take(30)}"
            }
        }
        for ((k, pi) in intents) actions += CallAction(label ?: k, false, pi)
    }

    private class Remote(val texts: List<String>, val countdownEndMs: Long?, val countUpStartMs: Long? = null)

    /** Some Clock apps draw the notification themselves. Build that layout to read its text and countdown clock. */
    private fun readRemote(n: Notification): Remote {
        val texts = mutableListOf<String>()
        var end: Long? = null
        var up: Long? = null
        fun walk(x: View) {
            if (x is Chronometer && x.isCountDown) end = System.currentTimeMillis() + (x.base - SystemClock.elapsedRealtime())
            // A count-up chronometer: its base is when it started, i.e. when the call was answered.
            if (x is Chronometer && !x.isCountDown) up = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - x.base)
            if (x is TextView) x.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        for (rv in listOfNotNull(n.bigContentView, n.contentView)) {
            try {
                walk(rv.apply(applicationContext, FrameLayout(applicationContext)))
            } catch (_: Throwable) {
                // That layout could not be built here: fine, the other sources are tried.
            }
        }
        return Remote(texts, end, up)
    }

    /**
     * Presses a button drawn inside the notification itself (Samsung draws its own layout, so its buttons are not in
     * the normal action list). Builds the layout, finds a clickable view whose text or description matches, clicks it.
     */
    private fun pressRemoteButton(n: Notification, cmd: String): Boolean {
        val words = if (cmd == "pause") Regex("pause|resume|play|start", RegexOption.IGNORE_CASE)
        else Regex("cancel|stop|delete|dismiss|reset", RegexOption.IGNORE_CASE)
        var done = false
        val seen = mutableListOf<String>()
        fun label(x: View): String {
            val parts = mutableListOf<String>()
            x.contentDescription?.let { parts += it.toString() }
            if (x is TextView) parts += x.text.toString()
            return parts.joinToString(" ")
        }
        fun walk(x: View) {
            if (done) return
            if (x.hasOnClickListeners()) seen += "[" + label(x).take(14) + "]"
            if (x.hasOnClickListeners() && words.containsMatchIn(label(x))) {
                done = x.performClick()
                return
            }
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        val views = listOfNotNull(n.bigContentView, n.contentView)
        for (rv in views) {
            try {
                walk(rv.apply(applicationContext, FrameLayout(applicationContext)))
            } catch (t: Throwable) { seen += "error ${t.javaClass.simpleName}" }
            if (done) break
        }
        if (!done) IslandBus.timerCmdDebug = "no button in notification (custom layouts: ${views.size}, clickable: ${seen.joinToString("").ifEmpty { "none" }})"
        return done
    }

    private val distanceRe = Regex("(\\d+(?:[.,]\\d+)?)\\s?(ft|feet|mi|miles|m|km|yd|yards)\\b", RegexOption.IGNORE_CASE)

    /** Google Maps' ongoing navigation notification: the next turn, the distance, the trip summary. */
    private fun checkNav() {
        val all = try { activeNotifications } catch (_: SecurityException) { null } ?: return
        val maps = all.filter { it.packageName == IslandBus.MAPS_PACKAGE }
        IslandBus.navDebug = maps.joinToString("; ") {
            val ex = it.notification.extras
            "cat=${it.notification.category} ongoing=${it.isOngoing} title=${ex.getCharSequence(Notification.EXTRA_TITLE)} " +
                    "text=${ex.getCharSequence(Notification.EXTRA_TEXT)} sub=${ex.getCharSequence(Notification.EXTRA_SUB_TEXT)} " +
                    "big=${ex.getCharSequence(Notification.EXTRA_BIG_TEXT)} info=${ex.getCharSequence(Notification.EXTRA_INFO_TEXT)} " +
                    "summary=${ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)} keys=${ex.keySet().filter { k -> k.startsWith("android.t") || k.startsWith("android.s") || k.startsWith("android.i") || k.startsWith("android.b") }}"
        }.ifEmpty { "No Google Maps notifications seen" }

        fun str(sbn: StatusBarNotification, key: String) = sbn.notification.extras.getCharSequence(key)?.toString().orEmpty()
        val sbn = maps.firstOrNull { it.isOngoing && it.notification.category == Notification.CATEGORY_NAVIGATION }
        // Not marked as navigation: an ongoing Maps notification that talks about a distance is still navigation.
            ?: maps.firstOrNull {
                it.isOngoing && distanceRe.containsMatchIn(
                    str(it, Notification.EXTRA_TITLE) + " " + str(it, Notification.EXTRA_TEXT) + " " + str(it, Notification.EXTRA_SUB_TEXT),
                )
            }
        if (sbn == null) {
            navEnded()
            return
        }
        val n = sbn.notification
        val title = str(sbn, Notification.EXTRA_TITLE)
        val text = str(sbn, Notification.EXTRA_TEXT)
        val sub = str(sbn, Notification.EXTRA_SUB_TEXT).ifEmpty { str(sbn, Notification.EXTRA_INFO_TEXT) }
        val isNew = IslandBus.navKey != sbn.key
        IslandBus.navReal = true
        IslandBus.navKey = sbn.key
        IslandBus.navTitle = title
        IslandBus.navText = text
        IslandBus.navSub = sub
        // Everything else Maps says (a longer text, the trip summary), shown in the expanded view.
        IslandBus.navMore = listOf(Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT)
            .map { str(sbn, it) }.filter { it.isNotBlank() && it != text && it != title && it != sub }.distinct().joinToString("\n")
        IslandBus.navDistance = distanceRe.find("$title $text")?.value?.replace(" ", "") ?: ""
        IslandBus.navOpen = n.contentIntent
        IslandBus.navActions = (n.actions ?: emptyArray())
            .filter { it.actionIntent != null && !it.title.isNullOrBlank() }
            .take(1)
            .map { CallAction(it.title.toString(), false, it.actionIntent) }
        IslandBus.navIcon = runCatching {
            n.getLargeIcon()?.loadDrawable(applicationContext)?.toBitmap(96, 96)?.asImageBitmap()
        }.getOrNull()

        if (IslandBus.navDismissedKey == sbn.key) return // swiped away: hidden for this trip
        if (IslandBus.hiddenByApp(IslandState.Nav)) return // inside Maps: nothing changes until you leave
        val s = IslandBus.state
        val busy = s.isTall()
        val showing = s == IslandState.Nav || s == IslandState.NavOpen
        if (!busy && (isNew || !showing)) {
            IslandBus.autoIdle = false
            IslandBus.state = IslandState.Nav
        }
    }

    private fun navEnded() {
        if (!IslandBus.navReal) return // the demo's navigation is not ours to clear
        IslandBus.navReal = false
        IslandBus.navKey = ""
        IslandBus.navDismissedKey = null
        IslandBus.navIcon = null
        if (IslandBus.state == IslandState.Nav || IslandBus.state == IslandState.NavOpen) {
            IslandBus.autoIdle = false
            IslandBus.state = IslandBus.restingState()
        }
    }

    private fun timerEnded() {
        if (!IslandBus.timerReal) return // the demo button's timer is not ours to clear
        IslandBus.timerReal = false
        IslandBus.timerStopwatch = false
        IslandBus.timerKnown = false
        IslandBus.timerKey = ""
        IslandBus.timerDismissedKey = null
        if (IslandBus.state == IslandState.Timer || IslandBus.state == IslandState.TimerOpen) {
            IslandBus.autoIdle = false
            IslandBus.state =
                if (IslandBus.mediaPlaying && IslandBus.mediaTitle.isNotEmpty()) IslandState.Music else IslandState.Idle
        }
    }

    private fun callEnded() {
        if (!IslandBus.callReal) return // the demo button's call is not ours to clear
        if (!IslandBus.callIncoming && IslandBus.callStartMs > 0L) {
            IslandBus.lastCallStart = IslandBus.callStartMs
            IslandBus.lastCallName = IslandBus.callName
            IslandBus.lastCallEndedAt = System.currentTimeMillis()
        }
        IslandBus.callReal = false
        IslandBus.callKey = ""
        IslandBus.callDismissedKey = null
        IslandBus.callIncoming = false
        if (IslandBus.state == IslandState.Call || IslandBus.state == IslandState.CallOpen) {
            IslandBus.autoIdle = false
            // Back to the music pill if something is still playing, otherwise idle.
            IslandBus.state = when {
                // A timer that kept running during the call comes back.
                else -> IslandBus.restingState()
            }
        }
    }
}