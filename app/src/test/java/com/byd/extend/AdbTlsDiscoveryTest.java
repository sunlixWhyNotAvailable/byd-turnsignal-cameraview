package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class AdbTlsDiscoveryTest {
    @Test public void terminalStartFailureAllowsNextConsentTickToRetryDiscovery() {
        AdbTlsDiscovery.StartGate gate = new AdbTlsDiscovery.StartGate();
        assertTrue(gate.begin(false));
        assertFalse(gate.begin(false));

        gate.failed();

        assertTrue(gate.begin(false));
    }

    @Test public void closedDiscoveryNeverStarts() {
        AdbTlsDiscovery.StartGate gate = new AdbTlsDiscovery.StartGate();
        assertFalse(gate.begin(true));
    }

    @Test public void simultaneousStartRequestsAdmitOnlyOneDiscoveryUntilFailure() throws Exception {
        AdbTlsDiscovery.StartGate gate = new AdbTlsDiscovery.StartGate();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> attempt = () -> {
            ready.countDown();
            assertTrue(start.await(2, TimeUnit.SECONDS));
            return gate.begin(false);
        };
        try {
            Future<Boolean> first = workers.submit(attempt);
            Future<Boolean> second = workers.submit(attempt);
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get(2, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(2, TimeUnit.SECONDS) ? 1 : 0));
            assertTrue(gate.started());
            gate.failed();
            assertFalse(gate.started());
            assertTrue(gate.begin(false));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
