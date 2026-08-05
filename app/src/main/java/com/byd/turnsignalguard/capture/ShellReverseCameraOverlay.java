package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;

import java.util.Arrays;
import java.util.function.BiConsumer;

final class ShellReverseCameraOverlay implements ReverseCameraCompositionView.Callback {
    private static final String WINDOW_TITLE = "BYD trusted reverse cameras";

    private final Context context;
    private final BiConsumer<String, Object[]> eventSink;

    private WindowManager windows;
    private ReverseCameraCompositionView root;
    private WindowManager.LayoutParams window;
    private int requestId;
    private int[] surfaceGenerations = new int[3];
    private int completedFrameRequestId;
    private boolean visible;
    private boolean closing;

    ShellReverseCameraOverlay(Context context, BiConsumer<String, Object[]> eventSink) {
        this.context = context;
        this.eventSink = eventSink;
    }

    void prepare(CameraShellProtocol.ReverseOverlaySpec spec) throws Exception {
        Display display = CameraDisplayTarget.resolve(context, CameraDisplayTarget.TABLET);
        if (display == null) throw new IllegalStateException("tablet display unavailable");
        Point size = new Point();
        display.getSize(size);
        spec.validate(size.x, size.y);

        if (root != null && !root.dewarpPipelineCompatible(
                spec.rearDewarp, spec.leftDewarp, spec.rightDewarp)) {
            close("dewarp_pipeline_changed");
        }
        requestId = spec.requestId;
        completedFrameRequestId = 0;
        visible = false;
        if (root == null) createWindow(display, size, spec);
        else {
            root.setCornerRadiusDp(spec.cornerRadiusDp);
            root.applyDewarpConfigs(spec.rearDewarp, spec.leftDewarp, spec.rightDewarp);
            root.applyLayout(spec.layout);
            window.alpha = 0.0f;
            windows.updateViewLayout(root, window);
        }
        if (root.surfacesReady()) onReverseSurfacesReady(surfaceGenerations);
        emit("reverse_overlay_prepare", "request_id", requestId,
                "width", size.x, "height", size.y,
                "rear_dewarp", spec.rearDewarp.enabled,
                "left_dewarp", spec.leftDewarp.enabled,
                "right_dewarp", spec.rightDewarp.enabled);
    }

    SurfaceSnapshot acquireSurfaces(int expectedRequestId) {
        requireRequest(expectedRequestId);
        ReverseCameraCompositionView.SurfaceBundle bundle = root.acquireSurfaces(requestId);
        return new SurfaceSnapshot(bundle.requestId, bundle.generations, bundle.surfaces);
    }

    void armFrames(int expectedRequestId, int[] expectedGenerations) {
        requireRequest(expectedRequestId);
        root.armFrames(expectedRequestId, expectedGenerations);
        completedFrameRequestId = 0;
        emit("reverse_overlay_frame", "state", "armed",
                "request_id", requestId,
                "surface_generations", Arrays.toString(expectedGenerations));
    }

    void setVisible(int expectedRequestId, int[] expectedGenerations, boolean nextVisible) {
        requireRequest(expectedRequestId);
        if (nextVisible) {
            if (!root.framesReady(expectedRequestId, expectedGenerations)
                    || completedFrameRequestId != expectedRequestId) {
                throw new IllegalStateException("reverse first frames not confirmed");
            }
        }
        window.alpha = nextVisible ? 1.0f : 0.0f;
        windows.updateViewLayout(root, window);
        visible = nextVisible;
        emit("reverse_overlay_visibility", "visible", nextVisible,
                "request_id", requestId,
                "surface_generations", Arrays.toString(expectedGenerations));
    }

    void close(String reason) {
        ReverseCameraCompositionView activeRoot = root;
        if (activeRoot == null) return;
        WindowManager activeWindows = windows;
        closing = true;
        try {
            if (activeWindows != null) activeWindows.removeViewImmediate(activeRoot);
            emit("reverse_overlay_window", "state", "removed", "reason", safe(reason));
        } catch (Throwable error) {
            emit("reverse_overlay_error", "stage", "remove_window",
                    "error", summary(error));
            closing = false;
            if (activeRoot.isAttachedToWindow()) {
                throw new IllegalStateException("reverse window removal failed", error);
            }
        }
        root = null;
        windows = null;
        window = null;
        visible = false;
        requestId = 0;
        completedFrameRequestId = 0;
        surfaceGenerations = new int[3];
        closing = false;
    }

    boolean isOpen() {
        return root != null;
    }

    @Override
    public void onReverseSurfacesReady(int[] generations) {
        if (root == null) return;
        surfaceGenerations = generations.clone();
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
    public void onReverseSurfaceLost(int cameraIndex, int generation) {
        visible = false;
        completedFrameRequestId = 0;
        if (!closing && window != null && windows != null && root != null) {
            window.alpha = 0.0f;
            windows.updateViewLayout(root, window);
        }
        emit("reverse_overlay_surface", "state", "destroyed",
                "request_id", requestId, "camera_index", cameraIndex,
                "surface_generation", generation);
    }

    @Override
    public void onReverseDewarpStats(
            int cameraIndex, CameraDewarpRenderer.Stats stats) {
        emit("camera_dewarp_stats",
                "camera_owner", CameraHelperMain.CAMERA_OWNER_REVERSE,
                "camera_index", cameraIndex,
                "request_id", requestId,
                "visible", visible,
                "interval_ms", stats.intervalMs,
                "callbacks", stats.callbacks,
                "completed_swaps", stats.completedSwaps,
                "average_render_ms", stats.averageRenderMs,
                "max_render_ms", stats.maxRenderMs,
                "max_swap_ms", stats.maxSwapMs,
                "max_callback_gap_ms", stats.maxCallbackGapMs,
                "last_frame_age_ms", stats.lastFrameAgeMs,
                "max_frame_age_ms", stats.maxFrameAgeMs,
                "process_max_concurrent_renders", stats.processMaxConcurrentRenders,
                "buffer_width", stats.bufferWidth,
                "buffer_height", stats.bufferHeight);
    }

    private void createWindow(
            Display display, Point size, CameraShellProtocol.ReverseOverlaySpec spec)
            throws Exception {
        Context windowContext = context.createDisplayContext(display);
        windows = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
        if (windows == null) throw new IllegalStateException("window manager unavailable");

        ReverseCameraCompositionView nextRoot = new ReverseCameraCompositionView(windowContext);
        nextRoot.setCallback(this);
        nextRoot.setCornerRadiusDp(spec.cornerRadiusDp);
        nextRoot.applyDewarpConfigs(spec.rearDewarp, spec.leftDewarp, spec.rightDewarp);
        nextRoot.applyLayout(spec.layout);
        WindowManager.LayoutParams nextWindow = new WindowManager.LayoutParams(
                size.x, size.y, WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.RGBA_8888);
        nextWindow.gravity = Gravity.TOP | Gravity.START;
        nextWindow.x = 0;
        nextWindow.y = 0;
        nextWindow.alpha = 0.0f;
        nextWindow.windowAnimations = 0;
        nextWindow.setTitle(WINDOW_TITLE);
        String trustedApi = ShellCameraOverlay.setTrustedOverlay(nextWindow);

        root = nextRoot;
        window = nextWindow;
        try {
            windows.addView(nextRoot, nextWindow);
        } catch (Throwable error) {
            root = null;
            window = null;
            windows = null;
            throw error;
        }
        emit("reverse_overlay_window", "state", "added",
                "request_id", requestId, "width", size.x, "height", size.y,
                "type", nextWindow.type, "format", nextWindow.format,
                "alpha", nextWindow.alpha, "trusted_api", trustedApi);
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
