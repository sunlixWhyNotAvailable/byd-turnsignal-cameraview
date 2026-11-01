package com.byd.extend;

/** Monotonic OFF-cycle schedule, owned by CellularApnRecovery's lock. */
final class CellularApnSchedule {
    static final long FAST_INTERVAL_MS = 5_000L;
    static final long FAST_WINDOW_MS = 30_000L;
    static final long STEADY_INTERVAL_MS = 30_000L;
    private int power = -1;
    private long offSince = -1L;
    private long nextPoll;
    private boolean networkPending;

    boolean powerChanged(int value, long now) {
        if (power == value) return false;
        power = value;
        // A temporary telemetry gap must not restart a known OFF window.
        if (value > 0) offSince = -1L;
        else if (value == 0 && offSince < 0) offSince = now;
        nextPoll = value > 0 ? Long.MAX_VALUE : now;
        if (value > 0) networkPending = false;
        return true;
    }

    boolean networkChanged() {
        if (power > 0 || networkPending) return false;
        networkPending = true;
        return true;
    }

    long delayMs(long now) {
        if (networkPending) return 0L;
        return nextPoll == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, nextPoll - now);
    }

    boolean checkStarted(long now) {
        boolean fromNetwork = networkPending;
        networkPending = false;
        nextPoll = nextPeriodicCheck(now);
        return fromNetwork;
    }

    void checkFinished(long now) {
        // Never overwrite an event received during the check or replay missed timer ticks.
        if (nextPoll <= now) nextPoll = nextPeriodicCheck(now);
    }

    private long nextPeriodicCheck(long now) {
        if (power > 0) return Long.MAX_VALUE;
        if (power != 0 || offSince < 0) return now + STEADY_INTERVAL_MS;
        long age = Math.max(0L, now - offSince);
        if (age < FAST_WINDOW_MS)
            return offSince + (age / FAST_INTERVAL_MS + 1L) * FAST_INTERVAL_MS;
        return offSince + FAST_WINDOW_MS
                + ((age - FAST_WINDOW_MS) / STEADY_INTERVAL_MS + 1L) * STEADY_INTERVAL_MS;
    }
}
