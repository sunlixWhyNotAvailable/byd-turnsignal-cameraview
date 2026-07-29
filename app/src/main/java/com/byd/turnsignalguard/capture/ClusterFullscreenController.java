package com.byd.turnsignalguard.capture;

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
        boolean enabled = settings.getBoolean(BlindSpotOverlayController.PREF_ENABLED, false);
        int leftTarget = BlindSpotOverlayController.readTarget(settings, false);
        int rightTarget = BlindSpotOverlayController.readTarget(settings, true);
        long attempted = settings.getLong(PREF_ATTEMPTED_SESSION, 0);
        if (!shouldAttempt(enabled, leftTarget, rightTarget,
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
        return enabled && interactive && sessionId > 0 && sessionId != attemptedSessionId
                && (leftTarget == CameraDisplayTarget.CLUSTER
                        || rightTarget == CameraDisplayTarget.CLUSTER);
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
