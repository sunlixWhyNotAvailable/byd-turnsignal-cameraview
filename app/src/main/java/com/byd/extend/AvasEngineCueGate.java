package com.byd.extend;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Only engine lifecycle cues delay NEW event playback; already started events keep playing. */
final class AvasEngineCueGate {
    private Object cue;

    synchronized Object begin() {
        cue = new Object();
        return cue;
    }

    synchronized void finish(Object owner) {
        if (cue == owner) {
            cue = null;
            notifyAll();
        }
    }

    // Avoid preparing a new route while a cue is pending.
    synchronized boolean awaitEventStart(BooleanSupplier cancelled) throws InterruptedException {
        while (cue != null && !cancelled.getAsBoolean()) wait(50);
        return !cancelled.getAsBoolean();
    }

    /** Linearize cue registration against the first non-blocking file PCM write. */
    synchronized int writeEventStart(BooleanSupplier cancelled, IntSupplier write)
            throws InterruptedException {
        return writeEventStart(cancelled, () -> false, write);
    }

    synchronized int writeEventStart(BooleanSupplier cancelled, BooleanSupplier paused,
            IntSupplier write) throws InterruptedException {
        long stalledAt = System.nanoTime();
        while (!cancelled.getAsBoolean()) {
            if (cue != null || paused.getAsBoolean()) {
                wait(50);
                stalledAt = System.nanoTime(); // A cue/NAV pause is not an AudioTrack stall.
                continue;
            }
            int count = write.getAsInt();
            if (count != 0) return count;
            if (System.nanoTime() - stalledAt > 3_000_000_000L) {
                throw new IllegalStateException("Event PCM start stalled");
            }
            wait(2);
        }
        return 0;
    }
}
