package com.byd.extend;

/** Bounded delivery retries for the latest desired snapshot, not a polling loop. */
final class MotionConfigRetry {
    private String desired;
    private int retries;

    boolean select(String config) {
        if (config.equals(desired)) return false;
        desired = config;
        retries = 0;
        return true;
    }

    void recovered() { retries = 0; }

    long afterFailure(String config) {
        if (!config.equals(desired) || retries >= 2) return -1L;
        return ++retries == 1 ? 1_000L : 3_000L;
    }
}
