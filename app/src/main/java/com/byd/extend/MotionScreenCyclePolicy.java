package com.byd.extend;

/** Pure per-session gate for one-shot wake cycles and deliberate user dismissal. */
final class MotionScreenCyclePolicy {
    private boolean wakeAttempted;
    private boolean userTakeover;
    private boolean uncertain;

    boolean mayStartPreflight(boolean eligible, boolean offContinuous, boolean ownsWake) {
        return eligible && offContinuous && (!ownsWake || needsFreshBlackOffBaseline())
                && (needsFreshBlackOffBaseline() || !wakeAttempted);
    }

    boolean needsFreshBlackOffBaseline() { return userTakeover || uncertain; }
    boolean userTookOver() { return userTakeover; }
    boolean uncertain() { return uncertain; }
    boolean wakeAttempted() { return wakeAttempted; }
    boolean mayWriteWake() { return !needsFreshBlackOffBaseline() && !wakeAttempted; }

    void beginWakeAttempt() { wakeAttempted = true; }
    void markUserTakeover() { userTakeover = true; }
    void markUncertain() { uncertain = true; }

    /** Call only after real preflight reads confirm Power=0 and the known black/off screen state. */
    void confirmFreshBlackOffBaseline() { resetWarningCycle(); }

    /** Call only after this worker observed its own complete sleep and black-restore contract. */
    void confirmOwnedBlackRestore() { resetWarningCycle(); }

    void beginSession() { resetWarningCycle(); }

    private void resetWarningCycle() {
        wakeAttempted = false;
        userTakeover = false;
        uncertain = false;
    }
}
