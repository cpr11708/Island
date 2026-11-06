package com.example.island

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Follows whatever app is playing media. When playback starts or the track changes, the island pops out for a few
 * seconds. Needs "Notification access", which is why it is started from [MediaListenerService].
 */
class MediaMonitor(private val context: Context, private val listener: ComponentName) {

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private var controller: MediaController? = null
    private var lastTitle = ""
    private var lastArt: Bitmap? = null
    private var wasPlaying = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        // Any watched session changing can change which one we should follow, so look at all of them again.
        override fun onMetadataChanged(metadata: MediaMetadata?) = pick(activeSessions())
        override fun onPlaybackStateChanged(state: PlaybackState?) = pick(activeSessions())
        override fun onSessionDestroyed() = pick(activeSessions())
    }

    fun start() {
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, listener)
            pick(activeSessions())
        } catch (e: SecurityException) {
            IslandBus.mediaStatus = "No notification access (SecurityException)"
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hidePending = false
    private val hideWhenIdle = Runnable {
        hidePending = false
        if (!IslandBus.mediaPlaying) {
            IslandBus.mediaLive = false
            hideIfShowing()
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        hidePending = false
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChanged) }
        watched.forEach { it.unregisterCallback(callback) }
        watched = emptyList()
        controller = null
    }

    private fun activeSessions(): List<MediaController> =
        try { manager.getActiveSessions(listener) } catch (_: SecurityException) { emptyList() }

    private var watched: List<MediaController> = emptyList()

    private fun pick(all: List<MediaController>) {
        // The phone app keeps a session for the call itself. It is not music, so ignore it.
        val list = all.filter { it.packageName != "com.android.server.telecom" }

        // Listen to every session, so we notice when a different app starts playing.
        val known = watched.map { it.sessionToken }.toSet()
        val now = list.map { it.sessionToken }.toSet()
        watched.filter { it.sessionToken !in now }.forEach { it.unregisterCallback(callback) }
        list.filter { it.sessionToken !in known }.forEach { it.registerCallback(callback) }
        watched = list

        val best = list.firstOrNull { isActive(it.playbackState?.state) } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller = best
            lastTitle = ""
            wasPlaying = false
        }
        val c = controller
        IslandBus.mediaPackage = c?.packageName ?: ""
        IslandBus.mediaStatus = if (c == null) "Connected, ${list.size} media sessions, none active"
        else "Following ${c.packageName} (${list.size} sessions), state ${c.playbackState?.state}"
        IslandBus.mediaSeek = { ms -> c?.transportControls?.seekTo(ms) }
        IslandBus.mediaPrev = { c?.transportControls?.skipToPrevious() }
        IslandBus.mediaNext = { c?.transportControls?.skipToNext() }
        IslandBus.mediaPlayPause = {
            if (IslandBus.mediaPlaying) c?.transportControls?.pause()
            else c?.transportControls?.play()
        }
        refresh()
    }

    private fun isActive(state: Int?): Boolean = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    private fun refresh() {
        val c = controller
        if (c == null) {
            IslandBus.mediaPlaying = false
            IslandBus.mediaTitle = ""
            IslandBus.mediaArtist = ""
            IslandBus.mediaArt = null
            IslandBus.mediaLive = false
            IslandBus.mediaOpen = null
            hideIfShowing()
            wasPlaying = false
            return
        }
        val m = c.metadata
        val s = c.playbackState
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        // Skipping or scrubbing makes players report short "buffering" or "skipping" states. Those must not look like
        // a pause, so they keep whatever the button showed before.
        val playing = when (s?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> true
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> IslandBus.mediaPlaying
            else -> false // paused, stopped, none, error
        }

        IslandBus.mediaTitle = title
        IslandBus.mediaArtist = artist
        if (art !== lastArt) {
            lastArt = art
            IslandBus.mediaArt = art?.asImageBitmap()
            IslandBus.mediaAccent = art?.let { accentOf(it) } ?: Color(0xFFD96C9B)
        }
        IslandBus.mediaDuration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        IslandBus.mediaPosBase = s?.position ?: 0L
        IslandBus.mediaPosTime = s?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime()
        IslandBus.mediaSpeed = s?.playbackSpeed ?: 1f
        IslandBus.mediaPlaying = playing
        if (playing) IslandBus.mediaLive = true
        IslandBus.mediaOpen = c.sessionActivity
        IslandBus.appContext = context.applicationContext

        val changed = title != lastTitle
        lastTitle = title
        if (playing && title.isNotEmpty() && (!wasPlaying || changed)) show()
        wasPlaying = playing

        // Nothing playing for a while: hide the player.
        if (playing) {
            handler.removeCallbacks(hideWhenIdle)
            hidePending = false
        } else if (!hidePending) {
            hidePending = true
            handler.postDelayed(hideWhenIdle, NOT_PLAYING_MS)
        }
    }

    /** The most colourful, reasonably bright colour in the cover. Grey covers give a soft white. */
    private fun accentOf(bitmap: Bitmap): Color {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val px = small.getPixel(x, y)
            android.graphics.Color.colorToHSV(px, hsv)
            val weight = (hsv[1] * hsv[2]).toDouble().let { it * it }
            r += android.graphics.Color.red(px) * weight
            g += android.graphics.Color.green(px) * weight
            b += android.graphics.Color.blue(px) * weight
            total += weight
        }
        if (total < 1.0) return Color(0xFFE5E5EA) // a grey or very dark cover
        android.graphics.Color.colorToHSV(
            android.graphics.Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt()), hsv,
        )
        hsv[1] = hsv[1].coerceIn(0.35f, 1f)
        hsv[2] = hsv[2].coerceIn(0.8f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    private fun show() {
        if (IslandBus.state == IslandState.Idle) {
            IslandBus.autoIdle = false
            IslandBus.state = IslandState.Music
        }
    }

    private fun hideIfShowing() {
        if (IslandBus.state == IslandState.Music || IslandBus.state == IslandState.MusicOpen) {
            IslandBus.autoIdle = false
            // A call or timer that is still going takes over; otherwise the island goes idle.
            IslandBus.state = IslandBus.otherActive(IslandState.Music) ?: IslandState.Idle
        }
    }


    companion object {
        /** How long music may be paused or stopped before the player goes away. */
        const val NOT_PLAYING_MS = 10_000L
    }
}