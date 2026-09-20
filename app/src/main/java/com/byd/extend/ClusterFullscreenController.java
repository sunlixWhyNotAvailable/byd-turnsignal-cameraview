package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.provider.Settings;
import android.view.Display;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

final class ClusterFullscreenController {
    private static final String PREF_ATTEMPTED_SESSION =
            "cluster_fullscreen_attempted_session";
    private static final String PREF_ATTEMPTED_BOOT = "cluster_fullscreen_attempted_boot";
    private static final String PREF_ATTEMPTED_DISPLAY = "cluster_fullscreen_attempted_display";

    private final Context context;
    private final SharedPreferences settings;
    private final BiConsumer<String, Object[]> eventSink;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final DisplayManager displays;
    private final long bootId;
    private long awakeSessionId = -1;
    private boolean interactive;
    private boolean displayObserved;
    private boolean displayWasReady;
    private String lastGate = "";
    private boolean stopped;
    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { displayChanged(); }
                @Override public void onDisplayRemoved(int id) { displayChanged(); }
                @Override public void onDisplayChanged(int id) { displayChanged(); }
            };

    ClusterFullscreenController(
            Context context, SharedPreferences settings, Handler handler,
            BiConsumer<String, Object[]> eventSink) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.eventSink = eventSink;
        bootId = readBootId();
        displays = (DisplayManager) this.context.getSystemService(Context.DISPLAY_SERVICE);
        if (displays != null) displays.registerDisplayListener(displayListener, handler);
    }

    synchronized void acceptEvent(String line) {
        if (line == null || stopped) return;
        try {
            JSONObject event = new JSONObject(line);
            if (!"shell_power_state".equals(event.optString("kind"))) return;
            awakeSessionId = event.optLong("awake_session_id", -1);
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

    private synchronized void displayChanged() {
        if (!stopped) evaluate("display_changed");
    }

    synchronized void shutdown() {
        stopped = true;
        if (displays != null) displays.unregisterDisplayListener(displayListener);
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
        Display display = CameraDisplayTarget.resolve(context, CameraDisplayTarget.CLUSTER);
        int displayId = display == null ? -1 : display.getDisplayId();
        int displayState = display == null ? Display.STATE_UNKNOWN : display.getState();
        boolean displayReady = displayUsable(display != null, displayState);
        if (!displayReady && (!displayObserved || displayWasReady)) {
            // Re-arm after destination sleep/removal, including across service restarts.
            settings.edit().remove(PREF_ATTEMPTED_SESSION).apply();
        }
        displayObserved = true;
        displayWasReady = displayReady;
        long attempted = settings.getLong(PREF_ATTEMPTED_SESSION, -1);
        long attemptedBoot = settings.getLong(PREF_ATTEMPTED_BOOT, Long.MIN_VALUE);
        int attemptedDisplay = settings.getInt(PREF_ATTEMPTED_DISPLAY, -1);
        boolean eligible = hasEnabledClusterTarget(
                        rearEnabled, rearLeftTarget, rearRightTarget,
                        frontEnabled, frontLeftTarget, frontRightTarget,
                        mirror.enabled, mirror.target);
        boolean request = shouldAttempt(eligible, displayReady, awakeSessionId, attempted,
                bootId, attemptedBoot, displayId, attemptedDisplay);
        String gate = !eligible ? "no_enabled_cluster_target"
                : !displayReady ? "waiting_for_cluster_display"
                : awakeSessionId < 0 ? "waiting_for_power_state"
                : request ? "request" : "already_attempted";
        if (!gate.equals(lastGate)) {
            lastGate = gate;
            emit("cluster_fullscreen_gate", "reason", gate, "trigger", trigger,
                    "boot_id", bootId, "awake_session_id", awakeSessionId,
                    "shell_interactive", interactive, "display_id", displayId,
                    "display_state", displayState, "mirror_hidden", mirror.manualHidden);
        }
        if (!request) {
            return;
        }
        long session = awakeSessionId;
        settings.edit().putLong(PREF_ATTEMPTED_SESSION, session)
                .putLong(PREF_ATTEMPTED_BOOT, bootId)
                .putInt(PREF_ATTEMPTED_DISPLAY, displayId).commit();
        emit("cluster_fullscreen", "state", "requested",
                "awake_session_id", session, "trigger", trigger,
                "boot_id", bootId, "display_id", displayId,
                "display_state", displayState, "shell_interactive", interactive,
                "mirror_hidden", mirror.manualHidden,
                "protocol", ClusterFullscreenProtocol.protocolForTest(),
                "operation", ClusterFullscreenProtocol.operationForTest());
        worker.execute(() -> {
            String error = ClusterFullscreenProtocol.dispatch(context);
            emit("cluster_fullscreen", "state", outcome(error),
                    "awake_session_id", session,
                    "boot_id", bootId, "display_id", displayId,
                    "error", error,
                    "retry_in_session", false);
        });
    }

    static boolean shouldAttempt(
            boolean enabled, int leftTarget, int rightTarget,
            boolean displayReady, long sessionId, long attemptedSessionId) {
        return shouldAttempt(enabled && (leftTarget == CameraDisplayTarget.CLUSTER
                        || rightTarget == CameraDisplayTarget.CLUSTER),
                displayReady, sessionId, attemptedSessionId);
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
            boolean eligible, boolean displayReady,
            long sessionId, long attemptedSessionId) {
        return eligible && displayReady && sessionId >= 0 && sessionId != attemptedSessionId;
    }

    static boolean displayUsable(boolean present, int state) {
        // Match camera preparation: an OEM UNKNOWN state is not a confirmed OFF state.
        return present && state != Display.STATE_OFF;
    }

    static boolean shouldAttempt(boolean eligible, boolean displayReady,
            long sessionId, long attemptedSessionId, long bootId, long attemptedBootId,
            int displayId, int attemptedDisplayId) {
        return shouldAttempt(eligible, displayReady, sessionId,
                bootId == attemptedBootId && displayId == attemptedDisplayId
                        ? attemptedSessionId : -1);
    }

    private long readBootId() {
        try {
            int count = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
            if (count >= 0) return count;
        } catch (RuntimeException error) {
            emit("cluster_fullscreen_error", "stage", "read_boot_count", "error", summary(error));
        }
        // If an OEM omits BOOT_COUNT, deduplicate within this controller lifetime only.
        return -System.currentTimeMillis();
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
