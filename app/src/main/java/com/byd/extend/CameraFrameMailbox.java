package com.byd.extend;

import java.util.function.IntPredicate;

/** Two snapshots: a reader owns one, while the producer replaces only the latest pending one. */
final class CameraFrameMailbox {
    static final int SLOT_COUNT = 2;
    private static final int FREE = 0, WRITING = 1, PENDING = 2, READING = 3;
    private final int[] states = new int[SLOT_COUNT];
    private int pending = -1;
    private long dropped;
    private boolean closed;

    synchronized int reserve(IntPredicate gpuReady) {
        if (closed) return -1;
        // Prefer replacing the pending snapshot, leaving the other slot available to the reader.
        if (pending >= 0 && gpuReady.test(pending)) {
            int slot = pending;
            pending = -1;
            states[slot] = WRITING;
            dropped++;
            return slot;
        }
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (states[slot] == FREE && gpuReady.test(slot)) {
                states[slot] = WRITING;
                return slot;
            }
        }
        dropped++;
        return -1;
    }

    synchronized int reserveFree(int slot) {
        if (closed || states[slot] != FREE) return -1;
        states[slot] = WRITING;
        return slot;
    }

    synchronized void cancel(int slot) {
        if (states[slot] != WRITING) throw new IllegalStateException("snapshot is not writable");
        states[slot] = FREE;
    }

    synchronized void publish(int slot) {
        if (states[slot] != WRITING) throw new IllegalStateException("snapshot is not writable");
        if (closed) {
            states[slot] = FREE;
            return;
        }
        if (pending >= 0) {
            states[pending] = FREE;
            dropped++;
        }
        states[slot] = PENDING;
        pending = slot;
    }

    synchronized int acquire() {
        if (closed || pending < 0) return -1;
        int slot = pending;
        pending = -1;
        states[slot] = READING;
        return slot;
    }

    synchronized void release(int slot) {
        if (states[slot] != READING) throw new IllegalStateException("snapshot is not held");
        states[slot] = FREE;
    }

    synchronized long dropped() { return dropped; }

    synchronized void close() {
        closed = true;
        pending = -1;
    }
}
