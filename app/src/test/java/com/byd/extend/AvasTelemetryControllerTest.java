package com.byd.extend;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class AvasTelemetryControllerTest {
    @Test public void initialGetIsQuietAndCallbacksDeliverWithPowerPriority() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.activate();
        assertTrue(rig.sink.profiles.isEmpty());

        rig.transport.emitPower(0, 100);
        rig.runAll();
        rig.transport.emitLock(1, 341);
        rig.runAll();

        assertEquals(Arrays.asList("power_off"), rig.sink.profiles);
        assertEquals(Arrays.asList("unlock:power_off:241"), rig.sink.suppressed);
    }

    @Test public void callbackArrivingDuringInitialGetWinsOverOlderSnapshot() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.transport.duringRead = () -> rig.transport.emitPower(0, 50);
        rig.activate();
        assertTrue(rig.sink.profiles.isEmpty());

        rig.transport.emitPower(2, 100);
        rig.runAll();
        assertEquals(Arrays.asList("power_on"), rig.sink.profiles);
        assertTrue(rig.sink.hasLog("avas_telemetry_snapshot_raced"));
    }

    @Test public void bothCallbacksRacingInitialGetQuietlyCompleteBaseline() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.transport.duringRead = () -> {
            rig.transport.emitPower(0, 50);
            rig.transport.emitLock(1, 51);
        };
        rig.activate();
        assertTrue(rig.sink.profiles.isEmpty());
        assertFalse(rig.transport.sessions.get(0).closed);

        rig.transport.emitLock(2, 100);
        rig.runAll();
        assertEquals(Arrays.asList("lock"), rig.sink.profiles);
    }

    @Test public void powerCallbackRacingInvalidLockSeedImmediatelyFallsBackAndReseeds() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(2, -1));
        rig.transport.duringRead = () -> rig.transport.emitPower(0, 50);
        rig.activate();

        assertTrue(rig.transport.sessions.get(0).closed);
        assertEquals(250, rig.executor.lastDelay);
        assertTrue(rig.sink.hasLog("avas_telemetry_gap"));
        assertEquals(2, rig.sink.lastGapPower);
        assertEquals(-1, rig.sink.lastGapLock);
        rig.transport.snapshots.add(snapshot(0, 1));
        rig.advance(250);
        assertTrue(rig.sink.profiles.isEmpty());
        assertEquals(250, rig.executor.lastDelay);
    }

    @Test public void lockCallbackRacingInvalidPowerSeedImmediatelyFallsBackAndReseeds() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(-1, 2));
        rig.transport.duringRead = () -> rig.transport.emitLock(1, 50);
        rig.activate();

        assertTrue(rig.transport.sessions.get(0).closed);
        assertEquals(250, rig.executor.lastDelay);
        assertTrue(rig.sink.hasLog("avas_telemetry_gap"));
        assertEquals(-1, rig.sink.lastGapPower);
        assertEquals(2, rig.sink.lastGapLock);
        rig.transport.snapshots.add(snapshot(0, 1));
        rig.advance(250);
        assertTrue(rig.sink.profiles.isEmpty());
        assertEquals(250, rig.executor.lastDelay);
    }

    @Test public void queuedCallbackBeforeReadIsNotSwallowedByInitialSnapshot() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.executor.afterSubscribe = () -> rig.transport.emitPower(0, 25);
        rig.activate();

        rig.transport.emitPower(2, 100);
        rig.runAll();
        assertEquals(Arrays.asList("power_on"), rig.sink.profiles);
    }

    @Test public void unchangedSixtySecondGetPreservesPowerSuppressionToken() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.activate();
        rig.transport.emitPower(0, 59_900);
        rig.runAll();
        rig.transport.snapshots.add(snapshot(0, 2));
        rig.advance(60_000);
        rig.transport.emitLock(1, 60_500);
        rig.runAll();

        assertEquals(Arrays.asList("power_off"), rig.sink.profiles);
        assertEquals(Arrays.asList("unlock:power_off:600"), rig.sink.suppressed);
    }

    @Test public void sparseMismatchIsSilentCreatesNoTokenAndUsesFallbackForNextEdge() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.activate();
        rig.transport.snapshots.add(snapshot(0, 2));
        rig.advance(60_000);
        assertTrue(rig.sink.profiles.isEmpty());
        assertTrue(rig.sink.hasLog("avas_telemetry_reconciled"));
        assertTrue(rig.transport.sessions.get(0).closed);

        rig.transport.snapshots.add(snapshot(0, 1));
        rig.advance(250);
        assertEquals(Arrays.asList("unlock"), rig.sink.profiles);
        assertTrue(rig.sink.suppressed.isEmpty());
    }

    @Test public void liveCallbackRacingSparseGetWinsAndRetainsLivePowerPriority() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.activate();
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.transport.duringRead = () -> rig.transport.emitPower(0, 60_000);
        rig.advance(60_000);
        rig.transport.emitLock(1, 60_241);
        rig.runAll();

        assertEquals(Arrays.asList("power_off"), rig.sink.profiles);
        assertEquals(Arrays.asList("unlock:power_off:241"), rig.sink.suppressed);
        assertFalse(rig.sink.hasLog("avas_telemetry_reconciled"));
        assertFalse(rig.transport.sessions.get(0).closed);
    }

    @Test public void listenerErrorAndInvalidCallbackImmediatelyFallbackAndReseed() {
        Rig listener = new Rig();
        listener.transport.snapshots.add(snapshot(2, 2));
        listener.activate();
        listener.transport.emitError("lost", 10);
        listener.runAll();
        assertEquals(0, listener.executor.lastDelay);
        listener.transport.snapshots.add(snapshot(0, 1));
        listener.advance(0);
        assertTrue(listener.sink.profiles.isEmpty());
        assertEquals(250, listener.executor.lastDelay);

        Rig invalid = new Rig();
        invalid.transport.snapshots.add(snapshot(2, 2));
        invalid.activate();
        invalid.transport.emitPower(-1, 10);
        invalid.runAll();
        assertEquals(0, invalid.executor.lastDelay);
        invalid.transport.snapshots.add(snapshot(0, 1));
        invalid.advance(0);
        assertTrue(invalid.sink.profiles.isEmpty());
        assertEquals(250, invalid.executor.lastDelay);
    }

    @Test public void invalidSubscribedGetFallsBackReseedsAndRetriesAfterSixtySeconds() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.activate();
        rig.transport.snapshots.add(snapshot(-1, -1));
        rig.advance(60_000);
        assertTrue(rig.transport.sessions.get(0).closed);

        rig.transport.snapshots.add(snapshot(0, 1));
        rig.advance(250); // first recovered snapshot is silent
        assertTrue(rig.sink.profiles.isEmpty());
        rig.transport.snapshots.add(snapshot(0, 2));
        rig.advance(250);
        assertEquals(Arrays.asList("lock"), rig.sink.profiles);

        while (rig.clock.now < 120_000) {
            rig.transport.snapshots.add(snapshot(0, 2));
            rig.advance(250);
        }
        assertEquals(2, rig.transport.sessions.size());
        assertFalse(rig.transport.sessions.get(1).closed);
        assertEquals(60_000, rig.executor.lastDelay);
    }

    @Test public void registrationFailureUsesFallbackAndRecoveryRejectsStaleCallbacks() {
        Rig rig = new Rig();
        rig.transport.failSubscriptions = 1;
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.activate();
        rig.transport.snapshots.add(snapshot(0, 2));
        rig.advance(250);
        assertEquals(Arrays.asList("power_off"), rig.sink.profiles);

        while (rig.clock.now < 60_000) {
            rig.transport.snapshots.add(snapshot(0, 2));
            rig.advance(250);
        }
        assertEquals(1, rig.transport.sessions.size());
        Session recovered = rig.transport.sessions.get(0);
        rig.deactivate();
        recovered.listener.onValue(AvasTelemetryController.POWER_DEVICE,
                AvasTelemetryController.POWER_FID, 2, 60_100);
        rig.runAll();
        assertEquals(Arrays.asList("power_off"), rig.sink.profiles);
        assertTrue(recovered.closed);
    }

    @Test public void reactivationRejectsOldSessionWithoutPoisoningNewCallbacks() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.activate();
        Session old = rig.transport.sessions.get(0);
        rig.deactivate();
        rig.transport.snapshots.add(snapshot(0, 1));
        rig.activate();
        Session current = rig.transport.sessions.get(1);

        old.listener.onValue(AvasTelemetryController.POWER_DEVICE,
                AvasTelemetryController.POWER_FID, 2, 50);
        rig.runAll();
        current.listener.onValue(AvasTelemetryController.POWER_DEVICE,
                AvasTelemetryController.POWER_FID, 2, 100);
        rig.runAll();
        assertEquals(Arrays.asList("power_on"), rig.sink.profiles);
    }

    @Test public void closeUnregistersAndLateCallbackDoesNotEscapeRejectedExecutor() {
        Rig rig = new Rig();
        rig.transport.snapshots.add(snapshot(2, 2));
        rig.activate();
        Session session = rig.transport.sessions.get(0);
        Thread closer = new Thread(rig.controller::close);
        closer.start();
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (rig.executor.immediate.isEmpty() && System.nanoTime() < deadline) Thread.yield();
        rig.runAll();
        try { closer.join(1_000); } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            fail(failure.toString());
        }
        assertFalse(closer.isAlive());
        assertTrue(session.closed);
        rig.executor.reject = true;
        session.listener.onValue(AvasTelemetryController.POWER_DEVICE,
                AvasTelemetryController.POWER_FID, 0, 10);
    }

    private static AvasTelemetryController.Snapshot snapshot(int power, int lock) {
        return new AvasTelemetryController.Snapshot(power, lock);
    }

    private static final class Rig {
        final FakeClock clock = new FakeClock();
        final FakeExecutor executor = new FakeExecutor(clock);
        final FakeTransport transport = new FakeTransport(executor);
        final FakeSink sink = new FakeSink();
        final AvasTelemetryController controller = new AvasTelemetryController(clock, executor,
                transport, sink, new AvasEventPolicy());
        void activate() { controller.activate(); runAll(); }
        void deactivate() { controller.deactivate(); runAll(); }
        void advance(long ms) { executor.advance(ms); }
        void runAll() { executor.runAll(); }
    }

    private static final class FakeClock implements AvasTelemetryController.Clock {
        long now;
        @Override public long now() { return now; }
    }

    private static final class FakeExecutor implements AvasTelemetryController.Executor {
        final FakeClock clock;
        final java.util.Queue<Runnable> immediate = new ConcurrentLinkedQueue<>();
        final List<Scheduled> scheduled = new ArrayList<>();
        Runnable afterSubscribe;
        long lastDelay = -1;
        boolean reject;
        FakeExecutor(FakeClock clock) { this.clock = clock; }
        @Override public void execute(Runnable task) {
            if (reject) throw new java.util.concurrent.RejectedExecutionException();
            immediate.add(task);
        }
        @Override public AvasTelemetryController.Cancellable schedule(Runnable task, long delayMs) {
            lastDelay = delayMs;
            Scheduled item = new Scheduled(clock.now + delayMs, task);
            scheduled.add(item);
            return () -> item.cancelled = true;
        }
        void runAll() {
            Runnable task;
            while ((task = immediate.poll()) != null) task.run();
        }
        void advance(long ms) {
            clock.now += ms;
            boolean added;
            do {
                added = false;
                for (Scheduled item : new ArrayList<>(scheduled)) {
                    if (!item.cancelled && item.at <= clock.now) {
                        item.cancelled = true;
                        immediate.add(item.task);
                        added = true;
                    }
                }
                runAll();
            } while (added);
        }
    }

    private static final class Scheduled {
        final long at;
        final Runnable task;
        boolean cancelled;
        Scheduled(long at, Runnable task) { this.at = at; this.task = task; }
    }

    private static final class FakeTransport implements AvasTelemetryController.Transport {
        final Deque<AvasTelemetryController.Snapshot> snapshots = new ArrayDeque<>();
        final List<Session> sessions = new ArrayList<>();
        final FakeExecutor executor;
        int failSubscriptions;
        Runnable duringRead;
        FakeTransport(FakeExecutor executor) { this.executor = executor; }
        @Override public AvasTelemetryController.Subscription subscribe(
                AvasTelemetryController.Listener listener) throws Exception {
            if (failSubscriptions-- > 0) throw new IllegalStateException("registration failed");
            Session session = new Session(listener);
            sessions.add(session);
            if (executor.afterSubscribe != null) {
                Runnable action = executor.afterSubscribe;
                executor.afterSubscribe = null;
                action.run();
            }
            return session;
        }
        @Override public AvasTelemetryController.Snapshot read() {
            if (duringRead != null) {
                Runnable action = duringRead;
                duringRead = null;
                action.run();
            }
            assertFalse("missing fake snapshot", snapshots.isEmpty());
            return snapshots.removeFirst();
        }
        void emitPower(int value, long received) {
            latest().listener.onValue(AvasTelemetryController.POWER_DEVICE,
                    AvasTelemetryController.POWER_FID, value, received);
        }
        void emitLock(int value, long received) {
            latest().listener.onValue(AvasTelemetryController.LOCK_DEVICE,
                    AvasTelemetryController.LOCK_FID, value, received);
        }
        void emitError(String reason, long received) {
            latest().listener.onError(reason, received);
        }
        Session latest() { return sessions.get(sessions.size() - 1); }
    }

    private static final class Session implements AvasTelemetryController.Subscription {
        final AvasTelemetryController.Listener listener;
        boolean closed;
        Session(AvasTelemetryController.Listener listener) { this.listener = listener; }
        @Override public void close() { closed = true; }
    }

    private static final class FakeSink implements AvasTelemetryController.Sink {
        final List<String> profiles = new ArrayList<>();
        final List<String> suppressed = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        int lastGapPower = Integer.MIN_VALUE;
        int lastGapLock = Integer.MIN_VALUE;
        @Override public boolean powerOnSuppressionEligible() { return true; }
        @Override public boolean powerOffSuppressionEligible() { return true; }
        @Override public void onProfile(String profile) { profiles.add(profile); }
        @Override public void onSuppressed(String profile, String powerProfile, long deltaMs) {
            suppressed.add(profile + ":" + powerProfile + ":" + deltaMs);
        }
        @Override public void log(String kind, Object... fields) {
            logs.add(kind);
            if ("avas_telemetry_gap".equals(kind)) {
                for (int index = 0; index + 1 < fields.length; index += 2) {
                    if ("power".equals(fields[index])) lastGapPower = (Integer) fields[index + 1];
                    if ("lock".equals(fields[index])) lastGapLock = (Integer) fields[index + 1];
                }
            }
        }
        boolean hasLog(String kind) { return logs.contains(kind); }
    }
}
