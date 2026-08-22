package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;

/** Runtime controller for the eight parking radar camera panes. */
final class ParkingCameraController {
    private static final long RETRY_MS = 3_000L;
    private static final long RADAR_STALE_MS = ParkingCameraTriggerPolicy.DEFAULT_RADAR_STALE_MS;
    private static final long SPEED_STALE_MS = ParkingCameraTriggerPolicy.DEFAULT_SPEED_STALE_MS;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences settings;
    private final BiConsumer<String, Object[]> eventSink;
    private final ParkingCameraTriggerPolicy.State policy =
            new ParkingCameraTriggerPolicy.State();
    private final Pane[] panes = new Pane[ParkingCameraProfile.COUNT];
    private final int[] radarRaw = new int[ParkingCameraProfile.allRadarFids().length];
    private final boolean[] radarValid = new boolean[radarRaw.length];
    private final long[] radarTimestamps = new long[radarRaw.length];
    private final Runnable retry = this::evaluate;
    private final Runnable closeRetry = this::retryClose;
    private final Runnable closeTick = this::evaluate;

    private CameraHelperMain.HelperBinder helper;
    private boolean suspended;
    private boolean activityVisible;
    private boolean reversePriority;
    private boolean shutdown;
    private boolean speedValid;
    private float speedKph = Float.NaN;
    private long speedTimestamp;
    private int desiredMask;
    private int potentialMask;
    private int attachedGroupMask;
    private int attachedGroupRequestId;
    private final CloseRetryState closeRetryState = new CloseRetryState();
    private int lastPotentialMask = -1;
    private int membershipGeneration;
    private int requestSequence;
    private ParkingCameraSettings.Rule[] rules;
    private int maxSpeedKph;

    ParkingCameraController(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.settings = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        this.eventSink = eventSink;
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            panes[profile.id] = new Pane(profile);
        }
        ParkingCameraSettings.migrate(settings);
        reloadSettings();
        Arrays.fill(radarRaw, -1);
    }

    void attachHelper(CameraHelperMain.HelperBinder value) {
        helper = value;
        evaluate();
    }

    void setSuspended(boolean value) {
        suspended = value;
        if (value) closeParkingGroup("camera_preview_preempt");
        evaluate();
    }

    void setUiHidden(boolean value) {
        activityVisible = value;
        if (value) closeParkingGroup("activity_preempt");
        evaluate();
    }

    void setReversePriority(boolean value) {
        reversePriority = value;
        if (value) closeParkingGroup("reverse_priority");
        evaluate();
    }

    void settingsChanged() {
        ParkingCameraSettings.migrate(settings);
        reloadSettings();
        closeParkingGroup("parking_settings_changed");
        evaluate();
    }

    void acceptEvent(String line) {
        if (shutdown || line == null) return;
        try {
            JSONObject event = new JSONObject(line);
            String kind = event.optString("kind");
            long now = event.optLong("t_ms", SystemClock.elapsedRealtime());
            if ("parking_radar_state".equals(kind)) {
                int fid = event.optInt("fid", Integer.MIN_VALUE);
                int index = radarIndex(fid);
                if (index >= 0) {
                    int raw = event.optInt("raw", -1);
                    boolean valid = event.optBoolean("valid", false)
                            && ParkingCameraProfile.isValidRadarRaw(fid, raw)
                            && event.optBoolean("configured", false)
                            && event.optBoolean("listener_ok", false);
                    radarRaw[index] = raw;
                    radarValid[index] = valid;
                    radarTimestamps[index] = valid ? now : 0L;
                    evaluate();
                }
            } else if ("vehicle_state".equals(kind)) {
                boolean valid = event.optBoolean("valid", false);
                String speed = event.optString("speed_kph", "");
                try {
                    speedKph = Float.parseFloat(speed);
                    speedValid = valid && Float.isFinite(speedKph);
                } catch (Throwable ignored) {
                    speedKph = Float.NaN;
                    speedValid = false;
                }
                speedTimestamp = speedValid ? now : 0L;
                evaluate();
            } else if ("camera_overlay_first_frame".equals(kind)) {
                OverlayFrameArm arm = OverlayFrameArm.fromEvent(event);
                if (CameraOverlayProfile.isParking(arm.cameraId)) {
                    Pane pane = panes[CameraOverlayProfile.parkingIdFor(arm.cameraId)];
                    if (matchesFirstFrame(arm, pane.requestId, pane.surfaceGeneration,
                            pane.frameEpoch, pane.wantVisible)) {
                        pane.firstFrameReady = true;
                        showPane(pane);
                    }
                }
            } else if ("camera_shell_died".equals(kind)) {
                onShellDeath();
            } else if ("camera_overlay_error".equals(kind)) {
                int cameraId = event.optInt("camera_id", -1);
                if (CameraOverlayProfile.isParking(cameraId)
                        && !"close".equals(event.optString("stage"))) {
                    resetAfterAttachFailure();
                }
            } else if ("camera_overlay_surface".equals(kind)
                    && "destroyed".equals(event.optString("state"))) {
                int cameraId = event.optInt("camera_id", -1);
                if (CameraOverlayProfile.isParking(cameraId)) {
                    Pane pane = panes[CameraOverlayProfile.parkingIdFor(cameraId)];
                    if (pane.requestId == event.optInt("request_id", -1)
                            && (pane.prepared || pane.attached)) {
                        resetAfterAttachFailure();
                    }
                }
            } else if ("camera_error".equals(kind)
                    && CameraHelperMain.CAMERA_OWNER_PARKING.equals(
                    event.optString("camera_owner"))) {
                resetAfterAttachFailure();
            } else if ("helper_connected".equals(kind)
                    || "camera_shell_recovery_failed".equals(kind)) {
                handler.removeCallbacks(retry);
                handler.postDelayed(retry, RETRY_MS);
            }
        } catch (Throwable ignored) {
            // A malformed telemetry line is an invalid state; the next tick fails closed.
            speedValid = false;
            evaluate();
        }
    }

    void shutdown() {
        shutdown = true;
        handler.removeCallbacks(retry);
        handler.removeCallbacks(closeRetry);
        handler.removeCallbacks(closeTick);
        closeRetryState.cancel();
        desiredMask = 0;
        potentialMask = 0;
        closeAll("controller_shutdown");
        helper = null;
    }

    private void onShellDeath() {
        boolean closed = closeParkingGroup("camera_shell_died");
        handler.removeCallbacks(retry);
        if (closed && !shutdown) handler.postDelayed(retry, RETRY_MS);
    }

    private void evaluate() {
        if (shutdown || closeRetryState.blocksEvaluation()) return;
        long now = SystemClock.elapsedRealtime();
        potentialMask = potentialMask(rules);
        if (potentialMask != lastPotentialMask) {
            lastPotentialMask = potentialMask;
            membershipGeneration++;
            if (attachedGroupMask != 0
                    && !closeParkingGroup("parking_membership_changed")) return;
        }
        desiredMask = policy.update(rules, maxSpeedKph,
                radarRaw, radarValid, radarTimestamps,
                speedKph, speedValid, speedTimestamp, now,
                RADAR_STALE_MS, SPEED_STALE_MS);
        if (isHardBlocked(suspended, activityVisible, reversePriority)) {
            desiredMask = 0;
            if (!closeParkingGroup("parking_preempted")) return;
            handler.removeCallbacks(closeTick);
            return;
        }
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            Pane pane = panes[profile.id];
            boolean wanted = (desiredMask & profile.bit()) != 0;
            boolean needed = (potentialMask & profile.bit()) != 0;
            pane.wantVisible = wanted;
            if (needed) {
                if (!pane.prepared) preparePane(pane);
                if (wanted) activatePane(pane);
                else scheduleHide(pane);
            } else {
                pane.wantVisible = false;
            }
        }
        if (potentialMask == 0) {
            if (!closeParkingGroup("parking_disabled")) return;
        } else {
            attachParkingGroup();
        }
        handler.removeCallbacks(closeTick);
        if (!shutdown) handler.postDelayed(closeTick, ParkingCameraTriggerPolicy.CLOSE_DELAY_MS);
    }

    private static int potentialMask(ParkingCameraSettings.Rule[] rules) {
        int mask = 0;
        if (rules == null) return mask;
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            ParkingCameraSettings.Rule rule = rules[profile.id];
            if (rule == null || !rule.enabled) continue;
            mask |= profile.bit();
            if (rule.addCentral && profile.corner()) {
                mask |= 1 << profile.additiveCentralId();
            }
        }
        return mask;
    }

    private void reloadSettings() {
        rules = ParkingCameraSettings.readRules(settings);
        maxSpeedKph = ParkingCameraSettings.readMaxSpeed(settings);
    }

    private void preparePane(Pane pane) {
        CameraHelperMain.HelperBinder activeHelper = helper;
        if (activeHelper == null || pane.preparing || shutdown) return;
        pane.preparing = true;
        pane.requestId = ++requestSequence;
        final int generation = membershipGeneration;
        final int requestId = pane.requestId;
        CameraShellProtocol.OverlaySpec spec = buildOverlaySpec(
                pane.profile, pane.requestId, CameraDisplayTarget.TABLET,
                displayWidth(), displayHeight(), settings);
        activeHelper.prepareParkingOverlayWindow(spec, surface -> {
            if (generation != membershipGeneration
                    || pane.requestId != requestId
                    || (potentialMask & pane.profile.bit()) == 0) {
                if (surface != null && surface.surface != null) surface.surface.release();
                return;
            }
            if (surface == null || surface.surface == null) {
                pane.preparing = false;
                handler.postDelayed(retry, RETRY_MS);
                return;
            }
            pane.surface = surface.surface;
            pane.surfaceGeneration = surface.surfaceGeneration;
            pane.prepared = true;
            pane.preparing = false;
            attachParkingGroup();
        }, () -> emit("parking_camera_prepare", "camera_id",
                CameraOverlayProfile.overlayIdForParking(pane.profile.id),
                "request_id", pane.requestId));
    }

    private void attachParkingGroup() {
        CameraHelperMain.HelperBinder activeHelper = helper;
        if (activeHelper == null) return;
        if (attachedGroupMask == potentialMask && attachedGroupMask != 0) return;
        int count = 0;
        for (Pane pane : panes) {
            if ((potentialMask & pane.profile.bit()) == 0) continue;
            if (!pane.prepared || pane.surface == null) return;
            count++;
        }
        if (count == 0) return;
        Surface[] surfaces = new Surface[count];
        int[] indexes = new int[count];
        int offset = 0;
        for (Pane pane : panes) {
            if ((potentialMask & pane.profile.bit()) == 0
                    || !pane.prepared || pane.surface == null) continue;
            surfaces[offset] = pane.surface;
            indexes[offset++] = pane.profile.physicalCameraIndex;
        }
        try {
            int groupRequestId = nextGroupRequest();
            String result = activeHelper.openParkingCameras(surfaces, indexes, groupRequestId);
            boolean ok = result != null && result.contains("camera_opened");
            attachedGroupMask = ok ? potentialMask : 0;
            attachedGroupRequestId = ok ? groupRequestId : 0;
            if (!ok) {
                resetAfterAttachFailure();
                return;
            }
            for (Pane pane : panes) {
                pane.attached = ok && (potentialMask & pane.profile.bit()) != 0;
            }
            if (ok) {
                for (Pane pane : panes) {
                    if (!pane.prepared) continue;
                    if (pane.wantVisible) {
                        activatePane(pane);
                    } else if (pane.surface != null) {
                        try {
                            activeHelper.setParkingTargetActive(pane.surface, false);
                            pane.active = false;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable error) {
            emit("parking_camera_error", "stage", "attach_group", "error", summary(error));
            resetAfterAttachFailure();
            return;
        }
    }

    private int nextGroupRequest() {
        return ++requestSequence;
    }

    private void activatePane(Pane pane) {
        if (pane.active) {
            if (pane.firstFrameReady) showPane(pane);
            return;
        }
        if (!shouldArmFirstFrame(pane.attached, pane.surface != null,
                pane.visible, pane.visibilityPending, pane.active)) return;
        pane.active = true;
        pane.firstFrameReady = false;
        pane.visibilityPending = false;
        pane.transition++;
        pane.frameEpoch++;
        try {
            helper.setParkingTargetActive(pane.surface, true);
        } catch (Throwable error) {
            pane.active = false;
            emit("parking_camera_error", "stage", "activate_target",
                    "camera_id", pane.profile.id, "error", summary(error));
            return;
        }
        helper.armParkingOverlayFirstFrame(OverlayFrameArm.create(
                CameraOverlayProfile.overlayIdForParking(pane.profile.id),
                pane.requestId, pane.surfaceGeneration, pane.frameEpoch));
        final int epoch = pane.frameEpoch;
        handler.postDelayed(() -> {
            if (pane.attached && pane.active && !pane.firstFrameReady
                    && pane.frameEpoch == epoch && pane.wantVisible) {
                emit("parking_camera_error", "stage", "first_frame_timeout",
                        "camera_id", pane.profile.id, "request_id", pane.requestId);
                resetAfterAttachFailure();
            }
        }, 3_000L);
    }

    private void showPane(Pane pane) {
        if (!pane.attached || !pane.wantVisible || !pane.firstFrameReady
                || pane.visible || pane.visibilityPending) return;
        final int transition = pane.transition;
        pane.visibilityPending = true;
        helper.setParkingOverlayWindowVisible(
                CameraOverlayProfile.overlayIdForParking(pane.profile.id),
                pane.requestId, pane.surfaceGeneration, true, success -> {
                    if (transition != pane.transition) return;
                    pane.visibilityPending = false;
                    if (success) {
                        pane.visible = true;
                        evaluate();
                    } else {
                        handler.removeCallbacks(retry);
                        handler.postDelayed(retry, RETRY_MS);
                    }
                });
    }

    private void scheduleHide(Pane pane) {
        if ((!pane.visible && !pane.active) || pane.visibilityPending) return;
        final int transition = ++pane.transition;
        pane.visibilityPending = true;
        helper.setParkingOverlayWindowVisible(
                CameraOverlayProfile.overlayIdForParking(pane.profile.id),
                pane.requestId, pane.surfaceGeneration, false, success -> {
                    if (transition != pane.transition) return;
                    pane.visibilityPending = false;
                    if (success) {
                        pane.visible = false;
                        pane.firstFrameReady = false;
                        if (pane.surface != null) {
                            try { helper.setParkingTargetActive(pane.surface, false); }
                            catch (Throwable ignored) {}
                        }
                        pane.active = false;
                        evaluate();
                    } else {
                        handler.removeCallbacks(retry);
                        handler.postDelayed(retry, RETRY_MS);
                    }
                });
    }

    private boolean closeParkingGroup(String reason) {
        boolean hasPaneState = attachedGroupMask != 0;
        for (Pane pane : panes) {
            hasPaneState |= pane.preparing || pane.prepared || pane.surface != null;
        }
        if (!hasPaneState) {
            attachedGroupRequestId = 0;
            cancelCloseRetry();
            return true;
        }
        CameraHelperMain.HelperBinder activeHelper = helper;
        if (activeHelper == null) {
            emit("parking_camera_error", "stage", "close_group",
                    "request_id", attachedGroupRequestId, "error", "helper unavailable");
            scheduleCloseRetry();
            return false;
        }
        if (attachedGroupMask != 0) {
            int requestId = attachedGroupRequestId;
            if (requestId <= 0) {
                emit("parking_camera_error", "stage", "close_group",
                        "request_id", requestId, "error", "missing group request id");
                scheduleCloseRetry();
                return false;
            }
            String result;
            try {
                result = activeHelper.closeParkingCameras(reason, requestId);
            } catch (Throwable error) {
                emit("parking_camera_error", "stage", "close_group",
                        "request_id", requestId, "error", summary(error));
                scheduleCloseRetry();
                return false;
            }
            if (!CameraHelperMain.HelperBinder.isSuccessfulCameraCloseResult(result)) {
                emit("parking_camera_error", "stage", "close_group",
                        "request_id", requestId,
                        "error", result == null ? "close rejected" : result);
                scheduleCloseRetry();
                return false;
            }
            attachedGroupMask = 0;
            attachedGroupRequestId = 0;
            membershipGeneration++;
            clearPaneState();
            cancelCloseRetry();
            try {
                activeHelper.closeParkingOverlayWindows(reason);
            } catch (Throwable error) {
                emit("parking_camera_error", "stage", "close_windows",
                        "request_id", requestId, "error", summary(error));
            }
            return true;
        }
        try {
            activeHelper.closeParkingOverlayWindows(reason);
        } catch (Throwable error) {
            emit("parking_camera_error", "stage", "close_windows",
                    "request_id", 0, "error", summary(error));
        }
        membershipGeneration++;
        attachedGroupMask = 0;
        attachedGroupRequestId = 0;
        clearPaneState();
        cancelCloseRetry();
        return true;
    }

    private void clearPaneState() {
        for (Pane pane : panes) {
            pane.attached = false;
            pane.prepared = false;
            pane.preparing = false;
            pane.surface = null;
            pane.requestId = 0;
            pane.surfaceGeneration = 0;
            pane.visible = false;
            pane.active = false;
            pane.wantVisible = false;
            pane.firstFrameReady = false;
            pane.visibilityPending = false;
            pane.transition++;
        }
    }

    private void scheduleCloseRetry() {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(closeTick);
        if (shutdown) return;
        closeRetryState.schedule(attachedGroupRequestId);
        handler.removeCallbacks(closeRetry);
        handler.postDelayed(closeRetry, RETRY_MS);
    }

    private void resetAfterAttachFailure() {
        if (closeParkingGroup("parking_attach_failed")) {
            handler.removeCallbacks(retry);
            if (!shutdown) handler.postDelayed(retry, RETRY_MS);
        }
    }

    private void retryClose() {
        if (shutdown) return;
        CloseRetryState.Result result = closeRetryState.retry(
                attachedGroupRequestId,
                ignored -> closeParkingGroup("parking_close_retry"));
        if (result == CloseRetryState.Result.STALE
                || result == CloseRetryState.Result.SUCCEEDED) evaluate();
    }

    private void cancelCloseRetry() {
        closeRetryState.cancel();
        handler.removeCallbacks(closeRetry);
    }

    private void closeAll(String reason) {
        closeParkingGroup(reason);
    }

    static final class CloseRetryState {
        enum Result { STALE, FAILED, SUCCEEDED }

        private boolean pending;
        private int requestId;

        void schedule(int value) {
            pending = true;
            requestId = value;
        }

        boolean blocksEvaluation() {
            return pending;
        }

        Result retry(int currentRequestId, IntPredicate close) {
            if (!pending || requestId <= 0 || requestId != currentRequestId) {
                cancel();
                return Result.STALE;
            }
            if (!close.test(currentRequestId)) return Result.FAILED;
            cancel();
            return Result.SUCCEEDED;
        }

        void cancel() {
            pending = false;
            requestId = 0;
        }
    }

    static CameraShellProtocol.OverlaySpec buildOverlaySpec(
            ParkingCameraProfile profile, int requestId, int target,
            int displayWidth, int displayHeight, SharedPreferences settings) {
        if (profile == null) throw new IllegalArgumentException("parking profile required");
        if (requestId <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("invalid parking overlay geometry");
        }
        String prefix = "parking_camera_"
                + profile.wireName.toLowerCase(java.util.Locale.US);
        int scalePercent = safeInt(settings, prefix + "_scale", 25, 20, 60);
        float anchorX = safeFloat(settings, prefix + "_x", defaultAnchorX(profile.id));
        float anchorY = safeFloat(settings, prefix + "_y", defaultAnchorY(profile.id));
        int width = Math.max(96, Math.min(displayWidth,
                Math.round(displayWidth * scalePercent / 100.0f)));
        int height = Math.max(72, Math.min(displayHeight,
                Math.round(width * 0.75f)));
        int x = Math.round(anchorX * Math.max(0, displayWidth - width));
        int y = Math.round(anchorY * Math.max(0, displayHeight - height));
        CameraDewarpConfig dewarp = CameraDewarpConfig.loadForParking(settings, profile);
        DirectCameraCrop raw = DirectCameraCrop.load(settings, profile);
        DirectCameraCrop corrected = DirectCameraCrop.loadCorrected(settings, profile, raw);
        DirectCameraCrop crop = dewarp.enabled ? corrected : raw;
        return new CameraShellProtocol.OverlaySpec(
                CameraOverlayProfile.overlayIdForParking(profile.id), requestId, target,
                width, height, Math.max(0, x), Math.max(0, y),
                crop.left, crop.top, crop.width, crop.height, crop.aspectMode,
                crop.rotationDegrees, crop.rotationMode, 8, dewarp,
                raw, CameraBufferQuality.load(settings), crop.mirrorHorizontally);
    }

    private int displayWidth() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager != null) manager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics.widthPixels > 0 ? metrics.widthPixels : 1920;
    }

    private int displayHeight() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager != null) manager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics.heightPixels > 0 ? metrics.heightPixels : 1080;
    }

    private static int radarIndex(int fid) {
        int[] fids = ParkingCameraProfile.allRadarFids();
        for (int i = 0; i < fids.length; i++) if (fids[i] == fid) return i;
        return -1;
    }

    static boolean matchesFirstFrame(
            OverlayFrameArm arm, int requestId, int surfaceGeneration,
            int frameEpoch, boolean wanted) {
        return arm != null && wanted && arm.requestId == requestId
                && arm.surfaceGeneration == surfaceGeneration
                && arm.frameArmEpoch == frameEpoch;
    }

    static boolean shouldArmFirstFrame(
            boolean attached, boolean hasSurface, boolean visible,
            boolean visibilityPending, boolean active) {
        return attached && hasSurface && !visible && !visibilityPending && !active;
    }

    static boolean isHardBlocked(
            boolean suspended, boolean activityVisible, boolean reversePriority) {
        return suspended || activityVisible || reversePriority;
    }

    private static int safeInt(
            SharedPreferences preferences, String key, int fallback, int minimum, int maximum) {
        try { return Math.max(minimum, Math.min(maximum,
                preferences.getInt(key, fallback))); }
        catch (Throwable ignored) { return fallback; }
    }

    private static float safeFloat(
            SharedPreferences preferences, String key, float fallback) {
        try {
            float value = preferences.getFloat(key, fallback);
            return Float.isFinite(value) ? Math.max(0.0f, Math.min(1.0f, value)) : fallback;
        } catch (Throwable ignored) { return fallback; }
    }

    private static float defaultAnchorX(int id) {
        return new float[]{0.0f, 0.5f, 1.0f, 1.0f, 0.5f, 0.0f, 0.0f, 1.0f}[id];
    }

    private static float defaultAnchorY(int id) {
        if (id == ParkingCameraProfile.LEFT || id == ParkingCameraProfile.RIGHT) return 0.5f;
        return id < 3 ? 0.0f : 1.0f;
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class Pane {
        final ParkingCameraProfile profile;
        Surface surface;
        int requestId;
        int surfaceGeneration;
        int frameEpoch;
        boolean preparing;
        boolean prepared;
        boolean attached;
        boolean active;
        boolean visible;
        boolean wantVisible;
        boolean firstFrameReady;
        int transition;
        boolean visibilityPending;

        Pane(ParkingCameraProfile profile) { this.profile = profile; }
    }
}
