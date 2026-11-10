package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReverseVisibilityFenceTest {
    @Test public void lateShowCannotUndoHideAndAnotherSurfaceGenerationRemainsIndependent() {
        ReverseVisibilityFence tablet = new ReverseVisibilityFence(), cluster = new ReverseVisibilityFence();
        int[] generations = {2, 3, 4};
        assertTrue(tablet.permits(7, generations));
        tablet.hide(7, generations); cluster.hide(7, new int[]{5, 6, 7});
        generations[0] = 100;
        assertFalse(tablet.permits(7, new int[]{2, 3, 4}));
        assertFalse(cluster.permits(7, new int[]{5, 6, 7}));
        assertTrue(tablet.permits(8, new int[]{2, 3, 4}));
        assertTrue(tablet.permits(7, new int[]{8, 9, 10}));
        tablet.newSession();
        assertTrue(tablet.permits(7, new int[]{2, 3, 4}));
        assertFalse(cluster.permits(7, new int[]{5, 6, 7}));
    }
}
