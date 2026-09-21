package com.byd.extend;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class AvasPlaybackQueueTest {
    @Test public void requestCarriesImmutableSourcePidAndMonotonicTimes() {
        long[] times = {10, 11};
        int[] index = {0};
        AvasPlaybackQueue queue = new AvasPlaybackQueue(() -> times[index[0]++], 77);
        AvasPlaybackQueue.Request request = queue.enqueueExterior("power_on", false);
        assertEquals(1, request.diagnostics.requestId);
        assertEquals("power_on", request.diagnostics.profile);
        assertEquals("automatic", request.diagnostics.source);
        assertEquals(77, request.diagnostics.helperPid);
        assertEquals(10, request.diagnostics.acceptedMs);
        assertEquals(11, request.diagnostics.enqueuedMs);
    }

    @Test public void pendingAutomaticIsVisibleAndRejectsNotes() {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        queue.enqueueExterior("lock", false);
        assertEquals("automatic_queued", queue.state("lock"));
        assertNull(queue.enqueueAudition("unlock", "asset", "session"));
    }

    @Test public void automaticEventsAreFifoAndNotPreempted() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request off = queue.enqueue("power_off", false);
        assertSame(off, queue.take());
        AvasPlaybackQueue.Request lock = queue.enqueue("lock", false);
        assertFalse(off.cancelled.get());
        queue.finish(off);
        assertSame(lock, queue.take());
    }

    @Test public void idleDoubleKeepsFirstAndSecondBeforeWorkerTake() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request first = queue.enqueue("power_on", false);
        AvasPlaybackQueue.Request second = queue.enqueue("unlock", false);

        assertEquals(2, queue.pendingCount());
        assertSame(first, queue.take());
        queue.finish(first);
        assertSame(second, queue.take());
        assertFalse(first.cancelled.get());
        assertFalse(second.cancelled.get());
        assertNull(second.supersededAutomatic);
    }

    @Test public void idleTripleKeepsFirstAndLatestBeforeWorkerTake() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request first = queue.enqueue("power_on", false);
        AvasPlaybackQueue.Request second = queue.enqueue("unlock", false);
        AvasPlaybackQueue.Request latest = queue.enqueue("lock", false);

        assertEquals(2, queue.pendingCount());
        assertFalse(first.cancelled.get());
        assertTrue(second.cancelled.get());
        assertEquals(second.id, latest.supersededAutomatic.requestId);
        assertEquals("unlock", latest.supersededAutomatic.profile);
        assertSame(first, queue.take());
        queue.finish(first);
        assertSame(latest, queue.take());
    }

    @Test public void activeAutomaticBurstKeepsActiveAndLatestThenRepeats() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request active = queue.enqueue("power_on", false);
        assertSame(active, queue.take());
        AvasPlaybackQueue.Request b = queue.enqueue("unlock", false);
        AvasPlaybackQueue.Request c = queue.enqueue("power_off", false);
        AvasPlaybackQueue.Request d = queue.enqueue("lock", false);

        assertFalse(active.cancelled.get());
        assertTrue(b.cancelled.get());
        assertTrue(c.cancelled.get());
        assertFalse(d.cancelled.get());
        assertEquals(c.id, d.supersededAutomatic.requestId);
        queue.finish(active);
        assertSame(d, queue.take());

        AvasPlaybackQueue.Request e = queue.enqueue("power_on", false);
        AvasPlaybackQueue.Request f = queue.enqueue("unlock", false);
        assertTrue(e.cancelled.get());
        assertEquals(e.id, f.supersededAutomatic.requestId);
        queue.finish(d);
        assertSame(f, queue.take());
    }

    @Test public void automaticReplacementAppendsAfterManualWithoutMovingIt() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request active = queue.enqueue("power_on", false);
        assertSame(active, queue.take());
        AvasPlaybackQueue.Request oldAutomatic = queue.enqueue("unlock", false);
        AvasPlaybackQueue.Request manual = queue.enqueue("lock", true);
        AvasPlaybackQueue.Request latestAutomatic = queue.enqueue("power_off", false);

        assertTrue(oldAutomatic.cancelled.get());
        assertEquals("idle", queue.state("unlock"));
        assertEquals("manual_queued", queue.state("lock"));
        assertEquals("automatic_queued", queue.state("power_off"));
        queue.finish(active);
        assertSame(manual, queue.take());
        queue.finish(manual);
        assertSame(latestAutomatic, queue.take());
    }

    @Test public void manualAtIdleHeadDoesNotProtectOlderAutomatic() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request manual = queue.enqueue("lock", true);
        AvasPlaybackQueue.Request oldAutomatic = queue.enqueue("power_on", false);
        AvasPlaybackQueue.Request latestAutomatic = queue.enqueue("unlock", false);

        assertTrue(oldAutomatic.cancelled.get());
        assertSame(manual, queue.take());
        queue.finish(manual);
        assertSame(latestAutomatic, queue.take());
    }

    @Test public void disablingProfileClearsQueuedReplacementState() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request active = queue.enqueue("power_on", false);
        assertSame(active, queue.take());
        queue.enqueue("unlock", false);

        queue.retainAutomaticProfiles(Collections.singleton("power_on"));

        assertEquals("idle", queue.state("unlock"));
        assertEquals(0, queue.pendingCount());
        assertFalse(active.cancelled.get());
    }

    @Test public void stopOnlyTargetsOwnManualIncludingQueued() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request automatic = queue.enqueue("lock", false);
        queue.take();
        assertNotNull(queue.enqueue("lock", true));
        assertNull(queue.enqueue("lock", true));
        assertEquals("manual_queued", queue.state("lock"));
        queue.stopManual("unlock");
        assertEquals(1, queue.pendingCount());
        queue.stopManual("lock");
        assertFalse(automatic.cancelled.get());
        assertEquals("automatic_playing", queue.state("lock"));
        assertEquals(0, queue.pendingCount());
    }

    @Test public void activeManualCancellationLeavesNextAutomatic() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request manual = queue.enqueue("unlock", true);
        queue.take();
        AvasPlaybackQueue.Request automatic = queue.enqueue("unlock", false);
        queue.stopManual("unlock");
        assertTrue(manual.cancelled.get());
        queue.finish(manual);
        assertSame(automatic, queue.take());
    }

    @Test public void disabledAutomaticDoesNotDisableExplicitAudition() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request automatic = queue.enqueue("lock", false);
        queue.take();
        AvasPlaybackQueue.Request manual = queue.enqueue("unlock", true);
        queue.retainAutomaticProfiles(Collections.emptySet());
        assertTrue(automatic.cancelled.get());
        queue.finish(automatic);
        assertSame(manual, queue.take());
    }

    @Test public void closeDropsBacklogAndCancelsCurrent() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request current = queue.enqueue("lock", false);
        queue.take();
        queue.enqueue("unlock", false);
        queue.close();
        assertTrue(current.cancelled.get());
        assertNull(queue.take());
        assertNull(queue.enqueue("power_on", false));
    }

    @Test public void queuedManualRemainsCancellableWhileSameProfilePlaysAutomatically() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        queue.enqueue("lock", false);
        queue.take();
        queue.enqueue("lock", true);
        assertEquals("manual_queued", queue.state("lock"));
    }

    @Test public void newerAuditionReplacesActiveAudition() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request first = queue.enqueueAudition("lock", "asset1", "session1");
        assertSame(first, queue.take());
        AvasPlaybackQueue.Request second = queue.enqueueAudition("unlock", "asset2", "session2");
        assertTrue(first.cancelled.get());
        assertEquals("idle", queue.auditionState("session1"));
        assertEquals("queued", queue.auditionState("session2"));
        queue.finish(first);
        assertSame(second, queue.take());
        assertEquals("playing", queue.auditionState("session2"));
    }

    @Test public void auditionNeverInterruptsOrQueuesBehindExterior() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request exterior = queue.enqueueExterior("lock", false);
        assertSame(exterior, queue.take());
        assertNull(queue.enqueueAudition("unlock", "asset", "session"));
        assertFalse(exterior.cancelled.get());
        assertEquals(0, queue.pendingCount());
        exterior.cancelled.set(true); // Teardown is still using the single player.
        assertNull(queue.enqueueAudition("unlock", "asset", "later"));
    }

    @Test public void acceptedAutomaticCancelsOnlyAuditionThenWaitsForCleanup() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request audition = queue.enqueueAudition("lock", "asset", "session");
        assertSame(audition, queue.take());
        AvasPlaybackQueue.Request automatic = queue.enqueueExterior("power_on", false);
        assertTrue(audition.cancelled.get());
        assertEquals(1, queue.pendingCount());
        queue.finish(audition);
        assertSame(automatic, queue.take());
    }

    @Test public void staleAuditionStopDoesNotCancelReplacementOrExterior() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request first = queue.enqueueAudition("lock", "asset1", "session1");
        assertSame(first, queue.take());
        AvasPlaybackQueue.Request replacement = queue.enqueueAudition(
                "lock", "asset2", "session2");
        queue.stopAudition("session1");
        queue.finish(first);
        assertSame(replacement, queue.take());
        assertFalse(replacement.cancelled.get());
        queue.finish(replacement);
        AvasPlaybackQueue.Request exterior = queue.enqueueExterior("lock", true);
        queue.stopAudition("session2");
        assertFalse(exterior.cancelled.get());
    }

    @Test public void deletedAssetCancelsOnlyMatchingAudition() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request audition = queue.enqueueAudition("lock", "gone", "session");
        assertSame(audition, queue.take());
        queue.removeAuditionsForAssets(Collections.singleton("gone"));
        assertTrue(audition.cancelled.get());
    }
}
