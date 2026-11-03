package com.byd.extend;

import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/** One replaceable value and one drain; control events always get a turn between values. */
final class LatestValueDispatcher<T> {
    private final Executor executor;
    private final BiConsumer<T, Long> consume;
    private T pending;
    private boolean scheduled;
    private long generation, replaced;

    LatestValueDispatcher(Executor executor, BiConsumer<T, Long> consume) {
        this.executor = executor;
        this.consume = consume;
    }

    synchronized void offer(T value) {
        if (value == null) throw new IllegalArgumentException("value required");
        if (pending != null) replaced++;
        pending = value;
        if (!scheduled) {
            scheduled = true;
            schedule();
        }
    }

    synchronized void clear() { pending = null; generation++; }
    synchronized boolean current(long token) { return generation == token; }
    synchronized long replaced() { return replaced; }

    synchronized void control(Runnable action) {
        clear();
        executor.execute(action);
    }

    private void schedule() {
        long ticket = generation;
        executor.execute(() -> drain(ticket));
    }

    private void drain(long ticket) {
        T value;
        long token;
        synchronized (this) {
            token = generation;
            value = ticket == token ? pending : null;
            if (ticket == token) pending = null;
        }
        try { if (value != null) consume.accept(value, token); }
        finally {
            synchronized (this) {
                if (pending == null) scheduled = false;
                else schedule();
            }
        }
    }
}
