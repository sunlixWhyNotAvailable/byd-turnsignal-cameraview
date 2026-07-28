package com.byd.turnsignalguard.capture;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;

final class ShellCameraOverlay implements BlindSpotCameraView.Callback {
    private static final int CORNER_RADIUS_DP = 8;
    private static final int WARNING_WIDTH_PERCENT = 20;
    private static final long WARNING_PULSE_HALF_CYCLE_MS = 800;
    private static final String WINDOW_TITLE = "BYD trusted blind-spot camera";

    private final Context context;
    private final WindowManager windows;
    private final BiConsumer<String, Object[]> eventSink;

    private FrameLayout root;
    private BlindSpotCameraView preview;
    private View warningGlow;
    private ObjectAnimator warningAnimator;
    private WindowManager.LayoutParams layout;
    private int requestId;
    private int surfaceGeneration;
    private int armedFrameRequestId;
    private int armedFrameUpdates;
    private int completedFrameRequestId;
    private boolean visible;
    private int warningEdge;

    ShellCameraOverlay(Context context, BiConsumer<String, Object[]> eventSink) {
        this.context = context;
        this.eventSink = eventSink;
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windows == null) throw new IllegalStateException("window manager unavailable");
    }

    void prepare(CameraShellProtocol.OverlaySpec spec) throws Exception {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        spec.validate(metrics.widthPixels, metrics.heightPixels);
        clearWarning("overlay_prepare");
        requestId = spec.requestId;
        armedFrameRequestId = 0;
        armedFrameUpdates = 0;
        completedFrameRequestId = 0;
        if (root == null) createWindow(spec);
        else updateWindow(spec);
        if (preview.isCameraSurfaceReady()) emitSurfaceReady(true);
    }

    SurfaceSnapshot acquireSurface(int expectedRequestId) {
        if (expectedRequestId != requestId) {
            throw new IllegalStateException("overlay request changed");
        }
        Surface surface = preview == null ? null : preview.getCameraSurface();
        if (surface == null || !surface.isValid()) {
            throw new IllegalStateException("overlay Surface unavailable");
        }
        return new SurfaceSnapshot(requestId, surfaceGeneration, surface);
    }

    void armFirstFrame(int expectedRequestId, int expectedSurfaceGeneration) {
        requireCurrent(expectedRequestId, expectedSurfaceGeneration);
        armedFrameRequestId = expectedRequestId;
        armedFrameUpdates = 0;
        completedFrameRequestId = 0;
        emit("camera_overlay_frame", "state", "armed",
                "request_id", requestId, "surface_generation", surfaceGeneration);
    }

    void setVisible(
            int expectedRequestId, int expectedSurfaceGeneration, boolean nextVisible) {
        if (root == null) throw new IllegalStateException("overlay window unavailable");
        if (nextVisible) {
            requireCurrent(expectedRequestId, expectedSurfaceGeneration);
            if (completedFrameRequestId != expectedRequestId) {
                throw new IllegalStateException("first frame not confirmed");
            }
        }
        layout.alpha = nextVisible ? 1.0f : 0.0f;
        windows.updateViewLayout(root, layout);
        visible = nextVisible;
        emit("camera_overlay_visibility", "visible", nextVisible,
                "request_id", requestId, "surface_generation", surfaceGeneration);
        if (!nextVisible) clearWarning("overlay_hidden");
    }

    void setWarning(
            int expectedRequestId, int expectedSurfaceGeneration, int edge, int mode) {
        CameraShellProtocol.validateWarning(
                expectedRequestId, expectedSurfaceGeneration, edge, mode);
        requireCurrent(expectedRequestId, expectedSurfaceGeneration);
        clearWarning("warning_replaced");
        if (edge == CameraShellProtocol.WARNING_EDGE_NONE) {
            emit("camera_overlay_warning", "active", false,
                    "request_id", requestId, "surface_generation", surfaceGeneration,
                    "reason", "controller_clear");
            return;
        }
        warningEdge = edge;
        configureWarningView(edge);
        warningGlow.setAlpha(1.0f);
        warningGlow.setVisibility(View.VISIBLE);
        if (mode == CameraShellProtocol.WARNING_MODE_PULSE) {
            warningAnimator = ObjectAnimator.ofFloat(warningGlow, View.ALPHA, 1.0f, 0.35f);
            warningAnimator.setDuration(WARNING_PULSE_HALF_CYCLE_MS);
            warningAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            warningAnimator.setRepeatCount(ValueAnimator.INFINITE);
            warningAnimator.setRepeatMode(ValueAnimator.REVERSE);
            warningAnimator.start();
        }
        emit("camera_overlay_warning", "active", true,
                "request_id", requestId, "surface_generation", surfaceGeneration,
                "edge", edgeName(edge), "mode", modeName(mode),
                "pulse_half_cycle_ms", mode == CameraShellProtocol.WARNING_MODE_PULSE
                        ? WARNING_PULSE_HALF_CYCLE_MS : 0);
    }

    void close(String reason) {
        FrameLayout activeRoot = root;
        if (activeRoot == null) return;
        clearWarning("overlay_close");
        root = null;
        preview = null;
        warningGlow = null;
        layout = null;
        visible = false;
        requestId = 0;
        armedFrameRequestId = 0;
        armedFrameUpdates = 0;
        completedFrameRequestId = 0;
        try {
            windows.removeViewImmediate(activeRoot);
            emit("camera_overlay_window", "state", "removed",
                    "reason", safeReason(reason));
        } catch (Throwable error) {
            emit("camera_overlay_error", "stage", "remove_window",
                    "error", summary(error));
        }
    }

    boolean isOpen() {
        return root != null;
    }

    private void createWindow(CameraShellProtocol.OverlaySpec spec) throws Exception {
        FrameLayout nextRoot = new FrameLayout(context);
        nextRoot.setVisibility(View.VISIBLE);
        nextRoot.setClipChildren(true);
        nextRoot.setClipToOutline(true);
        nextRoot.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.BLACK);
        background.setCornerRadius(dp(CORNER_RADIUS_DP));
        nextRoot.setBackground(background);

        BlindSpotCameraView nextPreview = new BlindSpotCameraView(context);
        nextPreview.setAlpha(1.0f);
        nextPreview.setCallback(this);
        nextPreview.applyDirectCameraCrop(spec.crop());
        nextRoot.addView(nextPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        View nextWarningGlow = new View(context);
        nextWarningGlow.setVisibility(View.INVISIBLE);
        nextRoot.addView(nextWarningGlow, new FrameLayout.LayoutParams(
                Math.max(1, spec.width * WARNING_WIDTH_PERCENT / 100),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.TOP | Gravity.START));

        WindowManager.LayoutParams nextLayout = new WindowManager.LayoutParams(
                spec.width, spec.height, WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.RGBA_8888);
        nextLayout.gravity = Gravity.TOP | Gravity.START;
        nextLayout.x = spec.x;
        nextLayout.y = spec.y;
        nextLayout.alpha = 0.0f;
        nextLayout.windowAnimations = 0;
        nextLayout.setTitle(WINDOW_TITLE);
        String trustedApi = setTrustedOverlay(nextLayout);

        root = nextRoot;
        preview = nextPreview;
        warningGlow = nextWarningGlow;
        layout = nextLayout;
        try {
            windows.addView(nextRoot, nextLayout);
        } catch (Throwable error) {
            root = null;
            preview = null;
            warningGlow = null;
            layout = null;
            throw error;
        }
        emit("camera_overlay_window", "state", "added",
                "request_id", requestId, "width", spec.width, "height", spec.height,
                "x", spec.x, "y", spec.y, "type", nextLayout.type,
                "format", nextLayout.format, "alpha", nextLayout.alpha,
                "trusted_api", trustedApi, "corner_radius_dp", CORNER_RADIUS_DP);
    }

    private void updateWindow(CameraShellProtocol.OverlaySpec spec) {
        visible = false;
        preview.applyDirectCameraCrop(spec.crop());
        layout.width = spec.width;
        layout.height = spec.height;
        layout.x = spec.x;
        layout.y = spec.y;
        layout.alpha = 0.0f;
        windows.updateViewLayout(root, layout);
        FrameLayout.LayoutParams warningParams =
                (FrameLayout.LayoutParams) warningGlow.getLayoutParams();
        warningParams.width = Math.max(1, spec.width * WARNING_WIDTH_PERCENT / 100);
        warningGlow.setLayoutParams(warningParams);
        emit("camera_overlay_geometry", "request_id", requestId,
                "width", spec.width, "height", spec.height,
                "x", spec.x, "y", spec.y);
    }

    @Override
    public void onCameraSurfaceAvailable(
            BlindSpotCameraView view, Surface surface, int width, int height) {
        if (view != preview) return;
        surfaceGeneration++;
        armedFrameRequestId = 0;
        armedFrameUpdates = 0;
        completedFrameRequestId = 0;
        emitSurfaceReady(false);
    }

    @Override
    public void onCameraSurfaceSizeChanged(
            BlindSpotCameraView view, Surface surface, int width, int height) {
        if (view != preview) return;
        emit("camera_overlay_surface", "state", "size_changed",
                "request_id", requestId, "surface_generation", surfaceGeneration,
                "width", width, "height", height);
    }

    @Override
    public void onCameraSurfaceDestroyed(BlindSpotCameraView view) {
        if (view != preview) return;
        armedFrameRequestId = 0;
        armedFrameUpdates = 0;
        completedFrameRequestId = 0;
        visible = false;
        clearWarning("surface_destroyed");
        emit("camera_overlay_surface", "state", "destroyed",
                "request_id", requestId, "surface_generation", surfaceGeneration);
    }

    @Override
    public void onCameraFrameUpdated(BlindSpotCameraView view) {
        if (view != preview || armedFrameRequestId == 0 || armedFrameRequestId != requestId
                || completedFrameRequestId == armedFrameRequestId) {
            return;
        }
        if (!isFramePastStaleBuffer(++armedFrameUpdates)) return;
        completedFrameRequestId = armedFrameRequestId;
        emit("camera_overlay_first_frame", "request_id", requestId,
                "surface_generation", surfaceGeneration);
    }

    static boolean isFramePastStaleBuffer(int updatesAfterArm) {
        return updatesAfterArm >= 2;
    }

    private void configureWarningView(int edge) {
        boolean right = edge == CameraShellProtocol.WARNING_EDGE_RIGHT;
        GradientDrawable gradient = new GradientDrawable(
                right ? GradientDrawable.Orientation.RIGHT_LEFT
                        : GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6FF2020, 0xB8FF2020, 0x66FF2020, 0x00FF2020});
        warningGlow.setBackground(gradient);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) warningGlow.getLayoutParams();
        params.gravity = Gravity.TOP | (right ? Gravity.END : Gravity.START);
        warningGlow.setLayoutParams(params);
    }

    private void clearWarning(String reason) {
        boolean active = warningEdge != CameraShellProtocol.WARNING_EDGE_NONE;
        if (warningAnimator != null) {
            warningAnimator.cancel();
            warningAnimator = null;
        }
        if (warningGlow != null) {
            warningGlow.setAlpha(1.0f);
            warningGlow.setVisibility(View.INVISIBLE);
        }
        warningEdge = CameraShellProtocol.WARNING_EDGE_NONE;
        if (active) {
            emit("camera_overlay_warning", "active", false,
                    "request_id", requestId, "surface_generation", surfaceGeneration,
                    "reason", safeReason(reason));
        }
    }

    private static String edgeName(int edge) {
        if (edge == CameraShellProtocol.WARNING_EDGE_LEFT) return "left";
        if (edge == CameraShellProtocol.WARNING_EDGE_RIGHT) return "right";
        return "none";
    }

    private static String modeName(int mode) {
        if (mode == CameraShellProtocol.WARNING_MODE_CONSTANT) return "constant";
        if (mode == CameraShellProtocol.WARNING_MODE_PULSE) return "pulse";
        return "off";
    }

    private void emitSurfaceReady(boolean reused) {
        emit("camera_overlay_surface", "state", "ready",
                "request_id", requestId, "surface_generation", surfaceGeneration,
                "reused", reused,
                "buffer_width", BlindSpotCameraView.BUFFER_WIDTH,
                "buffer_height", BlindSpotCameraView.BUFFER_HEIGHT);
    }

    private void requireCurrent(int expectedRequestId, int expectedSurfaceGeneration) {
        if (expectedRequestId != requestId
                || expectedSurfaceGeneration != surfaceGeneration
                || preview == null || !preview.isCameraSurfaceReady()) {
            throw new IllegalStateException("stale overlay Surface");
        }
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String setTrustedOverlay(WindowManager.LayoutParams value) throws Exception {
        try {
            Method method = WindowManager.LayoutParams.class
                    .getDeclaredMethod("setTrustedOverlay");
            method.setAccessible(true);
            method.invoke(value);
            return "method";
        } catch (Throwable methodError) {
            Field field = WindowManager.LayoutParams.class.getField("privateFlags");
            field.setInt(value, field.getInt(value) | 0x20000000);
            return "field";
        }
    }

    private static String safeReason(String reason) {
        return reason == null || reason.isEmpty() ? "unknown" : reason;
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    static final class SurfaceSnapshot {
        final int requestId;
        final int surfaceGeneration;
        final Surface surface;

        SurfaceSnapshot(int requestId, int surfaceGeneration, Surface surface) {
            this.requestId = requestId;
            this.surfaceGeneration = surfaceGeneration;
            this.surface = surface;
        }
    }
}
