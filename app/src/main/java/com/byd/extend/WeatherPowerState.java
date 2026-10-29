package com.byd.extend;

/** Consumes validated vehicle telemetry, never display wakefulness. */
final class WeatherPowerState {
    private volatile int state = -1;

    boolean update(boolean known, boolean off) {
        int next = known ? (off ? 0 : 1) : -1;
        boolean changed = state != next;
        state = next;
        return changed;
    }

    boolean allowsRequests() { return state == 1; }
}
