package com.byd.extend;

/** A borrowed display is never ours to sleep, even when recovery is disabled. */
final class MotionScreenOwnership {
    enum Inherited { KEEP, RESTORE, RELEASE, WAIT }

    static Inherited inherited(boolean owned, boolean darkeningEnabled, int power, int backlight) {
        if (!owned || backlight == 1) return Inherited.RELEASE;
        if (backlight != 0) return Inherited.WAIT;
        if (!darkeningEnabled || power > 0) return Inherited.RESTORE;
        return power == 0 ? Inherited.KEEP : Inherited.WAIT;
    }
    static boolean mayDarken(boolean ownConfirmedWake, boolean recoveryEnabled, boolean screenOffEnabled,
                             int vehiclePower, boolean warningActive) {
        return ownConfirmedWake && recoveryEnabled && screenOffEnabled && vehiclePower == 0
                && !warningActive;
    }
    static boolean maySleep(boolean priorDisplayOn, boolean ownsWake, boolean cellularEnabled) {
        return !priorDisplayOn && ownsWake && !cellularEnabled;
    }
}
