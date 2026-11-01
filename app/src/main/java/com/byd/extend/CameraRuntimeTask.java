package com.byd.extend;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/** Cancellation owns only queued work. A running OEM operation must finish its cleanup. */
final class CameraRuntimeTask<T> extends FutureTask<T> {
    private final AtomicInteger dispatch = new AtomicInteger();
    final long id, enqueuedNs = System.nanoTime();
    final String operation;
    volatile long startedNs;

    CameraRuntimeTask(long id, String operation, Callable<T> task) {
        super(task);
        this.id = id;
        this.operation = operation;
    }

    @Override public void run() {
        if (!dispatch.compareAndSet(0, 1)) return;
        startedNs = System.nanoTime();
        super.run();
    }

    boolean cancelQueued() {
        if (!dispatch.compareAndSet(0, 2)) return false;
        return cancel(false);
    }

    boolean started() { return dispatch.get() == 1; }
}
