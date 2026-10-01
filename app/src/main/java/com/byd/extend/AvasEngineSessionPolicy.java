package com.byd.extend;

/** OK controls live sound; a manual test temporarily owns the single output without disabling live. */
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
    private boolean liveRequested;
    private boolean testing;
    private boolean recoveryPending;
    private boolean powerUnknown;
    private boolean hasValidPower;
    private int rawPower = POWER_INVALID;
    private int lastValidPower = POWER_INVALID;
    private Boolean ready;
    private Boolean lastKnownReady;
    private boolean ignitionPending;

    public Action configure(boolean enabled, boolean outputsPresent) {
        this.enabled = enabled;
        this.outputsPresent = outputsPresent;
        if (!available()) {
            ready = null;
            liveRequested = false;
            testing = false;
            recoveryPending = false;
            if (active) {
                active = false;
                return Action.STOP_NOW;
            }
            return Action.NONE;
        }
        return reconcileReady();
    }

    public Action observeReady(Boolean present) {
        if (hasValidPower && lastValidPower == POWER_OFF) return Action.NONE;
        ready = present;
        if (present != null) {
            if (present && Boolean.FALSE.equals(lastKnownReady)) ignitionPending = true;
            if (!present) ignitionPending = false;
            lastKnownReady = present;
        }
        return reconcileReady();
    }

    private Action reconcileReady() {
        if (!available() || !hasValidPower || powerUnknown || lastValidPower == POWER_OFF) {
            return Action.NONE;
        }
        boolean wasLive = liveRequested;
        if (Boolean.TRUE.equals(ready)) liveRequested = true;
        if (!liveRequested || testing || active) return Action.NONE;
        if (recoveryPending && !Boolean.TRUE.equals(ready)) return Action.NONE;
        boolean restore = wasLive || recoveryPending || !ignitionPending;
        ignitionPending = false;
        return start(restore);
    }

    public Action observePower(int raw, boolean baseline) {
        rawPower = raw;
        if (!isValidPower(raw)) {
            powerUnknown = true;
            return Action.NONE;
        }
        powerUnknown = false;
        boolean hadValidPower = hasValidPower;
        int previousPower = lastValidPower;
        hasValidPower = true;
        lastValidPower = raw;

        if (raw == POWER_OFF) {
            if (hadValidPower && previousPower == POWER_OFF) return Action.NONE;
            ready = null;
            lastKnownReady = false;
            ignitionPending = false;
            liveRequested = false;
            testing = false;
            recoveryPending = false;
            if (!active) return Action.NONE;
            active = false;
            return Action.STOP_WITH_TAIL;
        }
        return reconcileReady();
    }

    /** Starts only the test override; vehicle demand continues to update underneath it. */
    public Action manualStart() {
        if (!available() || testing) return Action.NONE;
        testing = true;
        active = true;
        return Action.START;
    }

    /** A repeated/stale Stop must never stop live playback. */
    public Action manualStop() {
        if (!testing) return Action.NONE;
        testing = false;
        active = false;
        Action live = reconcileReady();
        return live == Action.NONE ? Action.STOP_NOW : live;
    }

    /** A failed test releases its override; a failed live output retains the existing retry policy. */
    public Action playbackFailed() {
        boolean wasTesting = testing;
        testing = false;
        if (active && !wasTesting) recoveryPending = true;
        active = false;
        return wasTesting ? reconcileReady() : Action.NONE;
    }

    public boolean desiredActive() { return active; }
    public boolean testActive() { return testing; }
    public boolean liveRequested() { return liveRequested; }
    public boolean hasValidPower() { return hasValidPower && !powerUnknown; }
    public int rawPower() { return rawPower; }
    public int lastValidPower() { return lastValidPower; }

    private Action start(boolean restore) {
        active = true;
        ignitionPending = false;
        recoveryPending = false;
        return restore ? Action.RESTORE : Action.START;
    }

    private boolean available() { return enabled && outputsPresent; }

    private static boolean isValidPower(int raw) {
        return raw >= POWER_OFF && raw <= POWER_FAKE_OK;
    }
}
