package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdbRecoveryCoordinatorTest {
    @Test public void wifiConnectionSuppressesHintForWholePersistedCycle() {
        MemoryStore store = new MemoryStore();
        AdbRecoveryCoordinator first = new AdbRecoveryCoordinator(store);
        first.configure(true);
        first.begin(false, 100L);
        first.wifi(false, 110L);
        assertFalse(first.snapshot().hintSuppressedForCycle());
        assertEquals(110L, first.snapshot().waitStartedElapsedMs());

        first.wifi(true, 120L);
        first.wifi(false, 130L);
        assertTrue(first.snapshot().hintSuppressedForCycle());

        AdbRecoveryCoordinator restarted = new AdbRecoveryCoordinator(store);
        restarted.configure(true);
        restarted.begin(false, 200L);
        restarted.wifi(false, 210L);
        assertTrue(restarted.snapshot().hintSuppressedForCycle());
        assertEquals(first.snapshot().cycleId(), restarted.snapshot().cycleId());
    }

    @Test public void explicitHoldPersistsAndRepeatedEventsDoNotResetWaitStart() {
        MemoryStore store = new MemoryStore();
        AdbRecoveryCoordinator coordinator = new AdbRecoveryCoordinator(store);
        coordinator.configure(true);
        coordinator.begin(false, 50L);
        assertEquals(-1L, coordinator.snapshot().waitStartedElapsedMs());
        coordinator.wifi(false, 60L);
        coordinator.suppressHint();
        coordinator.begin(false, 5_000L);
        assertTrue(coordinator.snapshot().hintSuppressedForCycle());
        assertEquals(60L, coordinator.snapshot().waitStartedElapsedMs());
    }

    @Test public void successThenNewLossRearmsHintInNewCycle() {
        MemoryStore store = new MemoryStore();
        AdbRecoveryCoordinator coordinator = new AdbRecoveryCoordinator(store);
        coordinator.configure(true);
        coordinator.begin(false, 1L);
        coordinator.suppressHint();
        long first = coordinator.snapshot().cycleId();
        coordinator.ready();
        assertTrue(coordinator.snapshot().authenticated5555());

        coordinator.begin(false, 2L);
        assertEquals(first + 1L, coordinator.snapshot().cycleId());
        assertFalse(coordinator.snapshot().hintSuppressedForCycle());
        assertFalse(coordinator.snapshot().authenticated5555());
    }

    @Test public void bootStartsNewCycleEvenWhenPriorCycleWasUnresolved() {
        MemoryStore store = new MemoryStore();
        AdbRecoveryCoordinator coordinator = new AdbRecoveryCoordinator(store);
        coordinator.configure(true);
        coordinator.begin(false, 1L);
        coordinator.suppressHint();
        long first = coordinator.snapshot().cycleId();
        coordinator.begin(true, 2L);
        assertEquals(first + 1L, coordinator.snapshot().cycleId());
        assertFalse(coordinator.snapshot().hintSuppressedForCycle());
    }

    @Test public void disablingProducesInactiveSnapshotWithoutDestroyingCyclePersistence() {
        MemoryStore store = new MemoryStore();
        AdbRecoveryCoordinator coordinator = new AdbRecoveryCoordinator(store);
        coordinator.configure(true);
        coordinator.begin(false, 1L);
        coordinator.configure(false);
        assertEquals(AdbRecoverySnapshot.Stage.DISABLED, coordinator.snapshot().stage());
        assertFalse(coordinator.snapshot().enabled());
        assertTrue(store.active);
    }

    private static final class MemoryStore implements AdbRecoveryCoordinator.Store {
        long id;
        boolean active;
        boolean suppressed;
        @Override public long cycleId() { return id; }
        @Override public boolean cycleActive() { return active; }
        @Override public boolean hintSuppressed() { return suppressed; }
        @Override public void save(long cycleId, boolean active, boolean hintSuppressed) {
            id = cycleId;
            this.active = active;
            suppressed = hintSuppressed;
        }
    }
}
