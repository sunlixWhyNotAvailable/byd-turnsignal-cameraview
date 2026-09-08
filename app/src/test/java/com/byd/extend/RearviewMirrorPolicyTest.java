package com.byd.extend;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RearviewMirrorPolicyTest {
    @Test public void allVisibilityGatesAreRequired() {
        for (int mask = 0; mask < 256; mask++) {
            boolean enabled = (mask & 1) != 0;
            boolean manuallyHidden = (mask & 2) != 0;
            boolean foreground = (mask & 4) != 0;
            boolean oemKnown = (mask & 8) != 0;
            boolean oemVisible = (mask & 16) != 0;
            boolean allowed = (mask & 32) != 0;
            boolean permission = (mask & 64) != 0;
            boolean shutdown = (mask & 128) != 0;
            assertEquals("gate mask " + mask, mask == (1 | 8 | 32 | 64),
                    RearviewMirrorController.shouldShow(enabled, manuallyHidden, foreground,
                            oemKnown, oemVisible, allowed, permission, shutdown));
        }
    }

    @Test public void staleOrOtherProfileFramesNeverRevealMirror() throws Exception {
        JSONObject frame = new JSONObject().put("camera_id", CameraOverlayProfile.MIRROR_ID)
                .put("request_id", 17).put("surface_generation", 4);
        assertTrue(RearviewMirrorController.matchesFrameEvent(frame, 17, 4));
        assertFalse(RearviewMirrorController.matchesFrameEvent(frame, 0, 4));
        assertFalse(RearviewMirrorController.matchesFrameEvent(frame, 17, 0));
        assertFalse(RearviewMirrorController.matchesFrameEvent(frame, 18, 4));
        assertFalse(RearviewMirrorController.matchesFrameEvent(frame, 17, 5));
        frame.put("camera_id", 1);
        assertFalse(RearviewMirrorController.matchesFrameEvent(frame, 17, 4));
    }

    @Test public void disabledPanoramaSuppressionBypassesOnlyThePanoramaGate() {
        assertTrue(RearviewMirrorController.shouldShow(
                true, false, false, false, true, false, true, true, false));
        assertFalse(RearviewMirrorController.shouldShow(
                true, true, false, false, true, false, true, true, false));
        assertFalse(RearviewMirrorController.shouldShow(
                true, false, true, false, true, false, true, true, false));
        assertFalse(RearviewMirrorController.shouldShow(
                true, false, false, false, true, false, false, true, false));
        assertFalse(RearviewMirrorController.shouldShow(
                true, false, false, false, true, false, true, false, false));
    }

    @Test public void mirrorDoesNotRenumberExistingOverlayProfiles() {
        assertEquals(4, CameraOverlayProfile.BLIND_COUNT);
        assertEquals(8, CameraOverlayProfile.PARKING_COUNT);
        assertEquals(12, CameraOverlayProfile.MIRROR_ID);
        assertEquals(13, CameraOverlayProfile.COUNT);
        assertTrue(CameraOverlayProfile.isMirror(12));
        assertFalse(CameraOverlayProfile.isParking(12));
        assertTrue(CameraOverlayProfile.isParking(11));
    }

    @Test public void externalDragCommitsOnTenthsGridInsideDisplay() {
        assertEquals(2, ShellCameraOverlay.roundedMirrorPosition(1, 1920, 672));
        assertEquals(0, ShellCameraOverlay.roundedMirrorPosition(-50, 1920, 672));
        assertEquals(1248, ShellCameraOverlay.roundedMirrorPosition(9999, 1920, 672));
        assertEquals(0, ShellCameraOverlay.roundedMirrorPosition(400, 1080, 1080));
        for (int x = 0; x <= 1920; x++) {
            int rounded = ShellCameraOverlay.roundedMirrorPosition(x, 1920, 672);
            assertTrue(rounded >= 0 && rounded + 672 <= 1920);
            assertEquals(rounded, ShellCameraOverlay.roundedMirrorPosition(rounded, 1920, 672));
        }
    }
}
