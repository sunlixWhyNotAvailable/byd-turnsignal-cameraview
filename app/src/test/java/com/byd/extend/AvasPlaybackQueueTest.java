package com.byd.extend;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class AvasPlaybackQueueTest {
    @Test public void automaticEventsAreFifoAndNotPreempted() throws Exception {
        AvasPlaybackQueue queue = new AvasPlaybackQueue();
        AvasPlaybackQueue.Request off = queue.enqueue("power_off", false);
        assertSame(off, queue.take());
        AvasPlaybackQueue.Request lock = queue.enqueue("lock", false);
        assertFalse(off.cancelled.get());
        queue.finish(off);
        assertSame(lock, queue.take());
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
}
