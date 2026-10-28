package com.byd.extend;

/** Worker-side eligibility and warm-up state; app callbacks never participate in this decision. */
final class MotionSessionRuntime {
    static final long ARM_DELAY_MS = MotionDetectorProtocol.WARMUP_MS;

    enum Change { NONE, ARMING, ARMED, DISARMED, EPOCH_CHANGED }

    private final MotionTelemetryState telemetry = new MotionTelemetryState();
    private MotionConfig config;
    private String sessionId;
    private String sourceEpoch;
    private long armDeadlineMs;
    private boolean armed;
    private boolean suspended;
    private boolean warningRetryBlocked;
    private long recordingRevision;

    void configure(String sessionId, MotionConfig config) {
        if (!MotionDetectorProtocol.validSession(sessionId) || config == null)
            throw new IllegalArgumentException("valid Motion session and config are required");
        this.sessionId = sessionId;
        this.config = config;
        sourceEpoch = null;
        telemetry.invalidate();
        armDeadlineMs = 0L;
        armed = false;
        suspended = false;
        warningRetryBlocked = false;
        recordingRevision++;
    }

    Change updateInPlaceConfig(MotionConfig next, long nowMs) {
        if (nowMs < 0) throw new IllegalArgumentException("negative elapsed time");
        if (config == null || !next.onlyInPlaceMotionChangesDifferFrom(config))
            throw new IllegalArgumentException("only Motion modes and sensitivity may change in place");
        boolean modesChanged = config.warningEnabled != next.warningEnabled
                || config.recordingEnabled != next.recordingEnabled;
        if (config.recordingEnabled != next.recordingEnabled) recordingRevision++;
        config = next;
        // Sensitivity does not affect eligibility. Let the existing warm-up callback advance it.
        return modesChanged ? advance(nowMs) : Change.NONE;
    }

    Change telemetry(String epoch, String signal, int value, boolean baseline, long nowMs) {
        if (sessionId == null || suspended || epoch == null || epoch.isEmpty()
                || epoch.length() > 128 || nowMs < 0) return Change.NONE;
        boolean changedEpoch = sourceEpoch != null && !sourceEpoch.equals(epoch);
        if (changedEpoch) {
            telemetry.invalidate();
            armDeadlineMs = 0L;
            armed = false;
            recordingRevision++;
        }
        sourceEpoch = epoch;
        if (config != null
                && ("power".equals(signal) && AvasEventPolicy.normalizedPower(value) > 0
                    || config.lockCondition != 0 && "lock".equals(signal) && value == 1)) {
            warningRetryBlocked = false;
            recordingRevision++;
        }
        if ("power".equals(signal)) telemetry.power(value, baseline);
        else if ("lock".equals(signal)) telemetry.lock(value, baseline);
        else return changedEpoch ? Change.EPOCH_CHANGED : Change.NONE;
        Change eligibility = advance(nowMs);
        return changedEpoch ? Change.EPOCH_CHANGED : eligibility;
    }

    Change advance(long nowMs) {
        boolean eligible = !suspended && config != null && config.motionEnabled()
                && telemetry.eligible(config);
        if (!eligible) {
            if (armed || armDeadlineMs != 0L) {
                armed = false;
                armDeadlineMs = 0L;
                return Change.DISARMED;
            }
            return Change.NONE;
        }
        if (armed) return Change.NONE;
        if (armDeadlineMs == 0L) {
            armDeadlineMs = nowMs + ARM_DELAY_MS;
            return Change.ARMING;
        }
        if (nowMs >= armDeadlineMs) {
            armDeadlineMs = 0L;
            armed = true;
            return Change.ARMED;
        }
        return Change.NONE;
    }

    void suspendForHostShutdown() {
        suspended = true;
        telemetry.invalidate();
        armDeadlineMs = 0L;
        armed = false;
    }

    void invalidateTelemetry() {
        telemetry.invalidate();
        armDeadlineMs = 0L;
        armed = false;
    }

    void cameraLost() {
        // A camera error does not invalidate independently observed Power/Lock.
        armDeadlineMs = 0L;
        armed = false;
    }

    boolean needsTelemetry() {
        return !suspended && config != null && config.anyEnabled() && !telemetry.readyFor(config);
    }

    String sessionId() { return sessionId; }
    String sourceEpoch() { return sourceEpoch; }
    MotionConfig config() { return config; }
    int power() { return telemetry.power(); }
    int lock() { return telemetry.lock(); }
    boolean armed() { return armed; }
    boolean arming() { return armDeadlineMs != 0L && !armed; }
    long remainingArmDelayMs(long nowMs) { return arming() ? Math.max(0L, armDeadlineMs - nowMs) : 0L; }
    long recordingRevision() { return recordingRevision; }
    boolean suspended() { return suspended; }
    void blockWarningRetry() { warningRetryBlocked = true; }
    void warningBaselineReconciled() { warningRetryBlocked = false; }
    boolean warningRetryBlocked() { return warningRetryBlocked; }
}
