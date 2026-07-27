package com.byd.turnsignalguard.capture;

import android.os.SystemClock;

final class UpdateAutoCheckRuntime {
    static final long AUTO_CHECK_DELAY_MS = 30_000L;
    private static final Scheduler SCHEDULER = new Scheduler(AUTO_CHECK_DELAY_MS);

    private UpdateAutoCheckRuntime() {
    }

    static synchronized void onProcessStarted() {
        SCHEDULER.start(SystemClock.elapsedRealtime());
    }

    static synchronized long remainingMs() {
        return SCHEDULER.remainingMs(SystemClock.elapsedRealtime());
    }

    static synchronized boolean consumeIfReady() {
        return SCHEDULER.consumeIfReady(SystemClock.elapsedRealtime());
    }

    static final class Scheduler {
        private final long delayMs;
        private long startedAtMs = -1L;
        private boolean consumed;

        Scheduler(long delayMs) {
            this.delayMs = Math.max(0L, delayMs);
        }

        void start(long nowMs) {
            if (startedAtMs < 0L) startedAtMs = nowMs;
        }

        long remainingMs(long nowMs) {
            if (consumed) return -1L;
            if (startedAtMs < 0L) start(nowMs);
            return Math.max(0L, delayMs - Math.max(0L, nowMs - startedAtMs));
        }

        boolean consumeIfReady(long nowMs) {
            if (consumed || remainingMs(nowMs) > 0L) return false;
            consumed = true;
            return true;
        }
    }
}
