package com.byd.extend;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
public final class TurnSignalGuardRuntimeThreadTest {
    private static final long WAIT_SECONDS = 5;

    @Test public void stopAndRestartStayOnGuardLooperAndFlushTerminalEventBeforeShutdown()
            throws Exception {
        HandlerThread thread = new HandlerThread(
                "turn-signal-guard-test", Process.THREAD_PRIORITY_DEFAULT);
        thread.start();
        List<String> events = new CopyOnWriteArrayList<>();
        List<String> eventThreads = new CopyOnWriteArrayList<>();
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch firstStart = new CountDownLatch(1);
        CountDownLatch secondStart = new CountDownLatch(1);
        AtomicBoolean watchStoppedRuntime = new AtomicBoolean();
        AtomicBoolean markerInvoked = new AtomicBoolean();
        AtomicInteger lateEvents = new AtomicInteger();
        TurnSignalGuardRuntime runtime = new TurnSignalGuardRuntime(
                RuntimeEnvironment.getApplication(), new Handler(thread.getLooper()),
                (kind, fields) -> {
                    events.add(kind);
                    eventThreads.add(Thread.currentThread().getName());
                    if (watchStoppedRuntime.get()) lateEvents.incrementAndGet();
                    if ("guard_self_check".equals(kind)) {
                        if (starts.incrementAndGet() == 1) firstStart.countDown();
                        else secondStart.countDown();
                    }
                },
                (generation, completed) -> markerInvoked.set(true));

        try {
            runtime.start();
            assertTrue(firstStart.await(WAIT_SECONDS, TimeUnit.SECONDS));

            CountDownLatch detached = new CountDownLatch(1);
            runtime.stop(() -> {
                watchStoppedRuntime.set(true);
                detached.countDown();
            });
            assertTrue(detached.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("telemetry_stopped", events.get(events.size() - 1));
            assertFalse(markerInvoked.get());
            TimeUnit.MILLISECONDS.sleep(150);
            assertEquals(0, lateEvents.get());

            watchStoppedRuntime.set(false);
            runtime.start();
            assertTrue(secondStart.await(WAIT_SECONDS, TimeUnit.SECONDS));

            AtomicBoolean terminalEventPrecededCompletion = new AtomicBoolean();
            CountDownLatch shutdown = new CountDownLatch(1);
            assertTrue(runtime.shutdown(() -> {
                terminalEventPrecededCompletion.set(
                        "telemetry_stopped".equals(events.get(events.size() - 1)));
                thread.quitSafely();
                shutdown.countDown();
            }));
            assertTrue(shutdown.await(WAIT_SECONDS, TimeUnit.SECONDS));
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

            assertTrue(terminalEventPrecededCompletion.get());
            assertFalse(thread.isAlive());
            assertFalse(markerInvoked.get());
            assertFalse(eventThreads.isEmpty());
            for (String eventThread : eventThreads) {
                assertEquals(thread.getName(), eventThread);
            }
        } finally {
            if (thread.isAlive()) {
                CountDownLatch cleanup = new CountDownLatch(1);
                if (runtime.shutdown(() -> {
                    thread.quitSafely();
                    cleanup.countDown();
                })) {
                    cleanup.await(WAIT_SECONDS, TimeUnit.SECONDS);
                }
                thread.quitSafely();
                thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            }
        }
    }
}
