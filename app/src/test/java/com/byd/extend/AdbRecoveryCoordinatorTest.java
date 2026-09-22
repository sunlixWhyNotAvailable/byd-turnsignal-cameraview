package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdbRecoveryCoordinatorTest {
    @Test public void initialHealthyProofIsAvailableWithoutClaimingRecovery() {
        AdbRecoveryCoordinator coordinator = configuredCoordinator(new MemoryStore());
        coordinator.begin(false, 1L);
        coordinator.ready();

        assertEquals(AdbRecoverySnapshot.Stage.READY, coordinator.snapshot().stage());
        assertEquals(AdbRecoverySnapshot.ReadyOutcome.AVAILABLE,
                coordinator.snapshot().readyOutcome());
    }

    @Test public void failedInitialProofThenRecoveryIsRestoredAcrossSameCycleRetries() {
        AdbRecoveryCoordinator coordinator = configuredCoordinator(new MemoryStore());
        coordinator.begin(false, 1L);
        coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);
        coordinator.begin(false, 2L);
        coordinator.ready();

        assertEquals(AdbRecoverySnapshot.ReadyOutcome.RESTORED,
                coordinator.snapshot().readyOutcome());
    }

    @Test public void freshCycleAndDisableReenableResetRecoveryAttempt() {
        AdbRecoveryCoordinator coordinator = configuredCoordinator(new MemoryStore());
        coordinator.begin(false, 1L);
        coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);
        coordinator.begin(true, 2L);
        coordinator.ready();
        assertEquals(AdbRecoverySnapshot.ReadyOutcome.AVAILABLE,
                coordinator.snapshot().readyOutcome());

        coordinator.begin(false, 3L);
        coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);
        coordinator.configure(false);
        coordinator.configure(true);
        coordinator.begin(false, 4L);
        coordinator.ready();
        assertEquals(AdbRecoverySnapshot.ReadyOutcome.AVAILABLE,
                coordinator.snapshot().readyOutcome());
    }

    @Test public void restartedRuntimeDoesNotInheritUnfinishedRecoveryAttempt() {
        MemoryStore store = new MemoryStore();
        AdbRecoveryCoordinator first = configuredCoordinator(store);
        first.begin(false, 1L);
        first.stage(AdbRecoverySnapshot.Stage.PREPARING);

        AdbRecoveryCoordinator restarted = configuredCoordinator(store);
        restarted.begin(false, 2L);
        restarted.ready();
        assertEquals(AdbRecoverySnapshot.ReadyOutcome.AVAILABLE,
                restarted.snapshot().readyOutcome());
    }

    @Test public void successfulOutcomeSurvivesOrdinaryReconfigure() {
        AdbRecoveryCoordinator coordinator = configuredCoordinator(new MemoryStore());
        coordinator.begin(false, 1L);
        coordinator.stage(AdbRecoverySnapshot.Stage.PREPARING);
        coordinator.ready();
        coordinator.configure(true);
        assertEquals(AdbRecoverySnapshot.ReadyOutcome.RESTORED,
                coordinator.snapshot().readyOutcome());
        assertTrue(coordinator.snapshot().authenticated5555());
    }

    @Test public void snapshotIdentityIncludesReadyOutcome() {
        AdbRecoverySnapshot available = new AdbRecoverySnapshot(
                AdbRecoverySnapshot.Stage.READY, true, true, true,
                -1L, 1L, false, AdbRecoverySnapshot.ReadyOutcome.AVAILABLE);
        AdbRecoverySnapshot restored = new AdbRecoverySnapshot(
                AdbRecoverySnapshot.Stage.READY, true, true, true,
                -1L, 1L, false, AdbRecoverySnapshot.ReadyOutcome.RESTORED);
        assertNotEquals(available, restored);
        // Hash collisions are legal; distinct outcomes must remain distinct keys.
        java.util.Set<AdbRecoverySnapshot> outcomes = new java.util.HashSet<>();
        outcomes.add(available);
        outcomes.add(restored);
        assertEquals(2, outcomes.size());
    }

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

    private static AdbRecoveryCoordinator configuredCoordinator(MemoryStore store) {
        AdbRecoveryCoordinator coordinator = new AdbRecoveryCoordinator(store);
        coordinator.configure(true);
        return coordinator;
    }
}
