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
                "productionCameraHosts.put(CameraHostKind.CalibrationOriginal, calibrationRawMirrorHost);"));
        assertTrue(activity.contains(
                "productionCameraHosts.put(CameraHostKind.CalibrationCorrected,\n                calibrationCorrectedMirrorHost);"));
        assertTrue(activity.contains(
                "productionCameraHosts.put(CameraHostKind.CalibrationOutput, calibrationOutputHost);"));
        assertTrue(activity.contains("calibrationOutputHost.addView(owner"));
        assertTrue(activity.contains("productionCalibrationRawOverlay.setListener"));
        assertTrue(activity.contains("productionCalibrationCorrectedOverlay.setListener"));
        assertTrue(activity.contains("calibrationHostProfile instanceof CameraProfileId.Reverse"));
        assertTrue(activity.contains("CameraDewarpStatsEvent.reverse("));
        assertTrue(activity.contains("owner.setRawMirrorTexture(null)"));
        assertTrue(activity.contains("owner.setCorrectedMirrorTexture(null)"));
        assertTrue(activity.contains("releaseProductionCalibrationHosts();"));
        assertFalse(activity.contains(
                "productionCameraHosts.put(CameraHostKind.CalibrationOriginal, raw);"));
        assertFalse(activity.contains(
                "productionCameraHosts.put(CameraHostKind.CalibrationCorrected, corrected);"));
        assertFalse(activity.contains(
                "productionCameraHosts.put(CameraHostKind.CalibrationOutput, owner);"));
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
        assertTrue(ui.contains("val display = state.displayGeometry"));
        assertTrue(ui.contains("display.marginLeft"));
        assertTrue(ui.contains("productionPlacementGeometry("));
        assertTrue(ui.contains("testTag(\"placement-canvas\")"));
        assertTrue(activity.contains("float safeX = clamp(x, 0.0f, 1.0f);"));
        assertTrue(activity.contains("id instanceof CameraProfileId.Blind"));
        assertTrue(activity.contains("id instanceof CameraProfileId.Parking"));
    }

    @Test
    public void hostLifecycleUsesCurrentOwnerAndBlackCoverGates() throws Exception {
        String activity = readMain("java/com/byd/extend/CameraProbeActivity.java");
        assertTrue(activity.contains("private FrameLayout cameraHostRoot()"));
        assertTrue(activity.contains("root.setClipChildren(true)"));
        assertTrue(activity.contains("root.setClipToPadding(true)"));
        assertTrue(activity.contains("cover.setVisibility(View.VISIBLE)"));
        assertTrue(activity.contains("if (directCameraPreview != surfaceView) return;"));
        assertTrue(activity.contains("if (debugPreview != surfaceView) return;"));
        assertTrue(activity.contains("holder.getSurface().isValid()"));
        assertTrue(activity.contains("if (activePreview == directCameraPreview"));
        assertTrue(activity.contains("&& directCameraSurfaceReady"));
        assertTrue(activity.contains("if (activePreview == debugPreview"));
        assertTrue(activity.contains("&& debugSurfaceReady"));
        assertTrue(activity.contains("reverseCameraPreview.setCallback(null);"));
        assertTrue(activity.contains("productionCameraHosts.get(slot.getKind()) != view"));
        assertTrue(activity.contains("productionCameraSlots.get(CameraHostKind.Placement)"));
        assertTrue(activity.contains(
                "if (!shutdownRequested && isAutoPreviewTab(selectedTab)) armResumeAutoPreview();"));
    }

    @Test
    public void reverseEditorIsPlacementOnlyAndSelectionAvoidsCameraReopen() throws Exception {
        String activity = readMain("java/com/byd/extend/CameraProbeActivity.java");
        String editor = readMain("java/com/byd/extend/ReverseCameraEditorView.java");
        assertTrue(activity.contains("productionReverseEditorEditable = slot.getEditable();"));
        assertTrue(activity.contains("productionReverseEditor.setEditable(productionReverseEditorEditable);"));
        assertTrue(activity.contains("productionUi.setReverseEditorSelection(selectedElement);"));
        assertTrue(activity.contains("ReverseCameraController.saveEditorSelection(preferences, selectedCamera);"));
        assertTrue(editor.contains("void setSelectedCameraSilently(int cameraIndex)"));
        assertTrue(editor.contains("if (!editable) return false;"));
    }

    @Test
    public void calibrationEditsUseIndependentStrictGeometryAndPersistTransformsSeparately()
            throws Exception {
        String activity = readMain("java/com/byd/extend/CameraProbeActivity.java");
        assertTrue(activity.contains("withIndependentGeometry("));
        assertTrue(activity.contains("DirectCameraCrop.requireUiGeometry("));
        assertTrue(activity.contains("withOutputTransformPreservingGeometry("));
        assertTrue(activity.contains("saveRawGeometryEdit(preferences"));
        assertTrue(activity.contains("saveCorrectedGeometryEdit(preferences"));
        assertTrue(activity.contains("replaceReverseRectValue(crop, pane, field, value)"));
        assertTrue(activity.contains(
                "left, top, width, height, pane.rotationDegrees, pane.displayMode"));
        assertTrue(activity.contains("crop.left, crop.top, crop.width, crop.height, 0, CameraRotation.MODE_FIT"));
        assertTrue(activity.contains("productionCalibrationGestureTransformChanged"));
        assertTrue(activity.contains("if (!changed) return;"));
    }

    @Test
    public void reverseFallbackCarriesTheAppliedSourceRoleInsteadOfUiSelection() throws Exception {
        String composition = readMain("java/com/byd/extend/ReverseCameraCompositionView.java");
        String activity = readMain("java/com/byd/extend/CameraProbeActivity.java");
        String apply = composition.substring(composition.indexOf("private void applyPaneDewarpConfig("),
                composition.indexOf("private void applyEffectiveVisibility()"));
        assertTrue(apply.indexOf("pane.frontCalibration =") < apply.indexOf("pane.applyDewarpConfig(value)"));
        assertTrue(apply.contains("pane.cameraIndex, previousFront, false"));
        assertTrue(composition.contains("cameraIndex, pane.frontCalibration, view.usesRawFallback()"));
        String handler = activity.substring(activity.indexOf("public void onReverseDewarpFallbackChanged("),
                activity.indexOf("public boolean automaticStartEnabled()"));
        assertTrue(handler.contains("frontSource ? ReverseSource.Front : ReverseSource.Rear"));
        assertFalse(handler.contains("sideMode()"));
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
