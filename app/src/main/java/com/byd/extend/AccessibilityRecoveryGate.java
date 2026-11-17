package com.byd.extend;

/** Coalesces one bounded Accessibility recovery attempt and invalidates stale work. */
final class AccessibilityRecoveryGate {
    static boolean isQuickbootExit(String description, long exitedAtMs, long processStartedAtMs) {
        return exitedAtMs > 0 && exitedAtMs <= processStartedAtMs && description != null
                && description.toLowerCase(java.util.Locale.ROOT).contains("quickboot");
    }

    static boolean immediateRebind(String reason, boolean quickboot) {
        return quickboot || "disconnect".equals(reason);
    }

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
