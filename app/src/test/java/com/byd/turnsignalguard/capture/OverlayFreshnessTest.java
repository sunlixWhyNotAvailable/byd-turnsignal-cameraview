package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OverlayFreshnessTest {
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
