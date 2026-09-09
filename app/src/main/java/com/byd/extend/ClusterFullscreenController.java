package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

final class ClusterFullscreenController {
    private static final String PREF_ATTEMPTED_SESSION =
            "cluster_fullscreen_attempted_session";

    private final Context context;
    private final SharedPreferences settings;
    private final BiConsumer<String, Object[]> eventSink;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private long awakeSessionId;
    private boolean interactive;
    private boolean stopped;

    ClusterFullscreenController(
            Context context, SharedPreferences settings,
            BiConsumer<String, Object[]> eventSink) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.eventSink = eventSink;
    }

    synchronized void acceptEvent(String line) {
        if (line == null || stopped) return;
        try {
            JSONObject event = new JSONObject(line);
            if (!"shell_power_state".equals(event.optString("kind"))) return;
            awakeSessionId = event.optLong("awake_session_id", 0);
            interactive = event.optBoolean("interactive", false);
            evaluate("power_state");
        } catch (Throwable error) {
            emit("cluster_fullscreen_error", "stage", "parse_power_state",
                    "error", summary(error));
        }
    }

    synchronized void settingsChanged() {
        if (!stopped) evaluate("settings_changed");
    }

    synchronized void shutdown() {
        stopped = true;
        worker.shutdownNow();
    }

    private void evaluate(String trigger) {
        boolean rearEnabled = settings.getBoolean(
                BlindSpotOverlayController.PREF_ENABLED, false);
        boolean frontEnabled = settings.getBoolean(
                BlindSpotOverlayController.PREF_FRONT_ENABLED, false);
        int rearLeftTarget = BlindSpotOverlayController.readTarget(
                settings, CameraProfile.of(CameraProfile.REAR_LEFT));
        int rearRightTarget = BlindSpotOverlayController.readTarget(
                settings, CameraProfile.of(CameraProfile.REAR_RIGHT));
        int frontLeftTarget = BlindSpotOverlayController.readTarget(
                settings, CameraProfile.of(CameraProfile.FRONT_LEFT));
        int frontRightTarget = BlindSpotOverlayController.readTarget(
                settings, CameraProfile.of(CameraProfile.FRONT_RIGHT));
        RearviewMirrorSettings.Settings mirror = new RearviewMirrorSettings(settings).load();
        long attempted = settings.getLong(PREF_ATTEMPTED_SESSION, 0);
        if (!shouldAttempt(hasEnabledClusterTarget(
                        rearEnabled, rearLeftTarget, rearRightTarget,
                        frontEnabled, frontLeftTarget, frontRightTarget,
                        mirror.enabled, mirror.target),
                interactive, awakeSessionId, attempted)) {
            return;
        }
        long session = awakeSessionId;
        settings.edit().putLong(PREF_ATTEMPTED_SESSION, session).commit();
        emit("cluster_fullscreen", "state", "requested",
                "awake_session_id", session, "trigger", trigger,
                "protocol", ClusterFullscreenProtocol.protocolForTest(),
                "operation", ClusterFullscreenProtocol.operationForTest());
        worker.execute(() -> {
            String error = ClusterFullscreenProtocol.dispatch(context);
            emit("cluster_fullscreen", "state", outcome(error),
                    "awake_session_id", session,
                    "error", error,
                    "retry_in_session", false);
        });
    }

    static boolean shouldAttempt(
            boolean enabled, int leftTarget, int rightTarget,
            boolean interactive, long sessionId, long attemptedSessionId) {
        return shouldAttempt(enabled && (leftTarget == CameraDisplayTarget.CLUSTER
                        || rightTarget == CameraDisplayTarget.CLUSTER),
                interactive, sessionId, attemptedSessionId);
    }

    static boolean hasEnabledClusterTarget(
            boolean rearEnabled, int rearLeftTarget, int rearRightTarget,
            boolean frontEnabled, int frontLeftTarget, int frontRightTarget,
            boolean mirrorEnabled, int mirrorTarget) {
        return rearEnabled && (rearLeftTarget == CameraDisplayTarget.CLUSTER
                        || rearRightTarget == CameraDisplayTarget.CLUSTER)
                || frontEnabled && (frontLeftTarget == CameraDisplayTarget.CLUSTER
                        || frontRightTarget == CameraDisplayTarget.CLUSTER)
                || mirrorEnabled && mirrorTarget == CameraDisplayTarget.CLUSTER;
    }

    static boolean shouldAttempt(
            boolean eligible, boolean interactive,
            long sessionId, long attemptedSessionId) {
        return eligible && interactive && sessionId > 0 && sessionId != attemptedSessionId;
    }

    static String outcome(String error) {
        if (error == null || error.isEmpty()) return "success";
        String normalized = error.toLowerCase(java.util.Locale.US);
        return normalized.contains("timeout") || normalized.contains("indeterminate")
                ? "indeterminate" : "failed";
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
