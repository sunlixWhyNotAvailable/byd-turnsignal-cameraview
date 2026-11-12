package com.byd.extend;

final class WeatherLocationSelection {
    static final long MAX_AGE_NANOS = 120_000_000_000L;

    private WeatherLocationSelection() {}

    static String rejectionReason(long elapsedNanos, long nowNanos) {
        if (elapsedNanos <= 0L) return "invalid_elapsed";
        if (elapsedNanos > nowNanos) return "future_elapsed";
        return nowNanos - elapsedNanos > MAX_AGE_NANOS ? "stale" : null;
    }
}
