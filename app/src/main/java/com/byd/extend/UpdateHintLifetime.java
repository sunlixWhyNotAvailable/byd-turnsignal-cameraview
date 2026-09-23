package com.byd.extend;

/** Pure elapsed-time arithmetic for the update hint's immutable visible deadline. */
final class UpdateHintLifetime {
    private UpdateHintLifetime() {}

    static long deadlineAfter(long shownElapsedMs, long durationMs) {
        return shownElapsedMs + durationMs;
    }

    static long remainingMs(long deadlineElapsedMs, long nowElapsedMs) {
        return deadlineElapsedMs <= nowElapsedMs ? 0L : deadlineElapsedMs - nowElapsedMs;
    }
}
