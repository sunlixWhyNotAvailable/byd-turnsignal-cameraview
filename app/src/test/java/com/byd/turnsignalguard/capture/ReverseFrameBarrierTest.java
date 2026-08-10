package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public final class ReverseFrameBarrierTest {
    private static final int REQUEST = 41;
    private static final int BASE = 7;
    private static final int[] DIRECT = {11, 12, 13};

    @Test
    public void partialFramesCannotRevealAndReadyIsExactOnce() {
        ReverseCameraCompositionView.FrameBarrier barrier = armed();

        for (int source = 0; source < 4; source++) {
            assertEquals(source == 0
                            ? ReverseCameraCompositionView.FrameBarrier.FrameResult.BLOCKED_GUARD
                            : ReverseCameraCompositionView.FrameBarrier.FrameResult.IGNORED,
                    barrier.frame(REQUEST, source, generation(source)));
            assertFalse(barrier.reveal(REQUEST, BASE, DIRECT));
        }
        for (int source = 0; source < 3; source++) {
            assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.ACCEPTED,
                    barrier.frame(REQUEST, source, generation(source)));
            assertFalse(barrier.recordReadyEvent(REQUEST, BASE, DIRECT));
            assertFalse(barrier.reveal(REQUEST, BASE, DIRECT));
        }
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.READY,
                barrier.frame(REQUEST, 3, generation(3)));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.IGNORED,
                barrier.frame(REQUEST, 3, generation(3)));
    }

    @Test
    public void staleRequestAndGenerationNeverContribute() {
        ReverseCameraCompositionView.FrameBarrier barrier = armed();

        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.BLOCKED_STALE,
                barrier.frame(REQUEST - 1, 1, DIRECT[0]));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.IGNORED,
                barrier.frame(REQUEST, 2, DIRECT[1] + 1));
        for (int source = 0; source < 4; source++) {
            barrier.frame(REQUEST, source, generation(source));
            barrier.frame(REQUEST, source, generation(source));
        }
        assertTrue(barrier.readyPending(REQUEST, BASE, DIRECT));
        assertFalse(barrier.readyPending(REQUEST - 1, BASE, DIRECT));
        assertFalse(barrier.readyPending(REQUEST, BASE + 1, DIRECT));
    }

    @Test
    public void surfaceLossClearsReadinessUntilRearmed() {
        ReverseCameraCompositionView.FrameBarrier barrier = armedAndReady();
        assertTrue(barrier.recordReadyEvent(REQUEST, BASE, DIRECT));

        barrier.clear();

        assertFalse(barrier.revealed());
        assertFalse(barrier.readyEventRecorded(REQUEST, BASE, DIRECT));
        assertFalse(barrier.reveal(REQUEST, BASE, DIRECT));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.IGNORED,
                barrier.frame(REQUEST, 1, DIRECT[0]));
    }

    @Test
    public void readyEventMustPrecedeSingleReveal() {
        ReverseCameraCompositionView.FrameBarrier barrier = armedAndReady();

        assertFalse(barrier.reveal(REQUEST, BASE, DIRECT));
        assertTrue(barrier.recordReadyEvent(REQUEST, BASE, DIRECT));
        assertTrue(barrier.readyEventRecorded(REQUEST, BASE, DIRECT));
        assertTrue(barrier.reveal(REQUEST, BASE, DIRECT));
        assertTrue(barrier.revealed());
        assertFalse(barrier.recordReadyEvent(REQUEST, BASE, DIRECT));
        assertFalse(barrier.reveal(REQUEST, BASE, DIRECT));
    }

    private static ReverseCameraCompositionView.FrameBarrier armed() {
        ReverseCameraCompositionView.FrameBarrier barrier =
                new ReverseCameraCompositionView.FrameBarrier();
        barrier.arm(REQUEST, BASE, DIRECT);
        return barrier;
    }

    private static ReverseCameraCompositionView.FrameBarrier armedAndReady() {
        ReverseCameraCompositionView.FrameBarrier barrier = armed();
        for (int source = 0; source < 4; source++) {
            barrier.frame(REQUEST, source, generation(source));
            barrier.frame(REQUEST, source, generation(source));
        }
        return barrier;
    }

    private static int generation(int source) {
        return source == 0 ? BASE : DIRECT[source - 1];
    }
}
