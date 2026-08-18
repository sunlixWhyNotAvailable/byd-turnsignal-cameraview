package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.LongPredicate;

final class TurnSignalGuardRuntime {
    private static final int PERIOD_MS = 50;
    private static final int MAX_POLL_GAP_MS = 250;
    private static final int RECENT_CANCEL_MS = 250;
    private static final int DEFERRED_BLINK_START_TIMEOUT_MS = 2_000;
    private static final int DEFAULT_CORRECTION_DELAY_MS = 100;
    private static final int DEFAULT_MAX_SPEED_KPH = 30;
    private static final int CORRECTION_CONFIRM_MS = 1_500;
    private static final int TELEMETRY_ERROR_REPEAT_MS = 5_000;
    private static final int CAMERA_STATE_PERIOD_MS = 200;
    private static final int TURN_SIGNAL_SET_FID = 871366669;
    private static final int LATCH_UNKNOWN = -1;

    private static final Signal STALK = new Signal(5, 1004, 321912876);
    private static final Signal STEERING = new Signal(7, 1001, 300941320);
    private static final Signal BLINK = new Signal(5, 1004, 950009900);
    private static final Signal SPEED = new Signal(7, 1013, -1807745016);
    private static final Signal GEAR = new Signal(5, 1011, 555745336);

    private static final int BLINK_OFF = 1;
    private static final int BLINK_LEFT = 2;
    private static final int BLINK_RIGHT = 4;
    private static final int BLINK_HAZARD = 6;

    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;
    private final LongPredicate startupCleanupAttemptMarker;
    private final Runnable pollRunnable = this::pollAndSchedule;

    private IBinder autoservice;
    private String autoserviceDescriptor;
    private Object lightDevice;
    private Object listener;
    private Method unregisterListener;
    private Method setLightFeature;
    private Class<?> eventValueType;

    private boolean started;
    private boolean requestedEnabled;
    private boolean listenerHealthy;
    private boolean pollHealthy;
    private boolean gestureLatched;
    private int gestureDirection;
    private boolean gestureLongSeen;
    private boolean gestureWasActivation;
    private float outwardDeg = 90.0f;
    private float centerDeg = 10.0f;
    private int correctionDelayMs = DEFAULT_CORRECTION_DELAY_MS;
    private int maxSpeedKph = DEFAULT_MAX_SPEED_KPH;
    private float latestAngle = Float.NaN;
    private float latestSpeedKph = Float.NaN;
    private int latestStalk = -1;
    private int latestBlink = -1;
    private int blinkBeforeLastChange = -1;
    private long lastBlinkChangeAt;
    private long lastPollAt;
    private long lastGestureAt;
    private long lastSampleAt;
    private long lastCameraStateAt;

    private boolean sessionActive;
    private int sessionDirection;
    private boolean outwardSeen;
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
    private int speedDeferredDirection;
    private boolean speedDeferredBlinkSeen;
    private long speedDeferredAt;
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
    private boolean writeInFlight;

    TurnSignalGuardRuntime(
            Context context,
            Handler handler,
            BiConsumer<String, Object[]> eventSink,
            LongPredicate startupCleanupAttemptMarker) {
        this.context = context;
        this.handler = handler;
        this.eventSink = eventSink;
        this.startupCleanupAttemptMarker = startupCleanupAttemptMarker;
        assumedLatchState = LATCH_UNKNOWN;
    }

    void start() {
        runOnHandler(this::startOnHandler);
    }

    void stop() {
        runOnHandler(this::stopOnHandler);
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
                initialAssumedLatchState));
    }

    void vehiclePowerStateChanged(
            boolean interactive, long generation, long cleanupAttemptedGeneration) {
        runOnHandler(() -> vehiclePowerStateChangedOnHandler(
                interactive, generation, cleanupAttemptedGeneration));
    }

    void reportStatus() {
        runOnHandler(() -> {
            emit("telemetry_ready", "ok", telemetryReady(),
                    "listener_ok", listenerHealthy, "poll_ok", pollHealthy,
                    "control_ready", setLightFeature != null,
                    "error", telemetryReady() ? "" : telemetryError());
            emit("turn_state_status", "feature_id", TURN_SIGNAL_SET_FID,
                    "assumed_latch_state", assumedLatchState);
            emitCameraState(SystemClock.elapsedRealtime());
            emitGuardConfig();
        });
    }

    void setManualTurnState(int payload) {
        runOnHandler(() -> setManualTurnStateOnHandler(payload));
    }

    private void startOnHandler() {
        if (started) return;
        selfCheck();
        started = true;
        String error = null;
        try {
            connectAutoservice();
            registerListener();
            prepareControl();
            pollOnce();
        } catch (Throwable failure) {
            error = summary(failure);
            primaryTelemetryError = error;
            listenerHealthy = false;
            pollHealthy = false;
        }
        emit("guard_self_check", "ok", true);
        emit("telemetry_ready", "ok", telemetryReady(),
                "listener_ok", listenerHealthy, "poll_ok", pollHealthy,
                "control_ready", setLightFeature != null,
                "error", telemetryReady() ? ""
                        : error == null ? telemetryError() : error);
        handler.postDelayed(pollRunnable, PERIOD_MS);
    }

    private void stopOnHandler() {
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
        handler.removeCallbacks(pollRunnable);
        resetSession();
        boolean unregistered = false;
        String error = null;
        try {
            if (unregisterListener != null && lightDevice != null && listener != null) {
                unregisterListener.invoke(lightDevice, listener);
                unregistered = true;
            }
        } catch (Throwable failure) {
            error = summary(failure);
        }
        listenerHealthy = false;
        pollHealthy = false;
        emit("telemetry_stopped", "listener_unregistered", unregistered,
                "error", error == null ? "" : error);
    }

    private void setManualTurnStateOnHandler(int payload) {
        long now = SystemClock.elapsedRealtime();
        String precheck = manualPrecheckReason(
                payload, manualCommandPending, requestedEnabled, now, lastPollAt);
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
        ReadResult blink = read(BLINK);
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
        ReadResult blink = read(BLINK);
        if (blink.status != 0 || blink.raw == null || !listenerHealthy) {
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
        handler.postDelayed(() -> awaitManualTurnStateConfirmation(
                payload, blinkBefore, deadline, generation), PERIOD_MS);
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
        vehicleInteractive = interactive;
        awakeSessionGeneration = Math.max(0, generation);
        startupCleanupAttemptedGeneration = Math.max(0, cleanupAttemptedGeneration);
        if (!interactive) {
            startupCleanupArmedGeneration = 0;
            startupCleanupFreshGeneration = 0;
            return;
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
        telemetryRecoveryCleanup.clear();
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
        pollOnce();
        if (started) handler.postDelayed(pollRunnable, PERIOD_MS);
    }

    private void pollOnce() {
        long now = SystemClock.elapsedRealtime();
        long gap = lastPollAt == 0 ? 0 : now - lastPollAt;
        lastPollAt = now;

        ReadResult stalk = read(STALK);
        ReadResult steering = read(STEERING);
        ReadResult blink = read(BLINK);
        ReadResult speed = read(SPEED);
        Float angle = decodeAngle(steering);
        Float speedKph = decodeSpeed(speed);
        boolean valid = stalk.okRange(1, 5) && blink.okRange(1, 9)
                && angle != null && speedKph != null;
        boolean gapOk = gap == 0 || gap <= MAX_POLL_GAP_MS;

        if (!valid || !gapOk) {
            boolean wasHealthy = pollHealthy;
            pollHealthy = false;
            if (hazardCleanupPending) cancelHazardCleanup("telemetry_gap_or_invalid");
            if (speedDeferredDirection != 0) {
                cancelSpeedDeferredSession("telemetry_gap_or_invalid");
            }
            if (sessionActive || pendingNeutralizeReason != null) {
                suppress("telemetry_gap_or_invalid");
            }
            String reason = gapOk ? "invalid_signal" : "poll_gap";
            if ((wasHealthy || requestedEnabled)
                    && (!reason.equals(lastTelemetryErrorReason)
                    || now - lastTelemetryErrorAt >= TELEMETRY_ERROR_REPEAT_MS)) {
                lastTelemetryErrorAt = now;
                lastTelemetryErrorReason = reason;
                emit("telemetry_error", "reason", reason,
                        "gap_ms", gap, "stalk_status", stalk.status,
                        "steering_status", steering.status, "blink_status", blink.status,
                        "speed_status", speed.status);
            }
            if (wasHealthy || now - lastCameraStateAt >= CAMERA_STATE_PERIOD_MS) {
                emitCameraState(now);
            }
            return;
        }

        boolean recovered = !pollHealthy;
        pollHealthy = true;
        if (recovered && lastTelemetryErrorReason != null) {
            emit("telemetry_recovered", "previous_reason", lastTelemetryErrorReason);
            lastTelemetryErrorReason = null;
        }
        latestAngle = angle;
        latestSpeedKph = speedKph;
        latestStalk = stalk.raw;
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
        observeStalk(stalk.raw, "poll", now);
        observeAngle(angle);
        observeBlink(blink.raw, now);
        if (startupCleanupArmedGeneration == awakeSessionGeneration) {
            startupCleanupFreshGeneration = awakeSessionGeneration;
        }
        if (now - lastCameraStateAt >= CAMERA_STATE_PERIOD_MS) {
            emitCameraState(now);
        }
        evaluateSpeedDeferredSession(now);
        evaluateStartupAwakeSessionCleanup();
        evaluateGuardDisableReset();
        evaluateHazardCleanup();
        evaluateSpeedLimitCleanup();
        evaluateTelemetryRecoveryCleanup();
        evaluateNeutralization();
        evaluateCorrection(now);
        if (recovered && telemetryReady()) {
            primaryTelemetryError = null;
            emit("telemetry_ready", "ok", true,
                    "listener_ok", true, "poll_ok", true,
                    "control_ready", true, "error", "");
        }
        if (recovered && requestedEnabled) emitGuardConfig();

        if ((sessionActive || speedDeferredDirection != 0) && now - lastSampleAt >= 200) {
            lastSampleAt = now;
            emit("telemetry_sample", "stalk", stalk.raw, "steering_deg", angle,
                    "speed_kph", speedKph, "max_speed_kph", maxSpeedKph,
                    "blink", blink.raw, "poll_gap_ms", gap,
                    "listener_ok", listenerHealthy, "guard_active", guardActive(),
                    "session", sessionActive, "armed", outwardSeen,
                    "speed_deferred", speedDeferredDirection != 0,
                    "direction", directionName(sessionActive
                            ? sessionDirection : speedDeferredDirection));
        }
    }

    private void evaluateStartupAwakeSessionCleanup() {
        long generation = awakeSessionGeneration;
        if (!canRunStartupAwakeSessionCleanup(
                vehicleInteractive,
                requestedEnabled,
                generation,
                startupCleanupArmedGeneration,
                startupCleanupFreshGeneration,
                startupCleanupAttemptedGeneration,
                startupCleanupPersistFailedGeneration,
                telemetryReady(),
                latestStalk,
                latestBlink,
                writeInFlight,
                manualCommandPending,
                confirmationPending,
                !sessionActive
                        && speedDeferredDirection == 0
                        && pendingNeutralizeReason == null
                        && !hazardCleanupPending
                        && !speedLimitCleanupPending)) {
            return;
        }
        if (!startupCleanupAttemptMarker.test(generation)) {
            startupCleanupPersistFailedGeneration = generation;
            emit("startup_awake_session_cleanup_failed",
                    "awake_session_id", generation,
                    "stage", "persist_attempted_session");
            return;
        }
        startupCleanupAttemptedGeneration = generation;
        startupCleanupArmedGeneration = 0;
        startupCleanupFreshGeneration = 0;
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
        if (!guardDisableResetPending || requestedEnabled || !telemetryReady()
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
        if (!canRunSpeedLimitCleanup(speedLimitCleanupPending, requestedEnabled,
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
                && speedDeferredDirection == 0
                && pendingNeutralizeReason == null
                && !hazardCleanupPending
                && !guardDisableResetPending
                && !speedLimitCleanupPending
                && (startupCleanupArmedGeneration == 0
                        || startupCleanupArmedGeneration != awakeSessionGeneration);
        int previous = assumedLatchState;
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
                        && neutralizeControlLatch("telemetry_recovered_off_cleanup", true));
        if (result == TelemetryRecoveryCleanup.SKIPPED) {
            emit("telemetry_recovery_latch_cleanup_skipped", "reason", "already_neutral",
                    "assumed_latch_state", assumedLatchState);
        } else if (result == TelemetryRecoveryCleanup.COMPLETED) {
            emit("telemetry_recovery_latch_cleanup_completed",
                    "previous_assumed_latch_state", previous,
                    "assumed_latch_state", assumedLatchState,
                    "blink", latestBlink, "stalk", latestStalk);
        } else if (result == TelemetryRecoveryCleanup.FAILED) {
            emit("telemetry_recovery_latch_cleanup_failed",
                    "previous_assumed_latch_state", previous,
                    "assumed_latch_state", assumedLatchState,
                    "blink", latestBlink, "stalk", latestStalk);
        }
    }

    private void observeStalk(int raw, String source, long now) {
        if (raw == 1) {
            if (now - lastGestureAt >= 150) {
                gestureLatched = false;
                gestureDirection = 0;
                gestureLongSeen = false;
                gestureWasActivation = false;
            }
            return;
        }
        if (raw < 2 || raw > 5) return;
        if (hazardCleanupPending) cancelHazardCleanup("new_stalk_event");

        int direction = directionForStalk(raw);
        boolean longMode = raw == 3 || raw == 5;
        if (gestureLatched) {
            if (longMode && !gestureLongSeen && direction == gestureDirection) {
                gestureLongSeen = true;
                if (gestureWasActivation && requestedEnabled) {
                    emit("driver_activation_promoted", "direction", directionName(direction),
                            "mode", "long", "stalk", raw, "source", source,
                            "origin", "driver_stalk");
                    startSession(direction, now);
                }
            }
            return;
        }
        gestureLatched = true;
        gestureDirection = direction;
        gestureLongSeen = longMode;
        gestureWasActivation = false;
        lastGestureAt = now;

        if (speedDeferredDirection != 0) {
            int canceledDirection = speedDeferredDirection;
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

        boolean activation = isActivationGesture(
                direction, latestBlink, blinkBeforeLastChange, now - lastBlinkChangeAt);
        gestureWasActivation = activation;
        if (!activation) return;

        emit("driver_activation", "direction", directionName(direction),
                "mode", longMode ? "long" : "short", "stalk", raw, "source", source,
                "origin", "driver_stalk");
        if (longMode && requestedEnabled) startSession(direction, now);
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
        if (!guardActive() || now - lastPollAt > MAX_POLL_GAP_MS
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
        outwardSeen = towardAngle(direction, latestAngle) >= outwardDeg;
        matchingBlinkSeen = latestBlink == direction;
        corrections = 0;
        offCandidateAt = 0;
        offNeutralized = false;
        confirmationPending = false;
        confirmationDeadline = 0;
        emit("guard_session", "direction", directionName(direction),
                "initial_angle", latestAngle, "speed_kph", latestSpeedKph,
                "max_speed_kph", maxSpeedKph, "armed", outwardSeen);
        if (outwardSeen) {
            emit("guard_armed", "direction", directionName(direction),
                    "steering_deg", latestAngle, "initial", true);
        }
    }

    private void observeAngle(float angle) {
        if (!sessionActive) return;
        if (!outwardSeen && towardAngle(sessionDirection, angle) >= outwardDeg) {
            outwardSeen = true;
            emit("guard_armed", "direction", directionName(sessionDirection),
                    "steering_deg", angle, "initial", false);
        }
        if (outwardSeen && Math.abs(angle) <= centerDeg) {
            emit("guard_completed", "direction", directionName(sessionDirection),
                    "steering_deg", angle, "corrections", corrections);
            finishSessionForOff("maneuver_completed");
        }
    }

    private void observeBlink(int blink, long now) {
        boolean changed = blink != latestBlink;
        if (changed) {
            blinkBeforeLastChange = latestBlink;
            lastBlinkChangeAt = now;
        }
        latestBlink = blink;
        if (changed) emitCameraState(now);
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
        speedDeferredDirection = direction;
        speedDeferredBlinkSeen = blinkSeen;
        speedDeferredAt = now;
        emit("guard_speed_deferred", "reason", reason,
                "direction", directionName(direction), "blink_seen", blinkSeen,
                "speed_kph", latestSpeedKph, "max_speed_kph", maxSpeedKph);
    }

    private void observeSpeedDeferredBlink(int blink, long now) {
        if (speedDeferredDirection == 0) return;
        if (blink == speedDeferredDirection) {
            speedDeferredBlinkSeen = true;
            return;
        }
        if (shouldCancelSpeedDeferredSession(
                speedDeferredDirection, speedDeferredBlinkSeen, blink)) {
            cancelSpeedDeferredSession("blink_changed");
            return;
        }
        if (!speedDeferredBlinkSeen
                && now - speedDeferredAt >= DEFERRED_BLINK_START_TIMEOUT_MS) {
            cancelSpeedDeferredSession("blink_start_timeout");
        }
    }

    private void evaluateSpeedDeferredSession(long now) {
        if (!canResumeSpeedDeferredSession(speedDeferredDirection,
                speedDeferredBlinkSeen, requestedEnabled, telemetryReady(),
                latestSpeedKph, maxSpeedKph, latestBlink)) {
            return;
        }
        int direction = speedDeferredDirection;
        clearSpeedDeferredSession();
        emit("guard_speed_deferred_resumed", "direction", directionName(direction),
                "speed_kph", latestSpeedKph, "max_speed_kph", maxSpeedKph);
        startSession(direction, now);
    }

    private void cancelSpeedDeferredSession(String reason) {
        int direction = speedDeferredDirection;
        clearSpeedDeferredSession();
        emit("guard_speed_deferred_canceled", "reason", reason,
                "direction", directionName(direction), "blink", latestBlink,
                "speed_kph", latestSpeedKph);
    }

    private void clearSpeedDeferredSession() {
        speedDeferredDirection = 0;
        speedDeferredBlinkSeen = false;
        speedDeferredAt = 0;
    }

    private void evaluateCorrection(long now) {
        if (!sessionActive) return;
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
        if (!guardActive() || now - lastPollAt > MAX_POLL_GAP_MS) {
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
        if (latestBlink != BLINK_OFF) return;

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
        if (!hazardCleanupPending || latestBlink != BLINK_OFF) return;
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
        boolean ready = allowAboveSpeed
                ? requestedEnabled && telemetryReady() : guardActive();
        if (!ready || SystemClock.elapsedRealtime() - lastPollAt > MAX_POLL_GAP_MS) {
            emit("control_latch_reset_failed", "reason", reason,
                    "error", "telemetry_or_control_not_ready");
            return false;
        }
        ReadResult blink = read(BLINK);
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
                    "error", summary(failure));
            throw failure;
        } finally {
            writeInFlight = false;
        }
    }

    private void connectAutoservice() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        autoservice = (IBinder) getService.invoke(null, "autoservice");
        if (autoservice == null) throw new IllegalStateException("autoservice not found");
        autoserviceDescriptor = autoservice.getInterfaceDescriptor();
        if (autoserviceDescriptor == null || autoserviceDescriptor.isEmpty()) {
            throw new IllegalStateException("autoservice descriptor unavailable");
        }
    }

    private void registerListener() throws Exception {
        Class<?> listenerType = Class.forName("android.hardware.IBYDAutoListener");
        Class<?> eventType = Class.forName("android.hardware.IBYDAutoEvent");
        Method getEventId = eventType.getMethod("getEventType");
        Method getEventValue = eventType.getMethod("getValue");
        listener = Proxy.newProxyInstance(
                TurnSignalGuardRuntime.class.getClassLoader(),
                new Class<?>[]{listenerType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return "TurnSignalGuardListener";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                    }
                    if ("onDataChanged".equals(method.getName())) {
                        int fid = (Integer) getEventId.invoke(args[0]);
                        int raw = (Integer) getEventValue.invoke(args[0]);
                        handler.post(() -> handleListenerEvent(fid, raw));
                    } else if ("onError".equals(method.getName())) {
                        int code = (Integer) args[0];
                        String message = String.valueOf(args[1]);
                        handler.post(() -> listenerFailed(code + ": " + message));
                    }
                    return null;
                });

        Class<?> lightType = Class.forName("android.hardware.bydauto.light.BYDAutoLightDevice");
        lightDevice = lightType.getMethod("getInstance", Context.class).invoke(null, context);
        Method register = findListenerMethod(lightDevice, "registerListener", true);
        unregisterListener = findListenerMethod(lightDevice, "unregisterListener", false);
        register.invoke(lightDevice, listener, new int[]{STALK.fid, BLINK.fid});
        listenerHealthy = true;
    }

    private void prepareControl() throws Exception {
        eventValueType = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
        setLightFeature = lightDevice.getClass().getMethod("set", int[].class, eventValueType);
    }

    private Method findListenerMethod(Object device, String name, boolean withFeatureIds)
            throws NoSuchMethodException {
        for (Method method : device.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name)
                    && parameters.length == (withFeatureIds ? 2 : 1)
                    && parameters[0].isAssignableFrom(listener.getClass())
                    && (!withFeatureIds || parameters[1] == int[].class)) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private void handleListenerEvent(int fid, int raw) {
        if (!started) return;
        emit("listener_event", "fid", fid,
                "signal", fid == STALK.fid ? "stalk" : fid == BLINK.fid ? "blink" : "unexpected",
                "raw", raw);
        long now = SystemClock.elapsedRealtime();
        if (fid == STALK.fid) {
            if (raw < 1 || raw > 5) {
                listenerFailed("invalid stalk callback " + raw);
            } else {
                latestStalk = raw;
                observeStalk(raw, "listener", now);
            }
        } else if (fid == BLINK.fid) {
            if (raw < 1 || raw > 9) {
                listenerFailed("invalid blink callback " + raw);
            } else {
                observeBlink(raw, now);
            }
        } else {
            listenerFailed("unexpected callback fid " + fid);
        }
    }

    private void listenerFailed(String reason) {
        listenerHealthy = false;
        primaryTelemetryError = "listener_error: " + reason;
        if (hazardCleanupPending) cancelHazardCleanup("listener_error");
        if (sessionActive || pendingNeutralizeReason != null) suppress("listener_error");
        emit("telemetry_error", "reason", "listener_error", "error", reason);
        emitGuardConfig();
    }

    private ReadResult read(Signal signal) {
        if (autoservice == null || autoserviceDescriptor == null) return new ReadResult(-900, null);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(autoserviceDescriptor);
            data.writeInt(signal.dev);
            data.writeInt(signal.fid);
            if (!autoservice.transact(signal.tx, data, reply, 0)) {
                return new ReadResult(-901, null);
            }
            int status = reply.dataAvail() >= 4 ? reply.readInt() : -902;
            Integer raw = reply.dataAvail() >= 4 ? reply.readInt() : null;
            return new ReadResult(status, raw);
        } catch (Throwable error) {
            return new ReadResult(-903, null);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void suppress(String reason) {
        boolean queueTelemetryRecoveryCleanup =
                telemetryRecoveryCleanup.queue(reason, assumedLatchState);
        emit("guard_suppressed", "reason", reason,
                "direction", directionName(sessionDirection),
                "steering_deg", Float.isFinite(latestAngle) ? latestAngle : "unknown",
                "blink", latestBlink, "corrections", corrections);
        resetSession();
        if (queueTelemetryRecoveryCleanup) {
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
        outwardSeen = false;
        matchingBlinkSeen = false;
        corrections = 0;
        offCandidateAt = 0;
        offNeutralized = false;
        pendingNeutralizeReason = null;
        confirmationPending = false;
        confirmationDeadline = 0;
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
        return primaryTelemetryError == null
                ? "telemetry_or_control_not_ready" : primaryTelemetryError;
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private void emitCameraState(long now) {
        lastCameraStateAt = now;
        boolean valid = pollHealthy && Float.isFinite(latestSpeedKph)
                && Float.isFinite(latestAngle)
                && lastPollAt != 0 && now - lastPollAt <= MAX_POLL_GAP_MS;
        emit("vehicle_state", "valid", valid, "blink", latestBlink,
                "speed_kph", valid ? latestSpeedKph : "unknown",
                "steering_angle_deg", valid ? latestAngle : "unknown");
    }

    private void runOnHandler(Runnable action) {
        if (Looper.myLooper() == handler.getLooper()) action.run();
        else handler.post(action);
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

    private static boolean validThresholds(float outward, float center) {
        return Float.isFinite(outward) && Float.isFinite(center)
                && outward >= 30.0f && outward <= 360.0f
                && center >= 2.0f && center <= 45.0f && center < outward;
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

    static int turnSignalSetFidForTest() {
        return TURN_SIGNAL_SET_FID;
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
        return "telemetry_gap_or_invalid".equals(reason)
                && isDirectionLatch(assumedPayload);
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

        boolean queue(String reason, int assumedPayload) {
            boolean queued = shouldQueueTelemetryRecoveryCleanup(reason, assumedPayload);
            if (queued) pending = true;
            return queued;
        }

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
                pending = false;
                return SKIPPED;
            }
            if (!canRunTelemetryRecoveryCleanup(
                    true, enabled, telemetryReady, assumedPayload, blink, stalk,
                    writeInFlight, manualCommandPending, confirmationPending, controlIdle)) {
                return WAIT;
            }
            pending = false;
            return writer.test(TURN_SIGNAL_SET_FID, 0) ? COMPLETED : FAILED;
        }

        void clear() {
            pending = false;
        }
    }

    static boolean canResumeSpeedDeferredSession(
            int direction, boolean blinkSeen, boolean enabled, boolean telemetryReady,
            float speedKph, int maxSpeedKph, int blink) {
        return direction != 0 && blinkSeen && enabled && telemetryReady
                && speedAllowed(speedKph, maxSpeedKph) && blink == direction;
    }

    static boolean shouldCancelSpeedDeferredSession(
            int direction, boolean blinkSeen, int blink) {
        if (direction == 0 || blink == direction) return false;
        if (!blinkSeen) return blink == BLINK_HAZARD || blink == 3 || blink == 5 || blink >= 7;
        return true;
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

    private static boolean isActivationGesture(
            int direction, int blink, int previousBlink, long blinkAgeMs) {
        boolean normalBlink = blink == BLINK_OFF || blink == BLINK_LEFT || blink == BLINK_RIGHT;
        boolean justCanceled = blinkAgeMs >= 0 && blinkAgeMs <= RECENT_CANCEL_MS
                && blink == BLINK_OFF && previousBlink == direction;
        return normalBlink && !justCanceled && blink != direction;
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
                && SPEED.tx == 7 && SPEED.dev == 1013 && SPEED.fid == -1807745016
                && isActivationGesture(BLINK_LEFT, BLINK_OFF, -1, 1_000)
                && isActivationGesture(BLINK_RIGHT, BLINK_LEFT, BLINK_OFF, 500)
                && !isActivationGesture(BLINK_LEFT, BLINK_LEFT, BLINK_OFF, 100)
                && !isActivationGesture(BLINK_LEFT, BLINK_OFF, BLINK_LEFT, 100)
                && validThresholds(90.0f, 10.0f)
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

    static void selfCheckForTest() {
        selfCheck();
    }

    private static String summary(Throwable error) {
        Throwable root = error;
        while (root instanceof InvocationTargetException && root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
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
