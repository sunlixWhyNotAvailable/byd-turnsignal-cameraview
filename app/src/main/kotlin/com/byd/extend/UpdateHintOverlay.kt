package com.byd.extend

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.OneShotPreDrawListener
import com.byd.extend.ui.UiLanguage
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Process-owned native window for the short-lived update hint. */
object UpdateHintOverlay {
    fun interface Callback {
        fun onOpen(eventId: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val requestGeneration = AtomicLong()
    private var callback: Callback? = null
    private var appContext: Context? = null
    private var windowContext: Context? = null
    private var windows: WindowManager? = null
    private var root: FrameLayout? = null
    private var card: UpdateHintCardView? = null
    private var params: WindowManager.LayoutParams? = null
    private var animator: android.animation.ValueAnimator? = null
    private var eventId: String? = null
    private var version: String? = null
    private var appearance = UpdateHintAppearance()
    private var language = UiLanguage.English
    private var darkTheme = true
    private var preferredWidthPx = 0
    private var preferredHeightPx = 0
    private var effectiveScalePercent = 0
    private var appliedScale = 0f
    private var deadlineElapsedMs = 0L
    private var generation = 0L
    private var scheduledExpiry: Runnable? = null
    private var preDrawListener: OneShotPreDrawListener? = null
    private var displays: DisplayManager? = null
    private var activeDisplayId = Display.INVALID_DISPLAY
    private var availableArea: Rect? = null
    private var availableDensity = 0f
    private var displayListenerRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == activeDisplayId) scheduleDisplayRefresh()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == activeDisplayId) dismissMain("display_removed", true)
        }
    }

    private val displayRefresh = Runnable {
        val context = appContext ?: return@Runnable
        val manager = windows ?: return@Runnable
        val display = displays?.getDisplay(activeDisplayId) ?: run {
            dismissMain("display_unavailable", true)
            return@Runnable
        }
        val density = windowContext?.resources?.displayMetrics?.density ?: 0f
        if (usableArea(context, manager, display) != availableArea ||
            abs(density - availableDensity) > 0.0001f
        ) refreshAppearanceMain()
    }

    private val expire = Runnable {
        if (eventId != null && deadlineElapsedMs > 0L &&
            SystemClock.elapsedRealtime() >= deadlineElapsedMs
        ) {
            dismissMain("expired", true)
        }
    }

    @JvmStatic
    fun setCallback(value: Callback?) {
        if (Looper.myLooper() == Looper.getMainLooper()) callback = value
        else handler.post { callback = value }
    }

    @JvmStatic
    fun show(context: Context, eventId: String, version: String) {
        val request = requestGeneration.incrementAndGet()
        val requestedAtNanos = SystemClock.elapsedRealtimeNanos()
        val application = context.applicationContext
        val action = Runnable {
            if (requestGeneration.get() == request) {
                showMain(application, eventId, version, requestedAtNanos)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else handler.post(action)
    }

    @JvmStatic
    @JvmOverloads
    fun hide(reason: String = "hidden") {
        val request = requestGeneration.incrementAndGet()
        val action = Runnable {
            if (requestGeneration.get() == request) dismissMain(reason, true)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else handler.post(action)
    }

    @JvmStatic
    fun refreshAppearance() {
        if (Looper.myLooper() == Looper.getMainLooper()) refreshAppearanceMain()
        else handler.post(::refreshAppearanceMain)
    }

    private fun showMain(context: Context, newEventId: String, newVersion: String, requestedAtNanos: Long) {
        if (newEventId.isBlank() || newVersion.isBlank()) {
            dismissMain("invalid_result", true)
            Log.w(TAG, "show_failed reason=invalid_result")
            return
        }
        if (eventId == newEventId) return
        dismissMain("replaced", eventId != null)
        if (!Settings.canDrawOverlays(context)) {
            UpdateHintCoordinator.get(context).clear("overlay_permission_missing")
            Log.w(TAG, "show_failed event=$newEventId reason=overlay_permission_missing")
            return
        }

        try {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                ?: throw IllegalStateException("main_display_unavailable")
            val displayContext = context.createDisplayContext(display)
            val overlayContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                displayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
                )
            } else displayContext
            val manager = overlayContext.getSystemService(WindowManager::class.java)
            val settings = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            val newAppearance = UpdateHintAppearance.read(UpdateHintAppearance.preferences(context))
            val newLanguage = readLanguage(settings)
            val newDarkTheme = settings.getBoolean(PREF_DARK_THEME, true)
            val newCard = UpdateHintCardView(
                overlayContext,
                Runnable { openCurrent() },
                Runnable { hide("dismissed") }
            ).apply { bind(newVersion, newLanguage, newDarkTheme, newAppearance) }
            val width = UpdateHintCardView.widthPx(overlayContext, newAppearance)
            val height = UpdateHintCardView.preferredHeightPx(
                overlayContext, newVersion, newLanguage, newDarkTheme, newAppearance
            )
            val area = usableArea(context, manager, display)

            appContext = context
            windowContext = overlayContext
            windows = manager
            card = newCard
            eventId = newEventId
            version = newVersion
            appearance = newAppearance
            language = newLanguage
            darkTheme = newDarkTheme
            preferredWidthPx = width
            preferredHeightPx = height
            effectiveScalePercent = newAppearance.sizePercent
            appliedScale = 1f
            displays = displayManager
            activeDisplayId = display.displayId
            availableArea = Rect(area)
            availableDensity = overlayContext.resources.displayMetrics.density
            displayManager.registerDisplayListener(displayListener, handler)
            displayListenerRegistered = true

            val coordinator = UpdateHintCoordinator.get(context)
            coordinator.setListener { ownState, layout, ready ->
                handler.post { onCoordinationChanged(ownState, layout, ready) }
            }
            coordinator.updateAvailableArea(
                display.displayId, area.left, area.top, area.width(), area.height(),
                overlayContext.resources.displayMetrics.density
            )
            coordinator.beginPending(
                newEventId, requestedAtNanos, display.displayId,
                newAppearance.sizePercent, width, height
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "show_failed event=$newEventId reason=${error.javaClass.simpleName}")
            dismissMain("show_failed", true)
        }
    }

    private fun onCoordinationChanged(
        ownState: UpdateHintState,
        layout: UpdateHintLayout.Result,
        ready: Boolean
    ) {
        val currentEvent = eventId ?: return
        if (ownState.eventId != currentEvent || ownState.isNone()) return
        val target = layout.find(PACKAGE_NAME, currentEvent)
        if (target == null) {
            if (ready) {
                Log.w(TAG, "show_failed event=$currentEvent reason=placement_unavailable")
                dismissMain("placement_unavailable", true)
            }
            return
        }
        if (root == null) {
            if (ready) attach(target)
        } else {
            applyPlacement(target, true)
        }
    }

    private fun attach(target: UpdateHintLayout.Placement) {
        val manager = windows ?: return
        val overlayContext = windowContext ?: return
        val currentCard = card ?: return
        if (!Settings.canDrawOverlays(overlayContext)) {
            dismissMain("overlay_permission_lost", true)
            return
        }
        val frame = FrameLayout(overlayContext).apply {
            clipChildren = false
            clipToPadding = false
            setOnApplyWindowInsetsListener { _, insets ->
                scheduleDisplayRefresh()
                insets
            }
            addView(currentCard, FrameLayout.LayoutParams(preferredWidthPx, preferredHeightPx))
        }
        val layoutParams = WindowManager.LayoutParams(
            target.widthPx,
            target.heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = target.xPx
            y = target.yPx
            alpha = 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
        val scale = target.effectiveScalePercent.toFloat() / appearance.sizePercent.coerceAtLeast(1)
        currentCard.pivotX = 0f
        currentCard.pivotY = 0f
        currentCard.scaleX = scale
        currentCard.scaleY = scale
        currentCard.translationX = -target.widthPx.toFloat()
        try {
            val activeGeneration = ++generation
            root = frame
            params = layoutParams
            effectiveScalePercent = target.effectiveScalePercent
            appliedScale = scale
            manager.addView(frame, layoutParams)
            frame.requestApplyInsets()
            preDrawListener = OneShotPreDrawListener.add(frame) {
                preDrawListener = null
                if (generation == activeGeneration && root === frame) {
                    startVisibleLifetime(overlayContext, currentCard, activeGeneration)
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "show_failed event=${eventId} reason=${error.javaClass.simpleName}")
            dismissMain("attach_failed", true)
        }
    }

    private fun startVisibleLifetime(
        overlayContext: Context,
        currentCard: View,
        activeGeneration: Long
    ) {
        val shownElapsedMs = SystemClock.elapsedRealtime()
        deadlineElapsedMs = shownElapsedMs + DISPLAY_DURATION_MS
        UpdateHintCoordinator.get(overlayContext).markVisible(shownElapsedMs)
        currentCard.animate()
            .translationX(0f)
            .setDuration(ANIMATION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        val expiry = Runnable {
            if (generation == activeGeneration) expire.run()
        }
        scheduledExpiry = expiry
        handler.postDelayed(expiry, DISPLAY_DURATION_MS)
        Log.i(TAG, "shown event=${eventId} scale=$effectiveScalePercent")
    }

    private fun applyPlacement(target: UpdateHintLayout.Placement, animate: Boolean) {
        val manager = windows ?: return
        val frame = root ?: return
        val layout = params ?: return
        val currentCard = card ?: return
        val targetScale = target.effectiveScalePercent.toFloat() /
            appearance.sizePercent.coerceAtLeast(1)
        if (layout.x == target.xPx && layout.y == target.yPx &&
            layout.width == target.widthPx && layout.height == target.heightPx &&
            effectiveScalePercent == target.effectiveScalePercent &&
            abs(appliedScale - targetScale) < 0.0001f
        ) return

        Log.i(
            TAG,
            "target_changed event=${eventId} x=${target.xPx} y=${target.yPx} " +
                "scale=${target.effectiveScalePercent}"
        )
        animator?.cancel()
        if (!animate) {
            updateWindow(manager, frame, layout, currentCard, target, 1f,
                layout.x, layout.y, layout.width, layout.height,
                effectiveScalePercent, appliedScale)
            return
        }
        val startX = layout.x
        val startY = layout.y
        val startWidth = layout.width
        val startHeight = layout.height
        val startEffectiveScalePercent = effectiveScalePercent
        val startScale = appliedScale
        animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                updateWindow(manager, frame, layout, currentCard, target,
                    it.animatedValue as Float, startX, startY, startWidth, startHeight,
                    startEffectiveScalePercent, startScale)
            }
            start()
        }
    }

    private fun updateWindow(
        manager: WindowManager,
        frame: View,
        layout: WindowManager.LayoutParams,
        currentCard: View,
        target: UpdateHintLayout.Placement,
        fraction: Float,
        startX: Int,
        startY: Int,
        startWidth: Int,
        startHeight: Int,
        startEffectiveScalePercent: Int,
        startScale: Float
    ) {
        layout.x = lerp(startX, target.xPx, fraction)
        layout.y = lerp(startY, target.yPx, fraction)
        layout.width = lerp(startWidth, target.widthPx, fraction).coerceAtLeast(1)
        layout.height = lerp(startHeight, target.heightPx, fraction).coerceAtLeast(1)
        val scalePercent = lerp(
            startEffectiveScalePercent, target.effectiveScalePercent, fraction
        )
        val targetScale = target.effectiveScalePercent.toFloat() /
            appearance.sizePercent.coerceAtLeast(1)
        val scale = lerp(startScale, targetScale, fraction)
        currentCard.scaleX = scale
        currentCard.scaleY = scale
        try {
            manager.updateViewLayout(frame, layout)
            effectiveScalePercent = scalePercent
            appliedScale = scale
        } catch (error: RuntimeException) {
            animator?.cancel()
            Log.w(TAG, "layout_failed event=${eventId} reason=${error.javaClass.simpleName}")
            dismissMain("layout_failed", true)
        }
    }

    private fun refreshAppearanceMain() {
        val context = appContext ?: return
        if (!Settings.canDrawOverlays(context)) {
            dismissMain("overlay_permission_lost", true)
            return
        }
        val currentCard = card ?: return
        val currentVersion = version ?: return
        val settings = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        appearance = UpdateHintAppearance.read(UpdateHintAppearance.preferences(context))
        language = readLanguage(settings)
        darkTheme = settings.getBoolean(PREF_DARK_THEME, true)
        currentCard.bind(currentVersion, language, darkTheme, appearance)
        val overlayContext = windowContext ?: return
        preferredWidthPx = UpdateHintCardView.widthPx(overlayContext, appearance)
        preferredHeightPx = UpdateHintCardView.preferredHeightPx(
            overlayContext, currentVersion, language, darkTheme, appearance
        )
        currentCard.layoutParams = FrameLayout.LayoutParams(preferredWidthPx, preferredHeightPx)
        params?.apply {
            flags = windowFlags()
            alpha = 1f
        }
        val frame = root
        val manager = windows
        val layout = params
        if (layout != null) {
            appliedScale = min(
                layout.width.toFloat() / preferredWidthPx.coerceAtLeast(1),
                layout.height.toFloat() / preferredHeightPx.coerceAtLeast(1)
            )
            currentCard.scaleX = appliedScale
            currentCard.scaleY = appliedScale
        }
        if (frame != null && manager != null && layout != null) {
            try {
                manager.updateViewLayout(frame, layout)
            } catch (error: RuntimeException) {
                Log.w(TAG, "appearance_failed event=${eventId} reason=${error.javaClass.simpleName}")
                dismissMain("appearance_failed", true)
                return
            }
        }
        val coordinator = UpdateHintCoordinator.get(context)
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        if (manager != null && display != null) {
            val area = usableArea(context, manager, display)
            availableArea = Rect(area)
            availableDensity = overlayContext.resources.displayMetrics.density
            coordinator.updateAvailableArea(
                display.displayId, area.left, area.top, area.width(), area.height(),
                overlayContext.resources.displayMetrics.density
            )
        }
        coordinator.updatePreferredGeometry(
            appearance.sizePercent, preferredWidthPx, preferredHeightPx
        )
    }

    private fun openCurrent() {
        val openedEvent = eventId ?: return
        requestGeneration.incrementAndGet()
        dismissMain("opened", true)
        callback?.onOpen(openedEvent)
    }

    private fun dismissMain(reason: String, publishNone: Boolean) {
        scheduledExpiry?.let(handler::removeCallbacks)
        scheduledExpiry = null
        preDrawListener?.removeListener()
        preDrawListener = null
        handler.removeCallbacks(displayRefresh)
        generation++
        animator?.cancel()
        animator = null
        card?.animate()?.cancel()
        val manager = windows
        val frame = root
        val dismissedEvent = eventId
        root = null
        card = null
        params = null
        windows = null
        windowContext = null
        eventId = null
        version = null
        preferredWidthPx = 0
        preferredHeightPx = 0
        effectiveScalePercent = 0
        appliedScale = 0f
        deadlineElapsedMs = 0L
        if (displayListenerRegistered) displays?.unregisterDisplayListener(displayListener)
        displayListenerRegistered = false
        displays = null
        activeDisplayId = Display.INVALID_DISPLAY
        availableArea = null
        availableDensity = 0f
        if (frame != null && manager != null) {
            try {
                manager.removeViewImmediate(frame)
            } catch (error: RuntimeException) {
                Log.w(TAG, "remove_failed reason=${error.javaClass.simpleName}")
            }
        }
        appContext?.let {
            val coordinator = UpdateHintCoordinator.get(it)
            coordinator.setListener(null)
            if (publishNone) coordinator.clear(reason)
        }
        if (dismissedEvent != null) Log.i(TAG, "hidden event=$dismissedEvent reason=$reason")
        appContext = null
    }

    private fun windowFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        (if (appearance.alpha == 0f) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

    private fun scheduleDisplayRefresh() {
        handler.removeCallbacks(displayRefresh)
        handler.post(displayRefresh)
    }

    @Suppress("DEPRECATION")
    private fun usableArea(context: Context, manager: WindowManager, display: Display): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = manager.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return Rect(
                metrics.bounds.left + insets.left,
                metrics.bounds.top + insets.top,
                metrics.bounds.right - insets.right,
                metrics.bounds.bottom - insets.bottom
            )
        }
        val real = DisplayMetrics().also(display::getRealMetrics)
        val usable = DisplayMetrics().also(display::getMetrics)
        val statusBarHeight = systemDimension(context, "status_bar_height")
        val navigationBarHeight =
            (real.heightPixels - usable.heightPixels - statusBarHeight).coerceAtLeast(0)
        return Rect(
            0, statusBarHeight, usable.widthPixels, real.heightPixels - navigationBarHeight
        )
    }

    private fun systemDimension(context: Context, name: String): Int {
        val resources = context.resources
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id == 0) 0 else resources.getDimensionPixelSize(id)
    }

    private fun readLanguage(settings: android.content.SharedPreferences): UiLanguage = when {
        AppLanguage.isUkrainian(AppLanguage.read(settings)) -> UiLanguage.Ukrainian
        AppLanguage.isChinese(AppLanguage.read(settings)) -> UiLanguage.Chinese
        else -> UiLanguage.English
    }

    private fun lerp(start: Int, end: Int, fraction: Float): Int =
        (start + (end - start) * fraction).roundToInt()

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private const val TAG = "UpdateHintOverlay"
    private const val PACKAGE_NAME = "com.byd.extend"
    private const val SETTINGS_PREFS = "settings"
    private const val PREF_DARK_THEME = "ui_dark_theme"
    private const val DISPLAY_DURATION_MS = 10_000L
    private const val ANIMATION_DURATION_MS = 220L
}
