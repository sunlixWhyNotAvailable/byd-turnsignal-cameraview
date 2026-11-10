package com.byd.extend;

import android.app.Application;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class, sdk=33)
public class CriticalOperationTraceTest {
    @After public void reset() { DiagnosticLogPolicy.configure(false); }
    @Test public void observesRealWaitingThreadAndDoesNotCreateAnObserverSpanWhenDisabled() throws Exception {
        DiagnosticLogPolicy.configure(false);
        assertNull(CriticalOperationTrace.begin("disabled", (kind, values) -> fail("disabled observer")));
        DiagnosticLogPolicy.configure(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), observed = new CountDownLatch(1);
        AtomicReference<Object[]> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            CriticalOperationTrace span = CriticalOperationTrace.begin("controlled_wait", (kind, fields) -> {
                result.set(fields); observed.countDown();
            }, "sequence", 7);
            try { entered.countDown(); release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            finally { CriticalOperationTrace.end(span); }
        }, "actual-observed-worker");
        try {
            worker.start(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            ShadowSystemClock.advanceBy(Duration.ofMillis(200));
            assertTrue(observed.await(3, TimeUnit.SECONDS));
            String fields = Arrays.toString(result.get());
            assertTrue(fields.contains("actual-observed-worker"));
            assertTrue(fields.contains("controlled_wait"));
            assertTrue(fields.contains("CountDownLatch.await"));
            assertTrue(fields.contains("sample_started_ms"));
            assertTrue(fields.contains("schedstat"));
        } finally { release.countDown(); worker.join(3000); }
        assertFalse(worker.isAlive());
    }
}
