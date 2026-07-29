package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.function.BiConsumer;

final class BlindSpotOverlayController {
    static final String PREF_ENABLED = "camera_enabled";
    static final String PREF_MIN_SPEED = "camera_min_speed_kph";
    static final String PREF_SCALE = "camera_overlay_scale_percent";
    static final String PREF_LEFT_SCALE = "camera_left_scale_percent";
    static final String PREF_RIGHT_SCALE = "camera_right_scale_percent";
    static final String PREF_LEFT_TARGET = "camera_left_display_target";
    static final String PREF_RIGHT_TARGET = "camera_right_display_target";
    static final String PREF_LEFT_POSITION = "camera_left_position";
    static final String PREF_RIGHT_POSITION = "camera_right_position";
    static final String PREF_LEFT_X = "camera_left_x";
    static final String PREF_LEFT_Y = "camera_left_y";
    static final String PREF_RIGHT_X = "camera_right_x";
    static final String PREF_RIGHT_Y = "camera_right_y";
    static final String PREF_WARNING_MODE = "camera_bsd_warning_mode";
    static final int DEFAULT_MIN_SPEED_KPH = 20;
    static final int DEFAULT_SCALE_PERCENT = 36;
    static final int MIN_SCALE_PERCENT = 20;
    static final int MAX_SCALE_PERCENT = 60;
    static final int DEFAULT_LEFT_POSITION = 0;
    static final int DEFAULT_RIGHT_POSITION = 2;
    static final int DEFAULT_WARNING_MODE = CameraShellProtocol.WARNING_MODE_PULSE;

    private static final int BLINK_LEFT = 2;
    private static final int BLINK_RIGHT = 4;
    private static final String DIRECT_CAMERA_TAG = "pano_h";
    private static final int LEFT_PREVIEW_INDEX = 2;
    private static final int RIGHT_PREVIEW_INDEX = 3;
    private static final long STATE_STALE_MS = 750;
    private static final long SURFACE_TIMEOUT_MS = 8_000;
    private static final long FIRST_FRAME_TIMEOUT_MS = 3_000;
    private static final long CAMERA_RETRY_MS = 3_000;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences settings;
    private final WindowManager windows;
    private final BiConsumer<String, Object[]> eventSink;
    private final Runnable staleState = () -> {
        stateValid = false;
        hide("vehicle_state_stale");
    };
    private final Runnable surfaceTimeout = this::handleSurfaceTimeout;
    private final Runnable firstFrameTimeout = this::handleFirstFrameTimeout;
    private final Runnable retryCamera = this::retryCameraOpen;

    private CameraHelperMain.HelperBinder helper;
    private boolean suspended;
    private boolean reversePriority;
    private boolean uiHidden;
    private boolean cameraReady;
    private boolean stateValid;
    private boolean visible;
    private boolean overlayPrepared;
    private final CameraRetryState cameraRetry = new CameraRetryState();
    private boolean shutdown;
    private int blink = -1;
    private float speedKph = Float.NaN;
    private int requestSequence;
    private int activeRequestId;
    private int surfaceGeneration;
    private int requestedPreviewIndex = -1;
    private int displayedPreviewIndex = -1;
    private int preparedDirection = -1;
    private int preparedTarget = -1;
    private boolean displaySwitchPending;
    private boolean leftBsdValid;
    private boolean rightBsdValid;
    private int leftBsdRaw = -1;
    private int rightBsdRaw = -1;
    private int appliedWarningRequestId;
    private int appliedWarningGeneration;
    private int appliedWarningEdge;
    private int appliedWarningMode;

    private void handleSurfaceTimeout() {
        cameraUnavailable("overlay_surface_timeout", requestedPreviewIndex);
    }

    private void handleFirstFrameTimeout() {
        cameraUnavailable("first_frame_timeout", requestedPreviewIndex);
    }

    BlindSpotOverlayController(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.eventSink = eventSink;
        settings = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        migrateOverlayPreferences(settings);
        windows = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    static int directionToShow(boolean valid, int blink, float speedKph, int minSpeedKph) {
        if (!valid || !Float.isFinite(speedKph)
                || minSpeedKph < 0 || minSpeedKph > 300 || speedKph < minSpeedKph) {
            return 0;
        }
        return blink == BLINK_LEFT || blink == BLINK_RIGHT ? blink : 0;
    }

    static float readPosition(
            SharedPreferences settings, boolean right, boolean vertical) {
        String key = right
                ? (vertical ? PREF_RIGHT_Y : PREF_RIGHT_X)
                : (vertical ? PREF_LEFT_Y : PREF_LEFT_X);
        if (settings.contains(key)) return clamp(settings.getFloat(key, 0.0f), 0.0f, 1.0f);
        int legacy = settings.getInt(right ? PREF_RIGHT_POSITION : PREF_LEFT_POSITION,
                right ? DEFAULT_RIGHT_POSITION : DEFAULT_LEFT_POSITION);
        return legacyPosition(legacy, vertical);
    }

    static float legacyPosition(int position, boolean vertical) {
        int safe = clamp(position, 0, 8);
        return (vertical ? safe / 3 : safe % 3) / 2.0f;
    }

    static void migrateOverlayPreferences(SharedPreferences settings) {
        int sharedScale = migratedScale(false, 0,
                settings.getInt(PREF_SCALE, DEFAULT_SCALE_PERCENT));
        SharedPreferences.Editor editor = null;
        if (!settings.contains(PREF_LEFT_SCALE)) {
            editor = settings.edit().putInt(PREF_LEFT_SCALE, sharedScale);
        }
        if (!settings.contains(PREF_RIGHT_SCALE)) {
            if (editor == null) editor = settings.edit();
            editor.putInt(PREF_RIGHT_SCALE, sharedScale);
        }
        if (!settings.contains(PREF_LEFT_TARGET)) {
            if (editor == null) editor = settings.edit();
            editor.putInt(PREF_LEFT_TARGET, CameraDisplayTarget.TABLET);
        }
        if (!settings.contains(PREF_RIGHT_TARGET)) {
            if (editor == null) editor = settings.edit();
            editor.putInt(PREF_RIGHT_TARGET, CameraDisplayTarget.TABLET);
        }
        if (editor != null) editor.apply();
    }

    static int migratedScale(boolean sidePresent, int sideScale, int sharedScale) {
        return clamp(sidePresent ? sideScale : sharedScale,
                MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
    }

    static int readScale(SharedPreferences settings, boolean right) {
        return clamp(settings.getInt(right ? PREF_RIGHT_SCALE : PREF_LEFT_SCALE,
                settings.getInt(PREF_SCALE, DEFAULT_SCALE_PERCENT)),
                MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
    }

    static int readTarget(SharedPreferences settings, boolean right) {
        int target = settings.getInt(right ? PREF_RIGHT_TARGET : PREF_LEFT_TARGET,
                CameraDisplayTarget.TABLET);
        return CameraDisplayTarget.isValid(target) ? target : CameraDisplayTarget.TABLET;
    }

    static int previewIndexForDirection(int direction) {
        if (direction == BLINK_LEFT) return LEFT_PREVIEW_INDEX;
        if (direction == BLINK_RIGHT) return RIGHT_PREVIEW_INDEX;
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
            if (value) destroyWindow("preview_handoff");
            else applySettingsOnMain();
        });
    }

    void setReversePriority(boolean value) {
        handler.post(() -> {
            if (reversePriority == value) return;
            reversePriority = value;
            if (value) destroyWindow("reverse_priority");
            else applySettingsOnMain();
        });
    }

    void setUiHidden(boolean value) {
        handler.post(() -> {
            if (uiHidden == value) return;
            uiHidden = value;
            if (value) hide("activity_visible");
            else evaluate();
        });
    }

    void applySettings() {
        handler.post(this::applySettingsOnMain);
    }

    void applyWarningSettings() {
        handler.post(() -> applyWarning("warning_setting_changed"));
    }

    void acceptEvent(String line) {
        if (line == null) return;
        try {
            JSONObject event = new JSONObject(line);
            String kind = event.optString("kind");
            if ("vehicle_state".equals(kind)) {
                boolean valid = event.optBoolean("valid");
                int nextBlink = event.optInt("blink", -1);
                float nextSpeed = valid ? (float) event.optDouble("speed_kph", Double.NaN)
                        : Float.NaN;
                handler.post(() -> acceptVehicleState(valid, nextBlink, nextSpeed));
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
                int previewIndex = event.optInt("preview_index", -1);
                handler.post(() -> cameraOpened(previewIndex));
            } else if ("camera_error".equals(kind)
                    && "helper".equals(event.optString("source"))
                    && CameraHelperMain.CAMERA_OWNER_OVERLAY.equals(
                            event.optString("camera_owner"))
                    && DIRECT_CAMERA_TAG.equals(event.optString("camera_tag"))) {
                int previewIndex = event.optInt("preview_index", -1);
                String stage = event.optString("stage", kind);
                handler.post(() -> cameraUnavailable(stage, previewIndex));
            } else if ("camera_overlay_first_frame".equals(kind)) {
                int requestId = event.optInt("request_id", -1);
                int generation = event.optInt("surface_generation", -1);
                handler.post(() -> firstFrameAvailable(requestId, generation));
            } else if ("camera_overlay_surface".equals(kind)
                    && "destroyed".equals(event.optString("state"))) {
                int requestId = event.optInt("request_id", -1);
                handler.post(() -> {
                    if (requestId == activeRequestId) {
                        cameraUnavailable("overlay_surface_destroyed", requestedPreviewIndex);
                    }
                });
            } else if ("camera_overlay_error".equals(kind)) {
                String stage = event.optString("stage", kind);
                int requestId = event.optInt("request_id", -1);
                handler.post(() -> {
                    if (requestId <= 0 || requestId == activeRequestId) {
                        cameraUnavailable(stage, requestedPreviewIndex);
                    }
                });
            }
        } catch (Throwable error) {
            emit("overlay_event_parse_error", "error", summary(error));
        }
    }

    void shutdown() {
        shutdown = true;
        handler.removeCallbacks(staleState);
        cancelCameraRetry("overlay_shutdown");
        destroyWindow("overlay_shutdown");
        helper = null;
    }

    private void applySettingsOnMain() {
        boolean enabled = settings.getBoolean(PREF_ENABLED, false);
        if (shutdown || !enabled || suspended || reversePriority
                || helper == null || windows == null) {
            destroyWindow(!enabled ? "overlay_disabled"
                    : shutdown ? "overlay_shutdown"
                    : suspended ? "overlay_suspended"
                    : reversePriority ? "reverse_priority"
                    : helper == null ? "helper_unavailable" : "window_manager_unavailable");
            return;
        }
        preparedDirection = -1;
        requestDirection(desiredDirection());
        evaluate();
    }

    private void destroyWindow(String reason) {
        cancelCameraRetry(reason);
        handler.removeCallbacks(staleState);
        handler.removeCallbacks(surfaceTimeout);
        handler.removeCallbacks(firstFrameTimeout);
        hide(reason);
        if (helper != null) {
            if (cameraReady || requestedPreviewIndex != -1 || displayedPreviewIndex != -1) {
                helper.closeOverlayCamera(reason);
            }
            if (overlayPrepared || activeRequestId != 0) helper.closeOverlayWindow(reason);
        }
        cameraReady = false;
        overlayPrepared = false;
        visible = false;
        activeRequestId = 0;
        surfaceGeneration = 0;
        requestedPreviewIndex = -1;
        displayedPreviewIndex = -1;
        preparedDirection = -1;
        preparedTarget = -1;
        displaySwitchPending = false;
        resetAppliedWarning();
        emit("overlay_window", "state", "remove_requested", "reason", reason);
    }

    private void acceptVehicleState(boolean valid, int nextBlink, float nextSpeed) {
        stateValid = valid;
        blink = nextBlink;
        speedKph = nextSpeed;
        handler.removeCallbacks(staleState);
        if (nextBlink == BLINK_LEFT || nextBlink == BLINK_RIGHT) {
            handler.postDelayed(staleState, STATE_STALE_MS);
        }
        evaluate();
    }

    private void acceptBsdState(
            boolean nextLeftValid, int nextLeftRaw,
            boolean nextRightValid, int nextRightRaw) {
        leftBsdValid = nextLeftValid && BlindSpotWarningRuntime.isValidRaw(nextLeftRaw);
        rightBsdValid = nextRightValid && BlindSpotWarningRuntime.isValidRaw(nextRightRaw);
        leftBsdRaw = leftBsdValid ? nextLeftRaw : -1;
        rightBsdRaw = rightBsdValid ? nextRightRaw : -1;
        emit("overlay_bsd_state",
                "left_valid", leftBsdValid,
                "left_raw", leftBsdValid ? leftBsdRaw : "unknown",
                "right_valid", rightBsdValid,
                "right_raw", rightBsdValid ? rightBsdRaw : "unknown");
        applyWarning("bsd_state");
    }

    private void evaluate() {
        if (!overlayPrepared || suspended || reversePriority) {
            hide("overlay_unavailable");
            return;
        }
        int activeDirection = blink == BLINK_LEFT || blink == BLINK_RIGHT ? blink : 0;
        if (activeDirection != 0) requestDirection(activeDirection);
        int direction = directionToShow(stateValid, blink, speedKph,
                settings.getInt(PREF_MIN_SPEED, DEFAULT_MIN_SPEED_KPH));
        if (direction == 0) {
            hide("turn_or_speed_condition_false");
            return;
        }
        int previewIndex = previewIndexForDirection(direction);
        if (!cameraReady || displayedPreviewIndex != previewIndex) {
            requestDirection(direction);
            hide("direct_camera_switch_pending");
            return;
        }
        if (uiHidden) {
            hide("activity_visible");
            return;
        }
        show(direction);
    }

    private void requestDirection(int direction) {
        int previewIndex = previewIndexForDirection(direction);
        if (cameraRetry.active()) {
            if (shouldOverrideCameraRetry(true, blink, direction)) {
                cancelCameraRetry("active_turn_override");
            }
            else return;
        }
        if (helper == null || windows == null || previewIndex < 0) return;
        int target = readTarget(settings, direction == BLINK_RIGHT);
        if (preparedDirection == direction && requestedPreviewIndex == previewIndex
                && preparedTarget == target && activeRequestId != 0) {
            return;
        }
        if (activeRequestId != 0 && preparedTarget != -1 && preparedTarget != target) {
            if (displaySwitchPending) return;
            if (visible && surfaceGeneration > 0) {
                displaySwitchPending = true;
                int hidingRequestId = activeRequestId;
                int hidingGeneration = surfaceGeneration;
                visible = false;
                resetAppliedWarning();
                helper.setOverlayWindowVisible(
                        hidingRequestId, hidingGeneration, false,
                        this::completeDisplaySwitch);
                emit("overlay_visibility", "visible", false,
                        "reason", "overlay_display_changed",
                        "request_id", hidingRequestId);
                return;
            }
            resetForDisplaySwitch();
        }
        if (visible) hide("overlay_geometry_changed");
        int requestId = nextRequestId();
        CameraShellProtocol.OverlaySpec spec = buildOverlaySpec(direction, requestId);
        preparedDirection = direction;
        preparedTarget = spec.target;
        requestedPreviewIndex = previewIndex;
        displayedPreviewIndex = -1;
        activeRequestId = requestId;
        surfaceGeneration = 0;
        cameraReady = false;
        resetAppliedWarning();
        overlayPrepared = true;
        handler.removeCallbacks(surfaceTimeout);
        handler.removeCallbacks(firstFrameTimeout);
        helper.prepareOverlayWindow(
                spec, this::overlaySurfaceAvailable, () -> overlayPrepared(requestId));
        emit("overlay_geometry", "request_id", requestId,
                "direction", direction == BLINK_RIGHT ? "right" : "left",
                "target", CameraDisplayTarget.name(spec.target),
                "width", spec.width, "height", spec.height,
                "x", spec.x, "y", spec.y);
    }

    private void overlayPrepared(int requestId) {
        if (requestId != activeRequestId || surfaceGeneration > 0) return;
        handler.removeCallbacks(surfaceTimeout);
        handler.postDelayed(surfaceTimeout, SURFACE_TIMEOUT_MS);
    }

    private void completeDisplaySwitch() {
        displaySwitchPending = false;
        if (shutdown || suspended || helper == null
                || !settings.getBoolean(PREF_ENABLED, false)) {
            return;
        }
        resetForDisplaySwitch();
        requestDirection(desiredDirection());
        evaluate();
    }

    private void resetForDisplaySwitch() {
        if (helper != null && (cameraReady
                || requestedPreviewIndex != -1 || displayedPreviewIndex != -1)) {
            helper.closeOverlayCamera("overlay_display_changed");
        }
        cameraReady = false;
        overlayPrepared = false;
        activeRequestId = 0;
        surfaceGeneration = 0;
        requestedPreviewIndex = -1;
        displayedPreviewIndex = -1;
        preparedDirection = -1;
        resetAppliedWarning();
    }

    private void overlaySurfaceAvailable(TurnSignalController.OverlaySurface value) {
        if (value.requestId != activeRequestId || value.surface == null
                || !value.surface.isValid()) {
            if (value.surface != null) value.surface.release();
            return;
        }
        handler.removeCallbacks(surfaceTimeout);
        surfaceGeneration = value.surfaceGeneration;
        int previewIndex = requestedPreviewIndex;
        try {
            helper.openOverlayDirectCamera(value.surface, DIRECT_CAMERA_TAG, previewIndex);
        } catch (Throwable error) {
            emit("camera_overlay_error", "stage", "open_direct_camera",
                    "request_id", activeRequestId,
                    "surface_generation", surfaceGeneration,
                    "error", summary(error));
            cameraUnavailable("open_direct_camera", previewIndex);
            return;
        }
        emit("overlay_camera_request", "request_id", activeRequestId,
                "surface_generation", surfaceGeneration,
                "camera_tag", DIRECT_CAMERA_TAG,
                "preview_index", previewIndex,
                "direction", preparedDirection == BLINK_RIGHT ? "right" : "left");
    }

    private int desiredDirection() {
        return blink == BLINK_RIGHT ? BLINK_RIGHT : BLINK_LEFT;
    }

    private void cameraOpened(int previewIndex) {
        if (previewIndex != requestedPreviewIndex || activeRequestId == 0
                || surfaceGeneration <= 0) {
            return;
        }
        cancelCameraRetry("camera_opened");
        displayedPreviewIndex = previewIndex;
        cameraReady = false;
        handler.removeCallbacks(firstFrameTimeout);
        handler.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS);
        helper.armOverlayFirstFrame(activeRequestId, surfaceGeneration);
        emit("overlay_camera_waiting_frame", "request_id", activeRequestId,
                "surface_generation", surfaceGeneration,
                "camera_tag", DIRECT_CAMERA_TAG,
                "preview_index", previewIndex,
                "timeout_ms", FIRST_FRAME_TIMEOUT_MS);
    }

    private void firstFrameAvailable(int requestId, int generation) {
        if (!isMatchingFirstFrame(
                requestId, generation, activeRequestId, surfaceGeneration,
                displayedPreviewIndex, requestedPreviewIndex)) {
            return;
        }
        handler.removeCallbacks(firstFrameTimeout);
        cameraReady = true;
        emit("overlay_camera_ready", "request_id", requestId,
                "surface_generation", generation,
                "camera_tag", DIRECT_CAMERA_TAG,
                "preview_index", displayedPreviewIndex,
                "readiness", "first_frame");
        evaluate();
    }

    private void cameraUnavailable(String reason, int previewIndex) {
        if (previewIndex >= 0 && requestedPreviewIndex >= 0
                && previewIndex != requestedPreviewIndex) {
            emit("overlay_camera_retry", "state", "ignored",
                    "reason", "preview_mismatch",
                    "preview_index", previewIndex,
                    "requested_preview_index", requestedPreviewIndex);
            return;
        }
        handler.removeCallbacks(surfaceTimeout);
        handler.removeCallbacks(firstFrameTimeout);
        hide(reason);
        if (helper != null && (requestedPreviewIndex != -1 || displayedPreviewIndex != -1)) {
            helper.closeOverlayCamera(reason);
        }
        cameraReady = false;
        activeRequestId = 0;
        surfaceGeneration = 0;
        requestedPreviewIndex = -1;
        displayedPreviewIndex = -1;
        preparedDirection = -1;
        preparedTarget = -1;
        displaySwitchPending = false;
        scheduleCameraRetry(reason);
    }

    private void scheduleCameraRetry(String reason) {
        String blocked = cameraRetryBlockReason();
        if (blocked != null) {
            emit("overlay_camera_retry", "state", "cancelled", "reason", blocked,
                    "trigger", reason);
            return;
        }
        if (!cameraRetry.schedule(reason)) return;
        handler.postDelayed(retryCamera, CAMERA_RETRY_MS);
        emit("overlay_camera_retry", "state", "scheduled", "reason", reason,
                "delay_ms", CAMERA_RETRY_MS,
                "preview_index", previewIndexForDirection(desiredDirection()));
    }

    private void retryCameraOpen() {
        String trigger = cameraRetry.consume();
        if (trigger == null) return;
        String blocked = cameraRetryBlockReason();
        if (blocked != null) {
            emit("overlay_camera_retry", "state", "cancelled", "reason", blocked,
                    "trigger", trigger);
            return;
        }
        int direction = desiredDirection();
        emit("overlay_camera_retry", "state", "attempt",
                "reason", trigger,
                "preview_index", previewIndexForDirection(direction));
        requestDirection(direction);
    }

    private String cameraRetryBlockReason() {
        return cameraRetryBlockReason(
                shutdown, settings.getBoolean(PREF_ENABLED, false), suspended, helper != null);
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
        emit("overlay_camera_retry", "state", "cancelled", "reason", reason,
                "trigger", trigger);
    }

    static final class CameraRetryState {
        private String trigger;

        boolean schedule(String reason) {
            if (trigger != null) return false;
            trigger = reason == null ? "unknown" : reason;
            return true;
        }

        String consume() {
            return clear();
        }

        String cancel() {
            return clear();
        }

        boolean active() {
            return trigger != null;
        }

        private String clear() {
            String value = trigger;
            trigger = null;
            return value;
        }
    }

    private void show(int direction) {
        if (displaySwitchPending || helper == null || !overlayPrepared || !cameraReady
                || activeRequestId == 0 || surfaceGeneration <= 0) {
            return;
        }
        if (preparedDirection != direction) {
            requestDirection(direction);
            return;
        }
        if (visible) {
            applyWarning("overlay_already_visible");
            return;
        }
        visible = true;
        applyWarning("overlay_show");
        helper.setOverlayWindowVisible(activeRequestId, surfaceGeneration, true);
        emit("overlay_visibility", "visible", true,
                "request_id", activeRequestId,
                "direction", direction == BLINK_LEFT ? "left" : "right",
                "speed_kph", speedKph);
    }

    private void hide(String reason) {
        boolean wasVisible = visible;
        if (wasVisible && helper != null && activeRequestId != 0) {
            helper.setOverlayWindowVisible(activeRequestId, surfaceGeneration, false);
        }
        visible = false;
        resetAppliedWarning();
        if (wasVisible) emit("overlay_visibility", "visible", false, "reason", reason);
    }

    private void applyWarning(String reason) {
        if (helper == null || activeRequestId <= 0 || surfaceGeneration <= 0) return;
        int configuredMode = readWarningMode(settings);
        int edge = warningEdge(
                configuredMode, visible, preparedDirection,
                leftBsdValid, leftBsdRaw, rightBsdValid, rightBsdRaw);
        int mode = edge == CameraShellProtocol.WARNING_EDGE_NONE
                ? CameraShellProtocol.WARNING_MODE_OFF : configuredMode;
        if (appliedWarningRequestId == activeRequestId
                && appliedWarningGeneration == surfaceGeneration
                && appliedWarningEdge == edge && appliedWarningMode == mode) {
            return;
        }
        helper.setOverlayWindowWarning(
                activeRequestId, surfaceGeneration, edge, mode);
        appliedWarningRequestId = activeRequestId;
        appliedWarningGeneration = surfaceGeneration;
        appliedWarningEdge = edge;
        appliedWarningMode = mode;
        emit("overlay_warning_decision",
                "request_id", activeRequestId,
                "surface_generation", surfaceGeneration,
                "active", edge != CameraShellProtocol.WARNING_EDGE_NONE,
                "edge", warningEdgeName(edge),
                "mode", warningModeName(mode),
                "reason", reason);
    }

    private void resetAppliedWarning() {
        appliedWarningRequestId = 0;
        appliedWarningGeneration = 0;
        appliedWarningEdge = CameraShellProtocol.WARNING_EDGE_NONE;
        appliedWarningMode = CameraShellProtocol.WARNING_MODE_OFF;
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
                || !overlayVisible) {
            return CameraShellProtocol.WARNING_EDGE_NONE;
        }
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

    private static String warningEdgeName(int edge) {
        if (edge == CameraShellProtocol.WARNING_EDGE_LEFT) return "left";
        if (edge == CameraShellProtocol.WARNING_EDGE_RIGHT) return "right";
        return "none";
    }

    private static String warningModeName(int mode) {
        if (mode == CameraShellProtocol.WARNING_MODE_CONSTANT) return "constant";
        if (mode == CameraShellProtocol.WARNING_MODE_PULSE) return "pulse";
        return "off";
    }

    private CameraShellProtocol.OverlaySpec buildOverlaySpec(int direction, int requestId) {
        boolean right = direction == BLINK_RIGHT;
        int target = readTarget(settings, right);
        int[] displaySize = CameraDisplayTarget.displaySize(context, target);
        DirectCameraCrop crop = loadDirectCrop(right);
        float x = readPosition(settings, right, false);
        float y = readPosition(settings, right, true);
        int marginX = target == CameraDisplayTarget.TABLET ? dp(16) : 0;
        int topMargin = target == CameraDisplayTarget.TABLET ? dp(36) : 0;
        int bottomMargin = target == CameraDisplayTarget.TABLET ? dp(88) : 0;
        int[] geometry = overlayGeometry(
                displaySize[0], displaySize[1], readScale(settings, right),
                crop.outputAspect(), x, y, marginX, topMargin, bottomMargin);
        return new CameraShellProtocol.OverlaySpec(
                requestId, target, geometry[2], geometry[3], geometry[0], geometry[1],
                crop.left, crop.top, crop.width, crop.height, crop.aspectMode);
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

    private DirectCameraCrop loadDirectCrop(boolean right) {
        DirectCameraCrop fallback = DirectCameraCrop.defaultFor(right);
        return DirectCameraCrop.of(
                settings.getFloat(right
                        ? DirectCameraCrop.PREF_RIGHT_X : DirectCameraCrop.PREF_LEFT_X,
                        fallback.left),
                settings.getFloat(right
                        ? DirectCameraCrop.PREF_RIGHT_Y : DirectCameraCrop.PREF_LEFT_Y,
                        fallback.top),
                settings.getFloat(right
                        ? DirectCameraCrop.PREF_RIGHT_WIDTH : DirectCameraCrop.PREF_LEFT_WIDTH,
                        fallback.width),
                settings.getFloat(right
                        ? DirectCameraCrop.PREF_RIGHT_HEIGHT : DirectCameraCrop.PREF_LEFT_HEIGHT,
                        fallback.height),
                settings.getInt(right
                        ? DirectCameraCrop.PREF_RIGHT_ASPECT : DirectCameraCrop.PREF_LEFT_ASPECT,
                        DirectCameraCrop.ASPECT_FOUR_THREE));
    }

    private int nextRequestId() {
        requestSequence = requestSequence == Integer.MAX_VALUE ? 1 : requestSequence + 1;
        return requestSequence;
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
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
}
