package com.byd.extend;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

import java.util.Arrays;
import java.util.function.BiConsumer;

final class ShellReverseCameraOverlay implements ReverseCameraCompositionView.Callback {
    private static final String WINDOW_TITLE = "BYD trusted reverse cameras";

    private final Context context;
    private final BiConsumer<String, Object[]> eventSink;

    private WindowlessOverlayHost windowless;
    private WindowlessOverlayHost frontControl;
    private WindowlessOverlayHost rearControl;
    private ReverseCameraCompositionView root;
    private float imageAlpha = 1.0f;
    private int requestId;
    private int[] surfaceGenerations = new int[0];
    private boolean centralFrontSourceEnabled;
    private int completedFrameRequestId;
    private boolean visible;
    private boolean active;
    private boolean closing;
    private boolean blockedRevealReported;

    ShellReverseCameraOverlay(Context context, BiConsumer<String, Object[]> eventSink) {
        this.context = context;
        this.eventSink = eventSink;
    }

    void prepare(CameraShellProtocol.ReverseOverlaySpec spec) throws Exception {
        // A selector attach failure leaves a process-scoped host quiesced.  Never replace that
        // retained host in this process; the controller must restart the shell first.
        if (root == null && (frontControl != null || rearControl != null)) {
            throw new CameraShellProtocol.PrepareRestartRequired("selector_attach_failed");
        }
        Display display = CameraDisplayTarget.resolve(context, CameraDisplayTarget.TABLET);
        if (display == null) throw new IllegalStateException("tablet display unavailable");
        Point size = new Point();
        display.getSize(size);
        spec.validate(size.x, size.y);
        centralFrontSourceEnabled = spec.requiresCentralFrontSource();

        if (root != null) {
            if (!root.dewarpPipelineCompatible(
                    spec.rearDewarp, spec.leftDewarp, spec.rightDewarp,
                    spec.frontLeftDewarp, spec.frontRightDewarp,
                    spec.centralFrontDewarp,
                    spec.frontLeftIntegrated && spec.widgetVisible,
                    spec.frontRightIntegrated && spec.widgetVisible,
                    centralFrontSourceEnabled)) {
                quiesce("dewarp_pipeline_changed");
                throw new CameraShellProtocol.PrepareRestartRequired(
                        "dewarp_pipeline_changed");
            } else if (!root.usesPaneGeometry(spec.layout, size.x, size.y)) {
                quiesce("camera_geometry_changed");
                throw new CameraShellProtocol.PrepareRestartRequired(
                        "camera_geometry_changed");
            } else if (!root.usesPaneBoundedBuffers(
                    spec.layout, size.x, size.y, spec.bufferQuality)) {
                quiesce("camera_buffer_size_changed");
                throw new CameraShellProtocol.PrepareRestartRequired(
                        "camera_buffer_size_changed");
            }
        }
        requestId = spec.requestId;
        imageAlpha = WindowlessOverlayHost.alphaForTransparency(spec.transparencyPercent);
        completedFrameRequestId = 0;
        blockedRevealReported = false;
        visible = false;
        if (windowless != null) {
            windowless.setDiagnosticState(requestId, Arrays.toString(surfaceGenerations));
        }
        if (root == null) createWindow(display, size, spec);
        else {
            root.setCallback(this);
            root.setCornerRadiusDp(spec.cornerRadiusDp);
            root.applyDewarpConfigs(spec.rearDewarp, spec.leftDewarp, spec.rightDewarp,
                    spec.centralFrontDewarp);
            root.applyRawFallbackLayout(spec.rawFallbackLayout);
            root.applyLayout(spec.layout);
            root.configureIntegratedFront(
                    spec.frontLayout, spec.frontRawFallbackLayout,
                    spec.frontLeftDewarp, spec.frontRightDewarp,
                    spec.centralFrontDewarp,
                    spec.frontLeftIntegrated, spec.frontRightIntegrated,
                    spec.centralFrontIntegrated,
                    centralFrontSourceEnabled,
                    spec.widgetVisible);
            root.setCentralFrontSourceEnabled(centralFrontSourceEnabled);
            root.applyVisibility(spec.visibilityMask);
            configureControls(display, size, spec.widgetVisible);
            windowless.setVisible(false, imageAlpha);
        }
        active = true;
        root.setDewarpStatsContext(requestId, surfaceGenerations);
        if (root.surfacesReady()) onReverseSurfacesReady(surfaceGenerations);
        emit("reverse_overlay_prepare", "request_id", requestId,
                "width", size.x, "height", size.y,
                "rear_dewarp", spec.rearDewarp.enabled,
                "left_dewarp", spec.leftDewarp.enabled,
                "right_dewarp", spec.rightDewarp.enabled,
                "rear_display_mode", spec.layout.rear.displayMode,
                "left_display_mode", spec.layout.rearLeft.displayMode,
                "right_display_mode", spec.layout.rearRight.displayMode,
                "visibility_mask", spec.visibilityMask,
                "widget_visible", spec.widgetVisible,
                "front_left_integrated", spec.frontLeftIntegrated,
                "front_right_integrated", spec.frontRightIntegrated,
                "central_front_integrated", spec.centralFrontIntegrated,
                "central_front_source", centralFrontSourceEnabled);
    }

    SurfaceSnapshot acquireSurfaces(int expectedRequestId) {
        requireRequest(expectedRequestId);
        ReverseCameraCompositionView.SurfaceBundle bundle = root.acquireSurfaces(requestId);
        onReverseSurfacesReady(bundle.generations);
        return new SurfaceSnapshot(bundle.requestId, bundle.generations, bundle.surfaces);
    }

    void armFrames(int expectedRequestId, int[] expectedGenerations) {
        requireRequest(expectedRequestId);
        root.armFrames(expectedRequestId, expectedGenerations);
        completedFrameRequestId = 0;
        blockedRevealReported = false;
        emit("reverse_overlay_frame", "state", "armed",
                "request_id", requestId,
                "surface_generations", Arrays.toString(expectedGenerations));
    }

    void setVisible(int expectedRequestId, int[] expectedGenerations, boolean nextVisible) {
        requireRequest(expectedRequestId);
        if (nextVisible) {
            if (!root.framesReady(expectedRequestId, expectedGenerations)
                    || completedFrameRequestId != expectedRequestId) {
                if (!blockedRevealReported) {
                    blockedRevealReported = true;
                    emit("reverse_overlay_frame", "state", "reveal_blocked",
                            "request_id", requestId,
                            "surface_generations", Arrays.toString(expectedGenerations));
                }
                throw new IllegalStateException("reverse first frames not confirmed");
            }
        }
        try {
            if (!nextVisible) setControlsVisible(false);
            windowless.setVisible(nextVisible, imageAlpha);
            if (nextVisible) {
                try {
                    setControlsVisible(true);
                } catch (Throwable controlError) {
                    disableControls("show", controlError);
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("reverse visibility update failed", error);
        }
        visible = nextVisible;
        emit("reverse_overlay_visibility", "visible", nextVisible,
                "request_id", requestId,
                "surface_generations", Arrays.toString(expectedGenerations));
    }

    void updateVisuals(int cornerRadiusDp, int transparencyPercent) {
        CameraShellProtocol.validateVisualStyle(cornerRadiusDp, transparencyPercent);
        imageAlpha = WindowlessOverlayHost.alphaForTransparency(transparencyPercent);
        if (root == null || windowless == null) return;
        root.setCornerRadiusDp(cornerRadiusDp);
        try {
            windowless.setVisible(visible, imageAlpha);
        } catch (Exception error) {
            throw new IllegalStateException("reverse visual update failed", error);
        }
    }

    /** Applies one persisted pane mask without replacing the Reverse host or input surfaces. */
    void updateVisibility(
            int expectedRequestId, int[] expectedGenerations,
            int visibilityMask, boolean widgetVisible) {
        requireRequest(expectedRequestId);
        if (expectedGenerations == null
                || !Arrays.equals(expectedGenerations, surfaceGenerations)) {
            throw new IllegalStateException("stale reverse Surface generations");
        }
        ReverseCameraLayout.requireVisibilityMask(visibilityMask);
        root.applyVisibility(visibilityMask);
        root.setWidgetVisible(widgetVisible);
        if (!widgetVisible) {
            setControlsVisible(false);
        } else if (visible) {
            setControlsVisible(true);
        }
        emit("reverse_overlay_visibility_mask", "request_id", requestId,
                "surface_generations", Arrays.toString(surfaceGenerations),
                "visibility_mask", visibilityMask,
                "widget_visible", widgetVisible);
    }

    void close(String reason) {
        ReverseCameraCompositionView activeRoot = root;
        if (activeRoot == null || !active) return;
        quiesce(reason);
    }

    private void quiesce(String reason) {
        ReverseCameraCompositionView activeRoot = root;
        if (activeRoot == null || !active) return;
        WindowlessOverlayHost activeHost = windowless;
        activeRoot.setCallback(null);
        closing = true;
        try {
            setControlsVisible(false);
            if (activeHost != null) {
                activeHost.quiesce();
            }
            active = false;
            emit("reverse_overlay_window", "state", "removed", "reason", safe(reason),
                    "reusable", true);
        } catch (Throwable error) {
            emit("reverse_overlay_error", "stage", "remove_window",
                    "error", summary(error));
            closing = false;
            throw new IllegalStateException("reverse window removal failed", error);
        } finally {
            visible = false;
            requestId = 0;
            completedFrameRequestId = 0;
            centralFrontSourceEnabled = false;
            closing = false;
            blockedRevealReported = false;
        }
    }

    boolean isOpen() {
        return root != null && active;
    }

    @Override
    public void onReverseSurfacesReady(int[] generations) {
        if (root == null) return;
        surfaceGenerations = generations.clone();
        if (windowless != null) {
            windowless.setDiagnosticState(requestId, Arrays.toString(surfaceGenerations));
        }
        root.setDewarpStatsContext(requestId, surfaceGenerations);
        emit("reverse_overlay_surface", "state", "ready",
                "request_id", requestId,
                "surface_generations", Arrays.toString(surfaceGenerations));
    }

    @Override
    public void onReverseFramesReady(int frameRequestId, int[] generations) {
        if (frameRequestId != requestId || !Arrays.equals(generations, surfaceGenerations)) return;
        completedFrameRequestId = frameRequestId;
        emit("reverse_overlay_first_frames", "request_id", requestId,
                "surface_generations", Arrays.toString(generations));
    }

    @Override
    public void onReverseTargetActive(int sourceIndex, int generation, boolean active) {
        emit("reverse_overlay_target", "state", "active",
                "request_id", requestId,
                "camera_index", sourceIndex,
                "surface_generation", generation,
                "active", active);
    }

    @Override
    public void onReverseFrameGateBlocked(
            int frameRequestId, String reason, int source, int expectedGeneration,
            int actualGeneration) {
        emit("reverse_overlay_frame", "state", "frame_blocked",
                "request_id", frameRequestId, "reason", reason,
                "source", source, "expected_generation", expectedGeneration,
                "actual_generation", actualGeneration);
    }

    @Override
    public void onReverseSurfaceLost(int cameraIndex, int generation) {
        if (cameraIndex == 4 && centralFrontSourceEnabled) {
            emit("reverse_overlay_surface", "state", "optional_destroyed",
                    "request_id", requestId, "camera_index", cameraIndex,
                    "surface_generation", generation);
            return;
        }
        visible = false;
        completedFrameRequestId = 0;
        if (!closing && windowless != null && root != null) {
            try {
                setControlsVisible(false);
                windowless.setVisible(false, imageAlpha);
            } catch (Exception error) {
                emit("reverse_overlay_error", "stage", "hide_surface_lost",
                        "request_id", requestId, "error", summary(error));
            }
        }
        emit("reverse_overlay_surface", "state", "destroyed",
                "request_id", requestId, "camera_index", cameraIndex,
                "surface_generation", generation);
    }

    @Override
    public void onReverseDewarpStats(
            int cameraIndex, CameraDewarpRenderer.Stats stats) {
        int generation = cameraIndex >= 1 && cameraIndex <= surfaceGenerations.length
                ? surfaceGenerations[cameraIndex - 1] : 0;
        if (stats.requestId != requestId || stats.contextGeneration != generation) return;
        emit("camera_dewarp_stats",
                CameraDewarpStatsEvent.shellReverse(cameraIndex, stats));
    }

    @Override
    public void onReverseDewarpEvent(
            int cameraIndex, CameraDewarpRenderer.Event event) {
        int generation = cameraIndex >= 1 && cameraIndex <= surfaceGenerations.length
                ? surfaceGenerations[cameraIndex - 1] : 0;
        emit(event.kind,
                "camera_owner", CameraHelperMain.CAMERA_OWNER_REVERSE,
                "camera_index", cameraIndex,
                "request_id", requestId,
                "surface_generation", generation,
                "lens", event.lens,
                "enabled", event.enabled,
                "fov_degrees", event.fovDegrees,
                "projection", CameraDewarpConfig.projectionLabel(event.projection),
                "mesh_vertices", event.vertexCount,
                "generation_ms", event.generationMs,
                "error", event.error);
        if (CameraDewarpRenderer.isFatalEventKind(event.kind)) {
            hideFailedRenderer();
            emit("reverse_overlay_error", "stage", event.kind,
                    "request_id", requestId,
                    "camera_index", cameraIndex,
                    "surface_generation", generation,
                    "error", event.error);
        }
    }

    private void hideFailedRenderer() {
        visible = false;
        completedFrameRequestId = 0;
        if (root == null || windowless == null) return;
        try {
            setControlsVisible(false);
            windowless.setVisible(false, imageAlpha);
        } catch (Throwable error) {
            emit("reverse_overlay_error", "stage", "hide_failed_renderer",
                    "request_id", requestId, "error", summary(error));
        }
    }

    private void createWindow(
            Display display, Point size, CameraShellProtocol.ReverseOverlaySpec spec)
            throws Exception {
        Context windowContext = context.createDisplayContext(display);

        ReverseCameraCompositionView nextRoot = new ReverseCameraCompositionView(windowContext);
        nextRoot.setCallback(this);
        nextRoot.setCornerRadiusDp(spec.cornerRadiusDp);
        nextRoot.setDewarpPipelineRequirements(
                spec.rearDewarp, spec.leftDewarp, spec.rightDewarp,
                spec.frontLeftDewarp, spec.frontRightDewarp,
                spec.centralFrontDewarp,
                spec.frontLeftIntegrated && spec.widgetVisible,
                spec.frontRightIntegrated && spec.widgetVisible,
                centralFrontSourceEnabled);
        nextRoot.applyDewarpConfigs(spec.rearDewarp, spec.leftDewarp, spec.rightDewarp,
                spec.centralFrontDewarp);
        nextRoot.applyRawFallbackLayout(spec.rawFallbackLayout);
        nextRoot.applyLayout(spec.layout);
        nextRoot.configureIntegratedFront(
                spec.frontLayout, spec.frontRawFallbackLayout,
                spec.frontLeftDewarp, spec.frontRightDewarp,
                spec.centralFrontDewarp,
                spec.frontLeftIntegrated, spec.frontRightIntegrated,
                spec.centralFrontIntegrated,
                centralFrontSourceEnabled,
                spec.widgetVisible);
        nextRoot.setCentralFrontSourceEnabled(centralFrontSourceEnabled);
        nextRoot.applyVisibility(spec.visibilityMask);
        nextRoot.setPaneBoundedBuffers(size.x, size.y, spec.bufferQuality);
        root = nextRoot;
        WindowlessOverlayHost nextHost = new WindowlessOverlayHost(
                windowContext, display, WindowlessOverlayHost.REVERSE_LAYER,
                "reverse", -1, eventSink);
        windowless = nextHost;
        nextHost.setDiagnosticState(requestId, Arrays.toString(surfaceGenerations));
        try {
            nextHost.attach(nextRoot, size.x, size.y, 0, 0, WINDOW_TITLE);
            configureControls(display, size, spec.widgetVisible);
        } catch (Throwable error) {
            releaseControlsQuietly();
            windowless = null;
            root = null;
            try {
                nextHost.quiesce();
            } catch (Throwable ignored) {}
            throw new CameraShellProtocol.PrepareRestartRequired("reverse_attach_failed");
        }
        emit("reverse_overlay_window", "state", "added",
                "request_id", requestId, "width", size.x, "height", size.y,
                "layer", nextHost.layer(), "alpha", 0.0f,
                "trusted_api", nextHost.trustedApi(),
                "transparency_percent", spec.transparencyPercent);
    }

    private void configureControls(Display display, Point size, boolean enabled)
            throws Exception {
        if (root == null) return;
        root.setWidgetAvailable(enabled);
        if (!enabled) {
            setControlsVisible(false);
            return;
        }
        if (frontControl != null && rearControl != null) {
            try {
                ReverseCameraLayout.PixelRect frontBounds = root.selectorButtonBounds(
                        ReverseSideSelectorView.MODE_FRONT, size.x, size.y);
                ReverseCameraLayout.PixelRect rearBounds = root.selectorButtonBounds(
                        ReverseSideSelectorView.MODE_REAR, size.x, size.y);
                frontControl.setPosition(frontBounds.left, frontBounds.top);
                rearControl.setPosition(rearBounds.left, rearBounds.top);
            } catch (Throwable error) {
                emit("reverse_overlay_error", "stage", "selector_reposition",
                        "request_id", requestId, "error", summary(error));
            }
            return;
        }
        Context windowContext = context.createDisplayContext(display);
        WindowlessOverlayHost nextFront = null;
        WindowlessOverlayHost nextRear = null;
        try {
            ReverseCameraLayout.PixelRect frontBounds = root.selectorButtonBounds(
                    ReverseSideSelectorView.MODE_FRONT, size.x, size.y);
            ReverseCameraLayout.PixelRect rearBounds = root.selectorButtonBounds(
                    ReverseSideSelectorView.MODE_REAR, size.x, size.y);
            nextFront = createControlHost(
                    windowContext, display, frontBounds,
                    ReverseSideSelectorView.MODE_FRONT, "BYD reverse front selector");
            frontControl = nextFront;
            nextRear = createControlHost(
                    windowContext, display, rearBounds,
                    ReverseSideSelectorView.MODE_REAR, "BYD reverse rear selector");
            rearControl = nextRear;
        } catch (Throwable error) {
            quiesceHost(nextFront);
            quiesceHost(nextRear);
            emit("reverse_overlay_error", "stage", "selector_attach",
                    "request_id", requestId, "error", summary(error));
            throw new CameraShellProtocol.PrepareRestartRequired("selector_attach_failed");
        }
    }

    private WindowlessOverlayHost createControlHost(
            Context windowContext, Display display, ReverseCameraLayout.PixelRect bounds,
            int mode, String title) throws Exception {
        View button = new View(windowContext);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setClickable(true);
        button.setSoundEffectsEnabled(false);
        button.setContentDescription(mode == ReverseSideSelectorView.MODE_FRONT
                ? "Перед" : "Зад");
        button.setOnTouchListener((view, event) -> {
            ReverseCameraCompositionView currentRoot = root;
            if (currentRoot != null) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    currentRoot.setSelectorPressed(mode, true);
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    currentRoot.setSelectorPressed(mode, false);
                }
            }
            return false;
        });
        button.setOnClickListener(view -> {
            if (root == null) return;
            root.setSideMode(mode);
            emit("reverse_overlay_selector", "request_id", requestId,
                    "mode", mode == ReverseSideSelectorView.MODE_FRONT ? "front" : "rear");
        });
        WindowlessOverlayHost host = new WindowlessOverlayHost(
                windowContext, display, WindowlessOverlayHost.REVERSE_CONTROL_LAYER,
                "reverse_selector", mode, eventSink);
        host.setDiagnosticState(requestId, Arrays.toString(surfaceGenerations));
        host.attach(button, bounds.width, bounds.height, bounds.left, bounds.top,
                title, true, true);
        return host;
    }

    private void setControlsVisible(boolean nextVisible) {
        if (!nextVisible && root != null) {
            root.setSelectorPressed(ReverseSideSelectorView.MODE_FRONT, false);
            root.setSelectorPressed(ReverseSideSelectorView.MODE_REAR, false);
        }
        if (frontControl == null || rearControl == null) return;
        try {
            frontControl.setStrictVisible(nextVisible, 1.0f);
            rearControl.setStrictVisible(nextVisible, 1.0f);
        } catch (Throwable error) {
            disableControls(nextVisible ? "show" : "hide", error);
        }
    }

    private void disableControls(String stage, Throwable error) {
        if (root != null) root.setWidgetAvailable(false);
        releaseControlsQuietly();
        emit("reverse_overlay_error", "stage", "selector_" + stage,
                "request_id", requestId, "error", summary(error));
    }

    private void releaseControls() {
        WindowlessOverlayHost activeFront = frontControl;
        WindowlessOverlayHost activeRear = rearControl;
        quiesceHost(activeFront);
        quiesceHost(activeRear);
    }

    private void releaseControlsQuietly() {
        releaseControls();
    }

    private void quiesceHost(WindowlessOverlayHost host) {
        if (host == null) return;
        try {
            host.setStrictVisible(false, 1.0f);
        } catch (Throwable ignored) {}
        try {
            host.quiesce();
        } catch (Throwable error) {
            emit("reverse_overlay_error", "stage", "selector_quiesce",
                    "request_id", requestId, "error", summary(error));
        }
    }

    private void requireRequest(int expectedRequestId) {
        if (root == null || expectedRequestId <= 0 || expectedRequestId != requestId) {
            throw new IllegalStateException("stale reverse overlay request");
        }
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private static String safe(String reason) {
        return reason == null || reason.isEmpty() ? "unknown" : reason;
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    static final class SurfaceSnapshot {
        final int requestId;
        final int[] generations;
        final Surface[] surfaces;

        SurfaceSnapshot(int requestId, int[] generations, Surface[] surfaces) {
            this.requestId = requestId;
            this.generations = generations;
            this.surfaces = surfaces;
        }
    }
}
