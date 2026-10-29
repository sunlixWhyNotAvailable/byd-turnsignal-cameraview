package com.byd.extend;

/** A borrowed display is never ours to sleep, even when recovery is disabled. */
final class MotionScreenOwnership {
    static boolean maySleep(boolean priorDisplayOn, boolean ownsWake, boolean cellularEnabled) {
        return !priorDisplayOn && ownsWake && !cellularEnabled;
    }
}
