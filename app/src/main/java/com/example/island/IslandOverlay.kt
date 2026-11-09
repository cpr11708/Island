package com.example.island

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Draws the island in windows above every app. [windowType] is TYPE_APPLICATION_OVERLAY for a normal overlay, or
 * TYPE_ACCESSIBILITY_OVERLAY, which sits above the status bar so the island can be touched there too.
 *
 * There are two windows, and neither ever changes size, so nothing can jump around while the island opens and closes:
 *  - the main window is large and holds the island itself. It is only touchable while the island is open;
 *  - the pad window is small and invisible. It catches touches while the island is compact.
 * While the island is idle neither window takes touches, so everything passes through to the app underneath.
 */
class IslandOverlay(private val context: Context, private val windowType: Int) {

    private class Owner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val viewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        fun create() {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val battery: BatteryMonitor = BatteryMonitor(context.applicationContext)
    private val events: SystemEventMonitor = SystemEventMonitor(context.applicationContext)
    private var owner: Owner? = null
    private var mainView: View? = null
    private var mainParams: WindowManager.LayoutParams? = null
    private var padView: View? = null
    private var editView: View? = null
    private var lastHole: Hole? = null
    private var autoHole: Hole? = null
    private var padParams: WindowManager.LayoutParams? = null
    private var lockView: View? = null
    private var guidesView: View? = null
    private var lockParams: WindowManager.LayoutParams? = null
    private var restoreView: View? = null
    private var restoreParams: WindowManager.LayoutParams? = null

    /** Builds the island and starts watching the battery. */
    fun start() {
        IslandBus.rebuildOverlay = { rebuild() }
        IslandBus.startCameraEdit = { startCameraEdit() }
        showIsland()
        battery.start()
        events.start()
    }

    /** Folding or unfolding changes the screen and its camera, so build the island again for the new screen. */
    fun rebuild() {
        // Remember where the camera was, so a changed position is glided to instead of jumped to.
        IslandBus.moveFrom = lastHole?.let {
            floatArrayOf(it.centerX.value, it.centerY.value, IslandBus.lastIdleD, it.screenW.value)
        }
        // Put the new island up first and only then take the old one away, so there is no blink in between.
        val oldMain = mainView
        val oldPad = padView
        val oldOwner = owner
        mainView = null
        padView = null
        owner = null
        hideIsland()
        showIsland()
        lockHideHandler.postDelayed({
            oldMain?.let { runCatching { windowManager.removeView(it) } }
            oldPad?.let { runCatching { windowManager.removeView(it) } }
            oldOwner?.destroy()
        }, 90L)
    }

    fun stop() {
        IslandBus.rebuildOverlay = null
        IslandBus.startCameraEdit = null
        battery.stop()
        events.stop()
        hideIsland()
    }

    private fun showIsland() {
        val density = context.resources.displayMetrics.density
        val metrics = windowManager.currentWindowMetrics
        val screenW = metrics.bounds.width()

        // Turn the cutout into a circle. Some phones report a box that runs from the very top of the screen to the
        // bottom of the camera, so the circle is as wide as the box and sits at the bottom of it.
        val rect = metrics.windowInsets.displayCutout?.boundingRectTop
        val hole0 = if (rect != null && !rect.isEmpty) {
            val d = rect.width().toFloat()
            Hole(
                centerX = (rect.exactCenterX() / density).dp,
                centerY = ((rect.bottom - d / 2f) / density).dp,
                diameter = (d / density).dp,
                real = true,
                screenW = (screenW / density).dp,
            )
        } else {
            Hole((screenW / 2f / density).dp, 20.dp, 0.dp, real = false, screenW = (screenW / density).dp)
        }
        // The person can choose where the camera is (a list, or a slider) instead of the phone's own cutout.
        val camPrefs = context.getSharedPreferences("island", Context.MODE_PRIVATE)
        val screenWdpAll = screenW / density
        val chosenX: Float? = when (camPrefs.getString("cam_pos", "auto")) {
            "left_corner" -> 30f
            "left" -> screenWdpAll * 0.25f
            "center" -> screenWdpAll * 0.5f
            "right" -> screenWdpAll * 0.75f
            "right_corner" -> screenWdpAll - 30f
            "custom" -> screenWdpAll * camPrefs.getFloat("cam_x_pct", 50f) / 100f
            else -> null
        }
        val hole1 = if (chosenX == null) hole0 else Hole(
            centerX = chosenX.dp,
            centerY = if (hole0.real) hole0.centerY else 20.dp,
            diameter = if (hole0.real) hole0.diameter else 24.dp,
            real = true,
            screenW = hole0.screenW,
        )
        // How far down the camera is, if the person set it by hand.
        val hole = if (camPrefs.getBoolean("cam_y_on", false) && camPrefs.getString("cam_pos", "auto") == "custom") {
            hole1.copy(centerY = camPrefs.getFloat("cam_y_dp", 20f).dp)
        } else hole1
        lastHole = hole
        autoHole = hole0
        // The island's height only applies when the position is Custom.
        IslandBus.compactH = if (camPrefs.getString("cam_pos", "auto") == "custom") camPrefs.getFloat("island_h", 36f) else 36f
        // The main window is as wide as the screen (but no wider than the widest island plus its touch margin).
        val tallWidthDp = (screenW / density).coerceIn(380f, 508f)
        val camXpx = hole.centerX.value * density

        fun params(widthDp: Float, heightDp: Int, name: String) = WindowManager.LayoutParams(
            (widthDp * density).toInt(),
            (heightDp * density).toInt(),
            windowType,
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Placed by its left edge, centred on the camera.
            gravity = Gravity.TOP or Gravity.START
            x = (camXpx - width / 2f).toInt()
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = name
        }

        val o = Owner().also { it.create() }
        IslandBus.lockFullWidthDp = screenW / density - 56f
        val pad = root(o) { IslandTouchPad() }
        val restore = root(o) { RestorePad() }
        val guides = root(o) { LockGuides(HOME_Y_DP) }
        // Full screen, never touchable: it only draws the guide lines while the lock screen player is moved.
        val guidesLp = WindowManager.LayoutParams(
            screenW.toInt(),
            windowManager.currentWindowMetrics.bounds.height(),
            windowType,
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            title = "Island guides"
        }
        val lockPlayer = lockRoot(o) { LockPlayerRoot { on, small -> onLockPlayerTouchable(on, small) } }
        // The lock screen player: across the screen, near the bottom (above the lock screen's own shortcuts).
        val lockLp = WindowManager.LayoutParams(
            (screenW - 56 * density).toInt(),
            (160 * density).toInt(),
            windowType,
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (HOME_Y_DP * density).toInt()
            title = "Island lock player"
        }
        applyLockPosition(lockLp, false)
        val shiftForMain = (camXpx - ((camXpx - tallWidthDp * density / 2f).coerceIn(
            minOf(0f, screenW - tallWidthDp * density), maxOf(0f, screenW - tallWidthDp * density),
        ) + tallWidthDp * density / 2f)) / density
        val main = root(o) {
            IslandRoot(
                hole = hole,
                xShift = shiftForMain.dp,
                onRestoreWanted = { on -> onRestoreWanted(on) },
            ) { touchable, tall -> onLayoutWanted(touchable, tall) }
        }
        // The touch windows follow the camera. Both stay on the screen: near an edge they slide inward, and the
        // island inside them is placed to match (the main window's xShift, the pad's centre).
        val camXdp = hole.centerX.value
        val screenWdp = screenW / density
        val padLp = params(COMPACT_W.toFloat(), maxOf(COMPACT_H, (hole.centerY.value + 40f).toInt()), "Island pad")
        var leftReach = minOf(COMPACT_W / 2f, camXdp)
        val rightReach = minOf(screenWdp - camXdp, COMPACT_W - leftReach).coerceAtLeast(0f)
        leftReach = minOf(camXdp, COMPACT_W - rightReach)
        padLp.width = ((leftReach + rightReach) * density).toInt().coerceAtLeast(1)
        padLp.x = (camXpx - leftReach * density).toInt()
        IslandBus.padCentre = (rightReach - leftReach) / 2f
        val mainLp = params(tallWidthDp, maxOf(TALL_H, (hole.centerY.value + 240f).toInt()), "Island")
        run {
            val w = mainLp.width.toFloat()
            val lo = minOf(0f, screenW - w)
            val hi = maxOf(0f, screenW - w)
            mainLp.x = (camXpx - w / 2f).coerceIn(lo, hi).toInt()
        }
        // A small area right over the camera (72 x 44 dp), only touchable while there is something to bring back.
        val restoreLp = params(72f, 44, "Island restore").apply {
            y = (hole.centerY.value * density - 44 * density / 2f).toInt().coerceAtLeast(0)
        }
        o.resume()
        // The pad goes in first, so the main window is above it.
        windowManager.addView(pad, padLp)
        windowManager.addView(restore, restoreLp)
        windowManager.addView(guides, guidesLp)
        guidesView = guides
        windowManager.addView(lockPlayer, lockLp)
        windowManager.addView(main, mainLp)
        owner = o
        padView = pad
        padParams = padLp
        restoreView = restore
        lockView = lockPlayer
        lockParams = lockLp
        restoreParams = restoreLp
        mainView = main
        mainParams = mainLp
    }

    /**
     * A ComposeView inside a FrameLayout, so that touches landing outside the window (reported to the window as
     * ACTION_OUTSIDE, thanks to FLAG_WATCH_OUTSIDE_TOUCH) can be caught.
     */
    private fun root(o: Owner, content: @Composable () -> Unit): View =
        object : FrameLayout(context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    IslandBus.outsideTouch()
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            addView(
                ComposeView(context).apply { setContent(content) },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

    // ------------------------------------------------------------------------------------------
    // The lock screen player can be held and moved. Its position is saved (in dp, from the middle of the screen
    // and from the bottom) and used for both the big and the small player.
    // ------------------------------------------------------------------------------------------

    private val prefs get() = context.getSharedPreferences("island", Context.MODE_PRIVATE)
    private val screenSize get() = windowManager.currentWindowMetrics.bounds

    /** Sets the window size for the big or small player and puts it where it was last left. */
    private fun applyLockPosition(lp: WindowManager.LayoutParams, small: Boolean) {
        val d = context.resources.displayMetrics.density
        lp.width = if (small) (IslandBus.LOCK_MIN_W_DP * d).toInt() else (screenSize.width() - 56 * d).toInt()
        lp.height = ((if (small) IslandBus.LOCK_MIN_H_DP else IslandBus.LOCK_BIG_H_DP) * d).toInt()
        lp.x = (prefs.getFloat("lock_x_dp", 0f) * d).toInt()
        lp.y = (prefs.getFloat("lock_y_dp", HOME_Y_DP) * d).toInt()
        clampLock(lp)
    }

    /** Keeps the window on the screen. */
    private fun clampLock(lp: WindowManager.LayoutParams) {
        val half = ((screenSize.width() - lp.width) / 2).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(-half, half)
        lp.y = lp.y.coerceIn(0, (screenSize.height() - lp.height).coerceAtLeast(0))
    }

    private fun moveLock(x: Int, y: Int) {
        val v = lockView ?: return
        val lp = lockParams ?: return
        lp.x = x
        lp.y = y
        clampLock(lp)
        runCatching { windowManager.updateViewLayout(v, lp) }
    }

    private fun saveLockPosition() {
        val lp = lockParams ?: return
        val d = context.resources.displayMetrics.density
        prefs.edit().putFloat("lock_x_dp", lp.x / d).putFloat("lock_y_dp", lp.y / d).apply()
    }

    /**
     * The lock screen player's window. Touch and hold it, then drag, to move it: this is done here, with the finger's
     * position on the screen (not inside the window, which moves along with the finger), before the player's own
     * buttons and bar get the touch. A quick tap or a swipe on a button or the bar works as normal.
     */
    private fun lockRoot(o: Owner, content: @Composable () -> Unit): View =
        object : FrameLayout(context) {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false
            private var snappedX = false
            private var snappedY = false
            private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
            private val startDrag = Runnable {
                val lp = lockParams ?: return@Runnable
                dragging = true
                snappedX = lp.x == 0
                snappedY = lp.y == (HOME_Y_DP * context.resources.displayMetrics.density).toInt()
                startX = lp.x
                startY = lp.y
                IslandBus.lockDragging = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }

            /** Moves the player with the finger, but it clings to the middle of the screen and to its usual height. */
            private fun dragTo(ev: MotionEvent) {
                val d = context.resources.displayMetrics.density
                val range = (SNAP_DP * d).toInt()
                val home = (HOME_Y_DP * d).toInt()
                var nx = startX + (ev.rawX - downX).toInt()
                var ny = startY - (ev.rawY - downY).toInt()
                val sx = Math.abs(nx) < range
                val sy = Math.abs(ny - home) < range
                if (sx) nx = 0
                if (sy) ny = home
                // A small tick when it catches.
                if ((sx && !snappedX) || (sy && !snappedY)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                snappedX = sx
                snappedY = sy
                IslandBus.lockSnapX = sx
                IslandBus.lockSnapY = sy
                moveLock(nx, ny)
            }

            private fun endDrag() {
                removeCallbacks(startDrag)
                if (dragging) saveLockPosition()
                dragging = false
                IslandBus.lockDragging = false
                IslandBus.lockSnapX = false
                IslandBus.lockSnapY = false
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragging = false
                        downX = ev.rawX
                        downY = ev.rawY
                        postDelayed(startDrag, 380)
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging && Math.hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()) > slop) {
                            removeCallbacks(startDrag)
                        }
                        if (dragging) {
                            dragTo(ev)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val was = dragging
                        endDrag()
                        return was
                    }
                }
                return dragging
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (!dragging) return super.onTouchEvent(ev)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> dragTo(ev)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
                }
                return true
            }
        }.apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            addView(
                ComposeView(context).apply { setContent(content) },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

    /**
     * Decides which window takes touches. Idle: neither. Compact: the small pad. Open: the large main window.
     * Only flags change, never sizes or positions.
     */
    private fun onLayoutWanted(touchable: Boolean, tall: Boolean) {
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        mainView?.let { v ->
            mainParams?.let { lp ->
                lp.flags = if (touchable && tall) BASE_FLAGS else BASE_FLAGS or notTouchable
                runCatching { windowManager.updateViewLayout(v, lp) }
            }
        }
        padView?.let { v ->
            padParams?.let { lp ->
                lp.flags = if (touchable && !tall) BASE_FLAGS else BASE_FLAGS or notTouchable
                runCatching { windowManager.updateViewLayout(v, lp) }
            }
        }
    }

    private var lockSmall: Boolean? = null
    private var lockAnim: android.animation.ValueAnimator? = null

    /**
     * The window follows the card's size, a frame at a time, so the empty part around a small player never blocks
     * touches and nothing is ever drawn in a window of the wrong size.
     */
    private fun animateLockSize(lp: WindowManager.LayoutParams, small: Boolean) {
        val v = lockView ?: return
        val d = context.resources.displayMetrics.density
        val fromW = lp.width
        val fromH = lp.height
        val toW = if (small) (IslandBus.LOCK_MIN_W_DP * d).toInt() else (screenSize.width() - 56 * d).toInt()
        val toH = ((if (small) IslandBus.LOCK_MIN_H_DP else IslandBus.LOCK_BIG_H_DP) * d).toInt()
        val savedX = (prefs.getFloat("lock_x_dp", 0f) * d).toInt()
        val savedY = (prefs.getFloat("lock_y_dp", HOME_Y_DP) * d).toInt()
        lockAnim?.cancel()
        lockAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            // Starts moving at once and eases to a stop, with no hard edge at either end.
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                lp.width = (fromW + (toW - fromW) * t).toInt()
                lp.height = (fromH + (toH - fromH) * t).toInt()
                lp.x = savedX
                lp.y = savedY
                clampLock(lp)
                runCatching { windowManager.updateViewLayout(v, lp) }
            }
            start()
        }
    }

    private val lockHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val lockHide = Runnable { lockView?.visibility = android.view.View.INVISIBLE }

    /** The player has slid down off the bottom edge because the notification shade is open. */
    private var lockSlid = false
    private var lockSlide: android.animation.ValueAnimator? = null

    /** Slides the whole window down past the bottom edge of the screen, or back up to where it lives. */
    private fun slideLock(out: Boolean) {
        val v = lockView ?: return
        val lp = lockParams ?: return
        val d = context.resources.displayMetrics.density
        val away = -(lp.height + 8 * d).toInt()
        val home = (prefs.getFloat("lock_y_dp", HOME_Y_DP) * d).toInt()
        val from = if (out) lp.y else away
        val to = if (out) away else home
        lockSlide?.cancel()
        lockAnim?.cancel()
        v.visibility = android.view.View.VISIBLE
        lockSlide = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (out) 230 else 320
            interpolator = if (out) android.view.animation.AccelerateInterpolator(1.6f)
            else android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                lp.y = (from + (to - from) * t).toInt()
                runCatching { windowManager.updateViewLayout(v, lp) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (out && !cancelled) v.visibility = android.view.View.INVISIBLE
                }
            })
            start()
        }
    }

    private fun onLockPlayerTouchable(on: Boolean, small: Boolean) {
        val shade = IslandBus.lockShadeOpen && IslandBus.lockPlayerShowing
        // The notification shade: the player slides down off the bottom of the screen, and back up when it closes.
        if (!on && shade) {
            lockHideHandler.removeCallbacks(lockHide)
            if (!lockSlid) {
                lockSlid = true
                lockParams?.let { lp ->
                    lp.flags = BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    lockView?.let { v -> runCatching { windowManager.updateViewLayout(v, lp) } }
                }
                slideLock(true)
            }
            return
        }
        if (on && lockSlid) {
            lockSlid = false
            lockHideHandler.removeCallbacks(lockHide)
            lockView?.let { v ->
                lockParams?.let { lp ->
                    lockAnim?.cancel()
                    applyLockPosition(lp, small)
                    lockSmall = small
                    lp.y = -(lp.height + 8 * (context.resources.displayMetrics.density)).toInt()
                    lp.flags = BASE_FLAGS
                    runCatching { windowManager.updateViewLayout(v, lp) }
                }
            }
            slideLock(false)
            return
        }
        if (!on && lockSlid) {
            // Unlocked while it was down: put it back in its place, out of sight.
            lockSlid = false
            lockSlide?.cancel()
            lockView?.let { v ->
                lockParams?.let { lp ->
                    applyLockPosition(lp, small)
                    lp.flags = BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    runCatching { windowManager.updateViewLayout(v, lp) }
                }
                v.visibility = android.view.View.INVISIBLE
            }
            return
        }
        // Once the player has faded out, the whole window is hidden, so no half-faded picture of it can be left behind
        // on screen (it was seen faintly over the notification shade and the home screen).
        lockHideHandler.removeCallbacks(lockHide)
        if (on) lockView?.visibility = android.view.View.VISIBLE
        else lockHideHandler.postDelayed(lockHide, 250)
        lockView?.let { v ->
            lockParams?.let { lp ->
                if (lockSmall == null || !on) {
                    // Nothing is showing: just set the right size and place.
                    lockAnim?.cancel()
                    applyLockPosition(lp, small)
                } else if (lockSmall != small) {
                    animateLockSize(lp, small)
                }
                lockSmall = small
                lp.flags = if (on) BASE_FLAGS else BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { windowManager.updateViewLayout(v, lp) }
            }
        }
    }

    private fun onRestoreWanted(on: Boolean) {
        restoreView?.let { v ->
            restoreParams?.let { lp ->
                lp.flags = if (on) BASE_FLAGS else BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { windowManager.updateViewLayout(v, lp) }
            }
        }
    }

    /** Opens the full-screen editor for the camera position (see [CameraEditor]). */
    private fun startCameraEdit() {
        if (editView != null) return
        val o = owner ?: return
        val hole = lastHole ?: return
        val density = context.resources.displayMetrics.density
        val bounds = windowManager.currentWindowMetrics.bounds
        val screenWdp = bounds.width() / density
        IslandBus.editingCamera = true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val startSize = IslandBus.compactH
        var closing = false
        val view = root(o) {
            CameraEditor(
                startX = hole.centerX.value,
                startY = hole.centerY.value.coerceIn(8f, CAM_MAX_Y),
                startSize = startSize,
                defaultX = (autoHole ?: hole).centerX.value,
                defaultY = (autoHole ?: hole).centerY.value,
                diameter = if (hole.real) hole.diameter.value * IDLE_SCALE else 24f,
                screenW = screenWdp,
                onSizeLive = { IslandBus.compactH = it },
                onSave = { x, y, size ->
                    if (!closing) {
                        closing = true
                        IslandBus.compactH = size
                        context.getSharedPreferences("island", Context.MODE_PRIVATE).edit()
                            .putString("cam_pos", "custom")
                            .putFloat("cam_x_pct", x / screenWdp * 100f)
                            .putBoolean("cam_y_on", true)
                            .putFloat("cam_y_dp", y)
                            .putFloat("island_h", size)
                            .apply()
                        IslandBus.camVersion++
                        // A short pause first, so the button's press animation can finish.
                        handler.postDelayed({ IslandBus.introNext = true; stopCameraEdit(); rebuild() }, CLOSE_PAUSE_MS)
                    }
                },
                onCancel = {
                    if (!closing) {
                        closing = true
                        IslandBus.compactH = startSize
                        handler.postDelayed({ IslandBus.introNext = true; stopCameraEdit(); rebuild() }, CLOSE_PAUSE_MS)
                    }
                },
            )
        }
        val lp = WindowManager.LayoutParams(
            bounds.width(), bounds.height(), windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Island camera editor"
        }
        windowManager.addView(view, lp)
        editView = view
    }

    private fun stopCameraEdit() {
        editView?.let { runCatching { windowManager.removeView(it) } }
        editView = null
        IslandBus.editingCamera = false
    }

    private fun hideIsland() {
        stopCameraEdit()
        lockHideHandler.removeCallbacks(lockHide)
        lockSlide?.cancel()
        lockSlid = false
        lockAnim?.cancel()
        lockSmall = null
        lockView?.let { runCatching { windowManager.removeView(it) } }
        lockView = null
        guidesView?.let { runCatching { windowManager.removeView(it) } }
        guidesView = null
        restoreView?.let { runCatching { windowManager.removeView(it) } }
        restoreView = null
        mainView?.let { runCatching { windowManager.removeView(it) } }
        padView?.let { runCatching { windowManager.removeView(it) } }
        mainView = null
        padView = null
        owner?.destroy()
        owner = null
    }

    companion object {
        private const val COMPACT_W = 280
        /** How long the editor stays after Save or Cancel, so the button's press animation can finish. */
        private const val CLOSE_PAUSE_MS = 400L
        private const val COMPACT_H = 72
        /** The lock screen player's usual place: centred, this far (dp) above the bottom of the screen. */
        private const val HOME_Y_DP = 36f
        /** How close (dp) to the middle or to its usual height it has to get before it clings there. */
        private const val SNAP_DP = 22f
        private const val TALL_H = 260
        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
}