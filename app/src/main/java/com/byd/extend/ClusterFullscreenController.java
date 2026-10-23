package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

final class ClusterFullscreenController {
    // Old attempt markers cannot distinguish a successful command from a failed bind.
    private static final String PREF_SUCCEEDED_SESSION = "cluster_fullscreen_succeeded_session";
    private static final String PREF_SUCCEEDED_BOOT = "cluster_fullscreen_succeeded_boot";
    private static final String PREF_SUCCEEDED_DISPLAY = "cluster_fullscreen_succeeded_display";
    private static final long[] RETRY_DELAYS_MS = {1_000, 3_000, 10_000};

    private final Context context;
    private final SharedPreferences settings;
    private final BiConsumer<String, Object[]> eventSink;
    private final Handler handler;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final DisplayManager displays;
    private final long bootId;
    private long awakeSessionId = -1;
    private boolean interactive;
    private boolean vehiclePowerKnown;
    private boolean vehiclePowerOff;
    private int consecutiveFailedPowerReads;
    private long vehiclePowerSourceEpoch = -1;
    private long vehiclePowerRevision = -1;
    private boolean displayObserved;
    private boolean displayWasReady;
    private String lastGate = "";
    private boolean stopped;
    private long generation, trackedSession = -1;
    private int trackedDisplay = -1, attempts;
    private boolean eligibleNow, inFlight, terminal;
    private Runnable pendingRetry;
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
        this.handler = handler;
        bootId = readBootId();
        displays = (DisplayManager) this.context.getSystemService(Context.DISPLAY_SERVICE);
        if (displays != null) displays.registerDisplayListener(displayListener, handler);
    }

    synchronized void acceptEvent(String line) {
        if (line == null || stopped) return;
        try {
            JSONObject event = new JSONObject(line);
            String kind = event.optString("kind");
            if ("shell_power_state".equals(kind)) {
                awakeSessionId = event.optLong("awake_session_id", -1);
                interactive = event.optBoolean("interactive", false);
                evaluate("power_state");
            } else if ("camera_vehicle_power_state".equals(kind)) {
                Object knownValue = event.opt("known");
                Object offValue = event.opt("off");
                Object failureCountValue = event.opt("consecutive_failed_reads");
                Object sourceEpochValue = event.opt("source_epoch");
                Object revisionValue = event.opt("revision");
                if (!(knownValue instanceof Boolean) || !(offValue instanceof Boolean)
                        || !(failureCountValue instanceof Number)
                        || !(sourceEpochValue instanceof Number)
                        || !(revisionValue instanceof Number)) {
                    throw new IllegalArgumentException("camera Power state fields missing");
                }
                boolean known = (Boolean) knownValue;
                boolean off = (Boolean) offValue;
                int failures = ((Number) failureCountValue).intValue();
                long sourceEpoch = ((Number) sourceEpochValue).longValue();
                long revision = ((Number) revisionValue).longValue();
                if (sourceEpoch < 0 || revision < 0 || off && !known
                        || failures < 0 || failures > 5
                        || known && failures == 5) {
                    throw new IllegalArgumentException("camera Power state out of range");
                }
                if (sourceEpoch < vehiclePowerSourceEpoch
                        || sourceEpoch == vehiclePowerSourceEpoch
                        && revision < vehiclePowerRevision) return;
                if (sourceEpoch == vehiclePowerSourceEpoch && revision == vehiclePowerRevision
                        && (known != vehiclePowerKnown || off != vehiclePowerOff
                        || failures != consecutiveFailedPowerReads)) {
                    throw new IllegalArgumentException(
                            "camera Power decision changed without a new revision");
                }
                vehiclePowerKnown = known;
                vehiclePowerOff = off;
                consecutiveFailedPowerReads = failures;
                vehiclePowerSourceEpoch = sourceEpoch;
                vehiclePowerRevision = revision;
                evaluate("vehicle_power_state");
            }
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
        generation++;
        cancelRetry();
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        worker.shutdownNow();
    }

    private void evaluate(String trigger) {
        if (stopped) return;
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
            settings.edit().remove(PREF_SUCCEEDED_SESSION).apply();
        }
        displayObserved = true;
        displayWasReady = displayReady;
        long succeeded = settings.getLong(PREF_SUCCEEDED_SESSION, -1);
        long succeededBoot = settings.getLong(PREF_SUCCEEDED_BOOT, Long.MIN_VALUE);
        int succeededDisplay = settings.getInt(PREF_SUCCEEDED_DISPLAY, -1);
        boolean eligible = hasEnabledClusterTarget(
                        rearEnabled, rearLeftTarget, rearRightTarget,
                        frontEnabled, frontLeftTarget, frontRightTarget,
                        mirror.enabled, mirror.target);
        boolean ready = eligible && displayReady && interactive && awakeSessionId >= 0
                && !(vehiclePowerKnown && vehiclePowerOff);
        if (trackedSession != awakeSessionId || trackedDisplay != displayId || eligibleNow != ready) {
            generation++;
            cancelRetry();
            attempts = 0;
            terminal = false;
            trackedSession = awakeSessionId;
            trackedDisplay = displayId;
            eligibleNow = ready;
        }
        boolean needsCommand = shouldAttempt(ready, displayReady, awakeSessionId, succeeded,
                bootId, succeededBoot, displayId, succeededDisplay);
        String gate = !eligible ? "no_enabled_cluster_target"
                : !displayReady ? "waiting_for_cluster_display"
                : vehiclePowerKnown && vehiclePowerOff ? "vehicle_power_off"
                : awakeSessionId < 0 ? "waiting_for_power_state"
                : !interactive ? "waiting_for_wake"
                : !needsCommand ? "already_succeeded"
                : inFlight ? "in_flight"
                : pendingRetry != null ? "waiting_for_retry"
                : terminal ? "attempts_finished" : "request";
        if (!gate.equals(lastGate)) {
            lastGate = gate;
            emit("cluster_fullscreen_gate", "reason", gate, "trigger", trigger,
                    "boot_id", bootId, "awake_session_id", awakeSessionId,
                    "shell_interactive", interactive, "display_id", displayId,
                    "display_state", displayState, "vehicle_power_known", vehiclePowerKnown,
                    "vehicle_power_off", vehiclePowerOff,
                    "consecutive_failed_power_reads", consecutiveFailedPowerReads,
                    "mirror_hidden", mirror.manualHidden);
        }
        if (!needsCommand || inFlight || pendingRetry != null || terminal) {
            return;
        }
        long session = awakeSessionId;
        long attemptGeneration = generation;
        inFlight = true;
        int attempt = ++attempts;
        emit("cluster_fullscreen", "state", "requested",
                "attempt", attempt,
                "awake_session_id", session, "trigger", trigger,
                "boot_id", bootId, "display_id", displayId,
                "display_state", displayState, "shell_interactive", interactive,
                "mirror_hidden", mirror.manualHidden,
                "protocol", ClusterFullscreenProtocol.protocolForTest(),
                "operation", ClusterFullscreenProtocol.operationForTest());
        worker.execute(() -> {
            ClusterFullscreenProtocol.Result result = ClusterFullscreenProtocol.dispatch(
                    context, () -> isCurrentAttempt(attemptGeneration));
            long completedAt = SystemClock.uptimeMillis();
            handler.post(() -> finishAttempt(attemptGeneration, session, displayId, attempt, completedAt, result));
        });
    }

    private synchronized boolean isCurrentAttempt(long expectedGeneration) {
        return !stopped && eligibleNow && !(vehiclePowerKnown && vehiclePowerOff)
                && generation == expectedGeneration;
    }

    private synchronized void finishAttempt(long expectedGeneration, long session, int displayId,
            int attempt, long completedAt, ClusterFullscreenProtocol.Result result) {
        inFlight = false;
        boolean current = isCurrentAttempt(expectedGeneration);
        long retryDelay = current && result.retryable && attempt <= RETRY_DELAYS_MS.length
                ? RETRY_DELAYS_MS[attempt - 1] : -1;
        emit("cluster_fullscreen", "state", result.state(), "awake_session_id", session,
                "boot_id", bootId, "display_id", displayId, "attempt", attempt,
                "error", result.error, "stale", !current,
                "retry_in_session", retryDelay >= 0, "retry_delay_ms", retryDelay);
        if (!current) {
            evaluate("stale_attempt_finished");
            return;
        }
        if (result.succeeded()) {
            settings.edit().putLong(PREF_SUCCEEDED_SESSION, session)
                    .putLong(PREF_SUCCEEDED_BOOT, bootId)
                    .putInt(PREF_SUCCEEDED_DISPLAY, displayId).apply();
        } else if (retryDelay >= 0) {
            pendingRetry = () -> {
                synchronized (ClusterFullscreenController.this) {
                    if (!isCurrentAttempt(expectedGeneration)) return;
                    pendingRetry = null;
                    evaluate("bind_retry");
                }
            };
            handler.postDelayed(pendingRetry, Math.max(0, completedAt + retryDelay - SystemClock.uptimeMillis()));
        } else terminal = true;
        evaluate("attempt_finished");
    }

    private void cancelRetry() {
        if (pendingRetry != null) handler.removeCallbacks(pendingRetry);
        pendingRetry = null;
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

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
