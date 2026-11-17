package com.byd.extend;

/** OK or the selector controls live sound; a test temporarily owns the single output. */
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
    private boolean tailing;
    private boolean liveChoiceMade;
    private boolean recoveryPending;
    private boolean powerUnknown;
    private boolean hasValidPower;
    private int rawPower = POWER_INVALID;
    private int lastValidPower = POWER_INVALID;
    private Boolean ready;
    private Boolean lastKnownReady;
    private boolean ignitionPending;
    private boolean configured;
    private boolean buttonOnly;
    private boolean soundInPark = true;
    private int rawGear = Integer.MIN_VALUE;
    private Boolean inPark;
    private Boolean lastKnownInPark;
    private Boolean rememberedChoice;

    public AvasEngineSessionPolicy() { this(null); }

    AvasEngineSessionPolicy(Boolean rememberedChoice) {
        this.rememberedChoice = rememberedChoice;
    }

    public Action configure(boolean enabled, boolean outputsPresent) {
        return configure(enabled, outputsPresent, false);
    }

    public Action configure(boolean enabled, boolean outputsPresent, boolean buttonOnly) {
        return configure(enabled, outputsPresent, buttonOnly, true);
    }

    public Action configure(boolean enabled, boolean outputsPresent, boolean buttonOnly, boolean soundInPark) {
        boolean wasGearAutomatic = gearAutomatic();
        if (buttonOnly && !this.buttonOnly && (configured || rememberedChoice == null)) {
            rememberedChoice = liveRequested;
        }
        this.buttonOnly = buttonOnly;
        this.soundInPark = soundInPark;
        configured = true;
        boolean wasPlaying = active || tailing;
        this.enabled = enabled;
        this.outputsPresent = outputsPresent;
        if (!available()) {
            ready = null;
            lastKnownReady = null;
            liveChoiceMade = false;
            liveRequested = false;
            testing = false;
            active = false;
            tailing = false;
            recoveryPending = false;
            ignitionPending = false;
            clearGear();
            return wasPlaying ? Action.STOP_NOW : Action.NONE;
        }
        if (wasGearAutomatic && !gearAutomatic() && !buttonOnly && !liveChoiceMade
                && Boolean.TRUE.equals(ready)) requestAutomatic(true);
        return reconcileReady();
    }

    public Action observeReady(Boolean present) {
        if (hasValidPower && lastValidPower == POWER_OFF) return Action.NONE;
        ready = present;
        if (gearAutomatic()) {
            if (present != null) lastKnownReady = present;
            return Action.NONE;
        }
        if (present != null) {
            if (present) {
                boolean wasLive = liveRequested;
                if (buttonOnly) {
                    liveRequested = Boolean.TRUE.equals(rememberedChoice);
                    if (!wasLive && liveRequested && !testing) ignitionPending = true;
                } else if (Boolean.FALSE.equals(lastKnownReady)) {
                    liveRequested = true;
                    if (!wasLive && !testing) ignitionPending = true;
                } else if (lastKnownReady == null && !liveChoiceMade) {
                    liveRequested = true;
                    ignitionPending = true;
                }
            } else {
                ignitionPending = false;
            }
            lastKnownReady = present;
        }
        return reconcileReady();
    }

    private Action reconcileReady() {
        if (!available() || !hasValidPower || powerUnknown || lastValidPower == POWER_OFF) {
            return Action.NONE;
        }
        if (gearAutomatic() && !liveChoiceMade && inPark != null) {
            Action action = requestAutomatic(!inPark);
            if (action != Action.NONE) return action;
        }
        if (!liveRequested || testing || active || tailing) return Action.NONE;
        if (recoveryPending && !recoveryAllowed()) return Action.NONE;
        if (!liveChoiceMade && !automaticReady()) return Action.NONE;
        return start(recoveryPending || !ignitionPending, false);
    }

    public Action observeGear(int raw) {
        if (!available() || hasValidPower && lastValidPower == POWER_OFF) return Action.NONE;
        rawGear = raw;
        inPark = raw >= 1 && raw <= 6 ? raw == 1 : null;
        if (inPark == null) return Action.NONE;
        if (gearAutomatic() && lastKnownInPark != null && !lastKnownInPark.equals(inPark)) {
            liveChoiceMade = false;
        }
        lastKnownInPark = inPark;
        return gearAutomatic() ? reconcileReady() : Action.NONE;
    }

    private Action requestAutomatic(boolean value) {
        boolean wasLive = liveRequested;
        liveRequested = value;
        if (value) {
            if (!wasLive && !testing) ignitionPending = true;
            return Action.NONE;
        }
        ignitionPending = false;
        recoveryPending = false;
        if (testing || tailing || !active) return Action.NONE;
        active = false;
        tailing = true;
        return Action.STOP_WITH_TAIL;
    }

    private boolean automaticReady() {
        return gearAutomatic() ? Boolean.FALSE.equals(inPark) : Boolean.TRUE.equals(ready);
    }

    private boolean recoveryAllowed() {
        return automaticReady() || gearAutomatic() && liveChoiceMade;
    }

    void clearGear() {
        rawGear = Integer.MIN_VALUE;
        inPark = null;
        lastKnownInPark = null;
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
            clearGear();
            if (hadValidPower && previousPower == POWER_OFF) return Action.NONE;
            ready = null;
            lastKnownReady = false;
            ignitionPending = false;
            liveChoiceMade = false;
            liveRequested = false;
            testing = false;
            recoveryPending = false;
            if (tailing) return Action.NONE;
            if (!active) return Action.NONE;
            active = false;
            tailing = true;
            return Action.STOP_WITH_TAIL;
        }
        return reconcileReady();
    }

    /** Starts only the test override; vehicle demand continues to update underneath it. */
    public Action manualStart() {
        if (!available() || testing || tailing) return Action.NONE;
        testing = true;
        recoveryPending = false;
        return start(false, true);
    }

    /** Toggles live demand for the current power session, independently of test playback. */
    public Action toggleLive() {
        if (!available()) return Action.NONE;
        liveChoiceMade = true;
        liveRequested = !liveRequested;
        if (buttonOnly) rememberedChoice = liveRequested;
        recoveryPending = false;
        if (!liveRequested) {
            ignitionPending = false;
            if (testing || tailing || !active) return Action.NONE;
            active = false;
            tailing = true;
            return Action.STOP_WITH_TAIL;
        }
        if (testing) {
            ignitionPending = false;
            return Action.NONE;
        }
        if (tailing) {
            ignitionPending = true;
            return Action.NONE;
        }
        if (active) {
            ignitionPending = false;
            return Action.NONE;
        }
        ignitionPending = false;
        return start(false, false);
    }

    /** Reconciles only after the current shutdown recording and its cue gate have finished. */
    public Action playbackTailFinished() {
        if (!tailing) return Action.NONE;
        tailing = false;
        return startAfterTail();
    }

    private Action startAfterTail() {
        if (!available() || !liveRequested || testing || active) return Action.NONE;
        if (recoveryPending && !recoveryAllowed()) return Action.NONE;
        if (!liveChoiceMade && (!hasValidPower || powerUnknown || lastValidPower == POWER_OFF
                || !automaticReady())) return Action.NONE;
        return start(recoveryPending || !ignitionPending, false);
    }

    /** A repeated/stale Stop must never stop live playback. */
    public Action manualStop() {
        if (!testing) return Action.NONE;
        testing = false;
        active = false;
        recoveryPending = false;
        ignitionPending = false;
        if (!gearAutomatic()) return liveRequested ? start(true, false) : Action.STOP_NOW;
        Action action = reconcileReady();
        return action == Action.NONE ? Action.STOP_NOW : action;
    }

    /** A failed test releases its override; a failed live output retains the existing retry policy. */
    public Action playbackFailed() {
        boolean wasTesting = testing;
        boolean wasTailing = tailing;
        boolean wasActive = active;
        testing = false;
        active = false;
        tailing = false;
        if (wasTailing) return startAfterTail();
        if (wasTesting) {
            recoveryPending = false;
            ignitionPending = false;
            return gearAutomatic() ? reconcileReady() : liveRequested ? start(true, false) : Action.NONE;
        }
        if (wasActive && liveRequested) recoveryPending = true;
        return Action.NONE;
    }

    public boolean desiredActive() { return liveRequested || testing; }
    public boolean testActive() { return testing; }
    public boolean liveRequested() { return liveRequested; }
    Boolean rememberedChoice() { return rememberedChoice; }
    public boolean hasValidPower() { return hasValidPower && !powerUnknown; }
    public int rawPower() { return rawPower; }
    public int lastValidPower() { return lastValidPower; }
    public boolean gearAutomatic() { return !soundInPark && !buttonOnly; }
    public int rawGear() { return rawGear; }
    public boolean gearValid() { return inPark != null; }
    public boolean manualOverride() { return liveChoiceMade; }

    private Action start(boolean restore, boolean test) {
        active = true;
        testing = test;
        ignitionPending = false;
        recoveryPending = false;
        return restore ? Action.RESTORE : Action.START;
    }

    private boolean available() { return enabled && outputsPresent; }

    private static boolean isValidPower(int raw) {
        return raw >= POWER_OFF && raw <= POWER_FAKE_OK;
    }
}
