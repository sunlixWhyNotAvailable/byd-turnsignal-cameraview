package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClusterFullscreenControllerTest {
    private static final int TABLET = CameraDisplayTarget.TABLET;
    private static final int CLUSTER = CameraDisplayTarget.CLUSTER;

    @Test
    public void disabledGroupsDoNotActivateClusterPreparation() {
        assertFalse(eligible(false, CLUSTER, CLUSTER,
                false, CLUSTER, CLUSTER, false, CLUSTER));
        assertFalse(eligible(true, TABLET, TABLET,
                false, CLUSTER, CLUSTER, false, TABLET));
        assertFalse(eligible(false, CLUSTER, CLUSTER,
                false, TABLET, TABLET, true, TABLET));
        assertFalse(eligible(true, TABLET, TABLET,
                false, TABLET, TABLET, false, CLUSTER));
    }

    @Test
    public void eitherFrontCameraCanBeTheOnlyClusterTarget() {
        assertTrue(eligible(false, TABLET, TABLET,
                true, CLUSTER, TABLET, false, TABLET));
        assertTrue(eligible(false, TABLET, TABLET,
                true, TABLET, CLUSTER, false, TABLET));
    }

    @Test
    public void mirrorCanBeTheOnlyClusterTarget() {
        assertTrue(eligible(false, TABLET, TABLET,
                false, TABLET, TABLET, true, CLUSTER));
        assertFalse(eligible(false, TABLET, TABLET,
                false, TABLET, TABLET, true, TABLET));
    }

    @Test
    public void rearBlindEligibilityIsUnchanged() {
        assertTrue(eligible(true, CLUSTER, TABLET,
                false, TABLET, TABLET, false, TABLET));
        assertTrue(eligible(true, TABLET, CLUSTER,
                false, TABLET, TABLET, false, TABLET));
        assertFalse(eligible(true, TABLET, TABLET,
                false, TABLET, TABLET, false, TABLET));
    }

    @Test
    public void sessionAndInteractiveGatesRemainOneShot() {
        assertTrue(ClusterFullscreenController.shouldAttempt(true, true, 4, 3));
        assertFalse(ClusterFullscreenController.shouldAttempt(true, true, 4, 4));
        assertFalse(ClusterFullscreenController.shouldAttempt(true, false, 4, 3));
        assertFalse(ClusterFullscreenController.shouldAttempt(true, true, 0, 3));
    }

    private static boolean eligible(
            boolean rearEnabled, int rearLeft, int rearRight,
            boolean frontEnabled, int frontLeft, int frontRight,
            boolean mirrorEnabled, int mirrorTarget) {
        return ClusterFullscreenController.hasEnabledClusterTarget(
                rearEnabled, rearLeft, rearRight,
                frontEnabled, frontLeft, frontRight,
                mirrorEnabled, mirrorTarget);
    }
}
