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
    private var padParams: WindowManager.LayoutParams? = null

    /** Builds the island and starts watching the battery. */
    fun start() {
        showIsland()
        battery.start()
        events.start()
    }

    /** Folding or unfolding changes the screen and its camera, so build the island again for the new screen. */
    fun rebuild() {
        hideIsland()
        showIsland()
    }

    fun stop() {
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
        val hole = if (rect != null && !rect.isEmpty) {
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
        val pad = root(o) { IslandTouchPad() }
        val main = root(o) {
            IslandRoot(hole = hole, xShift = 0.dp) { touchable, tall -> onLayoutWanted(touchable, tall) }
        }
        val padLp = params(COMPACT_W.toFloat(), COMPACT_H, "Island pad")
        val mainLp = params(tallWidthDp, TALL_H, "Island")
        o.resume()
        // The pad goes in first, so the main window is above it.
        windowManager.addView(pad, padLp)
        windowManager.addView(main, mainLp)
        owner = o
        padView = pad
        padParams = padLp
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

    private fun hideIsland() {
        mainView?.let { runCatching { windowManager.removeView(it) } }
        padView?.let { runCatching { windowManager.removeView(it) } }
        mainView = null
        padView = null
        owner?.destroy()
        owner = null
    }

    companion object {
        private const val COMPACT_W = 280
        private const val COMPACT_H = 72
        private const val TALL_H = 220
        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
}