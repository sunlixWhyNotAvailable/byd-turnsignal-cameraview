package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReversePreviewFrameBarrierTest {
    @Test public void baseOnlyNeedsFreshMatchingFrameAndCannotRevealAfterClose() {
        ReverseCameraCompositionView.FrameBarrier barrier = new ReverseCameraCompositionView.FrameBarrier();
        int[] empty = new int[0];
        barrier.arm(7,12,empty,empty,0,true);
        assertFalse(barrier.readyPending(7,12,empty));
        assertFalse(barrier.markBaseUnavailable(7));
        barrier.frame(7,0,12); // Existing guard rejects the first potentially queued frame.
        assertFalse(barrier.readyPending(7,12,empty));
        barrier.frame(7,0,12);
        assertTrue(barrier.recordReadyEvent(7,12,empty));
        assertTrue(barrier.reveal(7,12,empty));
        barrier.clear();
        barrier.frame(7,0,12);
        assertFalse(barrier.readyPending(7,12,empty));
        barrier.arm(8,13,empty,empty,0,true);
        barrier.frame(7,0,12); barrier.frame(8,0,12);
        assertFalse(barrier.readyPending(8,13,empty));
        barrier.frame(8,0,13); barrier.frame(8,0,13);
        assertTrue(barrier.readyPending(8,13,empty));
    }

    @Test public void mixedOutputsRemainReadyWhenOptionalBaseIsUnavailable() {
        ReverseCameraCompositionView.FrameBarrier barrier = new ReverseCameraCompositionView.FrameBarrier();
        int[] sources={2,3}, generations={14,15};
        barrier.arm(9,16,sources,generations,2,false);
        assertTrue(barrier.markBaseUnavailable(9));
        barrier.frame(9,2,14); barrier.frame(9,2,14);
        assertFalse(barrier.readyPending(9,16,generations));
        barrier.frame(9,3,15); barrier.frame(9,3,15);
        assertTrue(barrier.recordReadyEvent(9,16,generations));
        assertTrue(barrier.reveal(9,16,generations));
    }
}
