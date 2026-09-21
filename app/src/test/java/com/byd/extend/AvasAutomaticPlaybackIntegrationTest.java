package com.byd.extend;

import static org.junit.Assert.*;

import org.junit.Test;

/** Exercises the real event policy and queue together, without Android or audio. */
public final class AvasAutomaticPlaybackIntegrationTest {
    @Test(timeout = 2000)
    public void suppressedUnlockCannotReplacePendingPowerButLaterIndependentLockCan()
            throws Exception {
        AvasEventPolicy policy = new AvasEventPolicy();
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request playing = queue.enqueueExterior("power_on", true);
        assertSame(playing, queue.take());
        assertTrue(policy.sample(0, 2, 2, true, true).isEmpty());

        for (String profile : policy.sample(100, 0, 2, true, true)) {
            queue.enqueueExterior(profile, false);
        }
        assertEquals("automatic_queued", queue.state("power_off"));
        assertTrue(policy.sample(341, 0, 1, true, true).isEmpty());
        assertEquals("unlock", policy.suppressedProfile());
        assertEquals(1, queue.pendingCount());
        assertEquals("automatic_queued", queue.state("power_off"));

        AvasPlaybackQueue.Request latest = null;
        for (String profile : policy.sample(600, 0, 2, true, true)) {
            latest = queue.enqueueExterior(profile, false);
        }
        assertNotNull(latest);
        assertEquals("lock", latest.profile);
        assertEquals("power_off", latest.supersededAutomatic.profile);
        assertEquals("idle", queue.state("power_off"));
        assertFalse(playing.cancelled.get());
        queue.finish(playing);
        assertSame(latest, queue.take());
        queue.close();
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
        assertNull(unlock.supersededAutomatic);
        queue.close();
    }
}
