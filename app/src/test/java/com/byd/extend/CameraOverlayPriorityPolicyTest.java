package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CameraOverlayPriorityPolicyTest {
    @Test public void reversePriorityBlocksBlindSpotAndTracksBothEdges() {
        boolean blocked = BlindSpotOverlayController.isHardBlocked(
                false, false, true);
        assertTrue(blocked);
        assertEquals(1, BlindSpotOverlayController.hardBlockEdge(false, blocked));
        assertEquals(-1, BlindSpotOverlayController.hardBlockEdge(blocked, false));
        assertFalse(BlindSpotOverlayController.isHardBlocked(false, false, false));
    }

    @Test public void parkingCoexistenceOnlyOverridesReversePriority() {
        assertTrue(ParkingCameraController.isHardBlocked(false, false, true, false));
        assertFalse(ParkingCameraController.isHardBlocked(false, false, true, true));
        assertTrue(ParkingCameraController.isHardBlocked(true, false, true, true));
    }
}
