package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClusterFullscreenControllerTest {
    @Test public void onlyEnabledOrConfiguredClusterModesQualify() {
        ReverseCameraLayout reverseLayout = ReverseCameraLayout.defaults();
        assertFalse(hasTarget(false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, false, reverseLayout));
        assertTrue(hasTarget(true, CameraDisplayTarget.TABLET, CameraDisplayTarget.CLUSTER,
                false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, false, reverseLayout));
        assertTrue(hasTarget(true, CameraDisplayTarget.CLUSTER, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, false, reverseLayout));
        assertTrue(hasTarget(false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                true, CameraDisplayTarget.TABLET, CameraDisplayTarget.CLUSTER,
                false, CameraDisplayTarget.TABLET, false, reverseLayout));
        assertTrue(hasTarget(false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                true, CameraDisplayTarget.CLUSTER, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, false, reverseLayout));
        assertTrue(hasTarget(false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                true, CameraDisplayTarget.CLUSTER, false, reverseLayout));
        reverseLayout = ReverseCameraLayout.withTarget(
                reverseLayout, ReverseCameraLayout.REAR_CAMERA_INDEX,
                CameraDisplayTarget.CLUSTER);
        assertFalse(hasTarget(false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, false, reverseLayout));
        assertTrue(hasTarget(false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, true, reverseLayout));
        assertFalse(hasTarget(false, CameraDisplayTarget.CLUSTER, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, false, ReverseCameraLayout.defaults()));
        assertFalse(hasTarget(false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.CLUSTER, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, false, ReverseCameraLayout.defaults()));
        assertFalse(hasTarget(false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.TABLET, CameraDisplayTarget.TABLET,
                false, CameraDisplayTarget.CLUSTER, false, ReverseCameraLayout.defaults()));
    }

    private static boolean hasTarget(boolean rearEnabled, int rearLeft, int rearRight,
            boolean frontEnabled, int frontLeft, int frontRight,
            boolean mirrorEnabled, int mirrorTarget,
            boolean reverseEnabled, ReverseCameraLayout reverseLayout) {
        return ClusterFullscreenController.clusterTargetMask(
                rearEnabled, rearLeft, rearRight,
                frontEnabled, frontLeft, frontRight,
                mirrorEnabled, mirrorTarget,
                reverseEnabled,
                reverseLayout.containsTarget(CameraDisplayTarget.CLUSTER)) != 0;
    }
}
