package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

public final class ReverseFrameBarrierTest {
    @Test public void surfaceAcquisitionWaitsForPrepareButNotUnavailableCluster() {
        assertTrue(CameraShellMain.ShellBinder.shouldDeferReverseSurfaceReady(
                true, true, true, false)); // Reused Tablet ready before Cluster.prepare.
        assertTrue(CameraShellMain.ShellBinder.shouldDeferReverseSurfaceReady(
                false, true, true, false)); // Open Cluster still preparing its Surface.
        assertFalse(CameraShellMain.ShellBinder.shouldDeferReverseSurfaceReady(
                false, true, true, true));
        assertFalse(CameraShellMain.ShellBinder.shouldDeferReverseSurfaceReady(
                false, false, true, false)); // Unavailable Cluster cannot hold Tablet.
    }

    @Test public void preparedEmptyOutputIsDistinctFromUnavailableCluster() {
        assertTrue(ReverseCameraController.hasPreparedDisplayTarget(0, new int[]{0}));
        assertFalse(ReverseCameraController.hasPreparedDisplayTarget(1, new int[]{0}));
        assertTrue(ReverseCameraController.isValidOutputGroup(0, new int[0], new int[0]));
        assertTrue(ReverseCameraController.isValidOutputGroup(1, new int[]{1, 3, 4},
                new int[]{11, 13, 14}));
        assertFalse(ReverseCameraController.isValidOutputGroup(1, new int[]{1, 1},
                new int[]{11, 12}));
        org.junit.Assert.assertArrayEquals(new int[]{12},
                ReverseCameraController.generationsForDisplay(0,
                        new int[]{0, 1, 1}, new int[]{12, 11, 13}));
        org.junit.Assert.assertArrayEquals(new int[]{11, 13},
                ReverseCameraController.generationsForDisplay(1,
                        new int[]{0, 1, 1}, new int[]{12, 11, 13}));
    }

    private static final int REQUEST = 41;
    private static final int BASE = 7;
    private static final int[] DIRECT = {11, 12, 13};

    @Test public void splitDisplayWaitsOnlyForItsAssignedSources() {
        ReverseCameraCompositionView.FrameBarrier tablet =
                new ReverseCameraCompositionView.FrameBarrier();
        ReverseCameraCompositionView.FrameBarrier cluster =
                new ReverseCameraCompositionView.FrameBarrier();
        tablet.arm(REQUEST, 0, new int[]{2}, new int[]{12}, 1, false);
        cluster.arm(REQUEST, 0, new int[]{1, 3, 4}, new int[]{11, 13, 14}, 2, false);
        assertFalse(tablet.readyPending(REQUEST, 0, new int[]{12}));
        tablet.frame(REQUEST, 2, 12); // Discard the queued pre-arm frame.
        assertFalse(tablet.readyPending(REQUEST, 0, new int[]{12}));
        tablet.frame(REQUEST, 2, 12);
        assertTrue(tablet.recordReadyEvent(REQUEST, 0, new int[]{12}));
        assertTrue(tablet.reveal(REQUEST, 0, new int[]{12}));
        assertFalse(cluster.readyPending(REQUEST, 0, new int[]{11, 13, 14}));
        for (int source : new int[]{1, 3}) {
            cluster.frame(REQUEST, source, source + 10);
            cluster.frame(REQUEST, source, source + 10);
        }
        // The integrated central Front source is paused until selected, not a reveal prerequisite.
        assertTrue(cluster.recordReadyEvent(REQUEST, 0, new int[]{11, 13, 14}));
        assertTrue(cluster.reveal(REQUEST, 0, new int[]{11, 13, 14}));
    }

    @Test public void displayWithOnlyBackgroundAndWidgetNeedsNoCameraFrame() {
        ReverseCameraCompositionView.FrameBarrier barrier =
                new ReverseCameraCompositionView.FrameBarrier();
        barrier.arm(REQUEST, 0, new int[0], new int[0], 0, false);
        assertTrue(barrier.recordReadyEvent(REQUEST, 0, new int[0]));
        assertTrue(barrier.reveal(REQUEST, 0, new int[0]));
    }

    @Test public void previewPanoramaBaseIsTabletOnlyAndRequiresDirectOutput() {
        assertTrue(ReverseCameraCompositionView.shouldAttachPreviewBase(
                CameraDisplayTarget.TABLET, 1));
        assertFalse(ReverseCameraCompositionView.shouldAttachPreviewBase(
                CameraDisplayTarget.TABLET, 0));
        assertFalse(ReverseCameraCompositionView.shouldAttachPreviewBase(
                CameraDisplayTarget.CLUSTER, 3));
    }

    @Test public void previewOutputsAndPreRevealEligibilityStayOnTheirDisplay() {
        ReverseCameraLayout layout = ReverseCameraLayout.withTarget(
                ReverseCameraLayout.defaults(), ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                CameraDisplayTarget.CLUSTER);
        assertArrayEquals(new int[]{1, 3},
                ReverseCameraCompositionView.outputSourceIndexesForDisplay(
                        layout, CameraDisplayTarget.TABLET,
                        ReverseCameraLayout.VISIBILITY_ALL, false, false));
        assertArrayEquals(new int[]{2},
                ReverseCameraCompositionView.outputSourceIndexesForDisplay(
                        layout, CameraDisplayTarget.CLUSTER,
                        ReverseCameraLayout.VISIBILITY_ALL, false, false));
        assertFalse(ReverseCameraCompositionView.isPaneEligibleOnDisplay(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                CameraDisplayTarget.TABLET, ReverseCameraLayout.VISIBILITY_ALL));
        assertTrue(ReverseCameraCompositionView.isPaneEligibleOnDisplay(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                CameraDisplayTarget.CLUSTER, ReverseCameraLayout.VISIBILITY_ALL));
        assertFalse(ReverseCameraCompositionView.shouldActivatePane(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                CameraDisplayTarget.TABLET, ReverseCameraLayout.VISIBILITY_ALL, true, false));
        assertTrue(ReverseCameraCompositionView.shouldActivatePane(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                CameraDisplayTarget.CLUSTER, ReverseCameraLayout.VISIBILITY_ALL, true, false));
    }

    @Test public void optionalTabletPanoramaFrameDoesNotDelayDirectReveal() {
        ReverseCameraCompositionView.FrameBarrier barrier =
                new ReverseCameraCompositionView.FrameBarrier();
        barrier.arm(REQUEST, BASE, new int[]{2}, new int[]{12}, 1, false);
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.BLOCKED_GUARD,
                barrier.frame(REQUEST, 2, 12));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.READY,
                barrier.frame(REQUEST, 2, 12));
        assertTrue(barrier.recordReadyEvent(REQUEST, BASE, new int[]{12}));
        assertTrue(barrier.reveal(REQUEST, BASE, new int[]{12}));
    }

    @Test public void clusterPreviewRetiredInputCannotSatisfyFreshFrameBarrier() {
        BlindSpotCameraView.InputGeneration input = new BlindSpotCameraView.InputGeneration();
        int retired = input.next();
        input.frame();
        int current = input.next();
        ReverseCameraCompositionView.FrameBarrier barrier =
                new ReverseCameraCompositionView.FrameBarrier();
        barrier.arm(REQUEST, 0, new int[]{2}, new int[]{current}, 1, false);
        int queuedFrame = input.frame();
        assertEquals(retired, queuedFrame);
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.BLOCKED_STALE,
                barrier.frame(REQUEST, 2, queuedFrame));
        assertFalse(barrier.readyPending(REQUEST, 0, new int[]{current}));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.BLOCKED_GUARD,
                barrier.frame(REQUEST, 2, input.frame()));
        assertFalse(barrier.readyPending(REQUEST, 0, new int[]{current}));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.READY,
                barrier.frame(REQUEST, 2, input.frame()));
        assertTrue(barrier.recordReadyEvent(REQUEST, 0, new int[]{current}));
    }

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

    @Test
    public void optionalCentralFrontSourceUsesFourDirectGenerations() {
        int[] direct = {21, 22, 23, 24};
        ReverseCameraCompositionView.FrameBarrier barrier =
                new ReverseCameraCompositionView.FrameBarrier();
        barrier.arm(REQUEST, BASE, direct);

        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.BLOCKED_GUARD,
                barrier.frame(REQUEST, ReverseCameraCompositionView.FrameBarrier.SOURCE_BASE,
                        BASE));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.ACCEPTED,
                barrier.frame(REQUEST, ReverseCameraCompositionView.FrameBarrier.SOURCE_BASE,
                        BASE));
        for (int source = 1; source <= direct.length; source++) {
            assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.IGNORED,
                    barrier.frame(REQUEST, source, direct[source - 1]));
        }
        for (int source = 1; source <= direct.length; source++) {
            if (source < direct.length) {
                assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.ACCEPTED,
                        barrier.frame(REQUEST, source, direct[source - 1]));
            } else {
                assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.READY,
                        barrier.frame(REQUEST, source, direct[source - 1]));
            }
        }
        assertTrue(barrier.recordReadyEvent(REQUEST, BASE, direct));
        assertTrue(barrier.reveal(REQUEST, BASE, direct));
        assertFalse(barrier.reveal(REQUEST, BASE, direct));
    }

    @Test
    public void optionalCentralFrontMayArriveAfterRequiredThreeSources() {
        int[] direct = {31, 32, 33, 34};
        ReverseCameraCompositionView.FrameBarrier barrier =
                new ReverseCameraCompositionView.FrameBarrier();
        // Keep the four-source identity, but require only the base plus the
        // original three panes before revealing the composition.
        barrier.arm(REQUEST, BASE, direct, 3);

        for (int source = 0; source <= 3; source++) {
            assertEquals(source == 0
                            ? ReverseCameraCompositionView.FrameBarrier.FrameResult.BLOCKED_GUARD
                            : ReverseCameraCompositionView.FrameBarrier.FrameResult.IGNORED,
                    barrier.frame(REQUEST, source, generation(source, direct)));
        }
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.ACCEPTED,
                barrier.frame(REQUEST, 0, generation(0, direct)));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.ACCEPTED,
                barrier.frame(REQUEST, 1, generation(1, direct)));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.ACCEPTED,
                barrier.frame(REQUEST, 2, generation(2, direct)));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.READY,
                barrier.frame(REQUEST, 3, generation(3, direct)));
        assertTrue(barrier.readyPending(REQUEST, BASE, direct));

        // The optional source can signal after readiness; it is ignored by
        // the one-shot gate and does not invalidate the four-source identity.
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.IGNORED,
                barrier.frame(REQUEST, 4, generation(4, direct)));
        assertTrue(barrier.recordReadyEvent(REQUEST, BASE, direct));
        assertTrue(barrier.reveal(REQUEST, BASE, direct));
    }

    @Test
    public void previewCanRevealDirectSourcesWithoutBaseAndAcceptLateBase() {
        ReverseCameraCompositionView.FrameBarrier barrier =
                new ReverseCameraCompositionView.FrameBarrier();
        barrier.arm(REQUEST, BASE, DIRECT, DIRECT.length, false);

        for (int source = 1; source <= DIRECT.length; source++) {
            assertEquals(source == 1
                            ? ReverseCameraCompositionView.FrameBarrier.FrameResult.BLOCKED_GUARD
                            : ReverseCameraCompositionView.FrameBarrier.FrameResult.IGNORED,
                    barrier.frame(REQUEST, source, DIRECT[source - 1]));
            assertEquals(source == DIRECT.length
                            ? ReverseCameraCompositionView.FrameBarrier.FrameResult.READY
                            : ReverseCameraCompositionView.FrameBarrier.FrameResult.ACCEPTED,
                    barrier.frame(REQUEST, source, DIRECT[source - 1]));
        }
        assertTrue(barrier.readyPending(REQUEST, BASE, DIRECT));
        assertTrue(barrier.recordReadyEvent(REQUEST, BASE, DIRECT));
        assertTrue(barrier.reveal(REQUEST, BASE, DIRECT));

        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.IGNORED,
                barrier.frame(REQUEST, ReverseCameraCompositionView.FrameBarrier.SOURCE_BASE,
                        BASE));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.ACCEPTED,
                barrier.frame(REQUEST, ReverseCameraCompositionView.FrameBarrier.SOURCE_BASE,
                        BASE));
        assertTrue(barrier.revealed());
        assertTrue(barrier.markBaseUnavailable(REQUEST));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.IGNORED,
                barrier.frame(REQUEST, ReverseCameraCompositionView.FrameBarrier.SOURCE_BASE,
                        BASE));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.IGNORED,
                barrier.frame(REQUEST, ReverseCameraCompositionView.FrameBarrier.SOURCE_BASE,
                        BASE));
        barrier.arm(REQUEST + 1, BASE, DIRECT, DIRECT.length, false);
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.BLOCKED_GUARD,
                barrier.frame(REQUEST + 1, ReverseCameraCompositionView.FrameBarrier.SOURCE_BASE,
                        BASE));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.ACCEPTED,
                barrier.frame(REQUEST + 1, ReverseCameraCompositionView.FrameBarrier.SOURCE_BASE,
                        BASE));
    }

    @Test
    public void previewAcceptsBaseBeforeDirectSourcesWithoutMakingItRequired() {
        ReverseCameraCompositionView.FrameBarrier barrier =
                new ReverseCameraCompositionView.FrameBarrier();
        barrier.arm(REQUEST, BASE, DIRECT, DIRECT.length, false);

        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.BLOCKED_GUARD,
                barrier.frame(REQUEST, ReverseCameraCompositionView.FrameBarrier.SOURCE_BASE,
                        BASE));
        assertEquals(ReverseCameraCompositionView.FrameBarrier.FrameResult.ACCEPTED,
                barrier.frame(REQUEST, ReverseCameraCompositionView.FrameBarrier.SOURCE_BASE,
                        BASE));
        for (int source = 1; source <= DIRECT.length; source++) {
            barrier.frame(REQUEST, source, DIRECT[source - 1]);
            assertEquals(source == DIRECT.length
                            ? ReverseCameraCompositionView.FrameBarrier.FrameResult.READY
                            : ReverseCameraCompositionView.FrameBarrier.FrameResult.ACCEPTED,
                    barrier.frame(REQUEST, source, DIRECT[source - 1]));
        }
        assertTrue(barrier.readyPending(REQUEST, BASE, DIRECT));
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

    private static int generation(int source, int[] direct) {
        return source == 0 ? BASE : direct[source - 1];
    }
}
