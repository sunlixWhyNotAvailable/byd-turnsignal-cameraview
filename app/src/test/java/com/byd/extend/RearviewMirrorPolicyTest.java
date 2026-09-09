package com.byd.extend;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test public void manualHideRequiresTheCurrentGestureEvent() throws Exception {
        JSONObject event = new JSONObject().put("kind", "mirror_hidden_by_gesture")
                .put("camera_id", CameraOverlayProfile.MIRROR_ID)
                .put("request_id", 17).put("surface_generation", 4);
        assertTrue(RearviewMirrorController.matchesManualHideEvent(event, 17, 4));
        assertFalse(RearviewMirrorController.matchesManualHideEvent(event, 18, 4));
        assertFalse(RearviewMirrorController.matchesManualHideEvent(event, 17, 5));
        event.put("kind", "camera_overlay_visibility");
        assertFalse(RearviewMirrorController.matchesManualHideEvent(event, 17, 4));
    }

    @Test public void mirrorHoldAndToastKeepTheRequiredBoundaries() throws Exception {
        assertEquals(1_000L, ShellCameraOverlay.MIRROR_HIDE_HOLD_MS);
        String overlay = sourceText("java/com/byd/extend/ShellCameraOverlay.java");
        assertTrue(overlay.contains("postDelayed(hideMirrorGesture, MIRROR_HIDE_HOLD_MS)"));
        assertTrue(overlay.contains("case MotionEvent.ACTION_UP:\n"
                + "            case MotionEvent.ACTION_CANCEL:\n"
                + "                root.removeCallbacks(hideMirrorGesture);"));
        assertTrue(overlay.contains("dragging = true;\n"
                + "                    root.removeCallbacks(hideMirrorGesture);"));
        assertTrue(overlay.contains("if (!nextVisible) {\n"
                + "            root.removeCallbacks(hideMirrorGesture);"));

        String controller = sourceText("java/com/byd/extend/RearviewMirrorController.java");
        int accepted = controller.indexOf("matchesManualHideEvent(event, requestId, generation)");
        int stopped = controller.indexOf("stop(\"manual_hide\", false);", accepted);
        int toast = controller.indexOf("Toast.makeText(localized", stopped);
        assertTrue(accepted >= 0 && stopped > accepted && toast > stopped);
        assertTrue(controller.substring(toast).contains("Toast.LENGTH_LONG).show()"));
        assertTrue(controller.substring(accepted, toast).contains("AppLanguage.localizedContext("));
    }

    @Test public void manualHideTextIsLocalizedWithoutPreviewBranding() throws Exception {
        String[] folders = {"values", "values-uk", "values-zh-rCN"};
        String[] messages = {
                "Mirror hidden — open BYD Extend to restore it.",
                "Дзеркало приховано — відкрийте BYD Extend, щоб повернути.",
                "后视镜已隐藏，打开 BYD Extend 可恢复。"
        };
        for (int index = 0; index < folders.length; index++) {
            String resources = sourceText("res/" + folders[index] + "/strings.xml");
            int start = resources.indexOf("<string name=\"mirror_hidden\">");
            int end = resources.indexOf("</string>", start);
            String entry = resources.substring(start, end);
            assertTrue(entry.contains(messages[index]));
            assertFalse(entry.contains("Preview"));
        }
    }

    private static Path sourcePath(String relative) {
        Path path = Path.of("src/main", relative);
        return Files.exists(path) ? path : Path.of("app").resolve(path);
    }

    private static String sourceText(String relative) throws java.io.IOException {
        return new String(Files.readAllBytes(sourcePath(relative)),
                java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
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
