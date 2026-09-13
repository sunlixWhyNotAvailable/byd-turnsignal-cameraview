package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AvasFocusMaintainerTest {
    @Test public void startSchedules120msTicksAndCloseStopsFutureRequests() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger calls = new AtomicInteger();
        List<AvasFocusMaintainer.Report> reports = new ArrayList<>();
        AvasFocusMaintainer maintainer = new AvasFocusMaintainer(
                () -> { calls.incrementAndGet(); return 1; }, 1, reports::add, scheduler);

        maintainer.start();
        assertEquals(120, scheduler.initialDelay);
        assertEquals(120, scheduler.period);
        scheduler.tick();
        scheduler.tick();
        assertEquals(2, calls.get());
        assertTrue(reports.isEmpty());

        maintainer.close();
        scheduler.tick();
        assertEquals(2, calls.get());
        assertTrue(scheduler.cancelled);
        assertTrue(scheduler.closed);
        assertEquals(1, reports.size());
        assertEquals("summary", reports.get(0).phase);
        assertEquals(2, reports.get(0).successes);
    }

    @Test public void changedResultsAndFailuresAreBoundedButSummaryCountsEverything() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger calls = new AtomicInteger();
        List<AvasFocusMaintainer.Report> reports = new ArrayList<>();
        AvasFocusMaintainer maintainer = new AvasFocusMaintainer(() -> {
            int call = calls.getAndIncrement();
            if ((call & 1) == 0) return 0;
            throw new IllegalStateException("focus " + call);
        }, 1, reports::add, scheduler);
        maintainer.start();
        for (int i = 0; i < 8; i++) scheduler.tick();
        maintainer.close();

        assertEquals(4, reports.size());
        assertEquals("summary", reports.get(3).phase);
        assertEquals(0, reports.get(3).successes);
        assertEquals(8, reports.get(3).failures);
    }

    @Test public void closeWaitsForInflightRequestAndNoCallRunsAfterCleanup() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AvasFocusMaintainer maintainer = new AvasFocusMaintainer(() -> {
            calls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return 1;
        }, 1, report -> { }, scheduler);
        maintainer.start();

        Thread tick = new Thread(scheduler::tick);
        tick.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread close = new Thread(maintainer::close);
        close.start();
        Thread.sleep(30);
        assertTrue(close.isAlive());
        release.countDown();
        tick.join(2000);
        close.join(2000);
        assertFalse(close.isAlive());
        scheduler.tick();
        assertEquals(1, calls.get());
    }

    private static final class ManualScheduler implements AvasFocusMaintainer.Scheduler {
        Runnable task;
        long initialDelay;
        long period;
        boolean cancelled;
        boolean closed;

        @Override public AvasFocusMaintainer.ScheduledTask schedule(Runnable task,
                long initialDelayMillis, long periodMillis) {
            this.task = task;
            initialDelay = initialDelayMillis;
            period = periodMillis;
            return () -> cancelled = true;
        }

        void tick() {
            if (!cancelled && task != null) task.run();
        }

        @Override public void close() { closed = true; }
    }
}
