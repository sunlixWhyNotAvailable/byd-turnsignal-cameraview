package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.provider.Settings;
import android.view.Surface;
import org.json.JSONObject;
import java.util.function.BiConsumer;

/** Desired-state owner for one rear-camera consumer; it never owns the shared producer. */
final class RearviewMirrorController {
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;
    private final CameraShellRecoveryGate recovery = new CameraShellRecoveryGate();
    private final Runnable timeout = () -> fail("readiness_timeout");
    private final DisplayManager displays;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { evaluate(); }
        @Override public void onDisplayRemoved(int id) { settingsChanged(); }
        @Override public void onDisplayChanged(int id) { settingsChanged(); }
    };
    private CameraHelperMain.HelperBinder helper;
    private boolean appVisible = true;
    private boolean oemKnown;
    private boolean oemVisible;
    private boolean runtimeAllowed;
    private boolean shutdown;
    private boolean stopping;
    private boolean reconcileAfterStop;
    private int sequence;
    private int requestId;
    private int generation;
    private Surface target;
    private long shellEpoch;

    RearviewMirrorController(Context context, Handler handler,
            BiConsumer<String, Object[]> eventSink) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.eventSink = eventSink;
        preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        displays = context.getSystemService(DisplayManager.class);
        if (displays != null) displays.registerDisplayListener(displayListener, handler);
    }

    void attachHelper(CameraHelperMain.HelperBinder value) {
        if (helper == value) return;
        if (helper != null) stop("helper_changed", false);
        helper = value;
        evaluate();
    }

    void setRuntimeAllowed(boolean allowed) {
        runtimeAllowed = allowed;
        evaluate();
    }

    void appVisibility(boolean visible) {
        appVisible = visible;
        if (visible) RearviewMirrorSettings.setHidden(preferences, false);
        evaluate();
    }

    void settingsChanged() {
        if (requestId != 0) stop("settings_changed", true);
        else evaluate();
    }

    void oemVisibility(boolean known, boolean visible) {
        oemKnown = known;
        oemVisible = known && visible;
        evaluate();
    }

    static boolean shouldShow(boolean enabled, boolean manuallyHidden, boolean appVisible,
            boolean oemKnown, boolean oemVisible, boolean runtimeAllowed,
            boolean permission, boolean shutdown) {
        return enabled && !manuallyHidden && !appVisible && oemKnown && !oemVisible
                && runtimeAllowed && permission && !shutdown;
    }

    private boolean wanted() {
        return shouldShow(RearviewMirrorSettings.enabled(preferences),
                RearviewMirrorSettings.hidden(preferences), appVisible, oemKnown, oemVisible,
                runtimeAllowed, Settings.canDrawOverlays(context), shutdown)
                && !GuardRecovery.isUserShutdownActive(context)
                && !LegacySettingsImporter.blocksRuntime(context)
                && CameraDisplayTarget.resolve(context, RearviewMirrorSettings.target(preferences)) != null;
    }

    private void evaluate() {
        if (!wanted() || helper == null) {
            stop("not_visible", false);
            return;
        }
        if (stopping) {
            reconcileAfterStop = true;
            return;
        }
        if (requestId != 0 || recovery.pending()) return;
        requestId = ++sequence;
        generation = 0;
        int expected = requestId;
        try {
            CameraShellProtocol.OverlaySpec spec = buildSpec(expected);
            handler.postDelayed(timeout, 8_000);
            helper.prepareOverlayWindow(spec,
                    surface -> handler.post(() -> acceptSurface(expected, surface)), () -> {});
            emit("mirror_prepare", "request_id", expected, "target", spec.target);
        } catch (Throwable error) {
            emit("mirror_error", "stage", "prepare", "error", error.toString());
            fail("prepare");
        }
    }

    private CameraShellProtocol.OverlaySpec buildSpec(int id) {
        int displayTarget = RearviewMirrorSettings.target(preferences);
        int[] size = CameraDisplayTarget.displaySize(context, displayTarget);
        int[] rect = RearviewMirrorSettings.placement(preferences).toPixelRect(size[0], size[1]);
        DirectCameraCrop raw = RearviewMirrorSettings.raw(preferences);
        CameraDewarpConfig dewarp = RearviewMirrorSettings.dewarp(preferences);
        DirectCameraCrop crop = dewarp.enabled ? RearviewMirrorSettings.corrected(preferences) : raw;
        CameraShellProtocol.OverlaySpec result = new CameraShellProtocol.OverlaySpec(
                CameraOverlayProfile.MIRROR_ID, id, displayTarget,
                rect[2], rect[3], rect[0], rect[1],
                crop.left, crop.top, crop.width, crop.height, crop.aspectMode,
                crop.rotationDegrees, crop.rotationMode,
                BlindSpotOverlayController.readCornerRadius(preferences), dewarp, raw,
                CameraBufferQuality.load(preferences), crop.mirrorHorizontally, 0);
        result.mirrorBorderDp = RearviewMirrorSettings.borderDp(preferences);
        result.mirrorBorderArgb = RearviewMirrorSettings.borderArgb(preferences);
        return result;
    }

    private void acceptSurface(int expected, TurnSignalController.OverlaySurface surface) {
        if (expected != requestId || !wanted() || helper == null
                || surface.requestId != expected || surface.cameraId != CameraOverlayProfile.MIRROR_ID) {
            surface.surface.release();
            return;
        }
        generation = surface.surfaceGeneration;
        target = surface.surface;
        try {
            String result = helper.openMirrorCamera(surface.surface, expected);
            if (!"camera_opened".equals(new JSONObject(result).optString("kind"))) {
                target = null; // The rejected attach has already released its input handle.
                fail("consumer_open");
                return;
            }
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, 3_000);
            helper.armOverlayFirstFrame(OverlayFrameArm.create(
                    CameraOverlayProfile.MIRROR_ID, expected, generation, 1));
        } catch (Throwable error) {
            emit("mirror_error", "stage", "consumer_open", "error", error.toString());
            fail("consumer_open");
        }
    }

    void acceptEvent(String line) {
        if (shutdown || line == null) return;
        try {
            JSONObject event = new JSONObject(line);
            String kind = event.optString("kind");
            if ("camera_shell_died".equals(kind)) {
                long epoch = event.optLong("camera_shell_epoch", 0);
                if (!TurnSignalController.isCurrentCameraShellEpoch(shellEpoch, epoch)
                        || !recovery.isNewDeath(epoch)) return;
                recovery.onDeath(epoch, wanted());
                stop("camera_shell_died", false);
            } else if ("camera_shell_attached".equals(kind)) {
                shellEpoch = event.optLong("camera_shell_epoch", shellEpoch);
                if (recovery.claim(shellEpoch, wanted())) evaluate();
            } else if ("camera_shell_recovery_failed".equals(kind)) {
                recovery.clear();
                stop("camera_shell_recovery_failed", false);
            } else if (matchesFrameEvent(event, requestId, generation)) {
                if ("camera_overlay_first_frame".equals(kind) && event.optInt("frame_arm_epoch") == 1) {
                    handler.removeCallbacks(timeout);
                    if (wanted() && helper != null) {
                        helper.setOverlayWindowVisible(CameraOverlayProfile.MIRROR_ID,
                                requestId, generation, true);
                    }
                } else if ("mirror_hidden_by_gesture".equals(kind)) {
                    RearviewMirrorSettings.setHidden(preferences, true);
                    stop("manual_hide", false);
                } else if ("mirror_position_committed".equals(kind)) {
                    CameraPlacement placement = CameraPlacement.fromPixelRect(
                            event.getInt("x"), event.getInt("y"), event.getInt("width"),
                            event.getInt("height"), event.getInt("display_width"),
                            event.getInt("display_height")).roundedTenths();
                    RearviewMirrorSettings.writePlacement(preferences,
                            placement.x, placement.y, placement.width, placement.height);
                } else if ("camera_overlay_surface".equals(kind)
                        && "destroyed".equals(event.optString("state"))) fail("surface_destroyed");
                else if ("camera_overlay_error".equals(kind)) fail("camera_error");
            } else if (("camera_overlay_error".equals(kind)
                    && event.optInt("camera_id", -1) == CameraOverlayProfile.MIRROR_ID
                    || "camera_error".equals(kind)
                    && CameraHelperMain.CAMERA_OWNER_MIRROR.equals(event.optString("camera_owner")))
                    && requestId > 0 && event.optInt("request_id", -1) == requestId) {
                fail("camera_error");
            }
        } catch (Exception error) {
            emit("mirror_error", "stage", "event", "error", error.toString());
        }
    }

    static boolean matchesFrameEvent(JSONObject event, int request, int generation) {
        return request > 0 && generation > 0
                && event.optInt("camera_id", -1) == CameraOverlayProfile.MIRROR_ID
                && event.optInt("request_id", -1) == request
                && event.optInt("surface_generation", -1) == generation;
    }

    private void fail(String reason) {
        if (requestId == 0) return;
        emit("mirror_error", "stage", reason, "request_id", requestId);
        stop(reason, false);
    }

    private void stop(String reason, boolean reconcile) {
        handler.removeCallbacks(timeout);
        if (requestId == 0 || stopping) return;
        int closing = requestId;
        int closingGeneration = generation;
        requestId = 0;
        generation = 0;
        target = null;
        CameraHelperMain.HelperBinder closingHelper = helper;
        if (closingHelper == null) return;
        stopping = true;
        reconcileAfterStop = reconcile;
        Runnable finish = () -> {
            try {
                closingHelper.closeMirrorCamera(reason, closing);
            } catch (RuntimeException failure) {
                emit("mirror_error", "stage", "detach", "request_id", closing,
                        "error", failure.toString());
            } finally {
                try {
                    closingHelper.closeOverlayWindow(CameraOverlayProfile.MIRROR_ID, reason);
                } catch (RuntimeException failure) {
                    emit("mirror_error", "stage", "close_window", "request_id", closing,
                            "error", failure.toString());
                } finally {
                    stopping = false;
                }
            }
            boolean shouldReconcile = reconcileAfterStop;
            reconcileAfterStop = false;
            if (shouldReconcile && !shutdown) evaluate();
        };
        if (closingGeneration > 0) {
            closingHelper.setOverlayWindowVisible(CameraOverlayProfile.MIRROR_ID,
                    closing, closingGeneration, false, hidden -> handler.post(finish));
        } else finish.run();
    }

    void shutdown() {
        shutdown = true;
        runtimeAllowed = false;
        recovery.clear();
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        stop("shutdown", false);
    }

    private void emit(String kind, Object... fields) { eventSink.accept(kind, fields); }
}
