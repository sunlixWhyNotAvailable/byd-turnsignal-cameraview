package com.byd.extend;

import static org.junit.Assert.*;

import org.junit.Test;
import static com.byd.extend.AvasEngineSessionPolicy.Action.*;

/** Exercises the real event policy and queue together, without Android or audio. */
public final class AvasAutomaticPlaybackIntegrationTest {

    @org.junit.Rule public final org.junit.rules.TemporaryFolder files = new org.junit.rules.TemporaryFolder();

    @Test public void engineGearAutomationStartsWithoutOkAndStopsOnlyAtPark() {
        AvasEngineSessionPolicy policy = gearEngine();
        assertEquals(NONE, policy.observeGear(1));
        assertEquals(NONE, policy.observeReady(true));
        assertEquals(START, policy.observeGear(4));
        for (int raw : new int[]{4, 2, 3, 5, 6, 255, 0, Integer.MIN_VALUE, 6}) {
            assertEquals(NONE, policy.observeGear(raw));
            assertTrue(policy.liveRequested());
        }
        assertEquals(STOP_WITH_TAIL, policy.observeGear(1));
        assertEquals(NONE, policy.observeGear(1));
        assertFalse(policy.liveRequested());
        // A return to drive during the tail requests one later start, not a second player.
        assertEquals(NONE, policy.observeGear(4));
        assertTrue(policy.liveRequested());
    }

    @Test public void engineButtonChoiceLastsUntilNextParkBoundary() {
        AvasEngineSessionPolicy startedInPark = gearEngine();
        startedInPark.observeGear(1);
        assertEquals(START, startedInPark.toggleLive());
        assertEquals(NONE, startedInPark.observeGear(1));
        assertEquals(NONE, startedInPark.configure(true, true, false, false));
        assertTrue(startedInPark.manualOverride());
        assertEquals(NONE, startedInPark.observeGear(4));
        assertFalse(startedInPark.manualOverride());
        assertEquals(STOP_WITH_TAIL, startedInPark.observeGear(1));

        AvasEngineSessionPolicy stoppedInDrive = gearEngine();
        stoppedInDrive.observeGear(4);
        assertEquals(STOP_WITH_TAIL, stoppedInDrive.toggleLive());
        assertEquals(NONE, stoppedInDrive.observeGear(2));
        assertFalse(stoppedInDrive.liveRequested());
        assertTrue(stoppedInDrive.manualOverride());
        stoppedInDrive.observeGear(1);
        assertFalse(stoppedInDrive.manualOverride());
        stoppedInDrive.observeGear(4);
        assertTrue(stoppedInDrive.liveRequested());
    }

    @Test public void engineCurrentGearReconcilesOnStartupAndOptionChanges() {
        AvasEngineSessionPolicy policy = new AvasEngineSessionPolicy();
        policy.configure(true, true, false, true);
        policy.observePower(2, true);
        assertEquals(NONE, policy.observeGear(4));
        assertEquals(START, policy.configure(true, true, false, false));
        assertEquals(NONE, policy.configure(true, true, false, false));

        AvasEngineSessionPolicy playingInPark = new AvasEngineSessionPolicy();
        playingInPark.configure(true, true);
        playingInPark.observePower(2, true);
        playingInPark.observeGear(1);
        assertEquals(START, playingInPark.observeReady(true));
        assertEquals(STOP_WITH_TAIL, playingInPark.configure(true, true, false, false));

        AvasEngineSessionPolicy unknown = gearEngine();
        assertEquals(NONE, unknown.observeGear(255));
        assertEquals(NONE, unknown.configure(true, true, false, false));
        assertEquals(START, unknown.observeGear(3));
    }

    @Test public void engineTestReleasesToCurrentGearDemandAndOffClearsManualChoice() {
        AvasEngineSessionPolicy policy = gearEngine();
        policy.observeGear(1);
        assertEquals(START, policy.manualStart());
        assertEquals(NONE, policy.observeGear(4));
        assertEquals(RESTORE, policy.manualStop());
        assertEquals(START, policy.manualStart());
        assertEquals(NONE, policy.observeGear(1));
        assertEquals(STOP_NOW, policy.manualStop());
        assertEquals(START, policy.toggleLive());
        assertEquals(STOP_WITH_TAIL, policy.observePower(0, false));
        assertFalse(policy.manualOverride());
        assertFalse(policy.gearValid());
        assertEquals(NONE, policy.observeGear(4));
        assertFalse(policy.liveRequested());
        assertEquals(NONE, policy.observePower(2, false));
        assertFalse(policy.liveRequested());
    }

    @Test public void engineDefaultAndButtonOnlyKeepTheirExistingTriggers() {
        AvasEngineSessionPolicy standard = new AvasEngineSessionPolicy();
        standard.configure(true, true);
        standard.observePower(2, true);
        assertEquals(NONE, standard.observeGear(4));
        assertEquals(NONE, standard.observeReady(false));
        assertEquals(START, standard.observeReady(true));
        assertEquals(NONE, standard.observeGear(1));
        assertEquals(STOP_WITH_TAIL, standard.observePower(0, false));

        AvasEngineSessionPolicy button = new AvasEngineSessionPolicy();
        button.configure(true, true, true, false);
        button.observePower(2, true);
        assertEquals(NONE, button.observeReady(true));
        assertEquals(NONE, button.observeGear(4));
        assertEquals(START, button.toggleLive());
        assertEquals(NONE, button.observeGear(1));
        assertTrue(button.liveRequested());
        assertEquals(Boolean.TRUE, button.rememberedChoice());
    }

    private static AvasEngineSessionPolicy gearEngine() {
        AvasEngineSessionPolicy policy = new AvasEngineSessionPolicy();
        policy.configure(true, true, false, false);
        policy.observePower(2, true);
        policy.observeReady(false);
        return policy;
    }

    @Test public void powerOffSettingsAreExclusiveAndSurviveOtherProfileEdits() {
        AvasConfig.Profile original = new AvasConfig.Profile("power_off", true, false, 15,
                "", java.util.Collections.emptyList(), true);
        assertFalse(original.onlyWhenLockingPoweredOff);
        AvasConfig.Profile deferred = original.withSettings(true, false, 15, "", true, true);
        assertTrue(deferred.onlyWhenLockingPoweredOff);
        assertFalse(deferred.skipConcurrentLockUnlock);
        AvasConfig.Profile disabled = deferred.withSettings(false, false, 25, "")
                .withAssets(java.util.Collections.emptyList(), "");
        assertTrue(disabled.onlyWhenLockingPoweredOff);
        assertFalse(disabled.skipConcurrentLockUnlock);
        assertFalse(disabled.enabled);
        AvasConfig.Profile skip = disabled.withSettings(true, false, 25, "", true, false);
        assertTrue(skip.skipConcurrentLockUnlock);
        assertFalse(skip.onlyWhenLockingPoweredOff);
    }

    @Test public void deferredOffIsConsumedOnceAcrossHelperRecovery() throws Exception {
        java.io.File path = new java.io.File(files.getRoot(), "power-off.bin");
        AvasEventPolicy first = new AvasEventPolicy(new AvasPowerOffLatch(path, 10001));
        first.reconcile(2, 1);
        assertTrue(first.sample(100, 0, 1, false, false, true, true).isEmpty());
        assertTrue(new AvasPowerOffLatch(path, 10001).pending());
        AvasEventPolicy recovered = new AvasEventPolicy(new AvasPowerOffLatch(path, 10001));
        recovered.reconcile(0, 1);
        assertEquals(java.util.List.of("power_off"),
                recovered.sample(300_000, 0, 2, false, false, true, true));
        assertFalse(new AvasPowerOffLatch(path, 10001).pending());
        AvasEventPolicy afterConsumption = new AvasEventPolicy(new AvasPowerOffLatch(path, 10001));
        afterConsumption.reconcile(0, 2);
        assertEquals(java.util.List.of("unlock"),
                afterConsumption.sample(301_000, 0, 1, false, false, true, true));
        assertEquals(java.util.List.of("lock"),
                afterConsumption.sample(302_000, 0, 2, false, false, true, true));
    }

    @Test public void snapshotsNeverArmOrReplayAndUnavailableOffDoesNotSwallowLock() throws Exception {
        java.io.File path = new java.io.File(files.getRoot(), "power-off.bin");
        AvasEventPolicy policy = new AvasEventPolicy(new AvasPowerOffLatch(path, 10001));
        policy.reconcile(0, 1);
        assertEquals(java.util.List.of("lock"), policy.sample(10, 0, 2, false, false, true, true));
        policy.sample(20, 2, 1, false, false, true, true);
        policy.sample(30, 0, 1, false, false, true, true);
        assertEquals(java.util.List.of("lock"), policy.sample(40, 0, 2, false, false, true, false));
        assertFalse(new AvasPowerOffLatch(path, 10001).pending());
        policy.sample(50, 2, 1, false, false, true, true);
        policy.sample(60, 0, 1, false, false, true, true);
        AvasEventPolicy recoveredLocked = new AvasEventPolicy(new AvasPowerOffLatch(path, 10001));
        recoveredLocked.reconcile(0, 2);
        assertFalse(new AvasPowerOffLatch(path, 10001).pending());
    }

    @Test public void powerOnCancelsAndNewPowerOffRearmsWithSimultaneousLock() throws Exception {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.reconcile(2, 1);
        policy.sample(10, 0, 1, false, false, true, true);
        assertEquals(java.util.List.of("power_on"), policy.sample(20, 2, 1, false, false, true, true));
        assertEquals(java.util.List.of("lock"), policy.sample(30, 2, 2, false, false, true, true));
        policy.sample(40, 2, 1, false, false, true, true);
        assertEquals(java.util.List.of("power_off"), policy.sample(50, 0, 2, false, false, true, true));
        policy.sample(60, 0, 1, false, false, true, true);
        assertEquals(java.util.List.of("lock"), policy.sample(70, 0, 2, false, false, true, true));
    }

    @Test(timeout = 2000)
    public void twoEligibleEdgesFromOneSnapshotKeepPowerThenUnlockBeforeWorkerStarts()
            throws Exception {
        AvasEventPolicy policy = new AvasEventPolicy();
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        assertTrue(policy.sample(0, 2, 2, false, false).isEmpty());
        for (String profile : policy.sample(100, 0, 1, false, false)) {
            queue.enqueueExterior(profile, false);
        }
        assertEquals(2, queue.pendingCount());
        AvasPlaybackQueue.Request powerOff = queue.take();
        assertEquals("power_off", powerOff.profile);
        assertFalse(powerOff.cancelled.get());
        queue.finish(powerOff);
        AvasPlaybackQueue.Request unlock = queue.take();
        assertEquals("unlock", unlock.profile);
        assertFalse(unlock.cancelled.get());
        queue.close();
    }
}
