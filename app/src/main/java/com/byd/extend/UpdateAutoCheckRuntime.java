package com.byd.extend;

import android.os.SystemClock;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Process-owned one-shot callback; Activity visibility never pauses its deadline. */
final class UpdateAutoCheckRuntime implements Runnable {
    static final long AUTO_CHECK_DELAY_MS = 30_000L;
    private static final Scheduler SCHEDULER = new Scheduler(AUTO_CHECK_DELAY_MS);
    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final BiConsumer<Runnable, Long> post;
    private final Consumer<Runnable> cancel;
    private final BooleanSupplier enabled;
    private final Runnable check;
    private boolean stopped;

    UpdateAutoCheckRuntime(BiConsumer<Runnable, Long> post, Consumer<Runnable> cancel,
            BooleanSupplier enabled, Runnable check) {
        this(SCHEDULER, SystemClock::elapsedRealtime, post, cancel, enabled, check);
    }

    // Clock and queue injection exercise the actual scheduling path in local JVM tests.
    UpdateAutoCheckRuntime(Scheduler scheduler, LongSupplier clock,
            BiConsumer<Runnable, Long> post, Consumer<Runnable> cancel,
            BooleanSupplier enabled, Runnable check) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.post = post;
        this.cancel = cancel;
        this.enabled = enabled;
        this.check = check;
    }

    static synchronized void onProcessStarted() {
        SCHEDULER.start(SystemClock.elapsedRealtime());
    }

    void refresh() {
        cancel.accept(this);
        if (stopped || !enabled.getAsBoolean()) return;
        long remainingMs = scheduler.remainingMs(clock.getAsLong());
        if (remainingMs >= 0L) post.accept(this, remainingMs);
    }

    void shutdown() {
        stopped = true;
        cancel.accept(this);
    }

    void resume() {
        if (!stopped) return;
        stopped = false;
        refresh();
    }

    @Override public void run() {
        if (stopped || !enabled.getAsBoolean()) return;
        if (!scheduler.consumeIfReady(clock.getAsLong())) {
            refresh();
            return;
        }
        // Busy/throttled/failed checks still consume this process's single attempt.
        check.run();
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
