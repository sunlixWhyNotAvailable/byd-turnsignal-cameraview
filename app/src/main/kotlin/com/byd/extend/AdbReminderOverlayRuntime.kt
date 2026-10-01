package com.byd.extend

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.byd.extend.ui.UiLanguage
import com.byd.extend.ui.AdbRecoveryStage
import com.byd.extend.ui.AdbRecoveryUiState
import kotlin.math.abs
import kotlin.math.roundToInt

data class AdbReminderOverlayRequest @JvmOverloads constructor(
    val waitWifi: Boolean,
    val wifiConnected: Boolean,
    val waitStartedElapsedMs: Long,
    val cycleId: Long,
    val hintSuppressedForCycle: Boolean,
    val appearance: AdbReminderAppearance,
    val language: UiLanguage = UiLanguage.English,
    val darkTheme: Boolean = true,
) {
    companion object {
        @JvmStatic fun create(
            waitWifi: Boolean,
            wifiConnected: Boolean,
            waitStartedElapsedMs: Long,
            cycleId: Long,
            hintSuppressedForCycle: Boolean,
            appearance: AdbReminderAppearance,
            language: UiLanguage,
            darkTheme: Boolean,
        ) = AdbReminderOverlayRequest(waitWifi, wifiConnected, waitStartedElapsedMs, cycleId,
            hintSuppressedForCycle, appearance, language, darkTheme)

        @JvmStatic fun fromUiState(
            state: AdbRecoveryUiState,
            language: UiLanguage,
            darkTheme: Boolean,
        ) = AdbReminderOverlayRequest(
            waitWifi = state.enabled && state.stage == AdbRecoveryStage.WAIT_WIFI,
            wifiConnected = state.wifiConnected,
            waitStartedElapsedMs = state.waitStartedElapsedMs,
            cycleId = state.cycleId,
            hintSuppressedForCycle = state.hintSuppressedForCycle,
            appearance = state.appearance,
            language = language,
            darkTheme = darkTheme,
        )
    }
}

/** App-process WindowManager owner; it has no dependency on a shell helper or ADB connection. */
class AdbReminderOverlayRuntime(
    context: Context,
    private val callback: Callback,
) : AutoCloseable {
    interface Callback {
        fun onRetry()
        fun onSuppressed()
        fun onAppearanceChanged(appearance: AdbReminderAppearance)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private var request: AdbReminderOverlayRequest? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var card: ReminderView? = null
    private var closed = false
    private var retiredCycleId: Long? = null
    private val delayedShow = Runnable { reconcile() }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = hide("display_removed")
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) handler.post { reconcile(forceLayout = true) }
        }
    }

    init {
        displayManager.registerDisplayListener(displayListener, handler)
    }

    /** The backend supplies its cycle snapshot. This runtime owns only the saved display delay. */
    fun update(value: AdbReminderOverlayRequest) {
        if (closed) return
        handler.post {
            if (closed) return@post
            request = value.copy(appearance = value.appearance.normalized())
            if (value.wifiConnected) retiredCycleId = value.cycleId
            reconcile(forceLayout = true)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.post {
            handler.removeCallbacks(delayedShow)
            hide("close")
            displayManager.unregisterDisplayListener(displayListener)
            request = null
        }
    }

    private fun reconcile(forceLayout: Boolean = false) {
        handler.removeCallbacks(delayedShow)
        val current = request ?: run { hide("no_state"); return }
        if (!current.appearance.widgetEnabled || !current.waitWifi || current.wifiConnected ||
            current.hintSuppressedForCycle || retiredCycleId == current.cycleId) {
            hide("inactive")
            return
        }
        val showAt = current.waitStartedElapsedMs + current.appearance.delaySeconds * 1_000L
        val remaining = showAt - SystemClock.elapsedRealtime()
        if (current.waitStartedElapsedMs <= 0L || remaining > 0L) {
            hide("delay")
            if (current.waitStartedElapsedMs > 0L) handler.postDelayed(delayedShow, remaining)
            return
        }
        if (!Settings.canDrawOverlays(appContext)) {
            hide("permission_missing")
            Log.i(TAG, "not_shown reason=overlay_permission_missing cycle=${current.cycleId}")
            return
        }
        showOrUpdate(current, forceLayout)
    }

    private fun showOrUpdate(current: AdbReminderOverlayRequest, forceLayout: Boolean) {
        val manager = windowManager ?: appContext.getSystemService(WindowManager::class.java)
            .also { windowManager = it }
        val area = usableArea(manager)
        val runtimeCallback = object : Callback {
            override fun onRetry() = callback.onRetry()
            override fun onSuppressed() {
                retiredCycleId = request?.cycleId
                hide("held")
                callback.onSuppressed()
            }
            override fun onAppearanceChanged(appearance: AdbReminderAppearance) =
                callback.onAppearanceChanged(appearance)
        }
        val view = card ?: ReminderView(appContext, runtimeCallback) { next ->
            request = request?.copy(appearance = next)
            reconcile(forceLayout = true)
        }.also { card = it }
        val bodyWidth = (area.width() * current.appearance.widthPercent / 100f)
            .roundToInt().coerceAtLeast(1)
        val (bodyHeight, retryHeight) = view.measureText(current, bodyWidth)
        val geometry = AdbReminderGeometry.calculate(
            area.left, area.top, area.width(), area.height(), bodyHeight, retryHeight,
            current.appearance,
        )
        view.bind(current, geometry)
        val layout = params ?: WindowManager.LayoutParams(
            geometry.windowWidth,
            geometry.windowHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "BYD Extend ADB recovery reminder"
            windowAnimations = 0
        }.also { params = it }
        layout.x = geometry.windowLeft
        layout.y = geometry.windowTop
        layout.width = geometry.windowWidth
        layout.height = geometry.windowHeight
        try {
            if (view.parent == null) {
                manager.addView(view, layout)
                view.revealRetry()
                Log.i(TAG, "shown cycle=${current.cycleId}")
            } else if (forceLayout) {
                manager.updateViewLayout(view, layout)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "window_failed reason=${error.javaClass.simpleName}")
            hide("window_failed")
        }
    }

    private fun hide(reason: String) {
        val view = card ?: return
        card = null
        params = null
        val manager = windowManager
        if (view.parent != null && manager != null) {
            try { manager.removeViewImmediate(view) }
            catch (error: RuntimeException) {
                Log.w(TAG, "remove_failed reason=${error.javaClass.simpleName}")
            }
        }
        view.dispose()
        Log.i(TAG, "hidden reason=$reason")
    }

    @Suppress("DEPRECATION")
    private fun usableArea(manager: WindowManager): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = manager.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(metrics.bounds.left + insets.left, metrics.bounds.top + insets.top,
                metrics.bounds.right - insets.right, metrics.bounds.bottom - insets.bottom)
        }
        val real = DisplayMetrics().also(manager.defaultDisplay::getRealMetrics)
        val usable = DisplayMetrics().also(manager.defaultDisplay::getMetrics)
        return Rect(0, 0, usable.widthPixels, real.heightPixels)
    }

    private class ReminderView(
        context: Context,
        private val callback: Callback,
        private val onPlacementChanged: (AdbReminderAppearance) -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val retryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private var bodyText: StaticLayout? = null
        private var retryText: StaticLayout? = null
        private var request: AdbReminderOverlayRequest? = null
        private var geometry: AdbReminderGeometry? = null
        private var appearance = AdbReminderAppearance()
        private var retryReveal = 0f
        private var animator: ValueAnimator? = null
        private var downX = 0f
        private var downY = 0f
        private var dragged = false
        private var held = false
        private var retryPressed = false
        private var origin = appearance
        private val hold = Runnable {
            held = true
            callback.onSuppressed()
        }

        fun measureText(next: AdbReminderOverlayRequest, bodyWidth: Int): Pair<Int, Int> {
            val language = when (next.language) {
                UiLanguage.Ukrainian -> AppLanguage.UKRAINIAN
                UiLanguage.English -> AppLanguage.ENGLISH
                UiLanguage.Chinese -> AppLanguage.CHINESE
                UiLanguage.Russian -> AppLanguage.RUSSIAN
            }
            val strings = AppLanguage.localizedContext(context, language).resources
            val foreground = if (next.darkTheme) 0xFFF1F6FF.toInt() else 0xFF121A23.toInt()
            val scaledDensity = density * resources.configuration.fontScale
            bodyPaint.color = foreground
            bodyPaint.textSize = 16f * scaledDensity
            val message = strings.getString(R.string.adb_reminder_message)
            bodyText = StaticLayout.Builder.obtain(message, 0, message.length, bodyPaint,
                (bodyWidth - 32f * density).roundToInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                .setLineSpacing((22f * scaledDensity - bodyPaint.fontSpacing).coerceAtLeast(0f), 1f)
                .build()
            retryPaint.color = foreground
            retryPaint.textSize = 14f * scaledDensity
            retryPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val label = strings.getString(R.string.adb_reminder_retry)
            val labelWidth = minOf(kotlin.math.ceil(retryPaint.measureText(label)).toInt(),
                (bodyWidth - 57f * density).roundToInt()).coerceAtLeast(1)
            retryText = StaticLayout.Builder.obtain(label, 0, label.length, retryPaint, labelWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
            contentDescription = message + "\n" + label
            return maxOf((76f * density).roundToInt(), bodyText!!.height + (32f * density).roundToInt()) to
                maxOf((44f * density).roundToInt(), retryText!!.height + (16f * density).roundToInt())
        }

        fun bind(next: AdbReminderOverlayRequest, nextGeometry: AdbReminderGeometry) {
            request = next
            appearance = next.appearance
            geometry = nextGeometry
            invalidate()
        }

        fun revealRetry() {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220L
                addUpdateListener { retryReveal = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun dispose() {
            removeCallbacks(hold)
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            val current = request ?: return
            val layout = geometry ?: return
            val bodyTop = layout.bodyTopInWindow.toFloat()
            val bodyBottom = bodyTop + layout.bodyHeight
            val radius = appearance.cornerRadiusDp * density
            val background = if (current.darkTheme) 0xFF080D12.toInt() else Color.WHITE
            val foreground = if (current.darkTheme) 0xFFF1F6FF.toInt() else 0xFF121A23.toInt()
            val retryTop = if (layout.retryAbove) 0f else bodyBottom
            val overlap = radius.coerceAtMost(layout.bodyHeight / 2f).coerceAtMost(width / 2f)
            val hiddenOffset = (1f - retryReveal) * layout.retryHeight *
                (if (layout.retryAbove) 1f else -1f)
            canvas.save()
            canvas.translate(0f, hiddenOffset)
            paint.style = Paint.Style.FILL
            paint.color = if (retryPressed) {
                withAlpha(0xFF2F86F6.toInt(), if (current.darkTheme) .24f else .14f)
            } else withAlpha(background, appearance.alpha)
            val retryRect = RectF(0f, if (layout.retryAbove) retryTop else retryTop - overlap,
                width.toFloat(), retryTop + layout.retryHeight + if (layout.retryAbove) overlap else 0f)
            val retryPath = Path().apply {
                val corners = if (layout.retryAbove) floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                    else floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
                addRoundRect(retryRect, corners, Path.Direction.CW)
            }
            canvas.drawPath(retryPath, paint)
            if (appearance.borderEnabled) drawRetryOutline(canvas, layout, retryTop, radius)
            drawRetryText(canvas, retryTop + layout.retryHeight / 2f, foreground)
            canvas.restore()
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(background, appearance.alpha)
            canvas.drawRoundRect(RectF(0f, bodyTop, width.toFloat(), bodyBottom), radius, radius, paint)
            if (appearance.borderEnabled) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = appearance.borderThicknessDp * density
                paint.color = appearance.borderArgb
                val inset = paint.strokeWidth / 2f
                canvas.drawRoundRect(RectF(inset, bodyTop + inset, width - inset, bodyBottom - inset),
                    (radius - inset).coerceAtLeast(0f), (radius - inset).coerceAtLeast(0f), paint)
            }
            bodyText?.let { text ->
                canvas.save()
                canvas.translate((width - text.width) / 2f, (bodyTop + bodyBottom - text.height) / 2f)
                text.draw(canvas)
                canvas.restore()
            }
        }

        private fun drawRetryOutline(canvas: Canvas, layout: AdbReminderGeometry, top: Float, radius: Float) {
            val stroke = appearance.borderThicknessDp * density * 0.5f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = appearance.borderArgb
            val inset = stroke / 2f
            val left = inset
            val right = width - inset
            val outer = if (layout.retryAbove) top + inset else top + layout.retryHeight - inset
            val overlap = radius.coerceAtMost(layout.bodyHeight / 2f).coerceAtMost(width / 2f)
            val join = if (layout.retryAbove) top + layout.retryHeight + overlap else top - overlap
            val r = (radius - inset).coerceIn(0f, minOf(width / 2f, layout.retryHeight.toFloat()))
            val path = Path().apply {
                moveTo(left, join)
                lineTo(left, if (layout.retryAbove) outer + r else outer - r)
                quadTo(left, outer, left + r, outer)
                lineTo(right - r, outer)
                quadTo(right, outer, right, if (layout.retryAbove) outer + r else outer - r)
                lineTo(right, join)
            }
            canvas.drawPath(path, paint)
        }

        private fun drawRetryText(canvas: Canvas, centerY: Float, color: Int) {
            val text = retryText ?: return
            val iconSize = 18f * density
            val gap = 7f * density
            val left = (width - text.width - iconSize - gap) / 2f
            canvas.save()
            canvas.translate(left + iconSize + gap, centerY - text.height / 2f)
            text.draw(canvas)
            canvas.restore()
            // Material Refresh's 24x24 viewport, scaled to the same 18dp icon as the editor.
            canvas.save()
            canvas.translate(left, centerY - iconSize / 2f)
            canvas.scale(iconSize / 24f, iconSize / 24f)
            paint.style = Paint.Style.FILL
            paint.color = color
            val icon = Path().apply {
                moveTo(17.65f, 6.35f); cubicTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                cubicTo(7.58f, 4f, 4f, 7.58f, 4f, 12f); cubicTo(4f, 16.42f, 7.58f, 20f, 12f, 20f)
                cubicTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f); lineTo(17.65f, 14f)
                cubicTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f); cubicTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
                cubicTo(6f, 8.69f, 8.69f, 6f, 12f, 6f); cubicTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f)
                lineTo(13f, 11f); lineTo(20f, 11f); lineTo(20f, 4f); close()
            }
            canvas.drawPath(icon, paint)
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val layout = geometry ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; dragged = false; held = false
                    retryPressed = retryHit(event.y, layout)
                    origin = appearance
                    postDelayed(hold, 1_000L)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragged = true
                        retryPressed = false
                        removeCallbacks(hold)
                        invalidate()
                    }
                    if (dragged) {
                        val manager = context.getSystemService(WindowManager::class.java)
                        val area = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            manager.maximumWindowMetrics.bounds
                        } else {
                            @Suppress("DEPRECATION")
                            val metrics = DisplayMetrics().also(manager.defaultDisplay::getRealMetrics)
                            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
                        }
                        val travelX = (area.width() - layout.bodyWidth).coerceAtLeast(1)
                        val travelY = (area.height() - layout.bodyHeight).coerceAtLeast(1)
                        appearance = origin.copy(
                            x = (origin.x + dx / travelX).coerceIn(0f, 1f),
                            y = (origin.y + dy / travelY).coerceIn(0f, 1f),
                        )
                        onPlacementChanged(appearance)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(hold)
                    retryPressed = false
                    invalidate()
                    if (dragged) {
                        callback.onAppearanceChanged(appearance)
                    } else if (!held && retryHit(event.y, layout)) callback.onRetry()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(hold); retryPressed = false; invalidate(); return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun retryHit(y: Float, layout: AdbReminderGeometry): Boolean =
            if (layout.retryAbove) y < layout.retryHeight else y >= layout.bodyHeight

        private fun withAlpha(color: Int, alpha: Float): Int =
            (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
    }

    companion object { private const val TAG = "AdbReminderOverlay" }
}
