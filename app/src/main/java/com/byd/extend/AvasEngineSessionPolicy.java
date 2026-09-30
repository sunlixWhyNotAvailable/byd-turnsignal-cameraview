package com.byd.extend;

/** Pure raw-power and manual-control policy for one virtual engine session. */
public final class AvasEngineSessionPolicy {
    public static final int POWER_OFF = 0;
    public static final int POWER_ACC = 1;
    public static final int POWER_ON = 2;
    public static final int POWER_OK = 3;
    public static final int POWER_FAKE_OK = 4;
    public static final int POWER_INVALID = 255;

    public enum Action { NONE, START, RESTORE, STOP_WITH_TAIL, STOP_NOW }

    private boolean enabled;
    private boolean outputsPresent;
    private boolean active;
    private boolean suppressed;
    private boolean recoveryPending;
    private boolean powerUnknown;
    private boolean hasValidPower;
    private int rawPower = POWER_INVALID;
    private int lastValidPower = POWER_INVALID;

    public Action configure(boolean enabled, boolean outputsPresent) {
        this.enabled = enabled;
        this.outputsPresent = outputsPresent;
        if (!available()) {
            if (active) {
                active = false;
                recoveryPending = false;
                return Action.STOP_NOW;
            }
            return Action.NONE;
        }
        if (!active && !suppressed && hasValidPower && !powerUnknown
                && lastValidPower == POWER_ON) {
            return start(true);
        }
        return Action.NONE;
    }

    public Action observePower(int raw, boolean baseline) {
        rawPower = raw;
        if (!isValidPower(raw)) {
            powerUnknown = true;
            return Action.NONE;
        }
        boolean recovering = powerUnknown;
        powerUnknown = false;
        boolean hadValidPower = hasValidPower;
        int previousPower = lastValidPower;
        hasValidPower = true;
        lastValidPower = raw;

        if (raw == POWER_OFF) {
            if (hadValidPower && previousPower == POWER_OFF) return Action.NONE;
            suppressed = false;
            recoveryPending = false;
            if (!active) return Action.NONE;
            active = false;
            return Action.STOP_WITH_TAIL;
        }
        if (raw != POWER_ON || !available() || suppressed || active) return Action.NONE;
        if (baseline || recovering || recoveryPending || !hadValidPower || previousPower == POWER_ON) {
            return start(true);
        }
        if (previousPower == POWER_OFF || previousPower == POWER_ACC
                || previousPower == POWER_OK || previousPower == POWER_FAKE_OK) {
            return start(false);
        }
        return start(true);
    }

    public Action manualStart() {
        if (!available()) return Action.NONE;
        suppressed = false;
        if (active) return Action.NONE;
        return start(recoveryPending);
    }

    public Action manualStop() {
        suppressed = true;
        recoveryPending = false;
        if (!active) return Action.NONE;
        active = false;
        return Action.STOP_NOW;
    }

    /** Clears failed playback while preserving power and manual-stop suppression state. */
    public void playbackFailed() {
        if (active) recoveryPending = true;
        active = false;
    }

    public boolean desiredActive() { return active; }
    public boolean hasValidPower() { return hasValidPower && !powerUnknown; }
    public int rawPower() { return rawPower; }
    public int lastValidPower() { return lastValidPower; }

    private Action start(boolean restore) {
        active = true;
        recoveryPending = false;
        return restore ? Action.RESTORE : Action.START;
    }

    private boolean available() { return enabled && outputsPresent; }

    private static boolean isValidPower(int raw) {
        return raw >= POWER_OFF && raw <= POWER_FAKE_OK;
    }
}
