package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Display;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public void sessionAndDestinationGatesRemainOneShot() {
        assertTrue(ClusterFullscreenController.shouldAttempt(true, true, 4, 3));
        assertFalse(ClusterFullscreenController.shouldAttempt(true, true, 4, 4));
        assertFalse(ClusterFullscreenController.shouldAttempt(true, false, 4, 3));
        assertFalse(ClusterFullscreenController.shouldAttempt(true, true, -1, 3));
    }

    @Test
    public void retainedSession96DoesNotSuppressANewBoot() {
        assertTrue(ClusterFullscreenController.shouldAttempt(
                true, true, 96, 96, 1064, 1063, 2, 2));
        assertFalse(ClusterFullscreenController.shouldAttempt(
                true, true, 96, 96, 1064, 1064, 2, 2));
        assertTrue(ClusterFullscreenController.shouldAttempt(
                true, true, 97, 96, 1064, 1064, 2, 2));
        // Existing installs have no boot/display markers, so stale session alone cannot veto.
        assertTrue(ClusterFullscreenController.shouldAttempt(
                true, true, 96, 96, 1064, Long.MIN_VALUE, 2, -1));
    }

    @Test
    public void sleepRemovalAndReplacementRearmOnlyTheClusterDestination() {
        assertFalse(ClusterFullscreenController.displayUsable(false, Display.STATE_UNKNOWN));
        assertFalse(ClusterFullscreenController.displayUsable(true, Display.STATE_OFF));
        assertTrue(ClusterFullscreenController.displayUsable(true, Display.STATE_ON));
        assertTrue(ClusterFullscreenController.displayUsable(true, Display.STATE_UNKNOWN));
        assertFalse(ClusterFullscreenController.shouldAttempt(
                true, false, 96, 0, 1064, 1064, 2, 2));
        // OFF clears the attempted-session marker; ON can retry with the same helper session.
        assertTrue(ClusterFullscreenController.shouldAttempt(
                true, true, 96, -1, 1064, 1064, 2, 2));
        assertTrue(ClusterFullscreenController.shouldAttempt(
                true, true, 96, 96, 1064, 1064, 3, 2));
        assertFalse(ClusterFullscreenController.shouldAttempt(
                false, true, 96, 0, 1064, 1064, 2, 2));
        assertFalse(ClusterFullscreenController.shouldAttempt(
                true, true, -1, -1, 1064, 1063, 2, 2));
    }

    @Test
    public void firstNonInteractiveHelperSessionZeroIsValidButMissingSnapshotIsNot() {
        assertFalse(ClusterFullscreenController.shouldAttempt(
                true, true, -1, -1, 1064, Long.MIN_VALUE, 2, -1));
        assertTrue(ClusterFullscreenController.shouldAttempt(
                true, true, 0, -1, 1064, Long.MIN_VALUE, 2, -1));
        assertFalse(ClusterFullscreenController.shouldAttempt(
                true, true, 0, 0, 1064, 1064, 2, 2));
        assertTrue(ClusterFullscreenController.shouldAttempt(
                true, true, 0, 0, 1065, 1064, 2, 2));
        assertTrue(ClusterFullscreenController.shouldAttempt(
                true, true, 0, -1, 1064, 1064, 2, 2));
    }

    @Test
    public void hiddenMirrorDispatchUsesDisplayNotCachedInteractiveAndOwnsListener() throws Exception {
        Path path = Path.of("src/main/java/com/byd/extend/ClusterFullscreenController.java");
        if (!Files.exists(path)) path = Path.of("app").resolve(path);
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String eligibility = source.substring(source.indexOf("boolean eligible ="),
                source.indexOf("String gate ="));
        assertTrue(eligibility.contains("mirror.enabled, mirror.target"));
        assertFalse(eligibility.contains("manualHidden"));
        assertFalse(eligibility.contains("interactive"));
        assertTrue(eligibility.contains("shouldAttempt(eligible, displayReady"));
        assertTrue(source.contains("CameraDisplayTarget.resolve(context, CameraDisplayTarget.CLUSTER)"));
        assertTrue(source.contains("displays.registerDisplayListener(displayListener, handler)"));
        assertTrue(source.contains("displays.unregisterDisplayListener(displayListener)"));
        assertTrue(source.contains("if (!displayReady && (!displayObserved || displayWasReady))"));
        assertTrue(source.contains("remove(PREF_ATTEMPTED_SESSION)"));
        assertTrue(source.contains("\"cluster_fullscreen_gate\""));
        assertFalse(source.contains("putBoolean(RearviewMirrorSettings.PREF_MANUAL_HIDDEN"));
        assertFalse(source.contains("postDelayed"));
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
