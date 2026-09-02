package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OverlayFreshnessTest {
    @Test
    public void firstCurrentStampedFramePassesWithoutASecondUpdate() {
        assertFalse(ShellCameraOverlay.isFreshStampedFrame(9, 9, 1_000L, 999L));
        assertFalse(ShellCameraOverlay.isFreshStampedFrame(9, 9, 1_000L, 1_000L));
        assertFalse(ShellCameraOverlay.isFreshStampedFrame(9, 8, 1_000L, 1_001L));
        assertFalse(ShellCameraOverlay.isFreshStampedFrame(0, 0, 1_000L, 1_001L));
        assertFalse(ShellCameraOverlay.isFreshStampedFrame(9, 9, 0L, 1_001L));
        assertTrue(ShellCameraOverlay.isFreshStampedFrame(9, 9, 1_000L, 1_001L));
        // Parking has no timestamp contract and retains its existing two-update gate.
        assertFalse(ShellCameraOverlay.isFramePastStaleBuffer(1));
        assertTrue(ShellCameraOverlay.isFramePastStaleBuffer(2));
    }

    @Test
    public void stampedBufferTimeSurvivesDewarpAndDoesNotChangeTheWireProtocol() throws Exception {
        String hub = source("DirectCameraSourceHub.java");
        String renderer = source("CameraDewarpRenderer.java");
        String overlay = source("ShellCameraOverlay.java");
        String view = source("BlindSpotCameraView.java");
        assertTrue(hub.indexOf("acquiredFrameTimestampNanos = System.nanoTime()")
                < hub.indexOf("texture.updateTexImage()"));
        assertTrue(hub.contains("draw(target, acquiredFrameTimestampNanos)"));
        assertTrue(hub.contains("display, target.eglSurface, acquiredFrameTimestampNanos)"));
        assertTrue(renderer.contains("preserveTimestamp ? inputTimestamp : 0L"));
        assertTrue(renderer.indexOf("eglDisplay, surface, presentationTimestamp)")
                < renderer.indexOf("EGL14.eglSwapBuffers(eglDisplay, surface)"));
        assertTrue(overlay.contains(
                "setPreserveInputFrameTimestamp(!CameraOverlayProfile.isParking(cameraId))"));
        assertTrue(view.contains("texture == getSurfaceTexture() && callback != null"));
        assertTrue(view.contains("? inputGeneration.current() : inputGeneration.frame()"));
        assertEquals(27, CameraShellProtocol.VERSION);
    }

    private static String source(String name) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/byd/extend/" + name)), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    public void visibilityCompletionOrdersHidePauseAndRejectsStaleOrFailedCallbacks() {
        assertEquals(BlindSpotOverlayController.VISIBILITY_COMPLETION_PAUSE,
                BlindSpotOverlayController.visibilityCompletionAction(
                        4L, 4L, true, false, false, true));
        assertEquals(BlindSpotOverlayController.VISIBILITY_COMPLETION_STALE,
                BlindSpotOverlayController.visibilityCompletionAction(
                        5L, 4L, true, false, false, true));
        assertEquals(BlindSpotOverlayController.VISIBILITY_COMPLETION_FAILED,
                BlindSpotOverlayController.visibilityCompletionAction(
                        4L, 4L, false, false, false, true));
        assertEquals(BlindSpotOverlayController.VISIBILITY_COMPLETION_APPLY,
                BlindSpotOverlayController.visibilityCompletionAction(
                        4L, 4L, true, true, true, true));
    }

    @Test
    public void pausedRequestedOverlayMustResumeAndArmFreshFrames() {
        assertTrue(BlindSpotOverlayController.needsFreshFrameArm(
                true, false, false, false));
        assertFalse(BlindSpotOverlayController.needsFreshFrameArm(
                true, false, false, true));
        assertFalse(BlindSpotOverlayController.needsFreshFrameArm(
                true, true, true, false));
    }

    @Test
    public void rearmRejectsOldDeadlineAndDelayedFrame() {
        BlindSpotOverlayController.FrameFreshness freshness =
                new BlindSpotOverlayController.FrameFreshness();
        int oldArm = freshness.arm();
        int currentArm = freshness.arm();

        assertFalse(freshness.shouldTimeout(oldArm));
        assertFalse(freshness.current(oldArm));
        assertFalse(freshness.accept(oldArm));
        assertFalse(freshness.ready());

        assertTrue(freshness.current(currentArm));
        assertTrue(freshness.accept(currentArm));
        assertTrue(freshness.ready());
        assertFalse(freshness.accept(currentArm));
        assertFalse(freshness.shouldTimeout(currentArm));
    }

    @Test
    public void onlyCurrentUnfulfilledDeadlineFails() {
        BlindSpotOverlayController.FrameFreshness freshness =
                new BlindSpotOverlayController.FrameFreshness();
        int oldArm = freshness.arm();
        int currentArm = freshness.arm();

        assertFalse(freshness.shouldTimeout(oldArm));
        assertTrue(freshness.shouldTimeout(currentArm));

        freshness.invalidate();
        assertFalse(freshness.shouldTimeout(currentArm));
    }

    @Test
    public void armEpochRoundTripsThroughRuntimeWireAndEventFields() throws Exception {
        int cameraId = CameraProfile.REAR_LEFT;
        int requestId = 73;
        int generation = 9;
        BlindSpotOverlayController.FrameFreshness freshness =
                new BlindSpotOverlayController.FrameFreshness();
        OverlayFrameArm oldArm = OverlayFrameArm.create(
                cameraId, requestId, generation, freshness.arm());
        OverlayFrameArm currentArm = OverlayFrameArm.create(
                cameraId, requestId, generation, freshness.arm());

        assertArrayEquals(new int[]{cameraId, requestId, generation, currentArm.frameArmEpoch},
                currentArm.wireFields());
        OverlayFrameArm delayed = roundTrip(oldArm);
        OverlayFrameArm returned = roundTrip(currentArm);

        assertEquals(requestId, returned.requestId);
        assertEquals(generation, returned.surfaceGeneration);
        assertEquals(currentArm.frameArmEpoch, returned.frameArmEpoch);
        assertFalse(freshness.accept(delayed.frameArmEpoch));
        assertFalse(freshness.ready());
        assertTrue(freshness.accept(returned.frameArmEpoch));
        assertTrue(freshness.ready());
    }

    private static OverlayFrameArm roundTrip(OverlayFrameArm sent) throws Exception {
        int[] parcelFields = sent.wireFields();
        OverlayFrameArm shellDecoded = OverlayFrameArm.fromWireFields(
                parcelFields[0], parcelFields[1], parcelFields[2], parcelFields[3]);
        return OverlayFrameArm.fromEventFields(CameraShellMain.eventFieldMap(
                ShellCameraOverlay.tagCameraId(
                        shellDecoded.cameraId, shellDecoded.eventFields())));
    }
}
