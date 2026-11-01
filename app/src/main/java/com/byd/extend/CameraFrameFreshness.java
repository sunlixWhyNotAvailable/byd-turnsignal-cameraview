package com.byd.extend;

/** Source-local evidence: repeated output or an old buffer cannot renew readiness. */
final class CameraFrameFreshness {
    static final long MAX_AGE_MS = 1_000L;
    private long previousTimestamp;
    private volatile long advancedAtMs = -1L;

    void acquired(long producerTimestamp, long elapsedMs) {
        if (producerTimestamp <= 0 || producerTimestamp < previousTimestamp) {
            advancedAtMs = -1L;
            previousTimestamp = Math.max(0L, producerTimestamp);
        } else if (producerTimestamp > previousTimestamp) {
            if (previousTimestamp > 0) advancedAtMs = elapsedMs;
            previousTimestamp = producerTimestamp;
        }
    }

    long lastAdvanceMs() { return advancedAtMs; }

    static boolean fresh(long advancedAtMs, long nowMs) {
        return advancedAtMs >= 0 && nowMs >= advancedAtMs
                && nowMs - advancedAtMs <= MAX_AGE_MS;
    }
}
