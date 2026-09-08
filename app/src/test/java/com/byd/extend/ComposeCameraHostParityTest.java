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
    public void approvedWidgetCaptureAndSharedControlStylingArePorted() throws Exception {
        String primitives = readMain("kotlin/com/byd/extend/ui/UiPrimitives.kt");
        String settings = readMain("kotlin/com/byd/extend/ui/SettingsScreen.kt");
        String reverse = readMain("kotlin/com/byd/extend/ui/ReverseScreen.kt");
        String dialogs = readMain("kotlin/com/byd/extend/ui/BydExtendApp.kt");
        assertTrue(primitives.contains("if (colors.dark) .20f else .04f"));
        assertTrue(primitives.contains("val width = if (compact) 42.dp else 56.dp"));
        assertTrue(primitives.contains("val height = if (compact) 27.dp else 32.dp"));
        assertTrue(primitives.contains(".toggleable(value = checked"));
        assertTrue(settings.contains("compactSwitch = false"));
        assertTrue(settings.contains("colors, compact = false"));
        assertTrue(reverse.contains("if (selected == ReverseElement.Widget)"));
        assertTrue(reverse.contains("ReverseWidgetLearningRow(state.steeringKeyCode"));
        assertTrue(reverse.contains("Modifier.size(44.dp).testTag(\"reverse-key-reset\")"));
        assertTrue(reverse.contains("enabled = steeringKeyCode >= 0"));
        assertTrue(dialogs.contains("dismissOnClickOutside = captureDialog"));
        assertTrue(dialogs.contains("Modifier.fillMaxWidth().testTag(\"reverse-key-cancel\")"));
        assertTrue(dialogs.contains("setDimAmount(if (colors.dark) .48f else .32f)"));
    }

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
        assertTrue(activity.contains("bindProductionCalibrationOverlayListeners();"));
        assertTrue(activity.contains("raw.setListener((crop, finished)"));
        assertTrue(activity.contains("corrected.setListener((crop, finished)"));
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
    public void calibrationStagesUseStableKeysAndStatusIsOnlyInCameraHeader() throws Exception {
        String ui = readMain("kotlin/com/byd/extend/ui/CameraUiCommon.kt");
        String blindParking = readMain("kotlin/com/byd/extend/ui/BlindParkingScreens.kt");
        String reverse = readMain("kotlin/com/byd/extend/ui/ReverseScreen.kt");
        String activity = readMain("java/com/byd/extend/CameraProbeActivity.java");
        assertTrue(ui.contains("key(CameraHostKind.CalibrationOriginal)"));
        assertTrue(ui.contains("key(CameraHostKind.CalibrationCorrected)"));
        assertTrue(ui.contains("key(CameraHostKind.CalibrationOutput)"));
        assertTrue(ui.contains("profileStatus: StatusUiState = StatusUiState()"));
        assertTrue(ui.contains("panoramaStatus: StatusUiState? = null"));
        assertFalse(ui.contains("profileHeaderStatus"));
        assertTrue(ui.contains("PanoramaStatusPill(panoramaStatus, strings, colors)"));
        assertTrue(ui.indexOf("titleTrailing = if (panoramaStatus != null)")
                > ui.indexOf("testTag(\"camera-frame\")"));
        assertTrue(ui.contains("trailing = { CameraStatusPill(profileStatus, strings, colors) }"));
        assertTrue(ui.contains("with(LocalDensity.current) { 18.sp.toDp() } + 12.dp"));
        assertFalse(ui.contains("StatusPill(profileStatus, strings.text(\"Статус\", \"Status\"), colors)"));
        assertTrue(activity.contains("owner.refreshMirrorBuffer(texture, raw);"));
        assertTrue(blindParking.contains("profileStatus = profile.operation.status"));
        assertTrue(blindParking.contains("profileStatus = viewState.profile.operation.status"));
        assertTrue(reverse.contains("profileStatus = profile.operation.status"));
        assertTrue(reverse.split("panoramaStatus = state.panoramaOperation.status", -1).length == 3);
        assertFalse(reverse.contains("profileHeaderStatus"));
        assertTrue(activity.contains("if (profile instanceof CameraProfileId.Blind) return tab == TAB_CAMERAS;"));
        assertTrue(activity.contains("if (profile instanceof CameraProfileId.Parking) return tab == TAB_PARKING_CAMERAS;"));
        assertTrue(activity.contains("if (profile instanceof CameraProfileId.Reverse) return tab == TAB_REVERSE_CAMERAS;"));
        assertTrue(activity.contains("\"First frame ready\", StatusTone.Ok, false"));
        assertTrue(activity.contains("localizedCameraStatus(text)"));
        assertTrue(activity.contains("!\"stock_avm_shell\".equals(source)"));
        assertTrue(activity.contains("isDiagnosticStageIdentityMatch("));
        assertFalse(activity.contains("activeActivityCameraRequestStartedAtMs"));
        assertTrue(activity.contains("!isCurrentDiagnosticStageEvent(json)"));
    }

    @Test
    public void redundantUpdatePromptAndBackgroundToastAreAbsent() throws Exception {
        String controller = readMain("kotlin/com/byd/extend/ui/ProductionUiController.kt");
        String activity = readMain("java/com/byd/extend/CameraProbeActivity.java");
        assertTrue(controller.contains("if (command == CommandId.CheckForUpdates)"));
        assertTrue(controller.contains("backend.onProductionUiAction(BydExtendUiAction.Run(command))"));
        assertFalse(controller.contains("Check whether a newer BYD Extend version is available?"));
        assertFalse(activity.contains("Вимкніть BYD Extend у списку Disable background Apps"));
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
        assertTrue(activity.contains("selectedTab == TAB_REARVIEW_MIRROR"));
        assertTrue(activity.contains("? CameraHostKind.Mirror : CameraHostKind.Placement"));
        assertTrue(activity.contains(
                "if (!shutdownRequested && isAutoPreviewTab(selectedTab)) armResumeAutoPreview();"));
    }

    @Test
    public void everyLiveCameraHostUsesTheSharedTransformedCropMask() throws Exception {
        String blind = readMain("java/com/byd/extend/BlindSpotCameraView.java");
        String reverse = readMain("java/com/byd/extend/ReverseCameraCompositionView.java");
        assertTrue(blind.contains(
                "outputCropMask.setCrop(CameraRotation.transformedCropCornersForInput("));
        assertFalse(blind.contains("outputCropMask.setCrop(new float[]{"));
        assertTrue(reverse.contains(
                "float[] transformed = CameraRotation.transformedCropCornersForInput("));
    }

    @Test
    public void reverseChildrenAreMeasuredBeforeOneShotReadyReveal() throws Exception {
        String reverse = readMain("java/com/byd/extend/ReverseCameraCompositionView.java");
        String measure = reverse.substring(reverse.indexOf("protected void onMeasure("),
                reverse.indexOf("void setCallback("));
        int firstMeasure = measure.indexOf("super.onMeasure(");
        int applyMeasuredModel = measure.indexOf("applyModel(width, height);");
        int secondMeasure = measure.lastIndexOf("super.onMeasure(");
        assertTrue(firstMeasure >= 0 && firstMeasure < applyMeasuredModel);
        assertTrue(secondMeasure > applyMeasuredModel);
        assertTrue(reverse.contains(
                "value.mirrorHorizontally, baseRect.width, baseRect.height);"));
        assertTrue(reverse.contains(
                "centerValue.displayMode, centerValue.mirrorHorizontally,"));
        assertTrue(reverse.contains("centerRect.width, centerRect.height);"));

        String report = reverse.substring(reverse.indexOf("private void maybeReportFrames()"),
                reverse.indexOf("private void setAllCovers("));
        int boundsGate = report.indexOf("if (!primaryRendererBoundsReady()) return;");
        assertTrue(boundsGate >= 0 && boundsGate < report.indexOf("callback.onReverseFramesReady("));
        assertTrue(report.contains("view.getWidth() <= 0 || view.getHeight() <= 0"));
        assertTrue(report.contains("params.width > 0 && params.height > 0"));
        assertTrue(report.contains(
                "params.width == view.getWidth() && params.height == view.getHeight()"));
        String layout = measure.substring(measure.indexOf("protected void onLayout("));
        int childLayout = layout.indexOf("super.onLayout(changed, left, top, right, bottom);");
        assertTrue(childLayout >= 0 && childLayout < layout.indexOf("maybeReportFrames();"));

        String readiness = reverse.substring(
                reverse.indexOf("private boolean primaryRendererBoundsReady()"),
                reverse.indexOf("private static boolean laidOutBoundsMatch("));
        assertTrue(readiness.contains("for (PaneView pane : panes)"));
        assertTrue(readiness.contains("pane.texture"));
        assertFalse(readiness.contains("centralFrontPane"));
    }

    @Test
    public void reverseBackgroundAndWidgetKeepEditableBoundedGeometry() throws Exception {
        String ui = readMain("kotlin/com/byd/extend/ui/ReverseScreen.kt");
        String controls = ui.substring(ui.indexOf("private fun ReverseCompositionControls("),
                ui.indexOf("private fun ReverseCompositionFrame("));
        assertTrue(controls.contains("maxX = 100f - width, maxY = 100f - height"));
        assertTrue(controls.contains("5f..(100f - x).coerceAtLeast(5f)"));
        assertTrue(controls.contains("5f..(100f - y).coerceAtLeast(5f)"));
        String sizeControls = controls.substring(controls.indexOf("GeometryPair("),
                controls.indexOf("Row(horizontalArrangement"));
        assertFalse(sizeControls.contains("enabled = cameraElement"));
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
