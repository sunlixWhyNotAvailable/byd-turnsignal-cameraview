package com.byd.extend;

import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual request lifecycle on a real queue; no Binder/helper return values substituted. */
public class ReversePreparationTest {
    @Test public void cancellationBeforeQueueOrDuringHelperWaitPreventsIpcAdmission() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        try {
            ReversePreparation queued = new ReversePreparation(1, 7);
            queued.cancel();
            assertFalse(worker.submit(queued::begin).get(5,TimeUnit.SECONDS));
            assertFalse(queued.hasResources());
            ReversePreparation waiting = new ReversePreparation(2, 7);
            Future<Boolean> result = worker.submit(() -> {
                entered.countDown();
                if (!resume.await(5,TimeUnit.SECONDS)) throw new TimeoutException("queue did not resume");
                return waiting.begin();
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertTrue(waiting.pending());
            waiting.cancel(); resume.countDown();
            assertFalse(result.get(5,TimeUnit.SECONDS));
            assertFalse(waiting.pending()); assertFalse(waiting.hasResources());
        } finally { resume.countDown(); worker.shutdownNow(); }
    }

    @Test public void cancelledInflightAndCompletedRequestsKeepCleanupOwnershipUntilClosed() {
        ReversePreparation old = new ReversePreparation(1, 7);
        assertTrue(old.begin()); old.cancel();
        assertFalse(old.current()); assertTrue(old.hasResources());
        old.prepared(); // A late completion cannot revive the request.
        assertFalse(old.current()); assertFalse(old.pending());
        ReversePreparation next = new ReversePreparation(2, 7);
        assertTrue(next.pending());
        old.closed();
        assertFalse(old.hasResources()); assertTrue(next.current());
        assertTrue(next.begin()); next.prepared();
        assertFalse(next.pending()); assertTrue(next.current()); assertTrue(next.hasResources());
        next.cancel(); assertTrue(next.hasResources());
        next.closed(); assertFalse(next.hasResources());
    }
}
