package com.byd.extend

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
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
        val previous = settings
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
        if (settings != previous) settingsDiagnostic()
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
    fun loggingChanged() = dispatch { settingsDiagnostic(); if (needed) publishFrame() }
    fun toggle() = dispatch {
        if (!settings.enabled) return@dispatch
        context.settings.edit().putBoolean(DistanceWidgetStore.HIDDEN, !settings.hiddenByButton).apply()
        settings = DistanceWidgetStore.read(context.settings)
        settingsDiagnostic()
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
            if (host.preparedSettings != settings || host.generation != frame.generation)
                flushHostTiming(host, "prepare_update")
            host.generation = frame.generation
            host.view.update(settings, BlindSpotOverlayController.readCornerRadius(context.settings).toFloat(),
                if (host.visible) frame.at(SystemClock.elapsedRealtime()) else DistanceFrame.unavailable(frame.generation, 0, "preparing"))
            host.preparedSettings = settings
            render()
        } catch (error: Exception) { overlayError("prepare", error) }
    }
    private fun render() {
        val now = SystemClock.elapsedRealtime()
        frame = frame.at(now)
        val hideReason = hiddenReason(now)
        val show = hideReason == null
        try {
            val display = if (show && displayCached && cachedTarget == settings.target) cachedDisplay else null
            val metrics = cachedMetrics
            val hostKey = display?.let { Triple(it.displayId, metrics.widthPixels, metrics.heightPixels) }
            hosts.forEach { (key, host) -> if (host.visible && (!show || key != hostKey)) {
                host.view.stopAnimation()
                host.overlay.setStrictVisible(false, 0f); host.visible = false
                flushHostTiming(host, "hidden")
                visibilityDiagnostic(host, hideReason ?: "display_changed")
            } }
            if (!show) return
            val host = hosts[hostKey]
            if (host == null || host.preparedSettings != settings) {
                if (!displayCached || display != null) schedulePreparation()
                return
            }
            if (host.generation != frame.generation) {
                flushHostTiming(host, "generation_changed"); host.generation = frame.generation
            }
            host.view.update(settings, BlindSpotOverlayController.readCornerRadius(context.settings).toFloat(), frame)
            if (!requestedPowerOn || !frame.at(SystemClock.elapsedRealtime()).fresh(SystemClock.elapsedRealtime())) {
                host.view.stopAnimation()
                flushHostTiming(host, "late_visibility_check")
                if (host.visible) {
                    host.overlay.setStrictVisible(false, 0f); host.visible = false
                    visibilityDiagnostic(host, if (!requestedPowerOn) "power_requested_off" else "source_expired_during_update")
                }
                return
            }
            if (!host.visible) {
                host.overlay.setStrictVisible(true, 1f); host.visible = true
                visibilityDiagnostic(host, "eligible")
            }
        } catch (error: Exception) {
            overlayError("overlay", error)
        }
    }
    private fun flushHostTiming(host: Host, reason: String) {
        val now = SystemClock.elapsedRealtime()
        host.timing.flush(now)?.let {
            event.accept("distance_render_metrics", arrayOf<Any>(*it, "display", host.displayId,
                "width", host.view.width, "height", host.view.height, "generation", host.generation,
                "flush_reason", reason))
        }
        host.animationTiming.flush(now)?.let {
            event.accept("distance_animation_metrics", arrayOf<Any>(*it, "display", host.displayId,
                "generation", host.generation, "flush_reason", reason))
        }
    }
    private fun hiddenReason(now: Long): String? = when {
        !requestedPowerOn -> "power_requested_off"
        appVisible -> "app_visible"
        overlayFailure -> "overlay_error"
        !frame.fresh(now) -> "source_unavailable_or_stale"
        !settings.visible(powerOn, true, frame.distances().map { it.takeIf(Double::isFinite) }) -> when {
            !settings.enabled -> "disabled"
            settings.hiddenByButton -> "hidden_by_button"
            !powerOn -> "power_off"
            else -> "outside_zones"
        }
        else -> null
    }
    private fun visibilityDiagnostic(host: Host, reason: String) {
        if (!DiagnosticLogPolicy.extended()) return
        val now = SystemClock.elapsedRealtime()
        val distances = frame.distances()
        event.accept("distance_visibility_detail", arrayOf(
            "display", host.displayId, "visible", host.visible, "reason", reason,
            "frame_status", frame.status, "generation", frame.generation,
            "frame_received_ms", frame.receivedAt, "frame_age_ms", now - frame.receivedAt,
            "adas_received_ms", frame.adasReceivedAt, "adas_age_ms", now - frame.adasReceivedAt,
            "last_view_age_ms", now - host.view.sourceReceivedAt,
            "frame_fresh", frame.fresh(now), "radar_available", frame.radarAvailable, "radar_mask", frame.radarMask,
            "distances_cm", JSONArray(distances.map { if (it.isFinite()) it else JSONObject.NULL }),
            "nearest_cm", (distances.filter(Double::isFinite).minOrNull() ?: JSONObject.NULL),
            "threshold_cm", (settings.zones.maxOfOrNull { it.endCm } ?: JSONObject.NULL),
            "requested_power_on", requestedPowerOn, "power_on", powerOn, "app_visible", appVisible,
            "overlay_failure", overlayFailure, "enabled", settings.enabled,
            "hidden_by_button", settings.hiddenByButton, "format", settings.format.name,
            "target", settings.target.name))
    }
    private fun settingsDiagnostic() {
        if (!DiagnosticLogPolicy.extended()) return
        event.accept("distance_settings_detail", arrayOf("enabled", settings.enabled,
            "hidden_by_button", settings.hiddenByButton, "target", settings.target.name,
            "format", settings.format.name, "zones", JSONArray(settings.zones.map {
                JSONObject().put("id", it.id).put("end_cm", it.endCm).put("color_argb", it.color)
            })))
    }
    private fun overlayError(operation: String, error: Exception) {
        overlayFailure = true; failure(operation, error)
        hosts.values.forEach { host ->
            host.view.stopAnimation()
            flushHostTiming(host, "overlay_error")
            try {
                host.overlay.quiesce()
                if (host.visible) { host.visible = false; visibilityDiagnostic(host, "overlay_error") }
            }
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
                flushHostTiming(it, "shutdown")
                try {
                    it.overlay.quiesce()
                    if (it.visible) { it.visible = false; visibilityDiagnostic(it, "shutdown") }
                } catch(error: Exception) { failure("cleanup",error) }
            }
        }
    }
}
