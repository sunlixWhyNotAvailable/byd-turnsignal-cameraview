package com.byd.extend;

/** A borrowed display is never ours to sleep, even when recovery is disabled. */
final class MotionScreenOwnership {
    static boolean mayDarken(boolean ownConfirmedWake, boolean recoveryEnabled, boolean screenOffEnabled,
                             int vehiclePower, boolean warningActive, boolean apnPresent) {
        return ownConfirmedWake && recoveryEnabled && screenOffEnabled && vehiclePower == 0
                && !warningActive && apnPresent;
    }
    static boolean maySleep(boolean priorDisplayOn, boolean ownsWake, boolean cellularEnabled) {
        return !priorDisplayOn && ownsWake && !cellularEnabled;
    }
}
