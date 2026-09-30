package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public final class AvasAudioPlayerSharedRouteTest {
    @Test public void routeCleanupWaitsUntilLastMicOrEventLeaseEnds() throws Exception {
        AtomicInteger routeRestores = new AtomicInteger();

        assertFalse(AvasAudioPlayer.releaseExteriorSessionIfLast(1,
                () -> routeRestores.incrementAndGet())); // One event/mic source remains.
        assertEquals(0, routeRestores.get());

        assertTrue(AvasAudioPlayer.releaseExteriorSessionIfLast(0,
                () -> routeRestores.incrementAndGet())); // Last source releases route and focus.
        assertEquals(1, routeRestores.get());
    }
}
