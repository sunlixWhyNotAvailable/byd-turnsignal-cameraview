package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteCameraFrameProgressTest {
    @Test public void repeatedImagesAndRenderingDoNotKeepAnInputFresh() {
        RemoteCameraFrameProgress progress = new RemoteCameraFrameProgress();
        Object source = new Object();
        assertFalse(progress.fresh(0, 10));
        progress.offered(1);
        progress.received(source, 1, 100, 2);
        progress.composed();
        assertTrue(progress.fresh(10, 10));
        progress.offered(11);
        progress.received(source, 11, 100, 11);
        progress.composed();
        assertFalse(progress.fresh(11, 10));
        progress.offered(12); // A queued offer has not reached the composer yet.
        assertFalse(progress.fresh(12, 10));
        progress.received(source, 12, 101, 13);
        assertTrue(progress.fresh(13, 10));
    }

    @Test public void delayedDeliveryRemainsStaleAndSourcesStayIndependent() {
        RemoteCameraFrameProgress exterior = new RemoteCameraFrameProgress();
        RemoteCameraFrameProgress interior = new RemoteCameraFrameProgress();
        Object source = new Object();
        exterior.received(source, 1, 100, 15);
        interior.received(source, 15, 200, 15);
        assertFalse(exterior.fresh(15, 10));
        assertTrue(interior.fresh(15, 10));
        exterior.received(new Object(), 16, 100, 16); // New source may restart its timestamp domain.
        assertTrue(exterior.fresh(16, 10));
        interior.received(source, 20, 0, 20); // OEM without timestamps still supplies new snapshots.
        assertTrue(interior.fresh(20, 10));
    }
}
