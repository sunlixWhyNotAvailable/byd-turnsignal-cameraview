package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AvasNavSourceGateTest {
    @Test public void initialZeroNeedsNoFreshEdge() {
        FakeTransport transport = new FakeTransport(0);
        AvasNavSourceGate gate = gate(transport);
        gate.start();
        gate.refresh();
        assertTrue(gate.ready());
        assertEquals(1, transport.reads);
    }

    @Test public void callbackTransitionsOneToZeroAndWakesWorker() throws Exception {
        FakeTransport transport = new FakeTransport(1);
        AvasNavSourceGate gate = gate(transport);
        gate.start();
        gate.refresh();
        transport.fire(0);
        assertTrue(gate.awaitChangeOrPoll(1000, () -> false));
        assertTrue(gate.ready());
    }

    @Test public void staleInFlightGetCannotOverwriteNewerCallback() throws Exception {
        BlockingTransport transport = new BlockingTransport();
        AvasNavSourceGate gate = gate(transport);
        gate.start();
        Thread reader = new Thread(gate::refresh);
        reader.start();
        assertTrue(transport.readStarted.await(1, TimeUnit.SECONDS));
        transport.fire(0);
        transport.releaseRead.countDown();
        reader.join(1000);
        assertFalse(reader.isAlive());
        assertTrue(gate.ready());
        assertEquals(0, gate.value());
    }

    @Test public void callbackErrorEnablesFiftyMillisecondGetFallback() throws Exception {
        FakeClock clock = new FakeClock();
        FakeTransport transport = new FakeTransport(1, 0);
        AvasNavSourceGate gate = new AvasNavSourceGate(transport, clock);
        gate.start();
        gate.refresh();
        transport.listener.error("7:test");
        assertTrue(gate.fallbackPolling());
        gate.beginWait();
        for (int i = 0; i < 5 && !gate.ready(); i++) {
            gate.awaitChangeOrPoll(1000, () -> false);
        }
        assertTrue(gate.ready());
        assertEquals(AvasNavSourceGate.FALLBACK_POLL_MILLIS, clock.now);
        assertEquals(2, transport.reads);
    }

    @Test public void registrationFailureAlsoPollsAndFinalGetCanAdmitOrReject() throws Exception {
        FakeTransport transport = new FakeTransport(1, 1, 0);
        transport.registrationFailure = true;
        AvasNavSourceGate gate = gate(transport);
        gate.start();
        gate.refresh();
        assertFalse(gate.ready());
        gate.refresh();
        assertFalse(gate.ready());
        gate.refresh();
        assertTrue(gate.ready());
    }

    @Test public void cancellationReturnsWithoutPolling() throws Exception {
        FakeTransport transport = new FakeTransport(1);
        AvasNavSourceGate gate = gate(transport);
        gate.start();
        gate.refresh();
        assertFalse(gate.awaitChangeOrPoll(1000, () -> true));
        assertEquals(1, transport.reads);
    }

    @Test public void invalidFinalGetCannotReuseAnOlderReadyCallback() {
        FakeTransport transport = new FakeTransport(1) {
            @Override public AvasNavSourceGate.Snapshot read() {
                reads++;
                return new AvasNavSourceGate.Snapshot(-1, 0);
            }
        };
        AvasNavSourceGate gate = gate(transport);
        gate.start();
        transport.fire(0);
        assertTrue(gate.ready());
        gate.refresh();
        assertFalse(gate.ready());
    }

    @Test public void closeUnregistersAndLateCallbackCannotLeakToRequest() throws Exception {
        FakeTransport transport = new FakeTransport(1);
        AvasNavSourceGate gate = gate(transport);
        gate.start();
        gate.refresh();
        gate.close();
        transport.fire(0);
        assertEquals(1, gate.value());
        assertEquals(1, transport.closes);
    }

    private static AvasNavSourceGate gate(FakeTransport transport) {
        return new AvasNavSourceGate(transport, new FakeClock());
    }

    private static class FakeTransport implements AvasNavSourceGate.Transport {
        final ArrayDeque<Integer> values = new ArrayDeque<>();
        AvasNavSourceGate.Listener listener;
        boolean registrationFailure;
        int reads;
        int closes;

        FakeTransport(int... values) {
            for (int value : values) this.values.add(value);
        }

        @Override public AvasNavSourceGate.Subscription subscribe(AvasNavSourceGate.Listener next)
                throws Exception {
            listener = next;
            if (registrationFailure) throw new IllegalStateException("registration failed");
            return () -> closes++;
        }

        @Override public AvasNavSourceGate.Snapshot read() {
            reads++;
            int value = values.size() > 1 ? values.remove() : values.element();
            return new AvasNavSourceGate.Snapshot(0, value);
        }

        void fire(int value) { listener.changed(value); }
    }

    private static final class BlockingTransport extends FakeTransport {
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);

        BlockingTransport() { super(1); }

        @Override public AvasNavSourceGate.Snapshot read() {
            readStarted.countDown();
            try {
                if (!releaseRead.await(1, TimeUnit.SECONDS)) throw new AssertionError("read timeout");
            } catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
            return new AvasNavSourceGate.Snapshot(0, 1);
        }
    }

    private static final class FakeClock implements AvasNavSourceGate.Clock {
        long now;
        @Override public long now() { return now; }
        @Override public void sleep(long millis) { now += millis; }
    }
}
