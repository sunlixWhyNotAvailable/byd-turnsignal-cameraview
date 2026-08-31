package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class ComposeCameraHostParityTest {
    @Test
    public void calibrationBundleIsRegisteredAtomicallyWithOneOwner() throws Exception {
        String activity = readMain("java/com/byd/extend/CameraProbeActivity.java");
        assertTrue(activity.contains(
                "if (isProductionCalibrationKind(kind)) ensureProductionCalibrationHosts();"));
        assertTrue(activity.contains("TextureView raw = createProductionMirror(owner, true);"));
        assertTrue(activity.contains("TextureView corrected = createProductionMirror(owner, false);"));
        assertTrue(activity.contains(
                "productionCameraHosts.put(CameraHostKind.CalibrationOriginal, raw);"));
        assertTrue(activity.contains(
                "productionCameraHosts.put(CameraHostKind.CalibrationCorrected, corrected);"));
        assertTrue(activity.contains(
                "productionCameraHosts.put(CameraHostKind.CalibrationOutput, owner);"));
        assertTrue(activity.contains("releaseProductionCalibrationHosts();"));
        assertFalse(activity.contains(
                "productionCameraHosts.containsKey(CameraHostKind.CalibrationOutput)"));
    }

    @Test
    public void placementUsesVisibleGeometryAndPersistsBoundedDrag() throws Exception {
        String ui = readMain("kotlin/com/byd/extend/ui/CameraPlacementPreview.kt");
        String activity = readMain("java/com/byd/extend/CameraProbeActivity.java");
        assertTrue(ui.contains("detectDragGestures("));
        assertTrue(ui.contains("remember(profile) { mutableFloatStateOf(storedX) }"));
        assertTrue(ui.contains("rememberUpdatedState(onMove)"));
        assertTrue(ui.contains("latestOnMove(dragX.floatValue, dragY.floatValue)"));
        assertTrue(ui.contains("state.frameAspect"));
        assertTrue(ui.contains("testTag(\"placement-canvas\")"));
        assertTrue(ui.contains("state.target == DisplayTarget.Cluster"));
        assertTrue(activity.contains("float safeX = clamp(x, 0.0f, 1.0f);"));
        assertTrue(activity.contains("id instanceof CameraProfileId.Blind"));
        assertTrue(activity.contains("id instanceof CameraProfileId.Parking"));
    }

    @Test
    public void diagnosticIntentsUseTheComposeActionPath() throws Exception {
        String activity = readMain("java/com/byd/extend/CameraProbeActivity.java");
        String intentHandler = activity.substring(activity.indexOf("private void acceptDiagnosticIntent("),
                activity.indexOf("private void verifyMappings()"));
        assertTrue(intentHandler.contains("new BydExtendUiAction.Navigate(RootTab.Debug)"));
        assertTrue(intentHandler.contains("SelectionId.DiagnosticMode), DiagnosticMode.Avm.ordinal()"));
        assertTrue(intentHandler.contains("SelectionId.AvmMode), index"));
        assertTrue(intentHandler.contains("new BydExtendUiAction.Run(CommandId.StopDiagnosticCamera, null)"));
        assertFalse(intentHandler.contains("selectTab("));
        assertFalse(intentHandler.contains("selectDebugMode("));
        assertFalse(activity.contains("pendingDiagnosticAvmModeIndex"));
    }

    private static String readMain(String relative) throws Exception {
        Path path = Paths.get("src/main", relative);
        if (!Files.exists(path)) path = Paths.get("app/src/main", relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
