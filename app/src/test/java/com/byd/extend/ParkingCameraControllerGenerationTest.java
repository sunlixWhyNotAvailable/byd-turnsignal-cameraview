package com.byd.extend;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ParkingCameraControllerGenerationTest {
    @Test
    public void callbacksRequireCurrentReadyGeneration() {
        int fid = ParkingCameraProfile.coreRadarFids()[0];
        assertFalse(ParkingCameraController.acceptsRadarCallback(
                "radar_core", fid, 7L, 7L, false));
        assertFalse(ParkingCameraController.acceptsRadarCallback(
                "radar_core", fid, 7L, 0L, true));
        assertFalse(ParkingCameraController.acceptsRadarCallback(
                "radar_core", fid, 7L, 6L, true));
        assertFalse(ParkingCameraController.acceptsRadarCallback(
                "radar_core", fid, 7L, 8L, true));
        assertTrue(ParkingCameraController.acceptsRadarCallback(
                "radar_core", fid, 7L, 7L, true));
    }

    @Test
    public void callbacksRequireMatchingFamilyFid() {
        int coreFid = ParkingCameraProfile.coreRadarFids()[0];
        int sideFid = ParkingCameraProfile.sideRadarFids()[0];
        assertTrue(ParkingCameraController.matchesRadarFamilyFid("radar_core", coreFid));
        assertFalse(ParkingCameraController.matchesRadarFamilyFid("radar_core", sideFid));
        assertTrue(ParkingCameraController.matchesRadarFamilyFid("adas_side", sideFid));
        assertFalse(ParkingCameraController.matchesRadarFamilyFid("unknown", coreFid));
        assertFalse(ParkingCameraController.acceptsRadarCallback(
                "adas_side", coreFid, 7L, 7L, true));
    }

    @Test
    public void shellCallbackMarkerFencesOldEventsButAcceptsNewGenerationOne() {
        assertTrue(ParkingCameraController.isRadarHelperLossEvent("helper_death"));
        assertTrue(ParkingCameraController.isRadarHelperLossEvent("helper_ping_failed"));
        assertFalse(ParkingCameraController.isRadarHelperLossEvent("helper_connected"));
        assertTrue(ParkingCameraController.isRadarHelperReadyEvent(
                "shell_callback_registered"));
        assertFalse(ParkingCameraController.isRadarHelperReadyEvent("helper_connected"));
        assertFalse(ParkingCameraController.acceptsRadarSnapshotEpoch(
                500L, 499L, false));
        assertFalse(ParkingCameraController.acceptsRadarSnapshotEpoch(
                500L, 501L, true));
        assertTrue(ParkingCameraController.acceptsRadarSnapshotEpoch(
                500L, 501L, false));
        assertTrue(ParkingCameraController.acceptsRadarCallback(
                "radar_core", ParkingCameraProfile.coreRadarFids()[0],
                1L, 1L, true));
        assertFalse(ParkingCameraController.acceptsRadarCallback(
                "radar_core", ParkingCameraProfile.coreRadarFids()[0],
                1L, 0L, true));
    }

    @Test
    public void preparedSurfaceOwnershipTransfersWhenBatchIsSubmitted() {
        assertTrue(ParkingCameraController.controllerOwnsPreparedSurface(false));
        assertFalse(ParkingCameraController.controllerOwnsPreparedSurface(true));
    }

    @Test
    public void preparedSurfaceCleanupUsesSubmissionOwnershipNotAttachSuccess() throws Exception {
        Path path = Path.of("src/main/java/com/byd/extend/ParkingCameraController.java");
        if (!Files.exists(path)) path = Path.of("app").resolve(path);
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        int transfer = source.indexOf("pane.submittedToHelper = true;");
        assertTrue(transfer > source.indexOf("pane.submittedToHelper = false;"));
        assertTrue(transfer < source.indexOf("activeHelper.openParkingCameras("));
        String clear = source.substring(source.indexOf("private void clearPaneState()"),
                source.indexOf("static boolean controllerOwnsPreparedSurface("));
        assertTrue(clear.contains("controllerOwnsPreparedSurface(pane.submittedToHelper)"));
        assertTrue(clear.indexOf("pane.surface.release()") >= 0
                && clear.indexOf("pane.surface = null") > clear.indexOf("pane.surface.release()"));
        assertTrue(clear.contains("pane.submittedToHelper = false;"));
        assertFalse(clear.contains("if (pane.attached"));
    }
}
