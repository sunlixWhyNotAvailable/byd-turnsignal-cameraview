package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CameraSourceDemandTest {
    @Test
    public void reverseDemandFollowsEnabledPanesAndIntegratedCentralFront() {
        int centerAndLeft = ReverseCameraLayout.visibilityBitForPane(
                ReverseCameraLayout.REAR_CAMERA_INDEX)
                | ReverseCameraLayout.visibilityBitForPane(
                        ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);

        assertEquals(CameraSourceDemand.bit(1) | CameraSourceDemand.bit(2),
                CameraSourceDemand.reverse(centerAndLeft, true, false, false));
        assertEquals(CameraSourceDemand.bit(1) | CameraSourceDemand.bit(2)
                        | CameraSourceDemand.bit(4),
                CameraSourceDemand.reverse(centerAndLeft, true, false, true));
        assertEquals(CameraSourceDemand.bit(2),
                CameraSourceDemand.reverse(
                        ReverseCameraLayout.visibilityBitForPane(
                                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX),
                        true, true, true));
        assertEquals(0, CameraSourceDemand.reverse(centerAndLeft, false, true, true)
                & CameraSourceDemand.bit(4));
    }

    @Test
    public void blindAndMirrorMasksPreserveSharedIntegratedSources() {
        assertEquals(CameraSourceDemand.bit(2) | CameraSourceDemand.bit(3),
                CameraSourceDemand.blindStandby(false, true));
        assertEquals(0, CameraSourceDemand.blindStandby(false, false));
        assertEquals(CameraSourceDemand.bit(1) | CameraSourceDemand.bit(4),
                CameraSourceDemand.mirror(true, true));
        assertEquals(0, CameraSourceDemand.mirror(false, true));
    }

    @Test
    public void nonDirectFanoutKeepsSupportedSourceZeroButDirectInputDoesNot() {
        assertEquals(CameraSourceDemand.bit(0) | CameraSourceDemand.bit(2),
                CameraSourceDemand.fromIndexes(new int[]{0, 2}, false));
        assertEquals(CameraSourceDemand.bit(2),
                CameraSourceDemand.fromIndexes(new int[]{0, 2}, true));
    }

    @Test
    public void reopeningInvalidatesPriorReverseExpiryAndDisableClearsLease() {
        CameraSourceDemand.ReverseWarmLease lease =
                new CameraSourceDemand.ReverseWarmLease();
        long first = lease.arm(11, 4,
                CameraSourceDemand.bit(1) | CameraSourceDemand.bit(4));

        long reopened = lease.arm(12, 4, CameraSourceDemand.bit(2));

        assertFalse(lease.expire(first, 11, 4));
        assertEquals(CameraSourceDemand.bit(2), lease.sourceMask());
        assertFalse(lease.expire(reopened, 12, 3));
        assertTrue(lease.expire(reopened, 12, 4));
        assertEquals(0, lease.sourceMask());

        long disabled = lease.arm(13, 5, CameraSourceDemand.bit(3));
        lease.clear();
        assertFalse(lease.expire(disabled, 13, 5));
        assertEquals(0, lease.requestId());
    }
}
