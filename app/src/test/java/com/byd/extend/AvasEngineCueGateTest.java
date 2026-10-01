package com.byd.extend;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class AvasEngineCueGateTest {
    @Test public void newEventWaitsForCueAndStaleCompletionCannotReleaseReplacement() throws Exception {
        AvasEngineCueGate gate = new AvasEngineCueGate();
        Object start = gate.begin();
        Object stop = gate.begin();
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger writes = new AtomicInteger();
        FutureTask<Integer> event = new FutureTask<>(() -> gate.writeEventStart(() -> {
            waiting.countDown();
            return cancelled.get();
        }, () -> { writes.incrementAndGet(); return 96; }));
        Thread worker = new Thread(event);
        worker.start();
        try {
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            gate.finish(start);
            assertEquals(0, writes.get());
            assertFalse(event.isDone());
            gate.finish(stop); // Called only once the active outputs drained, or on failure.
            assertEquals(96, (int) event.get(1, TimeUnit.SECONDS));
            assertEquals(1, writes.get());
        } finally {
            cancelled.set(true);
            gate.finish(stop);
            worker.join(1000);
        }
    }

    @Test public void alreadyStartedEventMayMixButNextEventCanBeCancelledWhileWaiting() throws Exception {
        AvasEngineCueGate gate = new AvasEngineCueGate();
        assertEquals(64, gate.writeEventStart(() -> false, () -> 64));
        Object cue = gate.begin(); // Does not wait for, cancel or replay the existing event.
        assertEquals(0, gate.writeEventStart(() -> true, () -> { fail("cancelled event wrote PCM"); return 1; }));
        gate.finish(cue);
        assertEquals(64, gate.writeEventStart(() -> false, () -> 64));
    }

    @Test public void zeroWriteIsNotAStartedEventAndCueWinsBeforeRetry() throws Exception {
        AvasEngineCueGate gate = new AvasEngineCueGate();
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        Object[] cue = new Object[1];
        int written = gate.writeEventStart(cancelled::get, () -> {
            attempts.incrementAndGet();
            cue[0] = gate.begin();
            cancelled.set(true);
            return 0;
        });
        assertEquals(0, written);
        assertEquals(1, attempts.get());
        gate.finish(cue[0]);
        assertEquals(1, gate.writeEventStart(() -> false, () -> 1));
    }
}
