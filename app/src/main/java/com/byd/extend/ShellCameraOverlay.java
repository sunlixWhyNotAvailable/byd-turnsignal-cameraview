package com.byd.extend;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.Surface;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.function.BiConsumer;

final class ShellCameraOverlay implements BlindSpotCameraView.Callback {
    private static final int WARNING_WIDTH_PERCENT = 20;
    private static final long WARNING_PULSE_HALF_CYCLE_MS = 800;
    static final long MIRROR_HIDE_HOLD_MS = 1_000L;
    private static final String WINDOW_TITLE = "BYD trusted blind-spot camera";

    private final Context context;
    private final int cameraId;
    private final BiConsumer<String, Object[]> eventSink;

    private Context windowContext;
    private WindowlessOverlayHost windowless;
    private FrameLayout root;
    private FrameLayout previewLayer;
    private BlindSpotCameraView preview;
    private View warningGlow;
    private ObjectAnimator warningAnimator;
    private int requestId;
    private int surfaceGeneration;
    private int armedFrameRequestId;
    private int armedFrameEpoch;
    private int armedFrameUpdates;
    private long armedFrameAfterNanos;
    private int armedInputGeneration;
    private int completedFrameRequestId;
    private int completedFrameEpoch;
    private boolean visible;
    private boolean active;
    private int warningEdge;
    private int activeTarget = -1;
    private int activeDisplayId = -1;
    private int mirrorX;
    private int mirrorY;
    private int displayWidth;
    private int displayHeight;
    private int dragStartX;
    private int dragStartY;
    private float dragDownX;
    private float dragDownY;
    private boolean dragging;
    private boolean longPressed;
    private final Runnable hideMirrorGesture = this::hideMirrorFromGesture;

    private void hideMirrorFromGesture() {
        if (!visible || !active || dragging || !CameraOverlayProfile.isMirror(cameraId)) return;
        longPressed = true;
        setVisible(requestId, surfaceGeneration, false);
        emit("mirror_hidden_by_gesture", "request_id", requestId,
                "surface_generation", surfaceGeneration);
    }

    private void applyMirrorBorder(FrameLayout frame, CameraShellProtocol.OverlaySpec spec) {
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(dp(spec.cornerRadiusDp));
        if (spec.mirrorBorderDp > 0) {
            border.setStroke(dp(spec.mirrorBorderDp), spec.mirrorBorderArgb | 0xff000000);
        }
        frame.setForeground(border);
    }

    private boolean onMirrorTouch(MotionEvent event) {
        if (!active || !visible || windowless == null) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragDownX = event.getRawX();
                dragDownY = event.getRawY();
                dragStartX = mirrorX;
                dragStartY = mirrorY;
                dragging = false;
                longPressed = false;
                root.postDelayed(hideMirrorGesture, MIRROR_HIDE_HOLD_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (longPressed) return true;
                float dx = event.getRawX() - dragDownX;
                float dy = event.getRawY() - dragDownY;
                int slop = ViewConfiguration.get(windowContext).getScaledTouchSlop();
                if (!dragging && dx * dx + dy * dy > slop * slop) {
                    dragging = true;
                    root.removeCallbacks(hideMirrorGesture);
                }
                if (dragging) {
                    mirrorX = Math.max(0, Math.min(displayWidth - windowless.width(),
                            dragStartX + Math.round(dx)));
                    mirrorY = Math.max(0, Math.min(displayHeight - windowless.height(),
                            dragStartY + Math.round(dy)));
                    try {
                        windowless.setPosition(mirrorX, mirrorY);
                    } catch (Exception error) {
                        root.removeCallbacks(hideMirrorGesture);
                        hideFailedRenderer();
                        emit("camera_overlay_error", "stage", "mirror_move",
                                "request_id", requestId, "error", summary(error));
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                root.removeCallbacks(hideMirrorGesture);
                if (dragging && !longPressed) {
                    mirrorX = roundedMirrorPosition(mirrorX, displayWidth, windowless.width());
                    mirrorY = roundedMirrorPosition(mirrorY, displayHeight, windowless.height());
                    try {
                        windowless.setPosition(mirrorX, mirrorY);
                    } catch (Exception error) {
                        hideFailedRenderer();
                        emit("camera_overlay_error", "stage", "mirror_position_commit",
                                "request_id", requestId, "error", summary(error));
                        dragging = false;
                        return true;
                    }
                    emit("mirror_position_committed", "request_id", requestId,
                            "surface_generation", surfaceGeneration,
                            "x", mirrorX, "y", mirrorY,
                            "width", windowless.width(), "height", windowless.height(),
                            "display_width", displayWidth, "display_height", displayHeight,
                            "target", activeTarget);
                }
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    private String cameraOwner() {
        return CameraOverlayProfile.isMirror(cameraId) ? CameraHelperMain.CAMERA_OWNER_MIRROR
                : CameraOverlayProfile.isParking(cameraId) ? CameraHelperMain.CAMERA_OWNER_PARKING
                : CameraHelperMain.CAMERA_OWNER_OVERLAY;
    }

    static int roundedMirrorPosition(int position, int displaySize, int windowSize) {
        int size = Math.max(1, displaySize);
        int grid = Math.round(position * 1000.0f / size);
        return Math.max(0, Math.min(size - windowSize, Math.round(grid * size / 1000.0f)));
    }

    ShellCameraOverlay(
            Context context, int cameraId, BiConsumer<String, Object[]> eventSink) {
        this.context = context;
        this.cameraId = CameraOverlayProfile.of(cameraId).id;
        this.eventSink = eventSink;
    }

    void prepare(CameraShellProtocol.OverlaySpec spec) throws Exception {
        if (spec.cameraId != cameraId) {
            throw new IllegalArgumentException("overlay camera id mismatch");
        }
        Display display = CameraDisplayTarget.resolve(context, spec.target);
        if (display == null) {
            if (root != null && activeTarget != spec.target) {
                close("display_target_unavailable");
            }
            throw new IllegalStateException(CameraDisplayTarget.name(spec.target)
                    + " display unavailable");
        }
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        spec.validate(metrics.widthPixels, metrics.heightPixels);
        displayWidth = metrics.widthPixels;
        displayHeight = metrics.heightPixels;
        mirrorX = spec.x;
        mirrorY = spec.y;
        if (root != null) {
            if (activeTarget != spec.target || activeDisplayId != display.getDisplayId()) {
                quiesce("display_target_changed");
                throw new CameraShellProtocol.PrepareRestartRequired(
                        "display_target_changed");
            } else if (preview.usesDewarpPipeline() != spec.dewarp.usesGpu()) {
                quiesce("dewarp_pipeline_changed");
                throw new CameraShellProtocol.PrepareRestartRequired(
                        "dewarp_pipeline_changed");
            } else if (!samePaneSize(
                    windowless.width(), windowless.height(), spec.width, spec.height)) {
                quiesce("camera_geometry_changed");
                throw new CameraShellProtocol.PrepareRestartRequired(
                        "camera_geometry_changed");
            } else if (!preview.usesPaneBoundedBuffer(
                    spec.width, spec.height, spec.bufferQuality)) {
                quiesce("camera_buffer_size_changed");
                throw new CameraShellProtocol.PrepareRestartRequired(
                        "camera_buffer_size_changed");
            }
        }
        if (root == null) {
            windowContext = context.createDisplayContext(display);
            activeTarget = spec.target;
            activeDisplayId = display.getDisplayId();
        }
        clearWarning("overlay_prepare");
        requestId = spec.requestId;
        armedFrameRequestId = 0;
        armedFrameEpoch = 0;
        armedFrameUpdates = 0;
        completedFrameRequestId = 0;
        completedFrameEpoch = 0;
        if (windowless != null) {
            windowless.setDiagnosticState(requestId, Integer.toString(surfaceGeneration));
        }
        if (root == null) createWindow(spec, display);
        else {
            preview.setCallback(this);
            updateWindow(spec);
        }
        active = true;
        preview.setDewarpStatsContext(requestId, surfaceGeneration);
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

    void armFirstFrame(OverlayFrameArm arm) {
        if (arm.cameraId != cameraId) throw new IllegalArgumentException("camera id changed");
        requireCurrent(arm.requestId, arm.surfaceGeneration);
        armedFrameRequestId = arm.requestId;
        armedFrameEpoch = arm.frameArmEpoch;
        armedFrameUpdates = 0;
        armedFrameAfterNanos = cameraId < CameraOverlayProfile.BLIND_COUNT ? System.nanoTime() : 0L;
        armedInputGeneration = preview.cameraInputGeneration();
        completedFrameRequestId = 0;
        completedFrameEpoch = 0;
        emit("camera_overlay_frame", "state", "armed",
                "request_id", requestId, "surface_generation", surfaceGeneration,
                "frame_arm_epoch", arm.frameArmEpoch,
                "frame_timestamp_after_ns", armedFrameAfterNanos);
    }

    void setVisible(
            int expectedRequestId, int expectedSurfaceGeneration, boolean nextVisible) {
        if (root == null) throw new IllegalStateException("overlay window unavailable");
        if (nextVisible) {
            requireCurrent(expectedRequestId, expectedSurfaceGeneration);
            if (completedFrameRequestId != expectedRequestId
                    || completedFrameEpoch != armedFrameEpoch || armedFrameEpoch <= 0) {
                throw new IllegalStateException("first frame not confirmed");
            }
        }
        try {
            if (CameraOverlayProfile.isMirror(cameraId)) {
                windowless.setStrictVisible(nextVisible, 1.0f);
            } else {
                windowless.setVisible(nextVisible, 1.0f);
            }
        } catch (Exception error) {
            throw new IllegalStateException("overlay visibility update failed", error);
        }
        visible = nextVisible;
        emit("camera_overlay_visibility", "visible", nextVisible,
                "request_id", requestId, "surface_generation", surfaceGeneration);
        if (!nextVisible) {
            root.removeCallbacks(hideMirrorGesture);
            clearWarning("overlay_hidden");
        }
    }

    void setWarning(
            int expectedRequestId, int expectedSurfaceGeneration, int edge, int mode) {
        CameraShellProtocol.validateWarning(
                cameraId, expectedRequestId, expectedSurfaceGeneration, edge, mode);
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

    void updateVisuals(int cornerRadiusDp, int transparencyPercent) {
        CameraShellProtocol.validateVisualStyle(cornerRadiusDp, transparencyPercent);
        if (root == null) return;
        GradientDrawable background = (GradientDrawable) root.getBackground();
        background.setCornerRadius(dp(cornerRadiusDp));
        root.invalidateOutline();
        if (previewLayer != null) {
            previewLayer.setAlpha(WindowlessOverlayHost.alphaForTransparency(
                    transparencyPercent));
        }
    }

    void close(String reason) {
        FrameLayout activeRoot = root;
        if (activeRoot == null || !active) return;
        quiesce(reason);
    }

    private void quiesce(String reason) {
        FrameLayout activeRoot = root;
        if (activeRoot == null || !active) return;
        clearWarning("overlay_close");
        activeRoot.removeCallbacks(hideMirrorGesture);
        BlindSpotCameraView activePreview = preview;
        if (activePreview != null) {
            activePreview.setCallback(null);
        }
        WindowlessOverlayHost activeHost = windowless;
        int closedTarget = activeTarget;
        int closedDisplayId = activeDisplayId;
        active = false;
        visible = false;
        requestId = 0;
        armedFrameRequestId = 0;
        armedFrameEpoch = 0;
        armedFrameUpdates = 0;
        completedFrameRequestId = 0;
        completedFrameEpoch = 0;
        try {
            if (activeHost != null) {
                activeHost.quiesce();
            }
            emit("camera_overlay_window", "state", "removed",
                    "reason", safeReason(reason),
                    "target", CameraDisplayTarget.name(closedTarget),
                    "display_id", closedDisplayId,
                    "reusable", true);
        } catch (Throwable error) {
            emit("camera_overlay_error", "stage", "remove_window",
                    "error", summary(error));
        }
    }

    boolean isOpen() {
        return root != null && active;
    }

    private void createWindow(CameraShellProtocol.OverlaySpec spec, Display display)
            throws Exception {
        FrameLayout nextRoot = new FrameLayout(windowContext);
        nextRoot.setVisibility(View.VISIBLE);
        nextRoot.setClipChildren(true);
        nextRoot.setClipToOutline(true);
        nextRoot.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.TRANSPARENT);
        background.setCornerRadius(dp(spec.cornerRadiusDp));
        nextRoot.setBackground(background);

        BlindSpotCameraView nextPreview = new BlindSpotCameraView(windowContext);
        nextPreview.setPreserveInputFrameTimestamp(cameraId < CameraOverlayProfile.BLIND_COUNT);
        nextPreview.setPaneBoundedBuffer(spec.width, spec.height, spec.bufferQuality);
        nextPreview.setCallback(this);
        nextPreview.setDewarpStatsSink(this::emitDewarpStats);
        nextPreview.setDewarpEventSink(this::emitDewarpEvent);
        nextPreview.applyRawFallbackCrop(spec.rawFallbackCrop);
        nextPreview.applyDewarpSourceRoi(
                spec.rawFallbackCrop.left, spec.rawFallbackCrop.top,
                spec.rawFallbackCrop.width, spec.rawFallbackCrop.height);
        nextPreview.applyDewarpConfig(spec.dewarp);
        nextPreview.applyDirectCameraCrop(spec.crop());
        FrameLayout nextPreviewLayer = new FrameLayout(windowContext);
        nextPreviewLayer.setBackgroundColor(Color.TRANSPARENT);
        nextPreviewLayer.setAlpha(WindowlessOverlayHost.alphaForTransparency(
                spec.transparencyPercent));
        nextPreviewLayer.addView(nextPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        CropMaskView nextCropMask = new CropMaskView(windowContext);
        nextPreviewLayer.addView(nextCropMask, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        nextPreview.setOutputCropMask(nextCropMask);
        nextRoot.addView(nextPreviewLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        View nextWarningGlow = new View(windowContext);
        nextWarningGlow.setVisibility(View.INVISIBLE);
        nextRoot.addView(nextWarningGlow, new FrameLayout.LayoutParams(
                Math.max(1, spec.width * WARNING_WIDTH_PERCENT / 100),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.TOP | Gravity.START));

        if (CameraOverlayProfile.isMirror(cameraId)) {
            nextRoot.setClickable(true);
            nextRoot.setOnTouchListener((view, event) -> onMirrorTouch(event));
            applyMirrorBorder(nextRoot, spec);
        }

        root = nextRoot;
        previewLayer = nextPreviewLayer;
        preview = nextPreview;
        warningGlow = nextWarningGlow;
        WindowlessOverlayHost nextHost = new WindowlessOverlayHost(
                windowContext, display,
                CameraOverlayProfile.isMirror(cameraId) ? WindowlessOverlayHost.mirrorLayer()
                        : WindowlessOverlayHost.cameraLayer(cameraId),
                CameraOverlayProfile.isMirror(cameraId) ? "mirror"
                        : CameraOverlayProfile.isParking(cameraId) ? "parking" : "blind",
                cameraId, eventSink);
        windowless = nextHost;
        nextHost.setDiagnosticState(requestId, Integer.toString(surfaceGeneration));
        try {
            nextHost.attach(nextRoot, spec.width, spec.height, spec.x, spec.y,
                    WINDOW_TITLE + " " + CameraOverlayProfile.of(cameraId).wireName,
                    CameraOverlayProfile.isMirror(cameraId), CameraOverlayProfile.isMirror(cameraId));
        } catch (Throwable error) {
            windowless = null;
            root = null;
            previewLayer = null;
            preview = null;
            warningGlow = null;
            throw new CameraShellProtocol.PrepareRestartRequired("overlay_attach_failed");
        }
        emit("camera_overlay_window", "state", "added",
                "request_id", requestId, "width", spec.width, "height", spec.height,
                "x", spec.x, "y", spec.y, "layer", nextHost.layer(),
                "alpha", 0.0f, "trusted_api", nextHost.trustedApi(),
                "corner_radius_dp", spec.cornerRadiusDp,
                "transparency_percent", spec.transparencyPercent,
                "target", CameraDisplayTarget.name(spec.target),
                "display_id", activeDisplayId,
                "display_name", nextHost.displayName(),
                "dewarp_enabled", spec.dewarp.enabled,
                "dewarp_lens", spec.dewarp.lens,
                "dewarp_fov_degrees", spec.dewarp.fovDegrees,
                "dewarp_projection",
                CameraDewarpConfig.projectionLabel(spec.dewarp.projection));
    }

    private void updateWindow(CameraShellProtocol.OverlaySpec spec) {
        visible = false;
        previewLayer.setAlpha(WindowlessOverlayHost.alphaForTransparency(
                spec.transparencyPercent));
        preview.applyRawFallbackCrop(spec.rawFallbackCrop);
        preview.applyDewarpSourceRoi(
                spec.rawFallbackCrop.left, spec.rawFallbackCrop.top,
                spec.rawFallbackCrop.width, spec.rawFallbackCrop.height);
        preview.applyDewarpConfig(spec.dewarp);
        preview.applyDirectCameraCrop(spec.crop());
        GradientDrawable background = (GradientDrawable) root.getBackground();
        background.setCornerRadius(dp(spec.cornerRadiusDp));
        if (CameraOverlayProfile.isMirror(cameraId)) applyMirrorBorder(root, spec);
        try {
            windowless.setPosition(spec.x, spec.y);
            if (CameraOverlayProfile.isMirror(cameraId)) windowless.setStrictVisible(false, 1.0f);
            else windowless.setVisible(false, 1.0f);
        } catch (Exception error) {
            throw new IllegalStateException("windowless geometry update failed", error);
        }
        FrameLayout.LayoutParams warningParams =
                (FrameLayout.LayoutParams) warningGlow.getLayoutParams();
        warningParams.width = Math.max(1, spec.width * WARNING_WIDTH_PERCENT / 100);
        warningGlow.setLayoutParams(warningParams);
        emit("camera_overlay_geometry", "request_id", requestId,
                "width", spec.width, "height", spec.height,
                "x", spec.x, "y", spec.y,
                "target", CameraDisplayTarget.name(spec.target),
                "display_id", activeDisplayId);
    }

    @Override
    public void onCameraSurfaceAvailable(
            BlindSpotCameraView view, Surface surface, int width, int height) {
        if (view != preview) return;
        armedFrameRequestId = 0;
        armedFrameEpoch = 0;
        armedFrameUpdates = 0;
        completedFrameRequestId = 0;
        completedFrameEpoch = 0;
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
        armedFrameEpoch = 0;
        armedFrameUpdates = 0;
        completedFrameRequestId = 0;
        completedFrameEpoch = 0;
        visible = false;
        clearWarning("surface_destroyed");
        emit("camera_overlay_surface", "state", "destroyed",
                "request_id", requestId, "surface_generation", surfaceGeneration);
    }

    @Override
    public void onCameraFrameUpdated(BlindSpotCameraView view) {
        onCameraFrameUpdated(view, view.cameraInputGeneration());
    }

    @Override
    public void onCameraFrameUpdated(BlindSpotCameraView view, int inputGeneration) {
        if (view != preview || armedFrameRequestId == 0 || armedFrameRequestId != requestId
                || armedFrameEpoch <= 0
                || (completedFrameRequestId == armedFrameRequestId
                        && completedFrameEpoch == armedFrameEpoch)) {
            return;
        }
        long frameTimestamp = view.cameraFrameTimestampNanos();
        if (armedFrameAfterNanos > 0L) {
            if (!isFreshStampedFrame(armedInputGeneration, inputGeneration,
                    armedFrameAfterNanos, frameTimestamp)) return;
        } else if (!isFramePastStaleBuffer(++armedFrameUpdates)) return;
        completedFrameRequestId = armedFrameRequestId;
        completedFrameEpoch = armedFrameEpoch;
        emit("camera_overlay_first_frame",
                "request_id", requestId, "surface_generation", surfaceGeneration,
                "frame_arm_epoch", armedFrameEpoch,
                "frame_timestamp_after_ns", armedFrameAfterNanos,
                "frame_timestamp_ns", frameTimestamp);
    }

    private void emitDewarpStats(CameraDewarpRenderer.Stats stats) {
        if (stats.requestId != requestId
                || stats.contextGeneration != surfaceGeneration) return;
        emit("camera_dewarp_stats", CameraDewarpStatsEvent.overlay(
            cameraId, CameraOverlayProfile.of(cameraId).wireName,
            cameraOwner(),
            stats));
    }

    private void emitDewarpEvent(CameraDewarpRenderer.Event event) {
        emit(event.kind,
                "camera_owner", cameraOwner(),
            "camera_profile", CameraOverlayProfile.of(cameraId).wireName,
                "request_id", requestId,
                "surface_generation", surfaceGeneration,
                "lens", event.lens,
                "enabled", event.enabled,
                "fov_degrees", event.fovDegrees,
                "projection", CameraDewarpConfig.projectionLabel(event.projection),
                "mesh_vertices", event.vertexCount,
                "generation_ms", event.generationMs,
                "error", event.error);
        if (CameraDewarpRenderer.isFatalEventKind(event.kind)) {
            hideFailedRenderer();
            emit("camera_overlay_error", "stage", event.kind,
                    "request_id", requestId,
                    "surface_generation", surfaceGeneration,
                    "error", event.error);
        }
    }

    private void hideFailedRenderer() {
        visible = false;
        armedFrameRequestId = 0;
        armedFrameEpoch = 0;
        completedFrameRequestId = 0;
        completedFrameEpoch = 0;
        clearWarning("dewarp_failed");
        if (root == null || windowless == null) return;
        try {
            if (CameraOverlayProfile.isMirror(cameraId)) windowless.setStrictVisible(false, 1.0f);
            else windowless.setVisible(false, 1.0f);
        } catch (Throwable error) {
            emit("camera_overlay_error", "stage", "hide_failed_renderer",
                    "request_id", requestId, "error", summary(error));
        }
    }

    static boolean isFramePastStaleBuffer(int updatesAfterArm) {
        return updatesAfterArm >= 2;
    }

    static boolean isFreshStampedFrame(
            int armedGeneration, int frameGeneration, long afterNanos, long frameNanos) {
        return armedGeneration > 0 && frameGeneration == armedGeneration
                && afterNanos > 0L && frameNanos > afterNanos;
    }

    static boolean samePaneSize(
            int currentWidth, int currentHeight, int nextWidth, int nextHeight) {
        return currentWidth == nextWidth && currentHeight == nextHeight;
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
        int inputGeneration = preview == null ? 0 : preview.cameraInputGeneration();
        if (inputGeneration <= 0) return;
        surfaceGeneration = inputGeneration;
        if (windowless != null) {
            windowless.setDiagnosticState(requestId, Integer.toString(surfaceGeneration));
        }
        preview.setDewarpStatsContext(requestId, surfaceGeneration);
        emit("camera_overlay_surface", "state", "ready",
                "request_id", requestId, "surface_generation", surfaceGeneration,
                "reused", reused,
                "buffer_width", preview.cameraBufferWidth(),
                "buffer_height", preview.cameraBufferHeight());
    }

    private void requireCurrent(int expectedRequestId, int expectedSurfaceGeneration) {
        if (expectedRequestId != requestId
                || expectedSurfaceGeneration != surfaceGeneration
                || preview == null || !preview.isCameraSurfaceReady()) {
            throw new IllegalStateException("stale overlay Surface");
        }
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, tagCameraId(cameraId, fields));
    }

    static Object[] tagCameraId(int cameraId, Object... fields) {
        Object[] tagged = new Object[fields.length + 2];
        tagged[0] = OverlayFrameArm.CAMERA_ID;
        tagged[1] = cameraId;
        System.arraycopy(fields, 0, tagged, 2, fields.length);
        return tagged;
    }

    private int dp(int value) {
        Context valueContext = windowContext == null ? context : windowContext;
        return Math.round(value * valueContext.getResources().getDisplayMetrics().density);
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
