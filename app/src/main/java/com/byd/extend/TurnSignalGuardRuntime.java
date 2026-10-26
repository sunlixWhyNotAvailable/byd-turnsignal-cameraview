package com.byd.extend;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

final class TurnSignalGuardRuntime {
    private static final int PERIOD_MS = 50;
    static final int MAX_POLL_GAP_MS = 250;
    private static final int DEFAULT_CORRECTION_DELAY_MS = 100;
    private static final int DEFAULT_MAX_SPEED_KPH = 30;
    private static final int CORRECTION_CONFIRM_MS = 1_500;
    private static final int TELEMETRY_ERROR_REPEAT_MS = 5_000;
    private static final int CAMERA_STATE_PERIOD_MS = 200;
    private final BlindShortTurnSignal cameraShortTurn = new BlindShortTurnSignal();
    private static final int TURN_SIGNAL_SET_FID = 871366669;
    private static final int HAZARD_THANKS_LIGHT_DEVICE = 1004;
    private static final int HAZARD_THANKS_SOUND_DEVICE = 1007;
    private static final int HAZARD_THANKS_SOUND_FID = 339738670;
    private static final int HAZARD_THANKS_MODE_FID = 950009900;
    private static final int HAZARD_THANKS_STALK_FID = 321912876;
    private static final int LATCH_UNKNOWN = -1;

    private static final Signal GEAR = new Signal(5, 1011, 555745336);

    private static final int BLINK_OFF = 1;
    private static final int BLINK_LEFT = 2;
    private static final int BLINK_RIGHT = 4;
    private static final int BLINK_HAZARD = 6;

    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;
    private final StartupCleanupAttemptMarker startupCleanupAttemptMarker;
    private final Object dispatchLock = new Object();
    private final Runnable pollRunnable = this::pollAndSchedule;
    private Object timerToken = new Object();
    private boolean shutdownRequested;

    private IBinder autoservice;
    private String autoserviceDescriptor;
    private Object lightDevice;
    private Method setLightFeature;
    private Class<?> eventValueType;
    private final TurnSignalTelemetryController telemetryController;
    private final HazardThanksSequence hazardThanks = new HazardThanksSequence();

    private boolean started;
    private boolean requestedEnabled;
    private boolean hazardThanksEnabled;
    private int hazardThanksFlashes = 2;
    private boolean hazardThanksOwnsRequest;
    private int lastHazardThanksStalk = -1;
    private int lastHazardThanksMode = -1;
    private long runtimeStartedAtMs;
    private long activeHazardThanksWindowStartAt;
    private long lastHazardThanksWindowStartAt;
    private long lastHazardThanksWindowEndAt;
    private boolean listenerHealthy;
    private boolean pollHealthy;
    private final TurnSignalGestureState gestures = new TurnSignalGestureState();
    private String lastRejectedGesture;
    private float outwardDeg = 90.0f;
    private float centerDeg = 10.0f;
    private int correctionDelayMs = DEFAULT_CORRECTION_DELAY_MS;
    private int maxSpeedKph = DEFAULT_MAX_SPEED_KPH;
    private float latestAngle = Float.NaN;
    private float latestSpeedKph = Float.NaN;
    private int latestStalk = -1;
    private int latestBlink = -1;
    private long lastPollAt;
    private long lastSampleAt;
    private long lastCameraStateAt;

    private boolean sessionActive;
    private int sessionDirection;
    private final CenterReturnTracker centerReturnTracker = new CenterReturnTracker();
    private boolean matchingBlinkSeen;
    private int corrections;
    private long offCandidateAt;
    private boolean offNeutralized;
    private String pendingNeutralizeReason;
    private boolean hazardCleanupPending;
    private boolean guardDisableResetPending;
    private boolean speedLimitCleanupPending;
    private final TelemetryRecoveryCleanup telemetryRecoveryCleanup =
            new TelemetryRecoveryCleanup();
    private final TurnSignalDeferredIntent speedDeferred = new TurnSignalDeferredIntent();
    private int assumedLatchState;
    private boolean confirmationPending;
    private long confirmationDeadline;
    private boolean manualCommandPending;
    private int manualConfirmationGeneration;
    private boolean initialLatchApplied;
    private long lastTelemetryErrorAt;
    private String lastTelemetryErrorReason;
    private String primaryTelemetryError;
    private boolean vehicleInteractive;
    private long awakeSessionGeneration;
    private long startupCleanupAttemptedGeneration;
    private long startupCleanupArmedGeneration;
    private long startupCleanupFreshGeneration;
    private long startupCleanupPersistFailedGeneration;
    private long startupCleanupMarkerPendingGeneration;
    private long lifecycleGeneration;
    private boolean writeInFlight;
    private long lastControlInitAttemptAt = Long.MIN_VALUE;
    private boolean controlInitRetryStopped;
    private String lastControlInitError;
    private String lastControlInitDiagnostic;

    TurnSignalGuardRuntime(
            Context context,
            Handler handler,
            BiConsumer<String, Object[]> eventSink,
            StartupCleanupAttemptMarker startupCleanupAttemptMarker) {
        this.context = context;
        this.handler = handler;
        this.eventSink = eventSink;
        this.startupCleanupAttemptMarker = startupCleanupAttemptMarker;
        telemetryController = new TurnSignalTelemetryController(
                SystemClock::elapsedRealtime,
                action -> runOnHandler(action, "telemetry_callback"),
                new TurnSignalTelemetryTransport(context),
                new TurnSignalTelemetryController.Sink() {
                    @Override public void onSnapshot(TurnSignalTelemetryController.Snapshot snapshot,
                            TurnSignalTelemetryController.Source source, int liveMask,
                            boolean conflict, long observedMs) {
                        applyTelemetrySnapshot(snapshot, source, liveMask, conflict, observedMs);
                    }
                    @Override public void onMode(
                            boolean subscribed, boolean seeded, String reason) {
                        telemetryModeChanged(subscribed, seeded, reason);
                    }
                    @Override public void onDiagnostic(String event, Object... fields) {
                        emit(event, fields);
                    }
                });
        assumedLatchState = LATCH_UNKNOWN;
    }

    void start() {
        runOnHandler(this::startOnHandler, "start");
    }

    void stop() {
        runOnHandler(this::stopOnHandler, "stop");
    }

    void stop(Runnable completed) {
        runOnHandler(() -> {
            stopOnHandler();
            if (completed != null) completed.run();
        }, "stop");
    }

    boolean shutdown(Runnable completed) {
        boolean runInline;
        boolean posted = true;
        synchronized (dispatchLock) {
            if (shutdownRequested) return false;
            shutdownRequested = true;
            runInline = Looper.myLooper() == handler.getLooper();
            if (!runInline) posted = handler.post(() -> shutdownOnHandler(completed));
        }
        if (runInline) shutdownOnHandler(completed);
        return runInline || posted;
    }

    void configure(
            boolean enabled,
            float outward,
            float center,
            int correctionDelay,
            int maxSpeed,
            int initialAssumedLatchState) {
        runOnHandler(() -> configureOnHandler(
                enabled, outward, center, correctionDelay, maxSpeed,
                initialAssumedLatchState), "configure");
    }

    void configureHazardThanks(boolean enabled, int flashes) {
        runOnHandler(() -> configureHazardThanksOnHandler(enabled, flashes),
                "configure_hazard_thanks");
    }

    void startHazardThanks(String source, long requestedAtMs) {
        runOnHandler(() -> startHazardThanksOnHandler(source, requestedAtMs),
                "start_hazard_thanks");
    }

    void vehiclePowerStateChanged(
            boolean interactive, long generation, long cleanupAttemptedGeneration) {
        runOnHandler(() -> vehiclePowerStateChangedOnHandler(
                interactive, generation, cleanupAttemptedGeneration), "vehicle_power_state");
    }

    void reportStatus() {
        runOnHandler(() -> {
            emit("telemetry_ready", "ok", telemetryReady(),
                    "listener_ok", listenerHealthy, "poll_ok", pollHealthy,
                    "control_ready", setLightFeature != null,
                    "error", telemetryReady() ? "" : telemetryError());
            emit("turn_state_status", "feature_id", TURN_SIGNAL_SET_FID,
                    "assumed_latch_state", assumedLatchState);
            emitCameraState();
            emitGuardConfig();
        }, "report_status");
    }

    void setManualTurnState(int payload) {
        runOnHandler(() -> setManualTurnStateOnHandler(payload), "manual_state_set");
    }

    private void startOnHandler() {
        if (started) return;
        lifecycleGeneration++;
        runtimeStartedAtMs = SystemClock.elapsedRealtime();
        selfCheck();
        started = true;
        lastControlInitAttemptAt = Long.MIN_VALUE;
        controlInitRetryStopped = false;
        lastControlInitError = null;
        lastControlInitDiagnostic = null;
        initializeControlIfNeeded();
        String error = null;
        try {
            telemetryController.start();
        } catch (Throwable failure) {
            error = summary(failure);
            primaryTelemetryError = error;
            listenerHealthy = false;
            pollHealthy = false;
            emit("telemetry_start_failed", "operation", "telemetry_start",
                    "error", diagnostic(failure));
        }
        emit("guard_self_check", "ok", true);
        emit("telemetry_ready", "ok", telemetryReady(),
                "listener_ok", listenerHealthy, "poll_ok", pollHealthy,
                "control_ready", setLightFeature != null,
                "error", telemetryReady() ? ""
                        : error == null ? telemetryError() : error);
        postTimer(pollRunnable, PERIOD_MS);
    }

    private void stopOnHandler() {
        lifecycleGeneration++;
        startupCleanupMarkerPendingGeneration = 0;
        Object oldTimerToken = timerToken;
        timerToken = new Object();
        handler.removeCallbacksAndMessages(oldTimerToken);
        finishHazardThanks("runtime_stopped", "cancelled", "");
        if (!started) return;
        started = false;
        boolean resetLatch = requestedEnabled;
        requestedEnabled = false;
        guardDisableResetPending = resetLatch;
        evaluateGuardDisableReset();
        manualConfirmationGeneration++;
        manualCommandPending = false;
        hazardCleanupPending = false;
        guardDisableResetPending = false;
        speedLimitCleanupPending = false;
        telemetryRecoveryCleanup.clear();
        clearSpeedDeferredSession();
        resetSession();
        resetGesture();
        telemetryController.close();
        cameraShortTurn.reset();
        listenerHealthy = false;
        pollHealthy = false;
        emit("telemetry_stopped", "listener_unregistered", true, "error", "");
    }

    private void shutdownOnHandler(Runnable completed) {
        handler.removeCallbacksAndMessages(null);
        stopOnHandler();
        handler.removeCallbacksAndMessages(null);
        if (completed != null) completed.run();
    }

    private void setManualTurnStateOnHandler(int payload) {
        long now = SystemClock.elapsedRealtime();
        String precheck = manualPrecheckReason(
                payload, manualCommandPending, requestedEnabled, now,
                telemetryController.dataFresh(now) ? now : 0);
        if (precheck != null) {
            if ("stale_poll".equals(precheck)) {
                emit("manual_turn_state_rejected", "reason", precheck,
                        "payload", payload,
                        "poll_age_ms", lastPollAt == 0 ? "unknown" : now - lastPollAt,
                        "max_poll_gap_ms", MAX_POLL_GAP_MS);
            } else {
                emit("manual_turn_state_rejected", "reason", precheck,
                        "payload", payload);
            }
            return;
        }

        ReadResult gear = read(GEAR);
        ReadResult blink = latestBlink < 0
                ? new ReadResult(-1, null) : new ReadResult(0, latestBlink);
        String telemetryRejection = manualTelemetryRejectionReason(
                payload, telemetryReady(), gear.status, gear.raw, blink.status, blink.raw);
        if (telemetryRejection != null) {
            emit("manual_turn_state_rejected",
                    "reason", telemetryRejection,
                    "payload", payload, "telemetry_ready", telemetryReady(),
                    "gear_status", gear.status,
                    "gear", gear.raw == null ? "unknown" : gear.raw,
                    "blink_status", blink.status,
                    "blink", blink.raw == null ? "unknown" : blink.raw);
            return;
        }

        if (hazardThanks.active()) {
            finishHazardThanks("manual_turn_state_" + manualActionName(payload),
                    "cancelled", "");
        }
        try {
            int status = writeTurnSignal(payload, "manual_" + manualActionName(payload));
            emit("manual_turn_state_requested", "action", manualActionName(payload),
                    "feature_id", TURN_SIGNAL_SET_FID, "payload", payload,
                    "status", status, "gear", gear.raw, "blink_before", blink.raw);
            if (status != 0) {
                emit("manual_turn_state_failed", "action", manualActionName(payload),
                        "payload", payload, "reason", "framework_status_" + status);
                return;
            }
        } catch (Throwable failure) {
            emit("manual_turn_state_failed", "action", manualActionName(payload),
                    "payload", payload, "reason", summary(failure));
            return;
        }

        manualCommandPending = true;
        int generation = ++manualConfirmationGeneration;
        awaitManualTurnStateConfirmation(payload, blink.raw,
                SystemClock.elapsedRealtime() + CORRECTION_CONFIRM_MS, generation);
    }

    private void awaitManualTurnStateConfirmation(
            int payload, int blinkBefore, long deadline, int generation) {
        if (!shouldContinueManualConfirmation(
                started, manualCommandPending, generation, manualConfirmationGeneration)) return;
        ReadResult blink = latestBlink < 0
                ? new ReadResult(-1, null) : new ReadResult(0, latestBlink);
        if (blink.status != 0 || blink.raw == null || !telemetryReady()) {
            manualCommandPending = false;
            emit("manual_turn_state_failed", "action", manualActionName(payload),
                    "payload", payload, "reason", "telemetry_lost_during_confirmation",
                    "blink_status", blink.status,
                    "blink", blink.raw == null ? "unknown" : blink.raw,
                    "listener_ok", listenerHealthy);
            return;
        }
        int expected = expectedBlinkForPayload(payload);
        if (blink.raw == expected) {
            manualCommandPending = false;
            emit("manual_turn_state_confirmed", "action", manualActionName(payload),
                    "operation", manualConfirmationOperation(payload),
                    "payload", payload, "requested_payload", payload,
                    "blink", blink.raw, "expected_blink", expected,
                    "observed_blink", blink.raw,
                    "observable_transition", blinkBefore != expected,
                    "classification", confirmationSuccessClassification(payload, blinkBefore),
                    "framework_accepted", true, "framework_status", 0,
                    "assumed_latch_state", assumedLatchState);
            return;
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            manualCommandPending = false;
            emit("manual_turn_state_failed", "action", manualActionName(payload),
                    "operation", manualConfirmationOperation(payload),
                    "payload", payload, "requested_payload", payload,
                    "reason", "blink_confirmation_timeout",
                    "blink", blink.raw, "expected_blink", expected,
                    "observed_blink", blink.raw, "timeout_ms", CORRECTION_CONFIRM_MS,
                    "framework_accepted", true, "framework_status", 0,
                    "classification", confirmationTimeoutClassification(payload, blinkBefore),
                    "assumed_latch_state", assumedLatchState);
            return;
        }
        postTimer(() -> awaitManualTurnStateConfirmation(
                payload, blinkBefore, deadline, generation), PERIOD_MS);
    }

    private void configureHazardThanksOnHandler(boolean enabled, int flashes) {
        if (flashes < 1 || flashes > 3) {
            emit("hazard_thanks_config_rejected", "reason", "invalid_flash_count",
                    "requested_count", flashes);
            if (!enabled) {
                hazardThanksEnabled = false;
                if (hazardThanks.active()) {
                    finishHazardThanks("feature_disabled", "cancelled", "");
                }
            }
            return;
        }
        hazardThanksEnabled = enabled;
        hazardThanksFlashes = flashes;
        emit("hazard_thanks_config", "enabled", enabled, "flashes", flashes,
                "active", hazardThanks.active());
        if (!enabled && hazardThanks.active()) {
            finishHazardThanks("feature_disabled", "cancelled", "");
        }
    }

    private void configureOnHandler(
            boolean enabled,
            float outward,
            float center,
            int correctionDelay,
            int maxSpeed,
            int initialAssumedLatchState) {
        if (!initialLatchApplied) {
            assumedLatchState = initialAssumedLatchState >= LATCH_UNKNOWN
                    && initialAssumedLatchState <= 3
                    ? initialAssumedLatchState : LATCH_UNKNOWN;
            initialLatchApplied = true;
            emit("turn_state_assumption_loaded", "feature_id", TURN_SIGNAL_SET_FID,
                    "assumed_latch_state", assumedLatchState);
        }
        if (!validThresholds(outward, center) || !validCorrectionDelay(correctionDelay)
                || !validMaxSpeed(maxSpeed)) {
            requestedEnabled = false;
            hazardCleanupPending = false;
            telemetryRecoveryCleanup.clear();
            clearSpeedDeferredSession();
            resetSession();
            emit("guard_config", "requested", enabled, "active", false,
                    "outward_deg", outward, "center_deg", center,
                    "correction_delay_ms", correctionDelay,
                    "max_speed_kph", maxSpeed,
                    "reason", "invalid_configuration");
            return;
        }
        boolean disabling = requestedEnabled && !enabled;
        boolean configurationChanged = guardConfigurationChanged(
                requestedEnabled, outwardDeg, centerDeg, correctionDelayMs, maxSpeedKph,
                enabled, outward, center, correctionDelay, maxSpeed);
        outwardDeg = outward;
        centerDeg = center;
        correctionDelayMs = correctionDelay;
        maxSpeedKph = maxSpeed;
        requestedEnabled = enabled;
        if (!enabled) {
            hazardCleanupPending = false;
            telemetryRecoveryCleanup.clear();
            startupCleanupArmedGeneration = 0;
            startupCleanupFreshGeneration = 0;
        }
        if (enabled) guardDisableResetPending = false;
        if (configurationChanged) {
            if (enabled) queueOwnedLatchCleanup("configuration_changed");
            resetSession();
            clearSpeedDeferredSession();
        }
        if (disabling) {
            speedLimitCleanupPending = false;
            guardDisableResetPending = true;
            emit("guard_disable_latch_reset_queued", "feature_id", TURN_SIGNAL_SET_FID,
                    "requested_payload", 0, "assumed_latch_state", assumedLatchState);
        }
        if (enabled) armStartupCleanupIfNeeded();
        emitGuardConfig();
    }

    private void vehiclePowerStateChangedOnHandler(
            boolean interactive, long generation, long cleanupAttemptedGeneration) {
        boolean waking = interactive && !vehicleInteractive;
        vehicleInteractive = interactive;
        long nextGeneration = Math.max(0, generation);
        if (!interactive || awakeSessionGeneration != nextGeneration) {
            startupCleanupMarkerPendingGeneration = 0;
        }
        awakeSessionGeneration = nextGeneration;
        startupCleanupAttemptedGeneration = Math.max(0, cleanupAttemptedGeneration);
        if (!interactive) {
            startupCleanupArmedGeneration = 0;
            startupCleanupFreshGeneration = 0;
            return;
        }
        if (waking) {
            invalidateTelemetryOwnership("wake_reseed");
            telemetryController.reseed();
        }
        armStartupCleanupIfNeeded();
    }

    private void armStartupCleanupIfNeeded() {
        long generation = awakeSessionGeneration;
        if (!vehicleInteractive || !requestedEnabled || generation <= 0
                || startupCleanupAttemptedGeneration == generation
                || startupCleanupArmedGeneration == generation) {
            return;
        }
        resetSession();
        clearSpeedDeferredSession();
        hazardCleanupPending = false;
        speedLimitCleanupPending = false;
        queueOwnedLatchCleanup("startup_reseed");
        resetGesture();
        startupCleanupArmedGeneration = generation;
        startupCleanupFreshGeneration = 0;
        startupCleanupPersistFailedGeneration = 0;
        pollHealthy = false;
        latestStalk = -1;
        latestBlink = -1;
        emit("startup_awake_session_cleanup_pending",
                "awake_session_id", generation,
                "attempted_session_id", startupCleanupAttemptedGeneration);
    }

    private void emitGuardConfig() {
        emit("guard_config", "requested", requestedEnabled,
                "active", guardActive(), "outward_deg", outwardDeg,
                "center_deg", centerDeg, "correction_delay_ms", correctionDelayMs,
                "max_speed_kph", maxSpeedKph,
                "reason", guardActive() || !requestedEnabled ? "" : guardInactiveReason());
    }

    private void pollAndSchedule() {
        if (!started) return;
        initializeControlIfNeeded();
        if (hazardThanks.active()) pollHazardThanks();
        telemetryController.tick();
        evaluateTelemetryDeadlines();
        if (started) postTimer(pollRunnable, PERIOD_MS);
    }

    private void startHazardThanksOnHandler(String source, long requestedAtMs) {
        long dispatchAt = SystemClock.elapsedRealtime();
        if (requestedAtMs < runtimeStartedAtMs) {
            emit("hazard_thanks_start_ignored", "reason", "gesture_predates_runtime_start",
                    "source", source == null ? "unknown" : source,
                    "requested_ms", requestedAtMs,
                    "runtime_started_ms", runtimeStartedAtMs);
            return;
        }
        if (requestedAtMs <= 0 || requestedAtMs > dispatchAt) {
            emit("hazard_thanks_start_ignored", "reason", "invalid_input_timestamp",
                    "source", source == null ? "unknown" : source,
                    "requested_ms", requestedAtMs, "dispatched_ms", dispatchAt);
            return;
        }
        if (!hazardThanksEnabled) {
            emit("hazard_thanks_start_ignored", "reason", "feature_disabled",
                    "source", source == null ? "unknown" : source);
            return;
        }
        if (hazardThanks.active()) {
            emit("hazard_thanks_start_ignored", "reason", "active_retrigger_ignored",
                    "source", source == null ? "unknown" : source,
                    "generation", hazardThanks.generation(),
                    "completed", hazardThanks.completed(), "target", hazardThanks.target());
            return;
        }
        if (HazardThanksSequence.inputOccurredDuring(requestedAtMs,
                lastHazardThanksWindowStartAt, lastHazardThanksWindowEndAt)) {
            emit("hazard_thanks_start_ignored", "reason", "stale_active_sequence_retrigger",
                    "source", source == null ? "unknown" : source,
                    "requested_ms", requestedAtMs,
                    "previous_start_ms", lastHazardThanksWindowStartAt,
                    "previous_end_ms", lastHazardThanksWindowEndAt);
            return;
        }
        if (!started) {
            emit("hazard_thanks_preflight_failed", "reason", "runtime_stopped",
                    "source", source == null ? "unknown" : source);
            return;
        }

        long preflightStarted = SystemClock.elapsedRealtime();
        Integer stalk = readHazardThanksSignal(
                HAZARD_THANKS_LIGHT_DEVICE, HAZARD_THANKS_STALK_FID, "stalk", "preflight");
        Integer mode = readHazardThanksSignal(
                HAZARD_THANKS_LIGHT_DEVICE, HAZARD_THANKS_MODE_FID, "mode", "preflight");
        Integer sound = readHazardThanksSignal(HAZARD_THANKS_SOUND_DEVICE,
                HAZARD_THANKS_SOUND_FID, "instrument_sound", "preflight");
        long preflightCompletedAtMs = SystemClock.elapsedRealtime();
        String rejection = stalk == null || mode == null || sound == null
                ? "required_signal_unavailable"
                : preflightCompletedAtMs - preflightStarted > MAX_POLL_GAP_MS
                        ? "preflight_signal_age_exceeded" : null;
        if (rejection == null) {
            rejection = hazardThanks.start(
                    mode, stalk, sound, hazardThanksFlashes, preflightCompletedAtMs);
        }
        if (rejection != null) {
            emit("hazard_thanks_preflight_failed", "reason", rejection,
                    "source", source == null ? "unknown" : source,
                    "mode", mode == null ? "unavailable" : mode,
                    "stalk", stalk == null ? "unavailable" : stalk,
                    "sound", sound == null ? "unavailable" : sound,
                    "elapsed_ms", preflightCompletedAtMs - preflightStarted,
                    "requested_flashes", hazardThanksFlashes);
            return;
        }

        activeHazardThanksWindowStartAt = requestedAtMs;
        lastHazardThanksStalk = stalk;
        lastHazardThanksMode = mode;
        hazardThanksOwnsRequest = true;
        emit("hazard_thanks_baseline", "source", source == null ? "unknown" : source,
                "requested_ms", requestedAtMs,
                "activation_cutoff_ms", preflightCompletedAtMs,
                "process_uid", Process.myUid(), "process_pid", Process.myPid(),
                "context_package", context.getPackageName(),
                "device", HAZARD_THANKS_SOUND_DEVICE, "feature_id", HAZARD_THANKS_SOUND_FID,
                "sound", sound, "stalk", stalk, "mode", mode,
                "target_flashes", hazardThanks.target(),
                "generation", hazardThanks.generation());
        emit("hazard_thanks_started", "source", source == null ? "unknown" : source,
                "activation_cutoff_ms", preflightCompletedAtMs,
                "process_uid", Process.myUid(), "process_pid", Process.myPid(),
                "context_package", context.getPackageName(),
                "target_flashes", hazardThanks.target(),
                "timeout_ms", HazardThanksSequence.NO_CYCLE_TIMEOUT_MS,
                "generation", hazardThanks.generation());
        try {
            int status = writeTurnSignal(1, "hazard_thanks_start");
            emit("hazard_thanks_write_result", "action", "start",
                    "payload", 1, "status", status);
            if (status != 0) {
                finishHazardThanks("start_framework_status_" + status, "failed", "");
            }
        } catch (Throwable failure) {
            finishHazardThanks("start_write_failed", "failed", diagnostic(failure));
        }
    }

    private void pollHazardThanks() {
        long generation = hazardThanks.generation();
        long now = SystemClock.elapsedRealtime();
        if (hazardThanks.timeoutDue(generation, now)) {
            finishHazardThanks("no_complete_cycle_within_timeout", "cancelled", "");
            return;
        }
        Integer stalk = readHazardThanksSignal(
                HAZARD_THANKS_LIGHT_DEVICE, HAZARD_THANKS_STALK_FID, "stalk", "active");
        Integer mode = readHazardThanksSignal(
                HAZARD_THANKS_LIGHT_DEVICE, HAZARD_THANKS_MODE_FID, "mode", "active");
        if (!hazardThanks.active() || hazardThanks.generation() != generation) return;
        if (stalk == null || mode == null) {
            finishHazardThanks("required_signal_get_failed", "cancelled", "");
            return;
        }
        if (stalk < 1 || stalk > 5 || mode < 1 || mode > 9) {
            emit("hazard_thanks_input_invalid", "stalk", stalk, "mode", mode,
                    "stalk_fid", HAZARD_THANKS_STALK_FID,
                    "mode_fid", HAZARD_THANKS_MODE_FID);
            finishHazardThanks("required_signal_invalid", "cancelled", "");
            return;
        }
        observeHazardThanksManualSignals(stalk, mode, "active_get",
                SystemClock.elapsedRealtime(), true, true);
        if (!hazardThanks.active() || hazardThanks.generation() != generation) return;

        Integer sound = readHazardThanksSignal(HAZARD_THANKS_SOUND_DEVICE,
                HAZARD_THANKS_SOUND_FID, "instrument_sound", "active");
        long receivedAt = SystemClock.elapsedRealtime();
        if (!hazardThanks.active() || hazardThanks.generation() != generation) return;
        if (hazardThanks.timeoutDue(generation, receivedAt)) {
            finishHazardThanks("no_complete_cycle_within_timeout", "cancelled", "");
            return;
        }
        if (sound == null) {
            finishHazardThanks("sound_get_failed", "cancelled", "");
            return;
        }

        Integer previousSound = hazardThanks.lastSound();
        int previousCycles = hazardThanks.completed();
        int observation = hazardThanks.observeSound(sound, receivedAt);
        if (observation == HazardThanksSequence.SOUND_INVALID) {
            emit("hazard_thanks_input_invalid", "signal", "instrument_sound",
                    "device", HAZARD_THANKS_SOUND_DEVICE,
                    "feature_id", HAZARD_THANKS_SOUND_FID, "value", sound);
            finishHazardThanks("invalid_sound_value", "cancelled", "");
            return;
        }
        if (!sound.equals(previousSound)) {
            emit("hazard_thanks_sound_edge", "previous", previousSound,
                    "value", sound, "completed", hazardThanks.completed(),
                    "received_ms", receivedAt);
        }
        if (hazardThanks.completed() > previousCycles) {
            emit("hazard_thanks_sound_cycle", "completed", hazardThanks.completed(),
                    "target", hazardThanks.target(), "received_ms", receivedAt,
                    "generation", generation);
        }
        if (observation == HazardThanksSequence.SOUND_TARGET_REACHED) {
            finishHazardThanks("target_cycles_reached", "complete", "");
        }
    }

    private Integer readHazardThanksSignal(
            int device, int fid, String signal, String phase) {
        String stage = "service_check";
        Parcel data = null;
        Parcel reply = null;
        try {
            if (autoservice == null || autoserviceDescriptor == null) {
                emit("hazard_thanks_get_failed", "operation", "GET", "phase", phase,
                        "signal", signal, "device", device, "feature_id", fid,
                        "stage", stage, "failure", "autoservice_not_initialized");
                return null;
            }
            if (!autoservice.isBinderAlive()) {
                emit("hazard_thanks_get_failed", "operation", "GET", "phase", phase,
                        "signal", signal, "device", device, "feature_id", fid,
                        "stage", stage, "failure", "autoservice_binder_dead");
                return null;
            }
            data = Parcel.obtain();
            reply = Parcel.obtain();
            stage = "interface_token";
            data.writeInterfaceToken(autoserviceDescriptor);
            data.writeInt(device);
            data.writeInt(fid);
            stage = "binder_transact_5";
            boolean transacted = autoservice.transact(5, data, reply, 0);
            int replyBytes = reply.dataAvail();
            if (!transacted) {
                emit("hazard_thanks_get_failed", "operation", "GET", "phase", phase,
                        "signal", signal, "device", device, "feature_id", fid,
                        "transaction", 5, "stage", stage,
                        "failure", "transact_returned_false", "reply_bytes", replyBytes);
                return null;
            }
            if (replyBytes < 8) {
                boolean statusPresent = replyBytes >= 4;
                int status = statusPresent ? reply.readInt() : Integer.MIN_VALUE;
                emit("hazard_thanks_get_failed", "operation", "GET", "phase", phase,
                        "signal", signal, "device", device, "feature_id", fid,
                        "transaction", 5, "stage", "reply", "failure", "short_reply",
                        "reply_bytes", replyBytes, "status_present", statusPresent,
                        "oem_status", statusPresent ? status : "unavailable");
                return null;
            }
            stage = "reply_parse";
            int status = reply.readInt();
            int value = reply.readInt();
            if (status != 0) {
                emit("hazard_thanks_get_failed", "operation", "GET", "phase", phase,
                        "signal", signal, "device", device, "feature_id", fid,
                        "transaction", 5, "stage", stage, "failure", "oem_status",
                        "oem_status", status, "raw_value", value);
                return null;
            }
            emit("hazard_thanks_get_sample", "phase", phase, "signal", signal,
                    "device", device, "feature_id", fid, "transaction", 5,
                    "oem_status", status, "value", value,
                    "received_ms", SystemClock.elapsedRealtime());
            return value;
        } catch (Throwable failure) {
            emit("hazard_thanks_get_failed", "operation", "GET", "phase", phase,
                    "signal", signal, "device", device, "feature_id", fid,
                    "transaction", 5, "stage", stage,
                    "exception_class", failure.getClass().getName(),
                    "exception_message", failure.getMessage() == null ? "" : failure.getMessage(),
                    "error", diagnostic(failure));
            return null;
        } finally {
            if (data != null) data.recycle();
            if (reply != null) reply.recycle();
        }
    }

    private void finishHazardThanks(String reason, String result, String primaryError) {
        if (!hazardThanks.active()) return;
        long generation = hazardThanks.generation();
        int completed = hazardThanks.completed();
        int target = hazardThanks.target();
        boolean owned = hazardThanksOwnsRequest;
        long windowStart = activeHazardThanksWindowStartAt;
        hazardThanks.cancel();
        lastHazardThanksStalk = -1;
        lastHazardThanksMode = -1;

        int releaseStatus = Integer.MIN_VALUE;
        String releaseError = "";
        if (owned) {
            try {
                releaseStatus = writeTurnSignal(0, "hazard_thanks_release_" + reason);
                emit("hazard_thanks_write_result", "action", "release",
                        "payload", 0, "status", releaseStatus, "reason", reason);
                if (releaseStatus != 0) {
                    releaseError = "framework_status_" + releaseStatus;
                    emit("hazard_thanks_release_failed", "reason", reason,
                            "failure", releaseError, "feature_id", TURN_SIGNAL_SET_FID,
                            "payload", 0, "status", releaseStatus);
                }
            } catch (Throwable failure) {
                releaseError = diagnostic(failure);
                emit("hazard_thanks_release_failed", "reason", reason,
                        "failure", "write_exception", "feature_id", TURN_SIGNAL_SET_FID,
                        "payload", 0, "exception_class", failure.getClass().getName(),
                        "exception_message", failure.getMessage() == null ? "" : failure.getMessage(),
                        "error", releaseError);
            } finally {
                hazardThanksOwnsRequest = false;
            }
        }
        long windowEnd = SystemClock.elapsedRealtime();
        if (windowStart > 0) {
            lastHazardThanksWindowStartAt = windowStart;
            lastHazardThanksWindowEndAt = windowEnd;
        }
        activeHazardThanksWindowStartAt = 0;
        if ("complete".equals(result) && owned && releaseStatus == 0) {
            emit("hazard_thanks_result", "result", result, "reason", reason,
                    "completed", completed, "target", target, "generation", generation,
                    "window_start_ms", windowStart, "window_end_ms", windowEnd,
                    "release_status", owned ? releaseStatus : "not_owned",
                    "release_error", releaseError);
        } else if ("complete".equals(result)) {
            emit("hazard_thanks_result", "result", "release_failed", "reason", reason,
                    "target_reached", true, "completed", completed, "target", target,
                    "generation", generation, "window_start_ms", windowStart,
                    "window_end_ms", windowEnd,
                    "release_status", owned ? releaseStatus : "not_owned",
                    "release_error", releaseError.isEmpty() ? "request_not_owned" : releaseError);
        } else {
            emit("hazard_thanks_cancel", "reason", reason,
                    "completed", completed, "target", target, "generation", generation,
                    "primary_error", primaryError == null ? "" : primaryError);
            emit("hazard_thanks_result", "result", result, "reason", reason,
                    "completed", completed, "target", target, "generation", generation,
                    "window_start_ms", windowStart, "window_end_ms", windowEnd,
                    "release_status", owned ? releaseStatus : "not_owned",
                    "release_error", releaseError,
                    "primary_error", primaryError == null ? "" : primaryError);
        }
    }

    private void applyTelemetrySnapshot(
            TurnSignalTelemetryController.Snapshot snapshot,
            TurnSignalTelemetryController.Source source,
            int liveMask,
            boolean conflict,
            long observedMs) {
        if (!started) return;
        long now = SystemClock.elapsedRealtime();
        boolean wasTelemetryReady = telemetryReady();
        long gap = lastPollAt == 0 ? 0 : now - lastPollAt;
        lastPollAt = now;

        ReadResult stalk = new ReadResult(0, snapshot.stalk);
        ReadResult steering = new ReadResult(0, snapshot.steering);
        ReadResult blink = new ReadResult(0, snapshot.blink);
        ReadResult speed = new ReadResult(0, snapshot.speed);
        Float angle = decodeAngle(steering);
        Float speedKph = decodeSpeed(speed);
        boolean valid = stalk.okRange(1, 5) && blink.okRange(1, 9)
                && angle != null && speedKph != null;
        boolean gapOk = telemetryController.dataFresh(now);

        observeHazardThanksTelemetrySnapshot(
                stalk.raw, blink.raw, source, liveMask, observedMs);

        if (!valid || !gapOk) {
            if (!gapOk) telemetryController.reportFreshnessFailure(now);
            boolean wasHealthy = pollHealthy;
            pollHealthy = false;
            invalidateTelemetryOwnership("telemetry_gap_or_invalid");
            String reason = gapOk ? "invalid_signal" : "poll_gap";
            if ((wasHealthy || requestedEnabled)
                    && (!reason.equals(lastTelemetryErrorReason)
                    || now - lastTelemetryErrorAt >= TELEMETRY_ERROR_REPEAT_MS)) {
                lastTelemetryErrorAt = now;
                lastTelemetryErrorReason = reason;
                TurnSignalTelemetryController.Metrics timing = telemetryController.metrics(now);
                emit("telemetry_error", "reason", reason,
                        "gap_ms", gap, "stalk_status", stalk.status,
                        "steering_status", steering.status, "blink_status", blink.status,
                        "speed_status", speed.status, "get_age_ms", timing.getAgeMs,
                        "get_duration_ms", timing.readDurationMs,
                        "callback_dispatch_age_ms", timing.callbackDispatchAgeMs);
            }
            if (wasHealthy || now - lastCameraStateAt >= CAMERA_STATE_PERIOD_MS) {
                emitCameraState();
            }
            return;
        }

        boolean recovered = !pollHealthy;
        listenerHealthy = telemetryController.subscriptionHealthy();
        pollHealthy = true;
        if (recovered) telemetryRecoveryCleanup.recovered();
        if (recovered && lastTelemetryErrorReason != null) {
            emit("telemetry_recovered", "previous_reason", lastTelemetryErrorReason);
            lastTelemetryErrorReason = null;
        }
        latestAngle = angle;
        latestSpeedKph = speedKph;
        latestStalk = stalk.raw;
        if (!wasTelemetryReady && telemetryReady()) telemetryRecoveryCleanup.recovered();
        if (source == TurnSignalTelemetryController.Source.RECONCILE && conflict) {
            cancelForReconciliationConflict();
        }
        if (sessionActive && !speedAllowed(latestSpeedKph, maxSpeedKph)) {
            int deferredDirection = sessionDirection;
            boolean deferredBlinkSeen = matchingBlinkSeen;
            boolean cleanupNeeded = isDirectionLatch(assumedLatchState);
            suppress("speed_above_limit");
            deferSessionForSpeed(deferredDirection, deferredBlinkSeen,
                    "speed_crossed_limit", now);
            if (cleanupNeeded) {
                speedLimitCleanupPending = true;
                emit("speed_limit_latch_cleanup_pending",
                        "assumed_latch_state", assumedLatchState,
                        "speed_kph", latestSpeedKph, "max_speed_kph", maxSpeedKph);
            }
        }
        boolean live = source == TurnSignalTelemetryController.Source.CALLBACK;
        expireSpeedDeferredSession(live ? observedMs : now);
        if (!live) {
            gestures.observeHealthySnapshot(stalk.raw, blink.raw, observedMs);
        } else if ((liveMask & TurnSignalTelemetryController.LIVE_BLINK) != 0) {
            gestures.observeBlink(blink.raw, observedMs,
                    sessionActive ? sessionDirection : speedDeferred.direction());
        }
        boolean cameraShortTurnEnded = cameraShortTurn.observe(
                stalk.raw, blink.raw, source, liveMask, conflict, observedMs);
        if (live && listenerHealthy
                && (liveMask & TurnSignalTelemetryController.LIVE_STALK) != 0) {
            observeStalk(stalk.raw, "callback", observedMs);
        }
        if (listenerHealthy) {
            observeAngle(angle, live
                    && (liveMask & TurnSignalTelemetryController.LIVE_STEERING) != 0);
        }
        if (live && listenerHealthy
                && (liveMask & TurnSignalTelemetryController.LIVE_BLINK) != 0) {
            observeBlink(blink.raw, observedMs);
        } else {
            latestBlink = blink.raw;
        }
        if (startupCleanupArmedGeneration == awakeSessionGeneration) {
            startupCleanupFreshGeneration = awakeSessionGeneration;
        }
        if (cameraShortTurnEnded || now - lastCameraStateAt >= CAMERA_STATE_PERIOD_MS) {
            emitCameraState();
        }
        evaluateSpeedDeferredSession(now);
        evaluateStartupAwakeSessionCleanup();
        evaluateGuardDisableReset();
        evaluateHazardCleanup();
        evaluateSpeedLimitCleanup();
        evaluateTelemetryRecoveryCleanup();
        evaluateNeutralization();
        evaluateCorrection(now);
        if (!wasTelemetryReady && telemetryReady()) {
            primaryTelemetryError = null;
            emit("telemetry_ready", "ok", true,
                    "listener_ok", true, "poll_ok", true,
                    "control_ready", true, "error", "");
        }
        if (!wasTelemetryReady && telemetryReady() && requestedEnabled) emitGuardConfig();

        if (DiagnosticLogPolicy.extended()
                && (sessionActive || speedDeferred.direction() != 0)
                && now - lastSampleAt >= 200) {
            lastSampleAt = now;
            emit("telemetry_sample", "stalk", stalk.raw, "steering_deg", angle,
                    "speed_kph", speedKph, "max_speed_kph", maxSpeedKph,
                    "blink", blink.raw, "poll_gap_ms", gap,
                    "listener_ok", listenerHealthy, "guard_active", guardActive(),
                    "session", sessionActive, "armed", centerReturnTracker.armed(),
                    "speed_deferred", speedDeferred.direction() != 0,
                    "direction", directionName(sessionActive
                            ? sessionDirection : speedDeferred.direction()));
        }
    }

    private void evaluateTelemetryDeadlines() {
        long now = SystemClock.elapsedRealtime();
        gestures.tick(now);
        boolean fresh = telemetryController.dataFresh(now);
        if (!fresh && pollHealthy) {
            telemetryController.reportFreshnessFailure(now);
            pollHealthy = false;
            invalidateTelemetryOwnership("telemetry_gap_or_invalid");
            TurnSignalTelemetryController.Metrics timing = telemetryController.metrics(now);
            emit("telemetry_error", "reason", "reconciliation_deadline",
                    "deadline_ms", listenerHealthy
                            ? TurnSignalTelemetryController.CALLBACK_GET_DEADLINE_MS
                            : TurnSignalTelemetryController.FALLBACK_GET_DEADLINE_MS,
                    "get_age_ms", timing.getAgeMs, "get_duration_ms", timing.readDurationMs,
                    "callback_dispatch_age_ms", timing.callbackDispatchAgeMs);
        }
        if (now - lastCameraStateAt >= CAMERA_STATE_PERIOD_MS) emitCameraState();
        evaluateSpeedDeferredSession(now);
        evaluateStartupAwakeSessionCleanup();
        evaluateGuardDisableReset();
        evaluateHazardCleanup();
        evaluateSpeedLimitCleanup();
        evaluateTelemetryRecoveryCleanup();
        evaluateNeutralization();
        evaluateCorrection(now);
    }

    private void cancelForReconciliationConflict() {
        resetGesture();
        if (confirmationPending || sessionActive || pendingNeutralizeReason != null) {
            suppress("reconciliation_conflict");
        }
        if (speedDeferred.direction() != 0) cancelSpeedDeferredSession("reconciliation_conflict");
        if (hazardCleanupPending) cancelHazardCleanup("reconciliation_conflict");
        emit("telemetry_reconciled", "action", "silent_no_gesture_or_correction");
    }

    private void telemetryModeChanged(boolean subscribed, boolean seeded, String reason) {
        boolean wasReady = telemetryReady();
        boolean wasListenerHealthy = listenerHealthy;
        listenerHealthy = subscribed && seeded;
        if (!listenerHealthy) {
            primaryTelemetryError = "listener_error: " + reason;
            invalidateTelemetryOwnership("listener_error");
        } else {
            primaryTelemetryError = null;
            if (!wasListenerHealthy) telemetryRecoveryCleanup.recovered();
        }
        emit("telemetry_subscription", "registered", subscribed, "seeded", seeded,
                "mode", subscribed ? "callback_1000ms_reconcile" : "fallback_50ms",
                "reason", reason);
        if (wasReady != telemetryReady() && requestedEnabled) emitGuardConfig();
    }

    private void invalidateTelemetryOwnership(String reason) {
        queueOwnedLatchCleanup(reason);
        cameraShortTurn.reset();
        resetGesture();
        if (hazardCleanupPending) cancelHazardCleanup(reason);
        if (speedDeferred.direction() != 0) cancelSpeedDeferredSession(reason);
        if (sessionActive || pendingNeutralizeReason != null || confirmationPending) {
            suppress(reason);
        }
    }

    private void evaluateStartupAwakeSessionCleanup() {
        long generation = awakeSessionGeneration;
        if (startupCleanupMarkerPendingGeneration == generation) return;
        if (!canRunStartupAwakeSessionCleanup(
                vehicleInteractive,
                requestedEnabled,
                generation,
                startupCleanupArmedGeneration,
                startupCleanupFreshGeneration,
                startupCleanupAttemptedGeneration,
                startupCleanupPersistFailedGeneration,
                telemetryReady()
                        && telemetryController.dataFresh(SystemClock.elapsedRealtime()),
                latestStalk,
                latestBlink,
                writeInFlight,
                manualCommandPending,
                confirmationPending,
                !sessionActive
                        && speedDeferred.direction() == 0
                        && pendingNeutralizeReason == null
                        && !hazardThanksOwnsRequest
                        && !hazardCleanupPending
                        && !speedLimitCleanupPending)) {
            return;
        }
        long lifecycle = lifecycleGeneration;
        startupCleanupMarkerPendingGeneration = generation;
        try {
            startupCleanupAttemptMarker.mark(generation, persisted -> runOnHandler(
                    () -> startupCleanupMarkerCompleted(
                            lifecycle, generation, persisted), "startup_cleanup_marker_result"));
        } catch (Throwable failure) {
            startupCleanupMarkerPendingGeneration = 0;
            startupCleanupPersistFailedGeneration = generation;
            emit("startup_awake_session_cleanup_failed",
                    "awake_session_id", generation,
                    "stage", "persist_attempted_session",
                    "error", diagnostic(failure));
        }
    }

    private void startupCleanupMarkerCompleted(
            long lifecycle, long generation, boolean persisted) {
        if (!started || lifecycle != lifecycleGeneration
                || generation != awakeSessionGeneration
                || startupCleanupMarkerPendingGeneration != generation) return;
        boolean telemetryIsFresh = telemetryController.dataFresh(SystemClock.elapsedRealtime());
        boolean currentlyReady = telemetryReady();
        boolean stillSafe = canRunStartupAwakeSessionCleanup(
                vehicleInteractive,
                requestedEnabled,
                generation,
                startupCleanupArmedGeneration,
                startupCleanupFreshGeneration,
                startupCleanupAttemptedGeneration,
                startupCleanupPersistFailedGeneration,
                currentlyReady && telemetryIsFresh,
                latestStalk,
                latestBlink,
                writeInFlight,
                manualCommandPending,
                confirmationPending,
                !sessionActive
                        && speedDeferred.direction() == 0
                        && pendingNeutralizeReason == null
                        && !hazardThanksOwnsRequest
                        && !hazardCleanupPending
                        && !speedLimitCleanupPending);
        startupCleanupMarkerPendingGeneration = 0;
        if (!persisted) {
            startupCleanupPersistFailedGeneration = generation;
            emit("startup_awake_session_cleanup_failed",
                    "awake_session_id", generation,
                    "stage", "persist_attempted_session");
            return;
        }
        startupCleanupAttemptedGeneration = generation;
        startupCleanupArmedGeneration = 0;
        startupCleanupFreshGeneration = 0;
        if (!stillSafe) {
            emit("startup_awake_session_cleanup_skipped",
                    "awake_session_id", generation,
                    "reason", "safety_preconditions_changed_during_persistence",
                    "interactive", vehicleInteractive,
                    "guard_enabled", requestedEnabled,
                    "telemetry_ready", currentlyReady,
                    "telemetry_fresh", telemetryIsFresh,
                    "stalk", latestStalk, "blink", latestBlink,
                    "manual_command_pending", manualCommandPending,
                    "confirmation_pending", confirmationPending,
                    "session_active", sessionActive,
                    "speed_deferred", speedDeferred.direction() != 0,
                    "write_in_flight", writeInFlight);
            return;
        }
        try {
            int status = writeTurnSignal(0, "startup_awake_session_cleanup");
            emit("startup_awake_session_cleanup",
                    "awake_session_id", generation,
                    "requested_payload", 0,
                    "framework_status", status);
        } catch (Throwable failure) {
            emit("startup_awake_session_cleanup_failed",
                    "awake_session_id", generation,
                    "stage", "framework_write",
                    "requested_payload", 0,
                    "error", summary(failure));
        }
    }

    private void evaluateGuardDisableReset() {
        if (hazardThanksOwnsRequest || !guardDisableResetPending || requestedEnabled
                || !telemetryReady()
                || !safeGuardDisableResetBlink(latestBlink)) {
            return;
        }
        guardDisableResetPending = false;
        if (assumedLatchState == 0) {
            emit("guard_disable_latch_reset", "feature_id", TURN_SIGNAL_SET_FID,
                    "requested_payload", 0, "status", 0, "write_skipped", true,
                    "reason", "already_neutral");
            return;
        }
        try {
            int status = writeTurnSignal(0, "guard_disabled");
            emit("guard_disable_latch_reset", "feature_id", TURN_SIGNAL_SET_FID,
                    "requested_payload", 0, "status", status, "write_skipped", false);
        } catch (Throwable failure) {
            emit("guard_disable_latch_reset", "feature_id", TURN_SIGNAL_SET_FID,
                    "requested_payload", 0, "status", -1, "write_skipped", false,
                    "error", summary(failure));
        }
    }

    private void evaluateSpeedLimitCleanup() {
        if (hazardThanksOwnsRequest || !canRunSpeedLimitCleanup(
                speedLimitCleanupPending, requestedEnabled,
                telemetryReady(), latestBlink, latestStalk)) {
            return;
        }
        speedLimitCleanupPending = false;
        if (!isDirectionLatch(assumedLatchState)) {
            emit("speed_limit_latch_cleanup_skipped", "reason", "already_neutral",
                    "assumed_latch_state", assumedLatchState);
            return;
        }
        if (neutralizeControlLatch("speed_limit_off_cleanup", true)) {
            emit("speed_limit_latch_cleanup_completed",
                    "assumed_latch_state", assumedLatchState,
                    "speed_kph", latestSpeedKph, "max_speed_kph", maxSpeedKph);
        } else {
            emit("speed_limit_latch_cleanup_failed",
                    "assumed_latch_state", assumedLatchState,
                    "speed_kph", latestSpeedKph, "max_speed_kph", maxSpeedKph);
        }
    }

    private void evaluateTelemetryRecoveryCleanup() {
        boolean controlIdle = !sessionActive
                && speedDeferred.direction() == 0
                && pendingNeutralizeReason == null
                && !hazardThanksOwnsRequest
                && !hazardCleanupPending
                && !guardDisableResetPending
                && !speedLimitCleanupPending
                && (startupCleanupArmedGeneration == 0
                        || startupCleanupArmedGeneration != awakeSessionGeneration);
        int previous = assumedLatchState;
        String cleanupReason = telemetryRecoveryCleanup.reason();
        int result = telemetryRecoveryCleanup.evaluate(
                requestedEnabled,
                telemetryReady(),
                assumedLatchState,
                latestBlink,
                latestStalk,
                writeInFlight,
                manualCommandPending,
                confirmationPending,
                controlIdle,
                (featureId, payload) -> featureId == TURN_SIGNAL_SET_FID
                        && payload == 0
                        && neutralizeControlLatch("owned_latch_" + cleanupReason, true));
        if (result == TelemetryRecoveryCleanup.SKIPPED) {
            emit("telemetry_recovery_latch_cleanup_skipped", "reason", "already_neutral",
                    "assumed_latch_state", assumedLatchState);
        } else if (result == TelemetryRecoveryCleanup.COMPLETED) {
            emit("telemetry_recovery_latch_cleanup_completed",
                    "reason", cleanupReason,
                    "previous_assumed_latch_state", previous,
                    "assumed_latch_state", assumedLatchState,
                    "blink", latestBlink, "stalk", latestStalk);
        } else if (result == TelemetryRecoveryCleanup.FAILED) {
            emit("telemetry_recovery_latch_cleanup_failed",
                    "reason", cleanupReason,
                    "previous_assumed_latch_state", previous,
                    "assumed_latch_state", assumedLatchState,
                    "blink", latestBlink, "stalk", latestStalk);
        }
    }

    private void observeStalk(int raw, String source, long now) {
        int activeDirection = sessionActive ? sessionDirection : speedDeferred.direction();
        TurnSignalGestureState.Result gesture =
                gestures.observeStalk(raw, now, latestBlink, activeDirection);
        if (gesture.event == TurnSignalGestureState.Event.NONE) {
            if (gesture.rejection == TurnSignalGestureState.Rejection.AWAITING_NEUTRAL) {
                logRejectedGesture(gesture, source);
            } else if (raw == 1) lastRejectedGesture = null;
            return;
        }
        if (hazardCleanupPending) cancelHazardCleanup("new_stalk_event");
        int direction = gesture.direction;
        if (gesture.event == TurnSignalGestureState.Event.PROMOTION) {
            if (gesture.activation && requestedEnabled) {
                emit("driver_activation_promoted", "direction", directionName(direction),
                        "mode", "long", "stalk", raw, "source", source,
                        "origin", "driver_stalk");
                startSession(direction, now);
            }
            return;
        }

        if (speedDeferred.direction() != 0) {
            int canceledDirection = speedDeferred.direction();
            emit("manual_cancel", "direction", directionName(canceledDirection),
                    "stalk", raw, "source", source,
                    "reason", direction == canceledDirection
                            ? "same_direction" : "direction_change");
            cancelSpeedDeferredSession("driver_stalk");
            if (direction == canceledDirection) return;
        }

        if (sessionActive) {
            int canceledDirection = sessionDirection;
            emit("manual_cancel", "direction", directionName(sessionDirection),
                    "stalk", raw, "source", source,
                    "reason", direction == sessionDirection
                            ? "same_direction" : "direction_change");
            finishSessionForOff("manual_cancel");
            if (direction == canceledDirection) return;
        }

        if (!gesture.activation) {
            logRejectedGesture(gesture, source);
            return;
        }
        lastRejectedGesture = null;

        emit("driver_activation", "direction", directionName(direction),
                "mode", gesture.longMode ? "long" : "short", "stalk", raw, "source", source,
                "origin", "driver_stalk");
        if (gesture.longMode && requestedEnabled) startSession(direction, now);
    }

    private void observeHazardThanksManualSignals(
            int stalk, int mode, String source, long observedMs,
            boolean observeStalk, boolean observeMode) {
        if (!hazardThanksOwnsRequest
                || !hazardThanks.observationIsAfterActivation(observedMs)) return;
        if (observeStalk && stalk != lastHazardThanksStalk) {
            emit("hazard_thanks_stalk_edge", "previous", lastHazardThanksStalk,
                    "value", stalk, "source", source, "observed_ms", observedMs);
            lastHazardThanksStalk = stalk;
        }
        if (observeMode && mode != lastHazardThanksMode) {
            emit("hazard_thanks_mode_edge", "previous", lastHazardThanksMode,
                    "value", mode, "source", source, "observed_ms", observedMs);
            lastHazardThanksMode = mode;
        }
        String stalkCancellation = observeStalk ? hazardThanks.observeStalk(stalk) : null;
        String modeCancellation = observeMode ? hazardThanks.observeMode(mode) : null;
        String reason = stalkCancellation != null ? stalkCancellation : modeCancellation;
        if (reason != null) finishHazardThanks(reason, "cancelled", "");
    }

    private void observeHazardThanksTelemetrySnapshot(
            int stalk, int mode, TurnSignalTelemetryController.Source source,
            int liveMask, long observedMs) {
        if (!hazardThanksOwnsRequest
                || !hazardThanks.observationIsAfterActivation(observedMs)) return;
        if (source != TurnSignalTelemetryController.Source.CALLBACK) return; // Active polls GET both signals.

        // Callback snapshots merge old fields; only the callback's live FID is fresh.
        boolean observeStalk = (liveMask & TurnSignalTelemetryController.LIVE_STALK) != 0;
        boolean observeMode = (liveMask & TurnSignalTelemetryController.LIVE_BLINK) != 0;
        if (!observeStalk && !observeMode) return;
        if ((observeStalk && (stalk < 1 || stalk > 5))
                || (observeMode && (mode < 1 || mode > 9))) {
            finishHazardThanks("required_telemetry_invalid", "cancelled",
                    "stalk=" + (observeStalk ? stalk : "not_observed")
                            + ", mode=" + (observeMode ? mode : "not_observed"));
            return;
        }
        observeHazardThanksManualSignals(stalk, mode, source.name(), observedMs,
                observeStalk, observeMode);
    }

    private void logRejectedGesture(TurnSignalGestureState.Result gesture, String source) {
        String key = gesture.rejection.name() + ":" + gesture.stalk + ":" + latestBlink;
        if (key.equals(lastRejectedGesture)) return;
        lastRejectedGesture = key;
        emit("driver_activation_rejected", "reason", gesture.rejection.name(),
                "direction", directionName(gesture.direction), "stalk", gesture.stalk,
                "blink", latestBlink, "source", source);
    }

    private void startSession(int direction, long now) {
        if (!speedAllowed(latestSpeedKph, maxSpeedKph)) {
            deferSessionForSpeed(direction, latestBlink == direction,
                    "activated_above_limit", now);
            emit("guard_suppressed", "reason", "speed_above_limit",
                    "direction", directionName(direction), "speed_kph", latestSpeedKph,
                    "max_speed_kph", maxSpeedKph);
            return;
        }
        if (!guardActive() || !telemetryController.dataFresh(SystemClock.elapsedRealtime())
                || !Float.isFinite(latestAngle)) {
            emit("guard_suppressed", "reason", "telemetry_or_control_not_ready",
                    "direction", directionName(direction));
            return;
        }
        if (speedLimitCleanupPending) {
            speedLimitCleanupPending = false;
            emit("speed_limit_latch_cleanup_canceled", "reason", "new_guard_session");
        }
        clearSpeedDeferredSession();
        sessionActive = true;
        pendingNeutralizeReason = null;
        sessionDirection = direction;
        centerReturnTracker.start(direction, latestAngle, outwardDeg);
        matchingBlinkSeen = latestBlink == direction;
        corrections = 0;
        offCandidateAt = 0;
        offNeutralized = false;
        confirmationPending = false;
        confirmationDeadline = 0;
        emit("guard_session", "direction", directionName(direction),
                "initial_angle", latestAngle, "speed_kph", latestSpeedKph,
                "max_speed_kph", maxSpeedKph, "armed", centerReturnTracker.armed());
        if (centerReturnTracker.armed()) {
            emit("guard_armed", "direction", directionName(direction),
                    "steering_deg", latestAngle, "initial", true);
        }
    }

    private void observeAngle(float angle, boolean allowArming) {
        if (!sessionActive) return;
        boolean wasArmed = centerReturnTracker.armed();
        boolean complete = centerReturnTracker.observeSession(
                sessionDirection, allowArming, outwardDeg, centerDeg, angle);
        if (!wasArmed && centerReturnTracker.armed()) {
            emit("guard_armed", "direction", directionName(sessionDirection),
                    "steering_deg", angle, "initial", false);
        }
        if (complete) {
            emit("guard_completed", "direction", directionName(sessionDirection),
                    "steering_deg", angle, "corrections", corrections);
            finishSessionForOff("maneuver_completed");
        }
    }

    private void observeBlink(int blink, long now) {
        boolean changed = blink != latestBlink;
        latestBlink = blink;
        if (changed) emitCameraState();
        observeSpeedDeferredBlink(blink, now);
        if (blink == BLINK_HAZARD) {
            if (shouldScheduleHazardCleanup(requestedEnabled, assumedLatchState, blink)
                    && !hazardCleanupPending) {
                hazardCleanupPending = true;
                emit("hazard_cleanup_pending", "assumed_latch_state", assumedLatchState,
                        "blink", blink);
            }
            if (sessionActive || pendingNeutralizeReason != null) {
                suppress("hazard_blink");
            }
            return;
        }
        if (blink == 3 || blink == 5 || blink >= 7) {
            if (hazardCleanupPending) cancelHazardCleanup("fault_or_safety_blink");
            if (sessionActive || pendingNeutralizeReason != null) {
                suppress("fault_or_safety_blink");
            }
            return;
        }
        if (!sessionActive) {
            return;
        }
        if (blink == sessionDirection) {
            matchingBlinkSeen = true;
            offCandidateAt = 0;
            offNeutralized = false;
            if (confirmationPending) {
                confirmationPending = false;
                emit("correction_confirmed", "direction", directionName(sessionDirection),
                        "blink", blink, "corrections", corrections);
            }
            return;
        }
        if (blink == BLINK_OFF && matchingBlinkSeen) {
            if (offCandidateAt == 0) offCandidateAt = now;
            return;
        }
        if (shouldSuppressDirectionChange(blink, sessionDirection, matchingBlinkSeen)) {
            suppress("direction_changed_without_stalk");
        }
    }

    private void deferSessionForSpeed(
            int direction, boolean blinkSeen, String reason, long now) {
        speedDeferred.start(direction, blinkSeen, now);
        emit("guard_speed_deferred", "reason", reason,
                "direction", directionName(direction), "blink_seen", blinkSeen,
                "speed_kph", latestSpeedKph, "max_speed_kph", maxSpeedKph);
    }

    private void observeSpeedDeferredBlink(int blink, long now) {
        String cancellation = speedDeferred.observeBlink(blink, now);
        if (cancellation != null) cancelSpeedDeferredSession(cancellation);
    }

    private void evaluateSpeedDeferredSession(long now) {
        if (expireSpeedDeferredSession(now)) return;
        if (!canResumeSpeedDeferredSession(speedDeferred.direction(),
                speedDeferred.blinkSeen(), requestedEnabled, telemetryReady(),
                latestSpeedKph, maxSpeedKph, latestBlink)) {
            return;
        }
        int direction = speedDeferred.direction();
        clearSpeedDeferredSession();
        emit("guard_speed_deferred_resumed", "direction", directionName(direction),
                "speed_kph", latestSpeedKph, "max_speed_kph", maxSpeedKph);
        startSession(direction, now);
    }

    private boolean expireSpeedDeferredSession(long now) {
        if (!speedDeferred.expired(now)) return false;
        cancelSpeedDeferredSession("blink_start_timeout");
        return true;
    }

    private void cancelSpeedDeferredSession(String reason) {
        int direction = speedDeferred.direction();
        clearSpeedDeferredSession();
        if ("blink_start_timeout".equals(reason)) gestures.forgetExpiredIntent();
        emit("guard_speed_deferred_canceled", "reason", reason,
                "direction", directionName(direction), "blink", latestBlink,
                "speed_kph", latestSpeedKph);
    }

    private void clearSpeedDeferredSession() {
        speedDeferred.clear();
    }

    private void evaluateCorrection(long now) {
        if (!sessionActive || hazardThanksOwnsRequest) return;
        if (confirmationPending && now >= confirmationDeadline) {
            int payload = sessionDirection == BLINK_LEFT ? 2
                    : sessionDirection == BLINK_RIGHT ? 3 : LATCH_UNKNOWN;
            int expected = payload == LATCH_UNKNOWN
                    ? sessionDirection : expectedBlinkForPayload(payload);
            emit("correction_failed", "reason", "blink_confirmation_timeout",
                    "operation", "guard_correction",
                    "direction", directionName(sessionDirection),
                    "payload", payload, "requested_payload", payload,
                    "expected_blink", expected,
                    "observed_blink", latestBlink, "timeout_ms", CORRECTION_CONFIRM_MS,
                    "framework_accepted", true, "framework_status", 0,
                    "classification", confirmationTimeoutClassification(payload, BLINK_OFF));
            finishSessionForOff("correction_timeout_cleanup");
            return;
        }
        if (confirmationPending || offCandidateAt == 0
                || !offNeutralized || now - offCandidateAt < correctionDelayMs
                || latestBlink != BLINK_OFF) {
            return;
        }
        if (!guardActive() || !telemetryController.dataFresh(now)) {
            suppress("telemetry_or_control_not_ready");
            return;
        }
        int direction = sessionDirection;
        int payload = controlPayload(direction);
        int status;
        try {
            status = writeTurnSignal(payload, "guard_correction");
        } catch (Throwable failure) {
            emit("correction_failed", "reason", summary(failure),
                    "direction", directionName(direction), "feature_id", TURN_SIGNAL_SET_FID,
                    "payload", payload);
            resetSession();
            return;
        }
        if (status != 0) {
            emit("correction_failed", "reason", "framework_status_" + status,
                    "direction", directionName(direction), "feature_id", TURN_SIGNAL_SET_FID,
                    "payload", payload, "status", status);
            resetSession();
            return;
        }

        corrections += 1;
        offCandidateAt = 0;
        offNeutralized = false;
        confirmationPending = true;
        confirmationDeadline = now + CORRECTION_CONFIRM_MS;
        emit("correction_requested", "direction", directionName(direction),
                "feature_id", TURN_SIGNAL_SET_FID, "payload", payload,
                "status", status, "correction_delay_ms", correctionDelayMs);
    }

    private void evaluateNeutralization() {
        if (hazardThanksOwnsRequest || latestBlink != BLINK_OFF) return;

        if (pendingNeutralizeReason != null) {
            if (!speedAllowed(latestSpeedKph, maxSpeedKph) && latestStalk != 1) return;
            String reason = pendingNeutralizeReason;
            pendingNeutralizeReason = null;
            neutralizeControlLatch(reason, true);
            return;
        }
        if (!shouldNeutralizePrematureOff(confirmationPending, sessionActive,
                matchingBlinkSeen, offCandidateAt, offNeutralized)) {
            return;
        }
        if (!neutralizeControlLatch("premature_off")) {
            suppress("control_latch_reset_failed");
            return;
        }
        offNeutralized = true;
    }

    private void evaluateHazardCleanup() {
        if (hazardThanksOwnsRequest || !hazardCleanupPending || latestBlink != BLINK_OFF) return;
        if (latestStalk != 1) {
            cancelHazardCleanup("stalk_not_neutral_after_hazard");
            return;
        }
        hazardCleanupPending = false;
        if (neutralizeControlLatch("hazard_off_cleanup")) {
            emit("hazard_cleanup_completed", "assumed_latch_state", assumedLatchState,
                    "blink", latestBlink, "stalk", latestStalk);
        } else {
            emit("hazard_cleanup_failed", "reason", "latch_reset_failed",
                    "blink", latestBlink, "stalk", latestStalk);
        }
    }

    private void cancelHazardCleanup(String reason) {
        hazardCleanupPending = false;
        emit("hazard_cleanup_canceled", "reason", reason,
                "assumed_latch_state", assumedLatchState,
                "blink", latestBlink, "stalk", latestStalk);
    }

    private boolean neutralizeControlLatch(String reason) {
        return neutralizeControlLatch(reason, false);
    }

    private boolean neutralizeControlLatch(String reason, boolean allowAboveSpeed) {
        if (hazardThanksOwnsRequest) {
            emit("control_latch_reset_deferred", "reason", reason,
                    "owner", "hazard_thanks", "feature_id", TURN_SIGNAL_SET_FID,
                    "payload", 0);
            return false;
        }
        boolean ready = allowAboveSpeed
                ? requestedEnabled && telemetryReady() : guardActive();
        if (!ready || !telemetryController.dataFresh(SystemClock.elapsedRealtime())) {
            emit("control_latch_reset_failed", "reason", reason,
                    "error", "telemetry_or_control_not_ready");
            return false;
        }
        ReadResult blink = latestBlink < 0
                ? new ReadResult(-1, null) : new ReadResult(0, latestBlink);
        if (blink.status != 0 || blink.raw == null || blink.raw != BLINK_OFF) {
            emit("control_latch_reset_failed", "reason", reason,
                    "error", "blink_not_confirmed_off", "blink_status", blink.status,
                    "blink", blink.raw == null ? "unknown" : blink.raw);
            return false;
        }
        int status;
        try {
            status = writeTurnSignal(0, "latch_reset_" + reason);
        } catch (Throwable failure) {
            emit("control_latch_reset_failed", "reason", reason,
                    "error", summary(failure), "feature_id", TURN_SIGNAL_SET_FID,
                    "payload", 0);
            return false;
        }
        if (status != 0) {
            emit("control_latch_reset_failed", "reason", reason,
                    "error", "framework_status_" + status,
                    "feature_id", TURN_SIGNAL_SET_FID, "payload", 0,
                    "status", status);
            return false;
        }
        emit("control_latch_reset_accepted", "reason", reason,
                "feature_id", TURN_SIGNAL_SET_FID, "payload", 0,
                "status", status, "blink", blink.raw);
        return true;
    }

    private int writeTurnSignal(int payload, String reason) throws Exception {
        if (hazardThanksOwnsRequest && !reason.startsWith("hazard_thanks_")) {
            throw new IllegalStateException("FID " + TURN_SIGNAL_SET_FID
                    + " owned by thank-you sequence; rejected " + reason);
        }
        if (writeInFlight) throw new IllegalStateException("turn-state write already in flight");
        if (lightDevice == null || setLightFeature == null || eventValueType == null) {
            throw new IllegalStateException("BYD light control unavailable");
        }
        int previous = assumedLatchState;
        writeInFlight = true;
        try {
            Object value = eventValueType.getDeclaredConstructor().newInstance();
            Field intValue = eventValueType.getField("intValue");
            intValue.setInt(value, payload);
            Object result = setLightFeature.invoke(
                    lightDevice, new Object[]{new int[]{TURN_SIGNAL_SET_FID}, value});
            if (!(result instanceof Integer)) {
                throw new IllegalStateException("Unexpected set result");
            }
            int status = (Integer) result;
            if (status == 0) {
                assumedLatchState = payload;
            }
            emit("turn_state_write", "feature_id", TURN_SIGNAL_SET_FID,
                    "origin", "fid_871366669", "reason", reason,
                    "requested_payload", payload,
                    "framework_status", status,
                    "previous_assumed_latch_state", previous,
                    "assumed_latch_state_after", assumedLatchState,
                    "state_edge_expected", previous == LATCH_UNKNOWN
                            ? "unknown" : previous != payload,
                    "blink_before", latestBlink, "stalk", latestStalk);
            return status;
        } catch (Exception failure) {
            emit("turn_state_write_failed", "feature_id", TURN_SIGNAL_SET_FID,
                    "reason", reason, "requested_payload", payload,
                    "previous_assumed_latch_state", previous,
                    "blink_before", latestBlink, "stalk", latestStalk,
                    "error", diagnostic(failure));
            throw failure;
        } finally {
            writeInFlight = false;
        }
    }

    private void initializeControlIfNeeded() {
        if (setLightFeature != null || controlInitRetryStopped) return;
        long now = SystemClock.elapsedRealtime();
        if (lastControlInitAttemptAt != Long.MIN_VALUE
                && now - lastControlInitAttemptAt < TurnSignalTelemetryController.SUBSCRIBE_RETRY_MS) {
            return;
        }
        lastControlInitAttemptAt = now;
        boolean wasReady = telemetryReady();
        String operation = "connect_autoservice";
        try {
            connectAutoservice();
            operation = "prepare_light_control";
            prepareControl();
            boolean recovering = lastControlInitError != null;
            lastControlInitError = null;
            lastControlInitDiagnostic = null;
            telemetryRecoveryCleanup.recovered();
            if (recovering) {
                invalidateTelemetryOwnership("control_recovered");
                telemetryController.reseed();
            }
            emit("turn_control_initialization_ready", "operation", operation);
            if (!wasReady && telemetryReady()) {
                emit("telemetry_ready", "ok", true, "listener_ok", listenerHealthy,
                        "poll_ok", pollHealthy, "control_ready", true, "error", "");
                emitGuardConfig();
            }
        } catch (Throwable failure) {
            Throwable cause = failure;
            while (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            boolean retryable = cause instanceof ControlServiceUnavailableException
                    || cause instanceof android.os.RemoteException;
            controlInitRetryStopped = !retryable;
            lastControlInitError = summary(failure);
            String detail = diagnostic(failure);
            if (!detail.equals(lastControlInitDiagnostic)) {
                lastControlInitDiagnostic = detail;
                emit("turn_control_initialization_failed", "operation", operation,
                        "retryable", retryable, "error", detail);
            }
        }
    }

    private static final class ControlServiceUnavailableException extends Exception {
        ControlServiceUnavailableException(String message) { super(message); }
    }

    private void connectAutoservice() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        autoservice = (IBinder) getService.invoke(null, "autoservice");
        if (autoservice == null) throw new ControlServiceUnavailableException("autoservice not found");
        autoserviceDescriptor = autoservice.getInterfaceDescriptor();
        if (autoserviceDescriptor == null || autoserviceDescriptor.isEmpty()) {
            throw new ControlServiceUnavailableException("autoservice descriptor unavailable");
        }
    }

    private void prepareControl() throws Exception {
        Class<?> lightType = Class.forName("android.hardware.bydauto.light.BYDAutoLightDevice");
        Object device = lightType.getMethod("getInstance", Context.class).invoke(null, context);
        Class<?> valueType = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
        Method setter = device.getClass().getMethod("set", int[].class, valueType);
        lightDevice = device;
        eventValueType = valueType;
        setLightFeature = setter;
    }

    private ReadResult read(Signal signal) {
        if (autoservice == null || autoserviceDescriptor == null) {
            emit("manual_turn_read_failed", "operation", "gear_get", "device", signal.dev,
                    "fid", signal.fid, "reason", "autoservice_not_initialized");
            return new ReadResult(-900, null);
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(autoserviceDescriptor);
            data.writeInt(signal.dev);
            data.writeInt(signal.fid);
            if (!autoservice.transact(signal.tx, data, reply, 0)) {
                emit("manual_turn_read_failed", "operation", "gear_get", "device", signal.dev,
                        "fid", signal.fid, "reason", "transact_returned_false");
                return new ReadResult(-901, null);
            }
            int bytes = reply.dataAvail();
            int status = reply.dataAvail() >= 4 ? reply.readInt() : -902;
            Integer raw = reply.dataAvail() >= 4 ? reply.readInt() : null;
            if (status != 0 || raw == null) {
                emit("manual_turn_read_failed", "operation", "gear_get", "device", signal.dev,
                        "fid", signal.fid, "status", status, "reply_bytes", bytes,
                        "reason", bytes < 8 ? "short_reply" : "oem_status");
            }
            return new ReadResult(status, raw);
        } catch (Throwable error) {
            emit("manual_turn_read_failed", "operation", "gear_get", "device", signal.dev,
                    "fid", signal.fid, "error", diagnostic(error));
            return new ReadResult(-903, null);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void suppress(String reason) {
        queueOwnedLatchCleanup(reason);
        emit("guard_suppressed", "reason", reason,
                "direction", directionName(sessionDirection),
                "steering_deg", Float.isFinite(latestAngle) ? latestAngle : "unknown",
                "blink", latestBlink, "corrections", corrections);
        resetSession();
    }

    private void queueOwnedLatchCleanup(String reason) {
        if (telemetryRecoveryCleanup.queue(reason, assumedLatchState)) {
            emit("telemetry_recovery_latch_cleanup_pending",
                    "previous_reason", reason,
                    "assumed_latch_state", assumedLatchState,
                    "requested_payload", 0);
        }
    }

    private void finishSessionForOff(String reason) {
        boolean alreadyNeutralized = offNeutralized && latestBlink == BLINK_OFF;
        resetSession();
        if (alreadyNeutralized) {
            emit("control_latch_reset_skipped", "reason", reason,
                    "detail", "already_neutralized_for_current_off");
        } else {
            pendingNeutralizeReason = reason;
        }
    }

    private void resetSession() {
        sessionActive = false;
        sessionDirection = 0;
        centerReturnTracker.reset();
        matchingBlinkSeen = false;
        corrections = 0;
        offCandidateAt = 0;
        offNeutralized = false;
        pendingNeutralizeReason = null;
        confirmationPending = false;
        confirmationDeadline = 0;
    }

    private void resetGesture() {
        gestures.resetForRecovery();
        lastRejectedGesture = null;
    }

    private boolean telemetryReady() {
        return listenerHealthy && pollHealthy && setLightFeature != null;
    }

    private boolean guardActive() {
        return requestedEnabled && telemetryReady()
                && speedAllowed(latestSpeedKph, maxSpeedKph);
    }

    private String guardInactiveReason() {
        if (!telemetryReady()) return telemetryError();
        return speedAllowed(latestSpeedKph, maxSpeedKph) ? "" : "speed_above_limit";
    }

    private String telemetryError() {
        if (lastControlInitError != null && setLightFeature == null) return lastControlInitError;
        return primaryTelemetryError == null
                ? "telemetry_or_control_not_ready" : primaryTelemetryError;
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private void postTimer(Runnable task, long delayMs) {
        Message message = Message.obtain(handler, task);
        message.obj = timerToken;
        handler.sendMessageDelayed(message, delayMs);
    }

    private void emitCameraState() {
        long emittedAt = SystemClock.elapsedRealtime();
        lastCameraStateAt = emittedAt;
        boolean valid = pollHealthy && Float.isFinite(latestSpeedKph)
                && Float.isFinite(latestAngle)
                && telemetryController.dataFresh(emittedAt);
        emit("vehicle_state", "valid", valid, "blink", latestBlink,
                "speed_kph", valid ? latestSpeedKph : "unknown",
                "steering_angle_deg", valid ? latestAngle : "unknown",
                "short_turn_end_ms", valid ? cameraShortTurn.endedAt() : 0,
                "short_turn_end_direction", valid ? cameraShortTurn.endedDirection() : 0);
    }

    private void runOnHandler(Runnable action) {
        runOnHandler(action, "runtime_dispatch");
    }

    private void runOnHandler(Runnable action, String operation) {
        boolean runInline;
        boolean posted = true;
        synchronized (dispatchLock) {
            if (shutdownRequested) return;
            runInline = Looper.myLooper() == handler.getLooper();
            if (!runInline) posted = handler.post(action);
        }
        if (runInline) action.run();
        else if (!posted) {
            throw new IllegalStateException("guard handler rejected " + operation
                    + " on looper " + handler.getLooper().getThread().getName());
        }
    }

    interface StartupCleanupAttemptMarker {
        void mark(long generation, Consumer<Boolean> completed);
    }

    private static Float decodeAngle(ReadResult result) {
        if (result.status != 0 || result.raw == null) return null;
        float value = Float.intBitsToFloat(result.raw);
        return Float.isFinite(value) && value >= -780.0f && value <= 780.0f ? value : null;
    }

    private static Float decodeSpeed(ReadResult result) {
        if (result.status != 0 || result.raw == null) return null;
        float value = Float.intBitsToFloat(result.raw);
        return validSpeedValue(value) ? value : null;
    }

    static boolean validThresholds(float outward, float center) {
        return Float.isFinite(outward) && Float.isFinite(center)
                && outward >= 0.0f && outward <= 360.0f
                && center >= 0.0f && center <= 45.0f;
    }

    private static boolean validCorrectionDelay(int value) {
        return value >= 0 && value <= 1_000;
    }

    static boolean validMaxSpeed(int value) {
        return value >= 0 && value <= 300;
    }

    static boolean validSpeedValue(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 300.0f;
    }

    static boolean pollFresh(long now, long lastPollAt) {
        return lastPollAt > 0 && now >= lastPollAt && now - lastPollAt <= MAX_POLL_GAP_MS;
    }

    static String manualPrecheckReason(
            int payload, boolean commandPending, boolean guardEnabled,
            long now, long lastPollAt) {
        if (payload < 0 || payload > 3) return "payload_not_whitelisted";
        if (commandPending) return "command_already_pending";
        if (guardEnabled) return "disable_guard_before_manual_command";
        if (!pollFresh(now, lastPollAt)) return "stale_poll";
        return null;
    }

    static String manualTelemetryRejectionReason(
            int payload, boolean telemetryReady,
            int gearStatus, Integer gear, int blinkStatus, Integer blink) {
        boolean blinkAllowed = payload == 0
                ? blink != null && (blink == BLINK_OFF || blink == BLINK_LEFT
                        || blink == BLINK_RIGHT || blink == BLINK_HAZARD)
                : blink != null && blink == BLINK_OFF;
        return telemetryReady && gearStatus == 0 && gear != null && gear == 1
                && blinkStatus == 0 && blinkAllowed
                ? null : "requires_healthy_telemetry_P_and_safe_blink_state";
    }

    static boolean speedAllowed(float speedKph, int maxSpeedKph) {
        return validSpeedValue(speedKph) && validMaxSpeed(maxSpeedKph)
                && speedKph <= maxSpeedKph;
    }

    static boolean canRunSpeedLimitCleanup(
            boolean pending, boolean enabled, boolean telemetryReady, int blink, int stalk) {
        return pending && enabled && telemetryReady && blink == BLINK_OFF && stalk == 1;
    }

    static boolean shouldQueueTelemetryRecoveryCleanup(String reason, int assumedPayload) {
        return isDirectionLatch(assumedPayload);
    }

    static boolean canRunTelemetryRecoveryCleanup(
            boolean pending,
            boolean enabled,
            boolean telemetryReady,
            int assumedPayload,
            int blink,
            int stalk,
            boolean writeInFlight,
            boolean manualCommandPending,
            boolean confirmationPending,
            boolean controlIdle) {
        return isDirectionLatch(assumedPayload)
                && canRunSpeedLimitCleanup(pending, enabled, telemetryReady, blink, stalk)
                && !writeInFlight
                && !manualCommandPending
                && !confirmationPending
                && controlIdle;
    }

    static final class TelemetryRecoveryCleanup {
        static final int WAIT = 0;
        static final int SKIPPED = 1;
        static final int COMPLETED = 2;
        static final int FAILED = 3;

        private boolean pending;
        private boolean attempted;
        private String reason = "unknown";

        boolean queue(String reason, int assumedPayload) {
            boolean queued = shouldQueueTelemetryRecoveryCleanup(reason, assumedPayload);
            if (!queued || pending) return false;
            pending = true;
            attempted = false;
            this.reason = reason;
            return true;
        }

        void recovered() { attempted = false; }

        String reason() { return reason; }

        int evaluate(
                boolean enabled,
                boolean telemetryReady,
                int assumedPayload,
                int blink,
                int stalk,
                boolean writeInFlight,
                boolean manualCommandPending,
                boolean confirmationPending,
                boolean controlIdle,
                BiPredicate<Integer, Integer> writer) {
            if (!pending) return WAIT;
            if (!isDirectionLatch(assumedPayload)) {
                clear();
                return SKIPPED;
            }
            if (attempted) return WAIT;
            if (!canRunTelemetryRecoveryCleanup(
                    true, enabled, telemetryReady, assumedPayload, blink, stalk,
                    writeInFlight, manualCommandPending, confirmationPending, controlIdle)) {
                return WAIT;
            }
            attempted = true;
            if (!writer.test(TURN_SIGNAL_SET_FID, 0)) return FAILED;
            clear();
            return COMPLETED;
        }

        void clear() {
            pending = false;
            attempted = false;
            reason = "unknown";
        }
    }

    static boolean canResumeSpeedDeferredSession(
            int direction, boolean blinkSeen, boolean enabled, boolean telemetryReady,
            float speedKph, int maxSpeedKph, int blink) {
        return direction != 0 && blinkSeen && enabled && telemetryReady
                && speedAllowed(speedKph, maxSpeedKph) && blink == direction;
    }

    static boolean guardConfigurationChanged(
            boolean currentEnabled, float currentOutward, float currentCenter, int currentDelay,
            int currentMaxSpeed, boolean nextEnabled, float nextOutward, float nextCenter,
            int nextDelay, int nextMaxSpeed) {
        return currentEnabled != nextEnabled
                || Float.compare(currentOutward, nextOutward) != 0
                || Float.compare(currentCenter, nextCenter) != 0
                || currentDelay != nextDelay
                || currentMaxSpeed != nextMaxSpeed;
    }

    static boolean shouldNeutralizePrematureOff(
            boolean confirmationPending, boolean sessionActive,
            boolean matchingBlinkSeen, long offCandidateAt, boolean offNeutralized) {
        return !confirmationPending && sessionActive && matchingBlinkSeen
                && offCandidateAt != 0 && !offNeutralized;
    }

    static boolean shouldSuppressDirectionChange(
            int blink, int sessionDirection, boolean matchingBlinkSeen) {
        return matchingBlinkSeen && (blink == BLINK_LEFT || blink == BLINK_RIGHT)
                && blink != sessionDirection;
    }

    private static int directionForStalk(int stalk) {
        if (stalk == 2 || stalk == 3) return BLINK_LEFT;
        if (stalk == 4 || stalk == 5) return BLINK_RIGHT;
        return 0;
    }

    private static float towardAngle(int direction, float signedAngle) {
        return direction == BLINK_LEFT ? signedAngle
                : direction == BLINK_RIGHT ? -signedAngle : Float.NEGATIVE_INFINITY;
    }

    static final class CenterReturnTracker {
        private boolean hasPrevious;
        private float previousAngle;
        private boolean armed;

        void start(int direction, float angle, float outwardDeg) {
            reset();
            seed(angle);
            armed = towardAngle(direction, angle) >= outwardDeg;
        }

        boolean armed() { return armed; }

        boolean observeSession(int direction, boolean liveSteering,
                float outwardDeg, float centerDeg, float angle) {
            if (liveSteering && towardAngle(direction, angle) >= outwardDeg) armed = true;
            return observe(direction, armed, centerDeg, angle);
        }

        void seed(float angle) {
            previousAngle = angle;
            hasPrevious = true;
        }

        boolean observe(int direction, boolean armed, float centerDeg, float angle) {
            boolean crossed = hasPrevious
                    && ((direction == BLINK_LEFT && previousAngle > 0.0f && angle <= 0.0f)
                    || (direction == BLINK_RIGHT && previousAngle < 0.0f && angle >= 0.0f));
            previousAngle = angle;
            hasPrevious = true;
            return armed && (Math.abs(angle) <= centerDeg || crossed);
        }

        void reset() {
            hasPrevious = false;
            previousAngle = 0.0f;
            armed = false;
        }
    }

    private static int controlPayload(int direction) {
        if (direction == BLINK_LEFT) return 2;
        if (direction == BLINK_RIGHT) return 3;
        throw new IllegalArgumentException("Invalid direction " + direction);
    }

    private static boolean isDirectionLatch(int payload) {
        return payload == 2 || payload == 3;
    }

    private static boolean shouldScheduleHazardCleanup(
            boolean enabled, int assumedPayload, int blink) {
        return enabled && blink == BLINK_HAZARD && isDirectionLatch(assumedPayload);
    }

    static boolean safeGuardDisableResetBlink(int blink) {
        return blink == BLINK_OFF || blink == BLINK_LEFT || blink == BLINK_RIGHT;
    }

    static boolean canRunStartupAwakeSessionCleanup(
            boolean interactive,
            boolean enabled,
            long generation,
            long armedGeneration,
            long freshGeneration,
            long attemptedGeneration,
            long persistFailedGeneration,
            boolean telemetryReady,
            int stalk,
            int blink,
            boolean writeInFlight,
            boolean manualCommandPending,
            boolean confirmationPending,
            boolean controlIdle) {
        return interactive && enabled && generation > 0
                && armedGeneration == generation
                && freshGeneration == generation
                && attemptedGeneration != generation
                && persistFailedGeneration != generation
                && telemetryReady
                && stalk == 1
                && blink == BLINK_OFF
                && !writeInFlight
                && !manualCommandPending
                && !confirmationPending
                && controlIdle;
    }

    private static int expectedBlinkForPayload(int payload) {
        if (payload == 0) return BLINK_OFF;
        if (payload == 1) return BLINK_HAZARD;
        if (payload == 2) return BLINK_LEFT;
        if (payload == 3) return BLINK_RIGHT;
        throw new IllegalArgumentException("Invalid payload " + payload);
    }

    private static String manualActionName(int payload) {
        if (payload == 0) return "reset_hazard_off";
        if (payload == 1) return "hazard_on";
        if (payload == 2) return "left";
        if (payload == 3) return "right";
        return "invalid";
    }

    static String manualConfirmationOperation(int payload) {
        if (payload == 0) return "manual_reset";
        if (payload == 1) return "manual_hazard_on";
        if (payload == 2) return "manual_left_activation";
        if (payload == 3) return "manual_right_activation";
        return "manual_invalid";
    }

    static boolean shouldContinueManualConfirmation(
            boolean started, boolean pending, int generation, int currentGeneration) {
        return started && pending && generation == currentGeneration;
    }

    static String confirmationTimeoutClassification(int payload, int blinkBefore) {
        if (payload == 1) return "hazard_on_transition_not_observed";
        if (payload == 2 || payload == 3) return "direction_activation_not_observed";
        if (payload == 0 && blinkBefore == BLINK_HAZARD) {
            return "hazard_off_transition_not_observed";
        }
        if (payload == 0 && blinkBefore == BLINK_OFF) {
            return "payload_zero_off_confirmation_not_observed";
        }
        if (payload == 0) return "direction_deactivation_not_observed";
        return "unsupported_payload_confirmation_timeout";
    }

    static String confirmationSuccessClassification(int payload, int blinkBefore) {
        if (payload == 0 && blinkBefore == BLINK_OFF) {
            return "accepted_no_observable_transition";
        }
        if (payload == 0 && blinkBefore == BLINK_HAZARD) {
            return "hazard_off_transition_observed";
        }
        if (payload == 0) return "direction_deactivation_observed";
        if (payload == 1) return "hazard_on_transition_observed";
        if (payload == 2 || payload == 3) return "direction_activation_observed";
        return "unsupported_payload_confirmed";
    }

    private static String directionName(int direction) {
        if (direction == BLINK_LEFT) return "left";
        if (direction == BLINK_RIGHT) return "right";
        return "none";
    }

    private static void selfCheck() {
        boolean valid = directionForStalk(2) == BLINK_LEFT
                && directionForStalk(3) == BLINK_LEFT
                && directionForStalk(4) == BLINK_RIGHT
                && directionForStalk(5) == BLINK_RIGHT
                && towardAngle(BLINK_LEFT, 90.0f) == 90.0f
                && towardAngle(BLINK_RIGHT, -90.0f) == 90.0f
                && towardAngle(BLINK_RIGHT, 90.0f) == -90.0f
                && controlPayload(BLINK_LEFT) == 2
                && controlPayload(BLINK_RIGHT) == 3
                && shouldScheduleHazardCleanup(true, 2, BLINK_HAZARD)
                && shouldScheduleHazardCleanup(true, 3, BLINK_HAZARD)
                && !shouldScheduleHazardCleanup(false, 3, BLINK_HAZARD)
                && !shouldScheduleHazardCleanup(true, 0, BLINK_HAZARD)
                && expectedBlinkForPayload(0) == BLINK_OFF
                && expectedBlinkForPayload(1) == BLINK_HAZARD
                && expectedBlinkForPayload(2) == BLINK_LEFT
                && expectedBlinkForPayload(3) == BLINK_RIGHT
                && GEAR.tx == 5 && GEAR.dev == 1011 && GEAR.fid == 555745336
                && validThresholds(90.0f, 10.0f)
                && validThresholds(0.0f, 0.0f)
                && validThresholds(360.0f, 45.0f)
                && !validThresholds(-0.1f, 0.0f)
                && !validThresholds(0.0f, 45.1f)
                && validCorrectionDelay(0)
                && validCorrectionDelay(1_000)
                && !validCorrectionDelay(-1)
                && !validCorrectionDelay(1_001)
                && validMaxSpeed(0)
                && validMaxSpeed(300)
                && !validMaxSpeed(-1)
                && !validMaxSpeed(301)
                && speedAllowed(0.0f, 0)
                && speedAllowed(30.0f, 30)
                && !speedAllowed(30.1f, 30);
        if (!valid) throw new IllegalStateException("Guard mapping self-check failed");
    }

    private static String summary(Throwable error) {
        Throwable root = error;
        while (root instanceof InvocationTargetException && root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String diagnostic(Throwable error) {
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static final class Signal {
        final int tx;
        final int dev;
        final int fid;

        Signal(int tx, int dev, int fid) {
            this.tx = tx;
            this.dev = dev;
            this.fid = fid;
        }
    }

    private static final class ReadResult {
        final int status;
        final Integer raw;

        ReadResult(int status, Integer raw) {
            this.status = status;
            this.raw = raw;
        }

        boolean okRange(int min, int max) {
            return status == 0 && raw != null && raw >= min && raw <= max;
        }
    }
}
