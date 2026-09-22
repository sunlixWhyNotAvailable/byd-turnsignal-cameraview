package com.byd.extend;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Real OEM proxy + production subscription requests + controller, without Android Binder GET. */
public final class TurnSignalTelemetryIntegrationTest {
    @Test public void productionSubscriptionDeliversBothFloatSignalsAndIntegerControls()
            throws Exception {
        Rig rig = new Rig();
        try {
            rig.controller.start();
            rig.backend.listener.onChanged(1004, 321912876, 3, null);
            rig.backend.listener.onChanged(1001, 300941320, -2.4f, null);
            rig.backend.listener.onChanged(1004, 950009900, 2, null);
            rig.backend.listener.onChanged(1013, -1807745016, 21.5f, null);
            rig.flush();

            assertTrue(rig.controller.subscriptionHealthy());
            assertEquals(List.of(0, 1, 2, 4, 8), rig.masks);
            TurnSignalTelemetryController.Snapshot state = rig.states.get(4);
            assertEquals(3, state.stalk);
            assertEquals(-2.4f, Float.intBitsToFloat(state.steering), 0f);
            assertEquals(2, state.blink);
            assertEquals(21.5f, Float.intBitsToFloat(state.speed), 0f);
            assertEquals(TurnSignalTelemetryController.Source.CALLBACK, rig.lastSource);
        } finally {
            rig.controller.close();
        }
        assertTrue(rig.backend.enabled.isEmpty());
        assertEquals(1, rig.backend.unregisterCount);
    }

    @Test public void typeFailureEntersFallbackAndQueuedValuesCannotResurrectClosedSession()
            throws Exception {
        Rig rig = new Rig();
        try {
            rig.controller.start();
            // OEM supplies the wrong overload for the Float steering request.
            rig.backend.listener.onChanged(1001, 300941320, 12, null);
            rig.backend.listener.onChanged(1013, -1807745016, 20.5f, null);
            rig.flush();
            assertFalse(rig.controller.subscriptionHealthy());
            assertEquals(1, rig.states.size()); // only initial GET, never a live gesture
            assertTrue(rig.backend.enabled.isEmpty());

            rig.now = 999;
            rig.controller.tick();
            assertFalse(rig.controller.subscriptionHealthy());
            rig.now = 1000;
            rig.controller.tick();
            assertTrue(rig.controller.subscriptionHealthy());
            rig.backend.listener.onChanged(1001, 300941320, 25.5f, null);
            int beforeClose = rig.states.size();
            rig.controller.close();
            rig.flush();
            assertEquals(beforeClose, rig.states.size());
            assertTrue(rig.backend.enabled.isEmpty());
        } finally {
            rig.controller.close();
        }
    }

    private static final class Rig implements TurnSignalTelemetryController.Sink {
        final FixedBydTelemetryManagerTest.FakeManager backend =
                new FixedBydTelemetryManagerTest.FakeManager();
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<TurnSignalTelemetryController.Snapshot> states = new ArrayList<>();
        final List<Integer> masks = new ArrayList<>();
        final TurnSignalTelemetryController controller;
        TurnSignalTelemetryController.Source lastSource;
        long now;

        Rig() throws Exception {
            FixedBydTelemetryManager manager = new FixedBydTelemetryManager(backend,
                    FixedBydTelemetryManagerTest.FakeOnAutoListener.class);
            controller = new TurnSignalTelemetryController(() -> now, tasks::add,
                    new TurnSignalTelemetryController.Transport() {
                        @Override public TurnSignalTelemetryController.Subscription subscribe(
                                TurnSignalTelemetryController.Listener listener) throws Exception {
                            return TurnSignalTelemetryTransport.subscribe(manager, listener);
                        }
                        @Override public TurnSignalTelemetryController.Snapshot read() {
                            return new TurnSignalTelemetryController.Snapshot(1, 0, 1, 0);
                        }
                    }, this);
        }
        void flush() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
        @Override public void onSnapshot(TurnSignalTelemetryController.Snapshot state,
                TurnSignalTelemetryController.Source source, int mask, boolean conflict, long now) {
            states.add(state);
            masks.add(mask);
            lastSource = source;
        }
        @Override public void onMode(boolean subscribed, boolean seeded, String reason) { }
    }
}
