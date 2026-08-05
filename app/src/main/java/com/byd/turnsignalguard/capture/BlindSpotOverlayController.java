package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

final class BlindSpotOverlayController {
    static final String PREF_ENABLED = "camera_enabled";
    static final String PREF_MIN_SPEED = "camera_min_speed_kph";
    static final String PREF_MAX_SPEED = "camera_max_speed_kph";
    static final String PREF_FRONT_ENABLED = "camera_front_enabled";
    static final String PREF_FRONT_MIN_SPEED = "camera_front_min_speed_kph";
    static final String PREF_FRONT_MAX_SPEED = "camera_front_max_speed_kph";
    static final String PREF_FRONT_MIN_ANGLE = "camera_front_min_angle_deg";
    static final String PREF_FRONT_TURN_REQUIRED = "camera_front_turn_required";
    static final String PREF_CORNER_RADIUS = "camera_corner_radius_dp";
    static final String PREF_SCALE = "camera_overlay_scale_percent";
    static final String PREF_LEFT_SCALE = "camera_left_scale_percent";
    static final String PREF_RIGHT_SCALE = "camera_right_scale_percent";
    static final String PREF_FRONT_LEFT_SCALE = "camera_front_left_scale_percent";
    static final String PREF_FRONT_RIGHT_SCALE = "camera_front_right_scale_percent";
    static final String PREF_LEFT_TARGET = "camera_left_display_target";
    static final String PREF_RIGHT_TARGET = "camera_right_display_target";
    static final String PREF_FRONT_LEFT_TARGET = "camera_front_left_display_target";
    static final String PREF_FRONT_RIGHT_TARGET = "camera_front_right_display_target";
    static final String PREF_LEFT_POSITION = "camera_left_position";
    static final String PREF_RIGHT_POSITION = "camera_right_position";
    static final String PREF_LEFT_X = "camera_left_x";
    static final String PREF_LEFT_Y = "camera_left_y";
    static final String PREF_RIGHT_X = "camera_right_x";
    static final String PREF_RIGHT_Y = "camera_right_y";
    static final String PREF_FRONT_LEFT_X = "camera_front_left_x";
    static final String PREF_FRONT_LEFT_Y = "camera_front_left_y";
    static final String PREF_FRONT_RIGHT_X = "camera_front_right_x";
    static final String PREF_FRONT_RIGHT_Y = "camera_front_right_y";
    static final String PREF_WARNING_MODE = "camera_bsd_warning_mode";

    static final int DEFAULT_MIN_SPEED_KPH = 10;
    static final int DEFAULT_MAX_SPEED_KPH = 300;
    static final int DEFAULT_FRONT_MIN_SPEED_KPH = 0;
    static final int DEFAULT_FRONT_MAX_SPEED_KPH = 10;
    static final float DEFAULT_FRONT_MIN_ANGLE_DEG = 10.0f;
    static final int DEFAULT_SCALE_PERCENT = 30;
    static final int MIN_SCALE_PERCENT = 20;
    static final int MAX_SCALE_PERCENT = 60;
    static final int DEFAULT_LEFT_POSITION = 0;
    static final int DEFAULT_RIGHT_POSITION = 2;
    static final int DEFAULT_WARNING_MODE = CameraShellProtocol.WARNING_MODE_PULSE;
    static final int DEFAULT_CORNER_RADIUS_DP = 10;
    static final int MAX_CORNER_RADIUS_DP = 48;

    private static final int BLINK_OFF = 1;
    private static final int BLINK_LEFT = 2;
    private static final int BLINK_RIGHT = 4;
    private static final String DIRECT_CAMERA_TAG = "pano_h";
    private static final long STATE_STALE_MS = 750;
    private static final long SURFACE_TIMEOUT_MS = 8_000;
    private static final long FIRST_FRAME_TIMEOUT_MS = 3_000;
    private static final long CAMERA_RETRY_MS = 3_000;
    static final int PREPARATION_WAIT = 0;
    static final int PREPARATION_OPEN = 1;
    static final int PREPARATION_RETRY = 2;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences settings;
    private final WindowManager windows;
    private final BiConsumer<String, Object[]> eventSink;
    private final PaneState[] panes = new PaneState[CameraProfile.COUNT];
    private final CameraRetryState cameraRetry = new CameraRetryState();
    private final Runnable staleState = () -> {
        stateValid = false;
        evaluate();
    };
    private final Runnable retryCamera = () -> {
        String trigger = cameraRetry.consume();
        if (trigger == null) return;
        String blocked = cameraRetryBlockReason();
        if (blocked != null) {
            emit("overlay_camera_retry", "state", "cancelled",
                    "reason", blocked, "trigger", trigger);
            return;
        }
        emit("overlay_camera_retry", "state", "attempt", "reason", trigger);
        rebuild("camera_retry");
    };

    private CameraHelperMain.HelperBinder helper;
    private boolean suspended;
    private boolean reversePriority;
    private long cameraShellEpoch;
    private boolean uiHidden;
    private boolean shutdown;
    private boolean stateValid;
    private boolean cameraOpenPending;
    private boolean cameraSessionOpen;
    private int cameraOpenRequestId;
    private int blink = -1;
    private float speedKph = Float.NaN;
    private float steeringAngle = Float.NaN;
    private int requestSequence;
    private boolean leftBsdValid;
    private boolean rightBsdValid;
    private int leftBsdRaw = -1;
    private int rightBsdRaw = -1;

    BlindSpotOverlayController(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.eventSink = eventSink;
        settings = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        migrateOverlayPreferences(settings);
        windows = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        for (CameraProfile profile : CameraProfile.values()) {
            panes[profile.id] = new PaneState(profile);
        }
    }

    static int directionToShow(boolean valid, int blink, float speedKph, int minSpeedKph) {
        int mask = CameraProfile.desiredMask(valid, blink, speedKph, Float.NaN,
                true, minSpeedKph, 300, false, 0, 300, true, 10.0f);
        if ((mask & CameraProfile.of(CameraProfile.REAR_LEFT).bit()) != 0) return BLINK_LEFT;
        if ((mask & CameraProfile.of(CameraProfile.REAR_RIGHT).bit()) != 0) return BLINK_RIGHT;
        return 0;
    }

    static int desiredCameraMask(
            boolean valid, int blink, float speedKph, float steeringAngle,
            boolean rearEnabled, int rearMinSpeed, int rearMaxSpeed,
            boolean frontEnabled, int frontMinSpeed, int frontMaxSpeed,
            boolean frontTurnRequired, float frontMinAngle) {
        return CameraProfile.desiredMask(valid, blink, speedKph, steeringAngle,
                rearEnabled, rearMinSpeed, rearMaxSpeed,
                frontEnabled, frontMinSpeed, frontMaxSpeed,
                frontTurnRequired, frontMinAngle);
    }

    static float readPosition(
            SharedPreferences settings, boolean right, boolean vertical) {
        return readPosition(settings,
                CameraProfile.of(right ? CameraProfile.REAR_RIGHT : CameraProfile.REAR_LEFT),
                vertical);
    }

    static float readPosition(
            SharedPreferences settings, CameraProfile profile, boolean vertical) {
        String key = positionKey(profile, vertical);
        if (settings.contains(key)) return clamp(settings.getFloat(key, 0.0f), 0.0f, 1.0f);
        if (profile.rear()) {
            int legacy = settings.getInt(profile.right()
                            ? PREF_RIGHT_POSITION : PREF_LEFT_POSITION,
                    profile.right() ? DEFAULT_RIGHT_POSITION : DEFAULT_LEFT_POSITION);
            return legacyPosition(legacy, vertical);
        }
        return defaultPosition(profile, vertical);
    }

    static float defaultPosition(CameraProfile profile, boolean vertical) {
        return vertical ? (profile.front() ? 1.0f : 0.0f)
                : profile.right() ? 1.0f : 0.0f;
    }

    static float legacyPosition(int position, boolean vertical) {
        int safe = clamp(position, 0, 8);
        return (vertical ? safe / 3 : safe % 3) / 2.0f;
    }

    static void migrateOverlayPreferences(SharedPreferences settings) {
        int sharedScale = migratedScale(false, 0,
                settings.getInt(PREF_SCALE, DEFAULT_SCALE_PERCENT));
        SharedPreferences.Editor editor = settings.edit();
        boolean changed = false;
        for (CameraProfile profile : CameraProfile.values()) {
            String scaleKey = scaleKey(profile);
            if (!settings.contains(scaleKey)) {
                editor.putInt(scaleKey, profile.rear() ? sharedScale : DEFAULT_SCALE_PERCENT);
                changed = true;
            }
            String targetKey = targetKey(profile);
            if (!settings.contains(targetKey)) {
                editor.putInt(targetKey, CameraDisplayTarget.TABLET);
                changed = true;
            }
        }
        if (!settings.contains(PREF_FRONT_ENABLED)) {
            editor.putBoolean(PREF_FRONT_ENABLED, false);
            changed = true;
        }
        if (!settings.contains(PREF_FRONT_MIN_SPEED)) {
            editor.putInt(PREF_FRONT_MIN_SPEED, DEFAULT_FRONT_MIN_SPEED_KPH);
            changed = true;
        }
        if (!settings.contains(PREF_MIN_SPEED)) {
            editor.putInt(PREF_MIN_SPEED, DEFAULT_MIN_SPEED_KPH);
            changed = true;
        }
        if (!settings.contains(PREF_MAX_SPEED)) {
            editor.putInt(PREF_MAX_SPEED, DEFAULT_MAX_SPEED_KPH);
            changed = true;
        }
        if (!settings.contains(PREF_FRONT_MAX_SPEED)) {
            editor.putInt(PREF_FRONT_MAX_SPEED, DEFAULT_FRONT_MAX_SPEED_KPH);
            changed = true;
        }
        if (!settings.contains(PREF_FRONT_MIN_ANGLE)) {
            editor.putFloat(PREF_FRONT_MIN_ANGLE, DEFAULT_FRONT_MIN_ANGLE_DEG);
            changed = true;
        }
        if (!settings.contains(PREF_FRONT_TURN_REQUIRED)) {
            editor.putBoolean(PREF_FRONT_TURN_REQUIRED, true);
            changed = true;
        }
        if (!settings.contains(PREF_CORNER_RADIUS)) {
            editor.putInt(PREF_CORNER_RADIUS, DEFAULT_CORNER_RADIUS_DP);
            changed = true;
        }
        if (changed) editor.apply();
    }

    static int migratedScale(boolean sidePresent, int sideScale, int sharedScale) {
        return clamp(sidePresent ? sideScale : sharedScale,
                MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
    }

    static int readScale(SharedPreferences settings, boolean right) {
        return readScale(settings,
                CameraProfile.of(right ? CameraProfile.REAR_RIGHT : CameraProfile.REAR_LEFT));
    }

    static int readScale(SharedPreferences settings, CameraProfile profile) {
        int fallback = profile.rear()
                ? settings.getInt(PREF_SCALE, DEFAULT_SCALE_PERCENT)
                : DEFAULT_SCALE_PERCENT;
        return clamp(settings.getInt(scaleKey(profile), fallback),
                MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
    }

    static int readTarget(SharedPreferences settings, boolean right) {
        return readTarget(settings,
                CameraProfile.of(right ? CameraProfile.REAR_RIGHT : CameraProfile.REAR_LEFT));
    }

    static int readTarget(SharedPreferences settings, CameraProfile profile) {
        int target = settings.getInt(targetKey(profile), CameraDisplayTarget.TABLET);
        return CameraDisplayTarget.isValid(target) ? target : CameraDisplayTarget.TABLET;
    }

    static int readCornerRadius(SharedPreferences settings) {
        return clamp(settings.getInt(PREF_CORNER_RADIUS, DEFAULT_CORNER_RADIUS_DP),
                0, MAX_CORNER_RADIUS_DP);
    }

    static int previewIndexForDirection(int direction) {
        if (direction == BLINK_LEFT) return CameraProfile.of(CameraProfile.REAR_LEFT).previewIndex;
        if (direction == BLINK_RIGHT) return CameraProfile.of(CameraProfile.REAR_RIGHT).previewIndex;
        return -1;
    }

    static boolean isMatchingFirstFrame(
            int eventRequestId, int eventGeneration,
            int activeRequestId, int activeGeneration,
            int displayedPreviewIndex, int requestedPreviewIndex) {
        return eventRequestId == activeRequestId
                && eventGeneration == activeGeneration
                && displayedPreviewIndex == requestedPreviewIndex;
    }

    static int[] fitAspect(
            int requestedWidth, int maxWidth, int maxHeight, float aspect) {
        float safeAspect = Float.isFinite(aspect) && aspect > 0.0f
                ? aspect : DirectCameraCrop.OUTPUT_ASPECT;
        int width = Math.max(1, Math.min(Math.max(1, requestedWidth),
                Math.min(Math.max(1, maxWidth),
                        Math.round(Math.max(1, maxHeight) * safeAspect))));
        return new int[]{width, Math.max(1, Math.round(width / safeAspect))};
    }

    static int[] fitFourThree(int requestedWidth, int maxWidth, int maxHeight) {
        return fitAspect(requestedWidth, maxWidth, maxHeight,
                DirectCameraCrop.OUTPUT_ASPECT);
    }

    void attachHelper(CameraHelperMain.HelperBinder value) {
        handler.post(() -> {
            helper = value;
            applySettingsOnMain();
        });
    }

    void setSuspended(boolean value) {
        handler.post(() -> {
            if (suspended == value) return;
            suspended = value;
            if (value) destroyAll("preview_handoff");
            else applySettingsOnMain();
        });
    }

    void setReversePriority(boolean value) {
        handler.post(() -> {
            if (reversePriority == value) return;
            reversePriority = value;
            if (value) destroyAll("reverse_priority");
            else applySettingsOnMain();
        });
    }

    void setUiHidden(boolean value) {
        handler.post(() -> {
            uiHidden = value;
            evaluate();
        });
    }

    void applySettings() {
        handler.post(this::applySettingsOnMain);
    }

    void applyWarningSettings() {
        handler.post(this::applyWarnings);
    }

    void acceptEvent(String line) {
        if (line == null) return;
        try {
            JSONObject event = new JSONObject(line);
            String kind = event.optString("kind");
            if ("vehicle_state".equals(kind)) {
                boolean valid = event.optBoolean("valid");
                int nextBlink = event.optInt("blink", -1);
                float nextSpeed = valid
                        ? (float) event.optDouble("speed_kph", Double.NaN) : Float.NaN;
                double angleValue = event.has("steering_angle_deg")
                        ? event.optDouble("steering_angle_deg", Double.NaN)
                        : event.optDouble("angle_deg", Double.NaN);
                float nextAngle = valid ? (float) angleValue : Float.NaN;
                handler.post(() -> acceptVehicleState(
                        valid, nextBlink, nextSpeed, nextAngle));
            } else if ("bsd_state".equals(kind)) {
                boolean listenerOk = event.optBoolean("listener_ok");
                boolean nextLeftValid = listenerOk && event.optBoolean("left_valid");
                boolean nextRightValid = listenerOk && event.optBoolean("right_valid");
                int nextLeftRaw = nextLeftValid ? event.optInt("left_raw", -1) : -1;
                int nextRightRaw = nextRightValid ? event.optInt("right_raw", -1) : -1;
                handler.post(() -> acceptBsdState(
                        nextLeftValid, nextLeftRaw, nextRightValid, nextRightRaw));
            } else if ("camera_discovery".equals(kind) && event.optBoolean("ok")) {
                handler.post(this::applySettingsOnMain);
            } else if ("camera_opened".equals(kind)
                    && "helper".equals(event.optString("source"))
                    && CameraHelperMain.CAMERA_OWNER_OVERLAY.equals(
                            event.optString("camera_owner"))
                    && DIRECT_CAMERA_TAG.equals(event.optString("camera_tag"))) {
                int requestId = event.optInt("request_id", -1);
                handler.post(() -> cameraOpened(requestId));
            } else if ("camera_error".equals(kind)
                    && "helper".equals(event.optString("source"))
                    && CameraHelperMain.CAMERA_OWNER_OVERLAY.equals(
                            event.optString("camera_owner"))) {
                String stage = event.optString("stage", kind);
                int requestId = event.optInt("request_id", -1);
                handler.post(() -> {
                    if (matchesCameraOpenEvent(
                            cameraOpenPending || cameraSessionOpen,
                            cameraOpenRequestId, requestId)) {
                        cameraUnavailable(stage);
                    }
                });
            } else if ("camera_overlay_first_frame".equals(kind)) {
                int cameraId = event.optInt("camera_id", -1);
                int requestId = event.optInt("request_id", -1);
                int generation = event.optInt("surface_generation", -1);
                handler.post(() -> firstFrameAvailable(cameraId, requestId, generation));
            } else if ("camera_overlay_surface".equals(kind)
                    && "destroyed".equals(event.optString("state"))) {
                int cameraId = event.optInt("camera_id", -1);
                int requestId = event.optInt("request_id", -1);
                handler.post(() -> paneSurfaceDestroyed(cameraId, requestId));
            } else if ("camera_overlay_error".equals(kind)) {
                int cameraId = event.optInt("camera_id", -1);
                int requestId = event.optInt("request_id", -1);
                String stage = event.optString("stage", kind);
                handler.post(() -> paneUnavailable(cameraId, requestId, stage));
            } else if ("camera_shell_attached".equals(kind)) {
                long epoch = event.optLong("camera_shell_epoch", 0);
                handler.post(() -> cameraShellAttached(epoch));
            } else if ("camera_shell_died".equals(kind)) {
                long epoch = event.optLong("camera_shell_epoch", 0);
                handler.post(() -> cameraShellDied(epoch));
            }
        } catch (Throwable error) {
            emit("overlay_event_parse_error", "error", summary(error));
        }
    }

    void shutdown() {
        shutdown = true;
        handler.removeCallbacks(staleState);
        cancelCameraRetry("overlay_shutdown");
        destroyAll("overlay_shutdown");
        helper = null;
    }

    private void applySettingsOnMain() {
        migrateOverlayPreferences(settings);
        if (cameraUnavailableReason() != null) {
            destroyAll(cameraUnavailableReason());
            return;
        }
        rebuild("settings_changed");
    }

    private String cameraUnavailableReason() {
        if (shutdown) return "overlay_shutdown";
        if (!anyLaneEnabled()) return "overlay_disabled";
        if (suspended) return "overlay_suspended";
        if (reversePriority) return "reverse_priority";
        if (helper == null) return "helper_unavailable";
        if (windows == null) return "window_manager_unavailable";
        return null;
    }

    private boolean anyLaneEnabled() {
        return settings.getBoolean(PREF_ENABLED, false)
                || settings.getBoolean(PREF_FRONT_ENABLED, false);
    }

    private void rebuild(String reason) {
        cancelCameraRetry(reason);
        destroyAll(reason);
        if (cameraUnavailableReason() != null) return;
        boolean rearEnabled = settings.getBoolean(PREF_ENABLED, false);
        boolean frontEnabled = settings.getBoolean(PREF_FRONT_ENABLED, false);
        for (PaneState pane : panes) {
            if (pane.profile.rear() ? rearEnabled : frontEnabled) preparePane(pane);
        }
    }

    private void preparePane(PaneState pane) {
        int requestId = nextRequestId();
        CameraShellProtocol.OverlaySpec spec = buildOverlaySpec(pane.profile, requestId);
        pane.expected = true;
        pane.requestId = requestId;
        pane.target = spec.target;
        helper.prepareOverlayWindow(spec,
                this::overlaySurfaceAvailable,
                () -> overlayPrepared(pane.profile.id, requestId));
        emit("overlay_geometry", "camera_id", pane.profile.id,
                "camera_profile", pane.profile.wireName,
                "request_id", requestId,
                "target", CameraDisplayTarget.name(spec.target),
                "width", spec.width, "height", spec.height,
                "x", spec.x, "y", spec.y);
    }

    private void overlayPrepared(int cameraId, int requestId) {
        PaneState pane = pane(cameraId);
        if (pane == null || pane.requestId != requestId || pane.resolved) return;
        handler.postDelayed(() -> surfaceTimedOut(cameraId, requestId), SURFACE_TIMEOUT_MS);
    }

    private void surfaceTimedOut(int cameraId, int requestId) {
        PaneState pane = pane(cameraId);
        if (pane == null || pane.requestId != requestId || pane.resolved) return;
        paneUnavailable(cameraId, requestId, "overlay_surface_timeout");
    }

    private void overlaySurfaceAvailable(TurnSignalController.OverlaySurface value) {
        PaneState pane = pane(value.cameraId);
        if (pane == null || !pane.expected || pane.requestId != value.requestId
                || value.surface == null || !value.surface.isValid()) {
            if (value.surface != null) value.surface.release();
            return;
        }
        releaseSurface(pane.pendingSurface);
        pane.pendingSurface = value.surface;
        pane.generation = value.surfaceGeneration;
        pane.resolved = true;
        maybeOpenCamera();
    }

    private void maybeOpenCamera() {
        if (cameraOpenPending || cameraSessionOpen || helper == null) return;
        List<PaneState> ready = new ArrayList<>();
        int expected = 0;
        int resolved = 0;
        int failed = 0;
        for (PaneState pane : panes) {
            if (!pane.expected) continue;
            expected++;
            if (pane.resolved) resolved++;
            if (pane.failed) failed++;
            if (!pane.failed && pane.pendingSurface != null) ready.add(pane);
        }
        int decision = preparationDecision(expected, resolved, failed);
        if (decision == PREPARATION_WAIT) return;
        if (decision == PREPARATION_RETRY) {
            destroyAll(failed > 0 ? "incomplete_overlay_set" : "no_overlay_surfaces");
            scheduleCameraRetry(failed > 0
                    ? "incomplete_overlay_set" : "no_overlay_surfaces");
            return;
        }
        Surface[] surfaces = new Surface[ready.size()];
        int[] indexes = new int[ready.size()];
        int[] cameraIds = new int[ready.size()];
        for (int i = 0; i < ready.size(); i++) {
            PaneState pane = ready.get(i);
            surfaces[i] = pane.pendingSurface;
            pane.pendingSurface = null;
            indexes[i] = pane.profile.previewIndex;
            cameraIds[i] = pane.profile.id;
        }
        cameraOpenPending = true;
        cameraOpenRequestId = nextRequestId();
        try {
            helper.openOverlayDirectCameras(
                    surfaces, indexes, cameraIds, cameraOpenRequestId);
            emit("overlay_camera_request", "camera_ids", java.util.Arrays.toString(cameraIds),
                    "preview_indexes", java.util.Arrays.toString(indexes),
                    "request_id", cameraOpenRequestId);
        } catch (Throwable error) {
            for (Surface surface : surfaces) releaseSurface(surface);
            cameraUnavailable("open_direct_camera");
        }
    }

    private void cameraOpened(int cameraRequestId) {
        if (!matchesCameraOpenEvent(
                cameraOpenPending, cameraOpenRequestId, cameraRequestId)) return;
        cameraOpenPending = false;
        cameraSessionOpen = true;
        cancelCameraRetry("camera_opened");
        for (PaneState pane : panes) {
            if (!pane.expected || pane.failed || pane.generation <= 0) continue;
            helper.armOverlayFirstFrame(
                    pane.profile.id, pane.requestId, pane.generation);
            int cameraId = pane.profile.id;
            int requestId = pane.requestId;
            int generation = pane.generation;
            handler.postDelayed(() -> firstFrameTimedOut(
                    cameraId, requestId, generation), FIRST_FRAME_TIMEOUT_MS);
        }
    }

    private void firstFrameTimedOut(int cameraId, int requestId, int generation) {
        PaneState pane = pane(cameraId);
        if (pane == null || pane.requestId != requestId
                || pane.generation != generation || pane.ready) return;
        cameraUnavailable("first_frame_timeout_" + pane.profile.wireName);
    }

    private void firstFrameAvailable(int cameraId, int requestId, int generation) {
        PaneState pane = pane(cameraId);
        if (pane == null || pane.requestId != requestId || pane.generation != generation
                || !cameraSessionOpen) return;
        pane.ready = true;
        emit("overlay_camera_ready", "camera_id", cameraId,
                "camera_profile", pane.profile.wireName,
                "request_id", requestId, "surface_generation", generation,
                "readiness", "first_frame");
        evaluate();
    }

    private void paneSurfaceDestroyed(int cameraId, int requestId) {
        PaneState pane = pane(cameraId);
        if (pane == null || pane.requestId != requestId) return;
        if (cameraOpenPending || cameraSessionOpen) {
            cameraUnavailable("overlay_surface_destroyed_" + pane.profile.wireName);
        } else {
            paneUnavailable(cameraId, requestId, "overlay_surface_destroyed");
        }
    }

    private void paneUnavailable(int cameraId, int requestId, String reason) {
        PaneState pane = pane(cameraId);
        if (pane == null || !pane.expected
                || requestId > 0 && pane.requestId != requestId) return;
        if (cameraOpenPending || cameraSessionOpen) {
            cameraUnavailable(reason + "_" + pane.profile.wireName);
            return;
        }
        pane.failed = true;
        pane.resolved = true;
        releaseSurface(pane.pendingSurface);
        pane.pendingSurface = null;
        emit("overlay_camera_output_unavailable", "camera_id", cameraId,
                "camera_profile", pane.profile.wireName, "reason", reason);
        maybeOpenCamera();
    }

    static int preparationDecision(int expected, int resolved, int failed) {
        if (failed > 0 || expected <= 0) return PREPARATION_RETRY;
        return resolved < expected ? PREPARATION_WAIT : PREPARATION_OPEN;
    }

    private void cameraUnavailable(String reason) {
        hideAll(reason);
        if (helper != null) helper.closeOverlayCamera(reason);
        cameraOpenPending = false;
        cameraSessionOpen = false;
        cameraOpenRequestId = 0;
        for (PaneState pane : panes) {
            pane.ready = false;
            pane.visible = false;
        }
        scheduleCameraRetry(reason);
    }

    private void cameraShellAttached(long epoch) {
        if (epoch > cameraShellEpoch) cameraShellEpoch = epoch;
    }

    private void cameraShellDied(long epoch) {
        if (!TurnSignalController.isCurrentCameraShellEpoch(cameraShellEpoch, epoch)) return;
        for (PaneState pane : panes) {
            if (!pane.expected && pane.requestId <= 0 && pane.generation <= 0) continue;
            emit("overlay_camera_output_invalidated",
                    "camera_id", pane.profile.id,
                    "camera_profile", pane.profile.wireName,
                    "request_id", pane.requestId,
                    "surface_generation", pane.generation,
                    "reason", "camera_shell_died");
        }
        cameraOpenPending = false;
        cameraSessionOpen = false;
        cameraOpenRequestId = 0;
        for (PaneState pane : panes) pane.reset();
        scheduleCameraRetry("camera_shell_died");
    }

    private void acceptVehicleState(
            boolean valid, int nextBlink, float nextSpeed, float nextAngle) {
        stateValid = valid && validBlink(nextBlink) && Float.isFinite(nextSpeed);
        blink = nextBlink;
        speedKph = nextSpeed;
        steeringAngle = nextAngle;
        handler.removeCallbacks(staleState);
        if (stateValid) handler.postDelayed(staleState, STATE_STALE_MS);
        evaluate();
    }

    private static boolean validBlink(int value) {
        return value == BLINK_OFF || value == BLINK_LEFT || value == BLINK_RIGHT;
    }

    private void acceptBsdState(
            boolean nextLeftValid, int nextLeftRaw,
            boolean nextRightValid, int nextRightRaw) {
        leftBsdValid = nextLeftValid && BlindSpotWarningRuntime.isValidRaw(nextLeftRaw);
        rightBsdValid = nextRightValid && BlindSpotWarningRuntime.isValidRaw(nextRightRaw);
        leftBsdRaw = leftBsdValid ? nextLeftRaw : -1;
        rightBsdRaw = rightBsdValid ? nextRightRaw : -1;
        applyWarnings();
    }

    private void evaluate() {
        int desired = desiredCameraMask(
                stateValid, blink, speedKph, steeringAngle,
                settings.getBoolean(PREF_ENABLED, false),
                settings.getInt(PREF_MIN_SPEED, DEFAULT_MIN_SPEED_KPH),
                settings.getInt(PREF_MAX_SPEED, DEFAULT_MAX_SPEED_KPH),
                settings.getBoolean(PREF_FRONT_ENABLED, false),
                settings.getInt(PREF_FRONT_MIN_SPEED, DEFAULT_FRONT_MIN_SPEED_KPH),
                settings.getInt(PREF_FRONT_MAX_SPEED, DEFAULT_FRONT_MAX_SPEED_KPH),
                settings.getBoolean(PREF_FRONT_TURN_REQUIRED, true),
                settings.getFloat(PREF_FRONT_MIN_ANGLE, DEFAULT_FRONT_MIN_ANGLE_DEG));
        if (suspended || reversePriority || uiHidden) desired = 0;
        for (PaneState pane : panes) {
            boolean show = (desired & pane.profile.bit()) != 0
                    && pane.expected && pane.ready && !pane.failed;
            setVisible(pane, show, show ? "trigger_active" : "trigger_inactive");
        }
        applyWarnings();
    }

    private void setVisible(PaneState pane, boolean visible, String reason) {
        if (pane.visible == visible) return;
        pane.visible = visible;
        if (helper != null && pane.requestId > 0 && pane.generation > 0) {
            helper.setOverlayWindowVisible(pane.profile.id,
                    pane.requestId, pane.generation, visible);
        }
        emit("overlay_visibility", "camera_id", pane.profile.id,
                "camera_profile", pane.profile.wireName,
                "visible", visible, "reason", reason,
                "speed_kph", Float.isFinite(speedKph) ? speedKph : "unknown");
    }

    private void hideAll(String reason) {
        for (PaneState pane : panes) setVisible(pane, false, reason);
    }

    private void applyWarnings() {
        int mode = readWarningMode(settings);
        for (PaneState pane : panes) {
            if (!pane.profile.rear() || !pane.ready || pane.generation <= 0) continue;
            boolean active = pane.profile.right()
                    ? BlindSpotWarningRuntime.isActiveRaw(rightBsdValid, rightBsdRaw)
                    : BlindSpotWarningRuntime.isActiveRaw(leftBsdValid, leftBsdRaw);
            int edge = pane.visible && active && mode != CameraShellProtocol.WARNING_MODE_OFF
                    ? (pane.profile.right() ? CameraShellProtocol.WARNING_EDGE_RIGHT
                            : CameraShellProtocol.WARNING_EDGE_LEFT)
                    : CameraShellProtocol.WARNING_EDGE_NONE;
            int appliedMode = edge == CameraShellProtocol.WARNING_EDGE_NONE
                    ? CameraShellProtocol.WARNING_MODE_OFF : mode;
            if (pane.warningEdge == edge && pane.warningMode == appliedMode) continue;
            helper.setOverlayWindowWarning(pane.profile.id,
                    pane.requestId, pane.generation, edge, appliedMode);
            pane.warningEdge = edge;
            pane.warningMode = appliedMode;
        }
    }

    private void destroyAll(String reason) {
        handler.removeCallbacks(staleState);
        hideAll(reason);
        if (helper != null) {
            if (cameraOpenPending || cameraSessionOpen || hasPendingSurfaces()) {
                helper.closeOverlayCamera(reason);
            }
            helper.closeOverlayWindows(reason);
        }
        cameraOpenPending = false;
        cameraSessionOpen = false;
        cameraOpenRequestId = 0;
        for (PaneState pane : panes) pane.reset();
    }

    static boolean matchesCameraOpenEvent(
            boolean pending, int expectedRequestId, int eventRequestId) {
        return pending && expectedRequestId > 0 && eventRequestId == expectedRequestId;
    }

    private boolean hasPendingSurfaces() {
        for (PaneState pane : panes) if (pane.pendingSurface != null) return true;
        return false;
    }

    private void scheduleCameraRetry(String reason) {
        String blocked = cameraRetryBlockReason();
        if (blocked != null) {
            emit("overlay_camera_retry", "state", "cancelled",
                    "reason", blocked, "trigger", reason);
            return;
        }
        if (!cameraRetry.schedule(reason)) return;
        handler.postDelayed(retryCamera, CAMERA_RETRY_MS);
        emit("overlay_camera_retry", "state", "scheduled",
                "reason", reason, "delay_ms", CAMERA_RETRY_MS);
    }

    private String cameraRetryBlockReason() {
        return cameraRetryBlockReason(
                shutdown, anyLaneEnabled(), suspended || reversePriority, helper != null);
    }

    static String cameraRetryBlockReason(
            boolean shutdown, boolean enabled, boolean suspended, boolean helperAvailable) {
        if (shutdown) return "shutdown";
        if (!enabled) return "overlay_disabled";
        if (suspended) return "overlay_suspended";
        if (!helperAvailable) return "helper_unavailable";
        return null;
    }

    static boolean shouldOverrideCameraRetry(
            boolean retryActive, int activeBlink, int requestedDirection) {
        return retryActive && activeBlink == requestedDirection
                && previewIndexForDirection(requestedDirection) >= 0;
    }

    private void cancelCameraRetry(String reason) {
        String trigger = cameraRetry.cancel();
        if (trigger == null) return;
        handler.removeCallbacks(retryCamera);
        emit("overlay_camera_retry", "state", "cancelled",
                "reason", reason, "trigger", trigger);
    }

    static final class CameraRetryState {
        private String trigger;

        boolean schedule(String reason) {
            if (trigger != null) return false;
            trigger = reason == null ? "unknown" : reason;
            return true;
        }

        String consume() { return clear(); }
        String cancel() { return clear(); }
        boolean active() { return trigger != null; }

        private String clear() {
            String value = trigger;
            trigger = null;
            return value;
        }
    }

    static int readWarningMode(SharedPreferences settings) {
        boolean present = settings.contains(PREF_WARNING_MODE);
        int mode = settings.getInt(PREF_WARNING_MODE, DEFAULT_WARNING_MODE);
        return normalizeWarningMode(present, mode);
    }

    static int normalizeWarningMode(boolean present, int mode) {
        if (!present) return DEFAULT_WARNING_MODE;
        return isWarningMode(mode) ? mode : CameraShellProtocol.WARNING_MODE_OFF;
    }

    static boolean isWarningMode(int mode) {
        return mode >= CameraShellProtocol.WARNING_MODE_OFF
                && mode <= CameraShellProtocol.WARNING_MODE_PULSE;
    }

    static int warningEdge(
            int mode, boolean overlayVisible, int direction,
            boolean leftValid, int leftRaw, boolean rightValid, int rightRaw) {
        if (!isWarningMode(mode) || mode == CameraShellProtocol.WARNING_MODE_OFF
                || !overlayVisible) return CameraShellProtocol.WARNING_EDGE_NONE;
        if (direction == BLINK_LEFT
                && BlindSpotWarningRuntime.isActiveRaw(leftValid, leftRaw)) {
            return CameraShellProtocol.WARNING_EDGE_LEFT;
        }
        if (direction == BLINK_RIGHT
                && BlindSpotWarningRuntime.isActiveRaw(rightValid, rightRaw)) {
            return CameraShellProtocol.WARNING_EDGE_RIGHT;
        }
        return CameraShellProtocol.WARNING_EDGE_NONE;
    }

    private CameraShellProtocol.OverlaySpec buildOverlaySpec(
            CameraProfile profile, int requestId) {
        int target = readTarget(settings, profile);
        int[] displaySize = CameraDisplayTarget.displaySize(context, target);
        CameraDewarpConfig dewarp = CameraDewarpConfig.load(
                settings, CameraDewarpConfig.lensFor(profile));
        DirectCameraCrop crop = DirectCameraCrop.load(settings, profile, dewarp.enabled);
        DirectCameraCrop rawCrop = DirectCameraCrop.load(settings, profile, false);
        int marginX = target == CameraDisplayTarget.TABLET ? dp(16) : 0;
        int topMargin = target == CameraDisplayTarget.TABLET ? dp(36) : 0;
        int bottomMargin = target == CameraDisplayTarget.TABLET ? dp(88) : 0;
        int[] geometry = overlayGeometry(
                displaySize[0], displaySize[1], readScale(settings, profile),
                crop.outputAspect(), readPosition(settings, profile, false),
                readPosition(settings, profile, true),
                marginX, topMargin, bottomMargin);
        return new CameraShellProtocol.OverlaySpec(
                profile.id, requestId, target,
                geometry[2], geometry[3], geometry[0], geometry[1],
                crop.left, crop.top, crop.width, crop.height, crop.aspectMode,
                crop.rotationDegrees, crop.rotationMode, readCornerRadius(settings),
                dewarp, rawCrop);
    }

    static int[] overlayGeometry(
            int displayWidth, int displayHeight, int scalePercent, float aspect,
            float normalizedX, float normalizedY,
            int marginX, int topMargin, int bottomMargin) {
        int safeWidth = Math.max(1, displayWidth);
        int safeHeight = Math.max(1, displayHeight);
        int scale = clamp(scalePercent, MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
        int maxWidth = Math.max(1, safeWidth - Math.max(0, marginX) * 2);
        int maxHeight = Math.max(1, safeHeight - Math.max(0, topMargin)
                - Math.max(0, bottomMargin));
        int[] size = fitAspect(safeWidth * scale / 100, maxWidth, maxHeight, aspect);
        int availableX = Math.max(0, maxWidth - size[0]);
        int availableY = Math.max(0, maxHeight - size[1]);
        return new int[]{
                Math.max(0, marginX) + Math.round(availableX
                        * clamp(normalizedX, 0.0f, 1.0f)),
                Math.max(0, topMargin) + Math.round(availableY
                        * clamp(normalizedY, 0.0f, 1.0f)),
                size[0], size[1]
        };
    }

    private static String scaleKey(CameraProfile profile) {
        if (profile.id == CameraProfile.REAR_LEFT) return PREF_LEFT_SCALE;
        if (profile.id == CameraProfile.REAR_RIGHT) return PREF_RIGHT_SCALE;
        if (profile.id == CameraProfile.FRONT_LEFT) return PREF_FRONT_LEFT_SCALE;
        return PREF_FRONT_RIGHT_SCALE;
    }

    private static String targetKey(CameraProfile profile) {
        if (profile.id == CameraProfile.REAR_LEFT) return PREF_LEFT_TARGET;
        if (profile.id == CameraProfile.REAR_RIGHT) return PREF_RIGHT_TARGET;
        if (profile.id == CameraProfile.FRONT_LEFT) return PREF_FRONT_LEFT_TARGET;
        return PREF_FRONT_RIGHT_TARGET;
    }

    private static String positionKey(CameraProfile profile, boolean vertical) {
        if (profile.id == CameraProfile.REAR_LEFT) return vertical ? PREF_LEFT_Y : PREF_LEFT_X;
        if (profile.id == CameraProfile.REAR_RIGHT) return vertical ? PREF_RIGHT_Y : PREF_RIGHT_X;
        if (profile.id == CameraProfile.FRONT_LEFT) {
            return vertical ? PREF_FRONT_LEFT_Y : PREF_FRONT_LEFT_X;
        }
        return vertical ? PREF_FRONT_RIGHT_Y : PREF_FRONT_RIGHT_X;
    }

    private int nextRequestId() {
        requestSequence = requestSequence == Integer.MAX_VALUE ? 1 : requestSequence + 1;
        return requestSequence;
    }

    private PaneState pane(int cameraId) {
        return CameraProfile.isValid(cameraId) ? panes[cameraId] : null;
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void releaseSurface(Surface surface) {
        if (surface != null) surface.release();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class PaneState {
        final CameraProfile profile;
        boolean expected;
        boolean resolved;
        boolean failed;
        boolean ready;
        boolean visible;
        int requestId;
        int generation;
        int target = -1;
        int warningEdge = CameraShellProtocol.WARNING_EDGE_NONE;
        int warningMode = CameraShellProtocol.WARNING_MODE_OFF;
        Surface pendingSurface;

        PaneState(CameraProfile profile) {
            this.profile = profile;
        }

        void reset() {
            releaseSurface(pendingSurface);
            pendingSurface = null;
            expected = false;
            resolved = false;
            failed = false;
            ready = false;
            visible = false;
            requestId = 0;
            generation = 0;
            target = -1;
            warningEdge = CameraShellProtocol.WARNING_EDGE_NONE;
            warningMode = CameraShellProtocol.WARNING_MODE_OFF;
        }
    }
}
