package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.Test;

public final class TurnSignalTelemetryControllerTest {
    @Test public void initialSeedIsSilentAndCallbacksAreSerializedLiveState() {
        Rig rig = new Rig();
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.controller.start();

        assertEquals(TurnSignalTelemetryController.Source.INITIAL, rig.sink.sources.get(0));
        assertTrue(rig.controller.subscriptionHealthy());
        rig.transport.emit(TurnSignalTelemetryController.STALK_DEVICE,
                TurnSignalTelemetryController.STALK_FID, 3, 10);
        rig.transport.emit(TurnSignalTelemetryController.BLINK_DEVICE,
                TurnSignalTelemetryController.BLINK_FID, 2, 11);
        assertEquals(TurnSignalTelemetryController.Source.CALLBACK, rig.sink.sources.get(2));
        assertEquals(3, rig.sink.snapshots.get(2).stalk);
        assertEquals(2, rig.sink.snapshots.get(2).blink);
    }

    @Test public void callbackModeGetsAtOneSecondAndUsesReconciliationDeadline() {
        Rig rig = new Rig();
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.controller.start();
        assertTrue(rig.controller.reconciliationFresh(1_250));
        assertFalse(rig.controller.reconciliationFresh(1_251));

        rig.clock.now = 999;
        rig.controller.tick();
        assertEquals(1, rig.transport.readCount);
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.clock.now = 1_000;
        rig.controller.tick();
        assertEquals(2, rig.transport.readCount);
        assertEquals(TurnSignalTelemetryController.Source.RECONCILE,
                rig.sink.sources.get(rig.sink.sources.size() - 1));
    }

    @Test public void explicitSubscriptionFailureUsesFiftyMsReadsAndRetriesAtOneSecond() {
        Rig rig = new Rig();
        rig.transport.failSubscriptions = 1;
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.controller.start();
        assertFalse(rig.controller.subscriptionHealthy());

        rig.clock.now = 49;
        rig.controller.tick();
        assertEquals(1, rig.transport.readCount);
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.clock.now = 50;
        rig.controller.tick();
        assertEquals(2, rig.transport.readCount);
        assertEquals(1, rig.transport.subscribeCount);

        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.clock.now = 1_000;
        rig.controller.tick();
        assertEquals(2, rig.transport.subscribeCount);
        assertTrue(rig.controller.subscriptionHealthy());
        assertEquals(TurnSignalTelemetryController.Source.RECOVERY_SEED,
                rig.sink.sources.get(rig.sink.sources.size() - 1));
    }

    @Test public void newerCallbackArrivalPreventsOlderGetFromOverwritingSignal() {
        Rig rig = new Rig();
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.controller.start();
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.transport.duringRead = () -> rig.transport.emit(
                TurnSignalTelemetryController.BLINK_DEVICE,
                TurnSignalTelemetryController.BLINK_FID, 2, 1_000);
        rig.clock.now = 1_000;
        rig.controller.tick();

        TurnSignalTelemetryController.Snapshot last =
                rig.sink.snapshots.get(rig.sink.snapshots.size() - 1);
        assertEquals(2, last.blink);
    }

    @Test public void reconcileMismatchIsMarkedConflictAndNeverPresentedAsCallback() {
        Rig rig = new Rig();
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.controller.start();
        rig.transport.reads.add(snapshot(3, 90, 2, 20));
        rig.clock.now = 1_000;
        rig.controller.tick();

        int last = rig.sink.sources.size() - 1;
        assertEquals(TurnSignalTelemetryController.Source.RECONCILE, rig.sink.sources.get(last));
        assertTrue(rig.sink.conflicts.get(last));
    }

    @Test public void numericReconciliationUpdatesStateWithoutCancelingControlHistory() {
        Rig rig = new Rig();
        rig.transport.reads.add(snapshot(3, 10.0f, 2, 20.0f));
        rig.controller.start();
        rig.transport.reads.add(snapshot(3, 12.5f, 2, 21.0f));
        rig.clock.now = 1_000;
        rig.controller.tick();

        int last = rig.sink.sources.size() - 1;
        assertEquals(TurnSignalTelemetryController.Source.RECONCILE,
                rig.sink.sources.get(last));
        assertFalse(rig.sink.conflicts.get(last));
        assertEquals(Float.floatToIntBits(12.5f), rig.sink.snapshots.get(last).steering);
        assertEquals(Float.floatToIntBits(21.0f), rig.sink.snapshots.get(last).speed);
        assertEquals(0, (int) rig.sink.liveMasks.get(last));
    }

    @Test public void invalidFallbackReadClearsFreshnessInsteadOfExtendingStaleState() {
        Rig rig = new Rig();
        rig.transport.failSubscriptions = 1;
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.controller.start();
        assertTrue(rig.controller.dataFresh(200));

        rig.transport.reads.add(new TurnSignalTelemetryController.Snapshot(
                TurnSignalTelemetryController.MISSING,
                TurnSignalTelemetryController.MISSING,
                TurnSignalTelemetryController.MISSING,
                TurnSignalTelemetryController.MISSING));
        rig.clock.now = 50;
        rig.controller.tick();
        assertFalse(rig.controller.dataFresh(50));
    }

    @Test public void stalledGetCannotRefreshCallbackOrFallbackFreshness() {
        Rig callback = new Rig();
        callback.transport.reads.add(snapshot(1, 0, 1, 0));
        callback.controller.start();
        callback.transport.reads.add(snapshot(1, 0, 1, 0));
        callback.transport.duringRead = () -> callback.clock.now = 2_251;
        callback.clock.now = 1_000;
        callback.controller.tick();
        assertFalse(callback.controller.subscriptionHealthy());
        assertFalse(callback.controller.dataFresh(2_251));

        Rig fallback = new Rig();
        fallback.transport.failSubscriptions = 1;
        fallback.transport.reads.add(snapshot(1, 0, 1, 0));
        fallback.controller.start();
        fallback.transport.reads.add(snapshot(1, 0, 1, 0));
        fallback.transport.duringRead = () -> fallback.clock.now = 301;
        fallback.clock.now = 50;
        fallback.controller.tick();
        assertFalse(fallback.controller.dataFresh(301));
    }

    @Test public void failedSteeringGetCannotSeedFreshOrHealthyState() {
        Rig rig = new Rig();
        rig.transport.reads.add(new TurnSignalTelemetryController.Snapshot(
                1, TurnSignalTelemetryController.MISSING, 1, Float.floatToIntBits(0)));
        rig.controller.start();

        assertFalse(rig.controller.subscriptionHealthy());
        assertFalse(rig.controller.dataFresh(0));
        assertTrue(rig.sink.snapshots.isEmpty());
    }

    @Test public void failedSpeedGetCannotSeedFreshOrHealthyState() {
        Rig rig = new Rig();
        rig.transport.reads.add(new TurnSignalTelemetryController.Snapshot(
                1, Float.floatToIntBits(0), 1, TurnSignalTelemetryController.MISSING));
        rig.controller.start();

        assertFalse(rig.controller.subscriptionHealthy());
        assertFalse(rig.controller.dataFresh(0));
        assertTrue(rig.sink.snapshots.isEmpty());
    }

    @Test public void unreadableFallbackRetriesGetAtOneSecondNotFiftyMs() {
        Rig rig = new Rig();
        rig.transport.failSubscriptions = 3;
        TurnSignalTelemetryController.Snapshot failed =
                new TurnSignalTelemetryController.Snapshot(
                        TurnSignalTelemetryController.MISSING,
                        TurnSignalTelemetryController.MISSING,
                        TurnSignalTelemetryController.MISSING,
                        TurnSignalTelemetryController.MISSING);
        rig.transport.reads.add(failed);
        rig.controller.start();
        assertEquals(1, rig.transport.readCount);

        rig.clock.now = 50;
        rig.controller.tick();
        assertEquals(1, rig.transport.readCount);
        rig.clock.now = 999;
        rig.controller.tick();
        assertEquals(1, rig.transport.readCount);

        rig.transport.reads.add(failed);
        rig.clock.now = 1_000;
        rig.controller.tick();
        assertEquals(2, rig.transport.readCount);
        rig.clock.now = 1_050;
        rig.controller.tick();
        assertEquals(2, rig.transport.readCount);
    }

    @Test public void queuedSteeringCallbackCannotMakeRacingGetStalkLookLive() {
        Rig rig = new Rig(true);
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.controller.start();
        rig.transport.reads.add(snapshot(3, 0, 1, 0));
        rig.transport.duringRead = () -> rig.transport.emit(
                TurnSignalTelemetryController.STEERING_DEVICE,
                TurnSignalTelemetryController.STEERING_FID,
                Float.floatToIntBits(10), 1_000);
        rig.clock.now = 1_000;
        rig.controller.tick();

        assertEquals(2, rig.sink.snapshots.size());
        int conflict = rig.sink.snapshots.size() - 1;
        assertEquals(TurnSignalTelemetryController.Source.RECONCILE,
                rig.sink.sources.get(conflict));
        assertEquals(0, (int) rig.sink.liveMasks.get(conflict));
        assertTrue(rig.sink.conflicts.get(conflict));
        assertEquals(3, rig.sink.snapshots.get(conflict).stalk);
        rig.dispatcher.runAll();
        int last = rig.sink.snapshots.size() - 1;
        assertEquals(TurnSignalTelemetryController.Source.CALLBACK, rig.sink.sources.get(last));
        assertEquals(TurnSignalTelemetryController.LIVE_STEERING,
                (int) rig.sink.liveMasks.get(last));
        assertEquals(3, rig.sink.snapshots.get(last).stalk);
    }

    @Test public void steeringCallbackCannotMakeEarlierReconciledStalkLookLive() {
        Rig rig = new Rig();
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.controller.start();
        rig.transport.reads.add(snapshot(3, 0, 1, 0));
        rig.clock.now = 1_000;
        rig.controller.tick();
        int reconcile = rig.sink.snapshots.size() - 1;
        assertEquals(TurnSignalTelemetryController.Source.RECONCILE,
                rig.sink.sources.get(reconcile));
        assertEquals(0, (int) rig.sink.liveMasks.get(reconcile));

        rig.transport.emit(TurnSignalTelemetryController.STEERING_DEVICE,
                TurnSignalTelemetryController.STEERING_FID,
                Float.floatToIntBits(10), 1_010);
        int callback = rig.sink.snapshots.size() - 1;
        assertEquals(TurnSignalTelemetryController.Source.CALLBACK,
                rig.sink.sources.get(callback));
        assertEquals(TurnSignalTelemetryController.LIVE_STEERING,
                (int) rig.sink.liveMasks.get(callback));
        assertEquals(3, rig.sink.snapshots.get(callback).stalk);
    }

    @Test public void invalidNonRacingGetFailsClosedWhileSiblingCallbackIsQueued() {
        Rig rig = new Rig(true);
        rig.transport.reads.add(snapshot(1, 0, 1, 0));
        rig.controller.start();
        rig.transport.reads.add(new TurnSignalTelemetryController.Snapshot(
                1, Float.floatToIntBits(0), 1, TurnSignalTelemetryController.MISSING));
        rig.transport.duringRead = () -> rig.transport.emit(
                TurnSignalTelemetryController.STEERING_DEVICE,
                TurnSignalTelemetryController.STEERING_FID,
                Float.floatToIntBits(10), 1_000);
        rig.clock.now = 1_000;
        rig.controller.tick();

        assertFalse(rig.controller.subscriptionHealthy());
        assertFalse(rig.controller.dataFresh(1_000));
        assertEquals(1, rig.sink.snapshots.size());
        assertTrue(rig.transport.subscriptions.get(0).closed);
        rig.dispatcher.runAll();
        assertEquals(1, rig.sink.snapshots.size());
    }

    private static TurnSignalTelemetryController.Snapshot snapshot(
            int stalk, float steering, int blink, float speed) {
        return new TurnSignalTelemetryController.Snapshot(stalk,
                Float.floatToIntBits(steering), blink, Float.floatToIntBits(speed));
    }

    private static final class Rig {
        final FakeClock clock = new FakeClock();
        final FakeTransport transport = new FakeTransport();
        final FakeSink sink = new FakeSink();
        final FakeDispatcher dispatcher;
        final TurnSignalTelemetryController controller;
        Rig() { this(false); }
        Rig(boolean queued) {
            dispatcher = new FakeDispatcher(queued);
            controller = new TurnSignalTelemetryController(clock, dispatcher, transport, sink);
        }
    }

    private static final class FakeDispatcher
            implements TurnSignalTelemetryController.Dispatcher {
        final boolean queued;
        final Deque<Runnable> tasks = new ArrayDeque<>();
        FakeDispatcher(boolean queued) { this.queued = queued; }
        @Override public void execute(Runnable task) {
            if (queued) tasks.addLast(task);
            else task.run();
        }
        void runAll() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }

    private static final class FakeClock implements TurnSignalTelemetryController.Clock {
        long now;
        @Override public long now() { return now; }
    }

    private static final class FakeTransport implements TurnSignalTelemetryController.Transport {
        final Deque<TurnSignalTelemetryController.Snapshot> reads = new ArrayDeque<>();
        final List<FakeSubscription> subscriptions = new ArrayList<>();
        int failSubscriptions;
        int subscribeCount;
        int readCount;
        Runnable duringRead;
        @Override public TurnSignalTelemetryController.Subscription subscribe(
                TurnSignalTelemetryController.Listener listener) throws Exception {
            subscribeCount++;
            if (failSubscriptions-- > 0) throw new IllegalStateException("rejected");
            FakeSubscription subscription = new FakeSubscription(listener);
            subscriptions.add(subscription);
            return subscription;
        }
        @Override public TurnSignalTelemetryController.Snapshot read() {
            readCount++;
            TurnSignalTelemetryController.Snapshot result = reads.removeFirst();
            if (duringRead != null) {
                Runnable action = duringRead;
                duringRead = null;
                action.run();
            }
            return result;
        }
        void emit(int device, int fid, int value, long receivedMs) {
            subscriptions.get(subscriptions.size() - 1).listener
                    .onValue(device, fid, value, receivedMs);
        }
    }

    private static final class FakeSubscription
            implements TurnSignalTelemetryController.Subscription {
        final TurnSignalTelemetryController.Listener listener;
        boolean closed;
        FakeSubscription(TurnSignalTelemetryController.Listener listener) {
            this.listener = listener;
        }
        @Override public void close() { closed = true; }
    }

    private static final class FakeSink implements TurnSignalTelemetryController.Sink {
        final List<TurnSignalTelemetryController.Snapshot> snapshots = new ArrayList<>();
        final List<TurnSignalTelemetryController.Source> sources = new ArrayList<>();
        final List<Integer> liveMasks = new ArrayList<>();
        final List<Boolean> conflicts = new ArrayList<>();
        @Override public void onSnapshot(TurnSignalTelemetryController.Snapshot snapshot,
                TurnSignalTelemetryController.Source source, int liveMask,
                boolean conflict, long observedMs) {
            snapshots.add(snapshot);
            sources.add(source);
            liveMasks.add(liveMask);
            conflicts.add(conflict);
        }
        @Override public void onMode(boolean subscribed, boolean seeded, String reason) {}
    }
}
