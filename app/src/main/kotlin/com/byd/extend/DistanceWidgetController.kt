package com.byd.extend

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.widget.FrameLayout
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlin.math.roundToInt

/** Owned by the existing shell helper; Activity death only ends the live preview. */
class DistanceWidgetController internal constructor(
    private val context: CameraRuntimeContext,
    identity: Context,
    private val handler: Handler,
    private val event: BiConsumer<String, Array<Any>>,
    private val previewSink: Consumer<Bundle>,
) {
    private var settings = DistanceWidgetSettings()
    private var frame = DistanceFrame.unavailable(0, 0, "inactive")
    private var powerOn = false
    private var appVisible = false
    private var preview = false
    private var overlayFailure = false
    private var stopped = false
    private var displayManager: android.hardware.display.DisplayManager? = null
    private var displayCached = false
    private var cachedTarget: DistanceTarget? = null
    private var cachedDisplay: android.view.Display? = null
    private val cachedMetrics = android.util.DisplayMetrics()
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = displayChanged()
        override fun onDisplayRemoved(id: Int) = displayChanged()
        override fun onDisplayChanged(id: Int) = displayChanged()
    }
    private data class Host(val displayId: Int, val overlay: WindowlessOverlayHost,
        val view: DistanceWidgetView, val width: Int, val height: Int,
        val timing: DiagnosticTimingWindow, var generation: Long = -1, var visible: Boolean = false)
    private val hosts = mutableMapOf<Triple<Int, Int, Int>, Host>()
    private val source = DistanceAdasSource(identity, handler, { next ->
        frame = next
        if (appVisible && preview) publishPreview()
        render()
    }, event)

    private fun dispatch(action: () -> Unit) {
        if (android.os.Looper.myLooper() == handler.looper) { if (!stopped) action() }
        else handler.post { if (!stopped) action() }
    }
    private fun displayChanged() {
        displayCached = false; overlayFailure = false
        if (!stopped) render()
    }

    fun settingsChanged() = dispatch {
        try { settings = DistanceWidgetStore.read(context.settings); overlayFailure = false }
        catch (error: Exception) {
            failure("settings", error)
            settings = DistanceWidgetSettings()
            preview = false
            reconcile()
            return@dispatch
        }
        event.accept("distance_settings", arrayOf("enabled", settings.enabled, "target", settings.target.name,
            "format", settings.format.name, "zones", settings.zones.size))
        reconcile()
    }
    fun power(on: Boolean) {
        if (on) dispatch { powerOn = true; reconcile() }
        else source.control { if (!stopped) { powerOn = false; reconcile() } }
    }
    fun visibility(visible: Boolean) = dispatch {
        appVisible = visible
        if (!visible) preview = false
        reconcile()
    }
    fun preview(value: Boolean) = dispatch {
        preview = value && appVisible
        reconcile(); publishPreview()
    }
    fun toggle() = dispatch {
        if (!settings.enabled) return@dispatch
        context.settings.edit().putBoolean(DistanceWidgetStore.HIDDEN, !settings.hiddenByButton).apply()
        settings = DistanceWidgetStore.read(context.settings)
        render()
        event.accept("distance_button", arrayOf("hidden", settings.hiddenByButton,
            "waiting", hosts.values.none { it.visible }))
    }
    private fun reconcile() {
        source.setNeeded(powerOn && (settings.enabled || preview))
        render()
    }
    private fun publishPreview() {
        previewSink.accept(Bundle().apply {
            putLong("generation", frame.generation); putLong("received", frame.receivedAt)
            putBoolean("valid", frame.valid); putString("status", frame.status); putDoubleArray("distances", frame.distances())
        })
    }
    private fun render() {
        val show = !appVisible && !overlayFailure && frame.fresh(SystemClock.elapsedRealtime()) &&
            settings.visible(powerOn, true, frame.distances().map { it.takeIf(Double::isFinite) })
        try {
            if (show && (!displayCached || cachedTarget != settings.target)) {
                if (displayManager == null) {
                    displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                    displayManager!!.registerDisplayListener(displayListener, handler)
                }
                cachedDisplay = CameraDisplayTarget.resolve(context, settings.target.ordinal)
                cachedDisplay?.getRealMetrics(cachedMetrics)
                cachedTarget = settings.target; displayCached = true
            }
            val display = if (show) cachedDisplay else null
            val metrics = cachedMetrics
            val hostKey = display?.let { Triple(it.displayId, metrics.widthPixels, metrics.heightPixels) }
            hosts.forEach { (key, host) -> if (host.visible && (!show || key != hostKey)) {
                host.overlay.setStrictVisible(false, 0f); host.visible = false
                host.timing.clear()
                if (DiagnosticLogPolicy.extended()) event.accept("distance_visibility_detail", arrayOf(
                    "display", host.displayId, "visible", false, "frame_status", frame.status,
                    "last_view_age_ms", SystemClock.elapsedRealtime() - host.view.sourceReceivedAt))
            } }
            if (!show) return
            if (display == null) throw IllegalStateException("Distance target display unavailable: ${settings.target}")
            val host = hosts.getOrPut(requireNotNull(hostKey)) {
                val root = FrameLayout(context)
                val view = DistanceWidgetView(context); root.addView(view)
                val timing = DiagnosticTimingWindow("draw_us", "draw_cpu_us", "frame_age_ms")
                view.drawTiming = { micros, cpu, age ->
                    timing.record(SystemClock.elapsedRealtime(), micros, cpu, age)?.let { report ->
                        event.accept("distance_render_metrics", arrayOf<Any>(*report,
                            "display", display.displayId, "width", view.width, "height", view.height))
                    }
                }
                val overlay = WindowlessOverlayHost(context, display, WindowlessOverlayHost.mirrorLayer()-1,
                    "distance", -1, event)
                overlay.attach(root, metrics.widthPixels, metrics.heightPixels, 0, 0, "BYD Extend distance", false, true)
                Host(display.displayId, overlay, view, metrics.widthPixels, metrics.heightPixels, timing)
            }
            if (host.generation != frame.generation) {
                host.timing.clear(); host.generation = frame.generation
            }
            val bounds = settings.profile.frame(host.width.toFloat(), host.height.toFloat())
            val layout = host.view.layoutParams as? FrameLayout.LayoutParams
            if (layout == null || layout.width != bounds.width.roundToInt() || layout.height != bounds.height.roundToInt() ||
                layout.leftMargin != bounds.x.roundToInt() || layout.topMargin != bounds.y.roundToInt()) {
                host.view.layoutParams = FrameLayout.LayoutParams(bounds.width.roundToInt(), bounds.height.roundToInt()).apply {
                    leftMargin = bounds.x.roundToInt(); topMargin = bounds.y.roundToInt()
                }
            }
            host.view.update(settings, BlindSpotOverlayController.readCornerRadius(context.settings).toFloat(), frame)
            if (!host.visible) { host.overlay.setStrictVisible(true, 1f); host.visible = true }
        } catch (error: Exception) {
            overlayFailure = true; failure("overlay", error)
            hosts.values.forEach { host ->
                try { host.overlay.quiesce(); host.visible = false }
                catch (cleanup: Exception) { failure("overlay_cleanup", cleanup) }
            }
        }
    }
    private fun failure(operation: String, error: Exception) {
        event.accept("distance_widget_error", arrayOf("operation", operation, "error", error.toString(), "stack", error.stackTraceToString()))
    }
    fun shutdown() {
        source.control {
            if (stopped) return@control
            stopped = true
            source.setNeeded(false)
            displayManager?.unregisterDisplayListener(displayListener)
            hosts.values.forEach { try { it.overlay.quiesce(); it.visible = false } catch(error: Exception) { failure("cleanup",error) } }
        }
    }
}
