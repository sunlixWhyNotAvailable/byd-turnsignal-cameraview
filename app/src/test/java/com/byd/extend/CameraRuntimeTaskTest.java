package com.byd.extend;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraRuntimeTaskTest {
    @Test public void expiredQueuedWorkCannotApplyLateSideEffects() {
        AtomicInteger value = new AtomicInteger();
        CameraRuntimeTask<Integer> task = new CameraRuntimeTask<>(1, "write", value::incrementAndGet);
        assertTrue(task.cancelQueued());
        task.run();
        assertEquals(0, value.get());
        assertFalse(task.started());
    }

    @Test public void runningWorkCompletesWithoutInterruptionWhenCallerTimesOut() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        CameraRuntimeTask<Boolean> task = new CameraRuntimeTask<>(2, "running", () -> {
            started.countDown();
            release.await();
            return Thread.currentThread().isInterrupted();
        });
        Thread thread = new Thread(task);
        thread.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(task.cancelQueued());
            assertTrue(task.started());
        } finally { release.countDown(); thread.join(2_000); }
        assertFalse(task.get(2, TimeUnit.SECONDS));
    }
}
