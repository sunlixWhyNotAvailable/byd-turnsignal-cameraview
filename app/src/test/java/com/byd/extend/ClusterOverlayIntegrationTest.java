package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class ClusterOverlayIntegrationTest {
    @Test
    public void displayEventsOnlyAffectActiveMatchingClusterDestination() {
        assertTrue(ShellCameraOverlay.isCurrentClusterDisplayEvent(true, 1, 4, 4));
        assertFalse(ShellCameraOverlay.isCurrentClusterDisplayEvent(false, 1, 4, 4));
        assertFalse(ShellCameraOverlay.isCurrentClusterDisplayEvent(true, 0, 0, 0));
        assertFalse(ShellCameraOverlay.isCurrentClusterDisplayEvent(true, 1, 4, 5));
        assertFalse(ShellCameraOverlay.isCurrentClusterDisplayEvent(true, 1, -1, -1));
    }

    @Test
    public void staleHideCannotAffectNewRequestOrSurface() {
        assertTrue(ShellCameraOverlay.matchesSurfaceRequest(true, 5, 2, 5, 2));
        assertFalse(ShellCameraOverlay.matchesSurfaceRequest(true, 5, 2, 4, 2));
        assertFalse(ShellCameraOverlay.matchesSurfaceRequest(true, 5, 2, 5, 1));
        assertFalse(ShellCameraOverlay.matchesSurfaceRequest(false, 5, 2, 5, 2));
        assertFalse(ShellCameraOverlay.matchesSurfaceRequest(true, 0, 0, 0, 0));
    }

    @Test
    public void readinessAndAcquisitionCheckAttachmentBeforeHandingSurfaceToConsumer()
            throws Exception {
        String source = new String(Files.readAllBytes(Path.of(
                "src/main/java/com/byd/extend/ShellCameraOverlay.java")), StandardCharsets.UTF_8);
        String ready = source.substring(source.indexOf("private void emitSurfaceReady("),
                source.indexOf("private void requireCurrent("));
        assertTrue(ready.indexOf("ensureClusterDestination();")
                < ready.indexOf("\"state\", \"ready\""));
        String acquire = source.substring(source.indexOf("SurfaceSnapshot acquireSurface("),
                source.indexOf("void armFirstFrame("));
        assertTrue(acquire.indexOf("ensureClusterDestination();")
                < acquire.indexOf("return new SurfaceSnapshot("));
        assertTrue(source.contains("if (nextVisible) requireCurrentClusterDisplay();"));
        assertTrue(source.contains("\"stage\", \"cluster_destination_unavailable\""));
    }

    @Test
    public void shellDisplayObserverUsesExistingMainHandlerAndIsRemovedAtShutdown()
            throws Exception {
        String source = new String(Files.readAllBytes(Path.of(
                "src/main/java/com/byd/extend/CameraShellMain.java")), StandardCharsets.UTF_8);
        assertTrue(source.contains("registerDisplayListener(displayListener, handler)"));
        assertTrue(source.contains("unregisterDisplayListener(displayListener)"));
        assertTrue(source.contains("overlay.onClusterDisplayChanged(displayId, removed)"));
    }
}
