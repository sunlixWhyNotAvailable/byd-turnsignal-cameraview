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
    private val identity: Context,
    private val handler: Handler,
    private val event: BiConsumer<String, Array<Any>>,
    private val previewSink: Consumer<Bundle>,
) {
    private var settings = DistanceWidgetSettings()
    private var frame = DistanceFrame.unavailable(0, 0, "inactive")
    private var powerOn = false
    @Volatile private var requestedPowerOn = false
    private var appVisible = false
    private var preview = false
    private var overlayFailure = false
    private var stopped = false
    private var needed = false
    private val sources = DistanceSources()
    private var radarThread: android.os.HandlerThread? = null
    private var radar: ParkingRadarRuntime? = null
    private var radarPackets: LatestValueDispatcher<ParkingRadarState.Snapshot>? = null
    private var radarEpoch = 0L
    private val diagnosticSampler = DistanceDiagnosticSampler()
    private val publishTiming = DiagnosticTimingWindow("work_us", "cpu_us", "view_us", "preview_us", "diagnostic_submit_us")
    private val pendingDiagnostic = Runnable { if (!stopped) publishDiagnostic() }
    private val prepareWindow = Runnable { prepareHost() }
    private var prepareQueued = false
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
        val timing: DiagnosticTimingWindow, val animationTiming: DiagnosticTimingWindow,
        var generation: Long = -1, var visible: Boolean = false,
        var preparedSettings: DistanceWidgetSettings? = null)
    private val hosts = mutableMapOf<Triple<Int, Int, Int>, Host>()
    private val source = DistanceAdasSource(identity, handler, { next ->
        if (needed && !stopped) { sources.adas(next); publishFrame() }
    }, event)

    private fun dispatch(action: () -> Unit) {
        if (android.os.Looper.myLooper() == handler.looper) { if (!stopped) action() }
        else handler.post { if (!stopped) action() }
    }
    private fun displayChanged() {
        displayCached = false; overlayFailure = false
        if (!stopped) { schedulePreparation(); render() }
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
        requestedPowerOn = on
        if (on) dispatch { powerOn = requestedPowerOn; reconcile() }
        else source.control { if (!stopped) { powerOn = requestedPowerOn; reconcile() } }
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
    fun loggingChanged() = dispatch { if (needed) publishFrame() }
    fun toggle() = dispatch {
        if (!settings.enabled) return@dispatch
        context.settings.edit().putBoolean(DistanceWidgetStore.HIDDEN, !settings.hiddenByButton).apply()
        settings = DistanceWidgetStore.read(context.settings)
        render()
        event.accept("distance_button", arrayOf("hidden", settings.hiddenByButton,
            "waiting", hosts.values.none { it.visible }))
    }
    private fun reconcile() {
        schedulePreparation()
        val next = powerOn && (settings.enabled || preview)
        if (needed != next) {
            needed = next
            val epoch = ++radarEpoch
            radarPackets?.clear()
            if (next) {
                val thread = radarThread ?: android.os.HandlerThread("distance-radar").also { it.start(); radarThread = it }
                val packets = LatestValueDispatcher<ParkingRadarState.Snapshot>(handler::post) { snapshot, _ ->
                    if (!stopped && needed && radarEpoch == epoch) { sources.radar(snapshot); publishFrame() }
                }
                radarPackets = packets
                radar = ParkingRadarRuntime(identity, Handler(thread.looper), event, packets::offer).also { it.start() }
            } else {
                radar?.stop(); radar = null; radarPackets = null
                sources.clear()
            }
        }
        source.setNeeded(next)
        publishFrame()
    }
    private fun publishFrame() {
        val measure = publishTiming.enabled()
        val start = if (measure) SystemClock.elapsedRealtimeNanos() else 0L
        val cpu = if (measure) android.os.Debug.threadCpuTimeNanos() else 0L
        val now = SystemClock.elapsedRealtime()
        frame = if (needed) sources.frame(now) else DistanceFrame.unavailable(frame.generation, now, "inactive")
        render()
        val rendered = if (measure) SystemClock.elapsedRealtimeNanos() else 0L
        if (appVisible && preview) publishPreview()
        val published = if (measure) SystemClock.elapsedRealtimeNanos() else 0L
        publishDiagnostic()
        if (measure) publishTiming.record(SystemClock.elapsedRealtime(),
            (SystemClock.elapsedRealtimeNanos() - start) / 1000,
            (android.os.Debug.threadCpuTimeNanos() - cpu) / 1000,
            (rendered - start) / 1000, (published - rendered) / 1000,
            (SystemClock.elapsedRealtimeNanos() - published) / 1000)?.let {
                event.accept("distance_publish_metrics", it)
            }
    }
    private fun publishDiagnostic() {
        handler.removeCallbacks(pendingDiagnostic)
        when (val delay = diagnosticSampler.delay(SystemClock.elapsedRealtime(), frame, sources.adas(), sources.radar())) {
            -1L -> Unit
            0L -> event.accept("distance_sources_detail", sources.diagnostic(frame))
            else -> if (needed) handler.postDelayed(pendingDiagnostic, delay)
        }
    }
    private fun publishPreview() {
        previewSink.accept(DistanceFrameCodec.encode(frame))
    }
    private fun schedulePreparation() {
        if (!stopped && requestedPowerOn && powerOn && settings.enabled && !overlayFailure && !prepareQueued) {
            prepareQueued = handler.post(prepareWindow)
        }
    }
    private fun prepareHost() {
        prepareQueued = false
        if (stopped || !requestedPowerOn || !powerOn || !settings.enabled || overlayFailure) return
        try {
            if (!displayCached || cachedTarget != settings.target) {
                if (displayManager == null) {
                    displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                    displayManager!!.registerDisplayListener(displayListener, handler)
                }
                cachedDisplay = CameraDisplayTarget.resolve(context, settings.target.ordinal)
                cachedDisplay?.getRealMetrics(cachedMetrics)
                cachedTarget = settings.target; displayCached = true
            }
            val display = cachedDisplay ?: return // The existing display listener retries availability changes.
            val key = Triple(display.displayId, cachedMetrics.widthPixels, cachedMetrics.heightPixels)
            val host = hosts.getOrPut(key) {
                val root = FrameLayout(context)
                val view = DistanceWidgetView(context); root.addView(view)
                val timing = DiagnosticTimingWindow("draw_us", "draw_cpu_us", "frame_age_ms")
                val animationTiming = DiagnosticTimingWindow("work_us", "cpu_us", "retarget")
                view.animationTiming = { micros, cpu, retarget ->
                    animationTiming.record(SystemClock.elapsedRealtime(), micros, cpu, retarget)?.let { report ->
                        event.accept("distance_animation_metrics", arrayOf<Any>(*report, "display", display.displayId))
                    }
                }
                view.drawTiming = { micros, cpu, age ->
                    timing.record(SystemClock.elapsedRealtime(), micros, cpu, age)?.let { report ->
                        event.accept("distance_render_metrics", arrayOf<Any>(*report,
                            "display", display.displayId, "width", view.width, "height", view.height))
                    }
                }
                val overlay = WindowlessOverlayHost(context, display, WindowlessOverlayHost.mirrorLayer()-1,
                    "distance", -1, event)
                if (!requestedPowerOn || stopped) return
                overlay.attach(root, cachedMetrics.widthPixels, cachedMetrics.heightPixels, 0, 0, "BYD Extend distance", false, true)
                Host(display.displayId, overlay, view, cachedMetrics.widthPixels, cachedMetrics.heightPixels, timing, animationTiming)
            }
            val bounds = settings.profile.frame(host.width.toFloat(), host.height.toFloat())
            host.view.layoutParams = FrameLayout.LayoutParams(bounds.width.roundToInt(), bounds.height.roundToInt()).apply {
                leftMargin = bounds.x.roundToInt(); topMargin = bounds.y.roundToInt()
            }
            // Load fonts/art on the owner thread before a telemetry callback needs to show this host.
            host.view.update(settings, BlindSpotOverlayController.readCornerRadius(context.settings).toFloat(),
                if (host.visible) frame.at(SystemClock.elapsedRealtime()) else DistanceFrame.unavailable(frame.generation, 0, "preparing"))
            host.preparedSettings = settings
            render()
        } catch (error: Exception) { overlayError("prepare", error) }
    }
    private fun render() {
        frame = frame.at(SystemClock.elapsedRealtime())
        val show = requestedPowerOn && !appVisible && !overlayFailure && frame.fresh(SystemClock.elapsedRealtime()) &&
            settings.visible(powerOn, true, frame.distances().map { it.takeIf(Double::isFinite) })
        try {
            val display = if (show && displayCached && cachedTarget == settings.target) cachedDisplay else null
            val metrics = cachedMetrics
            val hostKey = display?.let { Triple(it.displayId, metrics.widthPixels, metrics.heightPixels) }
            hosts.forEach { (key, host) -> if (host.visible && (!show || key != hostKey)) {
                host.view.stopAnimation()
                host.overlay.setStrictVisible(false, 0f); host.visible = false
                host.timing.clear(); host.animationTiming.clear()
                if (DiagnosticLogPolicy.extended()) event.accept("distance_visibility_detail", arrayOf(
                    "display", host.displayId, "visible", false, "frame_status", frame.status,
                    "last_view_age_ms", SystemClock.elapsedRealtime() - host.view.sourceReceivedAt))
            } }
            if (!show) return
            val host = hosts[hostKey]
            if (host == null || host.preparedSettings != settings) {
                if (!displayCached || display != null) schedulePreparation()
                return
            }
            if (host.generation != frame.generation) {
                host.timing.clear(); host.animationTiming.clear(); host.generation = frame.generation
            }
            host.view.update(settings, BlindSpotOverlayController.readCornerRadius(context.settings).toFloat(), frame)
            if (!requestedPowerOn || !frame.at(SystemClock.elapsedRealtime()).fresh(SystemClock.elapsedRealtime())) {
                host.view.stopAnimation()
                if (host.visible) { host.overlay.setStrictVisible(false, 0f); host.visible = false }
                return
            }
            if (!host.visible) { host.overlay.setStrictVisible(true, 1f); host.visible = true }
        } catch (error: Exception) {
            overlayError("overlay", error)
        }
    }
    private fun overlayError(operation: String, error: Exception) {
        overlayFailure = true; failure(operation, error)
        hosts.values.forEach { host ->
            host.view.stopAnimation()
            try { host.overlay.quiesce(); host.visible = false }
            catch (cleanup: Exception) { failure("overlay_cleanup", cleanup) }
        }
    }
    private fun failure(operation: String, error: Exception) {
        event.accept("distance_widget_error", arrayOf("operation", operation, "error", error.toString(), "stack", error.stackTraceToString()))
    }
    fun shutdown() {
        requestedPowerOn = false
        source.control {
            if (stopped) return@control
            stopped = true
            handler.removeCallbacks(pendingDiagnostic)
            handler.removeCallbacks(prepareWindow); prepareQueued = false
            needed = false; ++radarEpoch; radarPackets?.clear()
            radar?.stop(); radar = null; sources.clear()
            radarThread?.quitSafely(); radarThread = null
            source.setNeeded(false)
            displayManager?.unregisterDisplayListener(displayListener)
            hosts.values.forEach {
                it.view.stopAnimation()
                try { it.overlay.quiesce(); it.visible = false } catch(error: Exception) { failure("cleanup",error) }
            }
        }
    }
}
