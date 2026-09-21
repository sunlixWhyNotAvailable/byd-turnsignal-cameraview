package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class AvasNavSourcePlaybackTest {
    @Test public void initialZeroStartsAtFileBoundaryWithoutAnyZeros() throws Exception {
        FakeClock clock = new FakeClock();
        TimedTransport transport = new TimedTransport(clock, 0, 0, Long.MAX_VALUE);
        AvasNavSourceGate gate = gate(transport, clock);
        List<String> order = new ArrayList<>();
        CountingZeros zeros = new CountingZeros(order, 1);
        AvasNavSourcePlayback.Result result = AvasNavSourcePlayback.await(gate, 100, 20,
                () -> false, zeros, () -> { order.add("play"); return clock.now(); }, clock::now);
        order.add("file");
        assertFalse(result.cancelled);
        assertEquals(0, result.silenceFrames);
        assertEquals(List.of("play", "file"), order);
    }

    @Test public void initialZeroThenOneWaitsAndWritesZerosBeforeFile() throws Exception {
        FakeClock clock = new FakeClock();
        TimedTransport transport = new TimedTransport(clock, 0, 1, 60);
        AvasNavSourceGate gate = gate(transport, clock);
        List<String> order = new ArrayList<>();
        CountingZeros zeros = new CountingZeros(order, 1);
        AvasNavSourcePlayback.Result result = AvasNavSourcePlayback.await(gate, 100, 20,
                () -> false, zeros, () -> { order.add("play"); return clock.now(); }, clock::now);
        order.add("file");
        assertTrue(result.silenceFrames > 0);
        assertEquals("play", order.get(0));
        assertEquals("file", order.get(order.size() - 1));
        assertTrue(order.contains("zero"));
    }

    @Test public void timeoutFinalGetCanAdmitAtExactlyThreeSeconds() throws Exception {
        FakeClock clock = new FakeClock();
        TimedTransport transport = new TimedTransport(clock, 1, 1,
                AvasNavSourceGate.TIMEOUT_MILLIS);
        AvasNavSourceGate gate = gate(transport, clock);
        CountingZeros zeros = new CountingZeros(new ArrayList<>(), 2);
        AvasNavSourcePlayback.Result result = AvasNavSourcePlayback.await(gate, 100, 20,
                () -> false, zeros, clock::now, clock::now);
        assertFalse(result.cancelled);
        assertEquals(AvasNavSourceGate.TIMEOUT_MILLIS, result.gateOpenedMs);
        assertTrue(result.silenceFrames > 0);
        assertTrue(zeros.partialWrites > 0);
    }

    @Test public void timeoutFinalGetRejectsNonzero() {
        FakeClock clock = new FakeClock();
        TimedTransport transport = new TimedTransport(clock, 1, 1, Long.MAX_VALUE);
        AvasNavSourceGate gate = gate(transport, clock);
        assertThrows(IllegalStateException.class, () -> AvasNavSourcePlayback.await(gate,
                100, 20, () -> false, new CountingZeros(new ArrayList<>(), 1),
                clock::now, clock::now));
        assertEquals(AvasNavSourceGate.TIMEOUT_MILLIS, clock.now());
    }

    @Test public void cancellationDuringZeroWritesStopsBeforeFinalGetOrFile() throws Exception {
        FakeClock clock = new FakeClock();
        TimedTransport transport = new TimedTransport(clock, 1, 1, Long.MAX_VALUE);
        AvasNavSourceGate gate = gate(transport, clock);
        boolean[] cancelled = {false};
        CountingZeros zeros = new CountingZeros(new ArrayList<>(), 1) {
            @Override public long write(long frames) {
                cancelled[0] = true;
                return super.write(frames);
            }
        };
        AvasNavSourcePlayback.Result result = AvasNavSourcePlayback.await(gate, 100, 20,
                () -> cancelled[0], zeros, clock::now, clock::now);
        assertTrue(result.cancelled);
        assertEquals(2, transport.reads); // initial and immediate post-play snapshot only
    }

    @Test public void callbackDuringZeroWriteRequiresGetAndKeepsWaitingOnOne() throws Exception {
        FakeClock clock = new FakeClock();
        AvasNavSourceGate.Listener[] callback = new AvasNavSourceGate.Listener[1];
        int[] reads = {0};
        int[] writes = {0};
        AvasNavSourceGate.Transport transport = new AvasNavSourceGate.Transport() {
            @Override public AvasNavSourceGate.Subscription subscribe(
                    AvasNavSourceGate.Listener listener) {
                callback[0] = listener;
                return () -> { };
            }

            @Override public AvasNavSourceGate.Snapshot read() {
                // Initial, post-play and first callback confirmation remain1.
                return new AvasNavSourceGate.Snapshot(0, ++reads[0] < 4 ? 1 : 0);
            }
        };
        AvasNavSourceGate gate = new AvasNavSourceGate(transport, clock);
        gate.start();
        CountingZeros zeros = new CountingZeros(new ArrayList<>(), 1) {
            @Override public long write(long frames) {
                if (++writes[0] == 2) assertEquals(3, reads[0]);
                clock.now += 10;
                callback[0].changed(0);
                return super.write(frames);
            }
        };

        AvasNavSourcePlayback.Result result = AvasNavSourcePlayback.await(gate, 100, 20,
                () -> false, zeros, clock::now, clock::now);

        assertFalse(result.cancelled);
        assertEquals(2, writes[0]);
        assertEquals(4, reads[0]);
        assertEquals(0, result.finalSnapshot.value);
        assertEquals(140, result.silenceFrames);
        gate.close();
    }

    private static AvasNavSourceGate gate(TimedTransport transport, FakeClock clock) {
        AvasNavSourceGate gate = new AvasNavSourceGate(transport, clock);
        gate.start();
        return gate;
    }

    private static class CountingZeros implements AvasNavSourcePlayback.ZeroWriter {
        final List<String> order;
        final int divisor;
        int partialWrites;

        CountingZeros(List<String> order, int divisor) {
            this.order = order;
            this.divisor = divisor;
        }

        @Override public long prefill(long frames) {
            order.add("prefill");
            return frames / divisor;
        }

        @Override public long write(long frames) {
            order.add("zero");
            long written = frames / divisor;
            if (written != frames) partialWrites++;
            return written;
        }
    }

    private static final class TimedTransport implements AvasNavSourceGate.Transport {
        final FakeClock clock;
        final int initial;
        final int afterInitial;
        final long zeroAt;
        int reads;

        TimedTransport(FakeClock clock, int initial, int afterInitial, long zeroAt) {
            this.clock = clock;
            this.initial = initial;
            this.afterInitial = afterInitial;
            this.zeroAt = zeroAt;
        }

        @Override public AvasNavSourceGate.Subscription subscribe(AvasNavSourceGate.Listener listener)
                throws Exception {
            throw new IllegalStateException("force deterministic GET fallback");
        }

        @Override public AvasNavSourceGate.Snapshot read() {
            int value = reads++ == 0 ? initial : clock.now() >= zeroAt ? 0 : afterInitial;
            return new AvasNavSourceGate.Snapshot(0, value);
        }
    }

    private static final class FakeClock implements AvasNavSourceGate.Clock {
        long now;
        @Override public long now() { return now; }
        @Override public void sleep(long millis) { now += millis; }
    }
}
