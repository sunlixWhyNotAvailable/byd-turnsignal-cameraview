package com.byd.extend;

/** Coalesces one bounded Accessibility recovery attempt and invalidates stale work. */
final class AccessibilityRecoveryGate {
    private long epoch;
    private boolean running;

    synchronized long begin() {
        if (running) return 0;
        running = true;
        return ++epoch;
    }

    synchronized boolean isCurrent(long candidate) {
        return running && candidate == epoch;
    }

    synchronized boolean finish(long candidate) {
        if (candidate != epoch || !running) return false;
        running = false;
        return true;
    }

    synchronized void cancel() {
        epoch++;
        running = false;
    }
}
