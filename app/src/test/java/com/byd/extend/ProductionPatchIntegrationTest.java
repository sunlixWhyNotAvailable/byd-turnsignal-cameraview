package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.byd.extend.ui.CameraGroup;
import com.byd.extend.ui.CameraDisplayGeometry;
import com.byd.extend.ui.CameraProfileId;
import com.byd.extend.ui.CameraSide;
import com.byd.extend.ui.CommandId;
import com.byd.extend.ui.ReverseElement;
import com.byd.extend.ui.ReverseSource;
import com.byd.extend.ui.DisplayTarget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Test;

/** Focused source and preference regressions for the 2-September Production patch. */
public final class ProductionPatchIntegrationTest {
    @Test
    public void blindCornerResizeWritesOneCompleteBoundedRectangle() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfileId.Blind id = new CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left);
        CameraProbeActivity.saveProductionBlindGeometry(preferences, id,
                new com.byd.extend.ui.MirrorGeometryUiState("12.3", "23.4", "34.5", "45.6"));
        CameraPlacement actual = BlindSpotOverlayController.readPlacement(preferences,
                CameraProfile.of(CameraProfile.REAR_LEFT), 1920, 1080, 16, 36, 88);
        assertEquals(.123f, actual.x, .000001f);
        assertEquals(.234f, actual.y, .000001f);
        assertEquals(.345f, actual.width, .000001f);
        assertEquals(.456f, actual.height, .000001f);
        CameraProbeActivity.saveProductionBlindGeometry(preferences, id,
                new com.byd.extend.ui.MirrorGeometryUiState("80", "90", "40", "30"));
        actual = BlindSpotOverlayController.readPlacement(preferences,
                CameraProfile.of(CameraProfile.REAR_LEFT), 1920, 1080, 16, 36, 88);
        assertEquals(.6f, actual.x, .000001f);
        assertEquals(.7f, actual.y, .000001f);
        assertEquals(.4f, actual.width, .000001f);
        assertEquals(.3f, actual.height, .000001f);
    }

    @Test
    public void parkingPlacementResetDisablesSyncWithoutFanout() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        ParkingCameraProfile selected = ParkingCameraProfile.of(ParkingCameraProfile.FL);
        ParkingCameraProfile sibling = ParkingCameraProfile.of(ParkingCameraProfile.RR);
        String selectedPrefix = parkingPlacementPrefix(selected);
        String siblingPrefix = parkingPlacementPrefix(sibling);
        preferences.putInt(selectedPrefix + "scale", 51);
        preferences.putFloat(selectedPrefix + "x", .41f);
        preferences.putFloat(selectedPrefix + "y", .37f);
        preferences.putInt(siblingPrefix + "scale", 53);
        preferences.putFloat(siblingPrefix + "x", .73f);
        preferences.putFloat(siblingPrefix + "y", .67f);
        preferences.putBoolean("parking_camera_scale_sync", true);
        preferences.putBoolean(ParkingCameraSettings.enabledKey(selected), true);
        preferences.putFloat("parking_direct_crop_v1_fl_x", .44f);
        preferences.putInt("parking_direct_crop_v1_fl_rotation", 90);
        preferences.putInt("parking_calibration_preset_v1_fl_version", 1);

        CameraProfileId id = new CameraProfileId.Parking(
                com.byd.extend.ui.ParkingView.FrontLeft);
        assertTrue(CameraProbeActivity.resetProductionProfileSettings(
                preferences, id, CommandId.ResetProfilePlacement));

        assertEquals(ParkingCameraSettings.DEFAULT_SCALE_PERCENT,
                preferences.getInt(selectedPrefix + "scale", -1));
        assertEquals(0f, preferences.getFloat(selectedPrefix + "x", -1f), 0f);
        assertEquals(0f, preferences.getFloat(selectedPrefix + "y", -1f), 0f);
        assertFalse(preferences.getBoolean("parking_camera_scale_sync", true));
        assertEquals(53, preferences.getInt(siblingPrefix + "scale", -1));
        assertEquals(.73f, preferences.getFloat(siblingPrefix + "x", -1f), 0f);
        assertEquals(.67f, preferences.getFloat(siblingPrefix + "y", -1f), 0f);
        assertTrue(preferences.getBoolean(ParkingCameraSettings.enabledKey(selected), false));
        assertEquals(.44f, preferences.getFloat("parking_direct_crop_v1_fl_x", -1f), 0f);
        assertEquals(90, preferences.getInt("parking_direct_crop_v1_fl_rotation", -1));
        assertEquals(1, preferences.getInt("parking_calibration_preset_v1_fl_version", -1));
    }

    @Test
    public void blindPlacementResetIsScopedToSelectedProfile() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile selected = CameraProfile.of(CameraProfile.REAR_LEFT);
        CameraProfile sibling = CameraProfile.of(CameraProfile.REAR_RIGHT);
        preferences.putFloat(BlindSpotOverlayController.positionKey(selected, false), .71f);
        preferences.putFloat(BlindSpotOverlayController.positionKey(selected, true), .63f);
        preferences.putInt(BlindSpotOverlayController.scaleKey(selected), 48);
        preferences.putFloat(BlindSpotOverlayController.placementWidthKey(selected), .22f);
        preferences.putFloat(BlindSpotOverlayController.placementHeightKey(selected), .33f);
        preferences.putFloat(BlindSpotOverlayController.frameAspectKey(selected), 2.5f);
        preferences.putInt(BlindSpotOverlayController.targetKey(selected), CameraDisplayTarget.CLUSTER);
        preferences.putFloat(BlindSpotOverlayController.positionKey(sibling, false), .27f);
        preferences.putFloat(BlindSpotOverlayController.positionKey(sibling, true), .19f);
        preferences.putInt(BlindSpotOverlayController.scaleKey(sibling), 34);
        preferences.putInt(BlindSpotOverlayController.targetKey(sibling), CameraDisplayTarget.CLUSTER);
        CameraPlacement inactiveTablet = CameraPlacement.of(.11f, .12f, .31f, .32f);
        CameraPlacement siblingCluster = CameraPlacement.of(.21f, .22f, .33f, .34f);
        BlindSpotOverlayController.writePlacement((android.content.SharedPreferences) preferences, selected,
                CameraDisplayTarget.TABLET, inactiveTablet);
        BlindSpotOverlayController.writePlacement((android.content.SharedPreferences) preferences, sibling,
                CameraDisplayTarget.CLUSTER, siblingCluster);
        preferences.putFloat("direct_crop_left_x", .42f);
        preferences.putInt("direct_crop_left_rotation", 90);
        preferences.putBoolean(BlindSpotOverlayController.PREF_ENABLED, true);
        preferences.putInt("camera_calibration_preset_v1_rear_left_version", 1);

        CameraProfileId id = new CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left);
        CameraDisplayGeometry tablet = new CameraDisplayGeometry(
                1920, 1080, 16, 36, 16, 88, DisplayTarget.Tablet);
        assertTrue(CameraProbeActivity.resetProductionProfileSettings(
                preferences, id, CommandId.ResetProfilePlacement, tablet));

        assertEquals(BlindSpotOverlayController.defaultPlacement(selected,
                        CameraDisplayTarget.CLUSTER, 1920, 1080, 16, 36, 88),
                BlindSpotOverlayController.readPlacement(preferences, selected,
                        CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0));
        assertEquals(inactiveTablet, BlindSpotOverlayController.readPlacement(preferences, selected,
                CameraDisplayTarget.TABLET, 1920, 1080, 16, 36, 88));
        assertEquals(.71f,
                preferences.getFloat(BlindSpotOverlayController.positionKey(selected, false), -1f), 0f);
        assertEquals(.63f,
                preferences.getFloat(BlindSpotOverlayController.positionKey(selected, true), -1f), 0f);
        assertEquals(48, preferences.getInt(BlindSpotOverlayController.scaleKey(selected), -1));
        assertEquals(.22f, preferences.getFloat(
                BlindSpotOverlayController.placementWidthKey(selected), -1f), 0f);
        assertEquals(.33f, preferences.getFloat(
                BlindSpotOverlayController.placementHeightKey(selected), -1f), 0f);
        assertEquals(2.5f, preferences.getFloat(
                BlindSpotOverlayController.frameAspectKey(selected), -1f), 0f);
        assertEquals(CameraDisplayTarget.CLUSTER,
                preferences.getInt(BlindSpotOverlayController.targetKey(selected), -1));
        assertEquals(.27f, preferences.getFloat(BlindSpotOverlayController.positionKey(sibling, false), -1f), 0f);
        assertEquals(.19f, preferences.getFloat(BlindSpotOverlayController.positionKey(sibling, true), -1f), 0f);
        assertEquals(34, preferences.getInt(BlindSpotOverlayController.scaleKey(sibling), -1));
        assertEquals(CameraDisplayTarget.CLUSTER,
                preferences.getInt(BlindSpotOverlayController.targetKey(sibling), -1));
        assertEquals(siblingCluster, BlindSpotOverlayController.readPlacement(preferences, sibling,
                CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0));
        assertEquals(.42f, preferences.getFloat("direct_crop_left_x", -1f), 0f);
        assertEquals(90, preferences.getInt("direct_crop_left_rotation", -1));
        assertTrue(preferences.getBoolean(BlindSpotOverlayController.PREF_ENABLED, false));
        assertEquals(1, preferences.getInt("camera_calibration_preset_v1_rear_left_version", -1));
    }

    @Test
    public void invalidOrReversePlacementResetDoesNotMutatePreferences() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putInt("sentinel", 7);
        Map<String, ?> before = preferences.getAll();
        assertFalse(CameraProbeActivity.resetProductionProfileSettings(
                preferences, null, CommandId.ResetProfilePlacement));
        assertEquals(before, preferences.getAll());

        CameraProfileId reverse = new CameraProfileId.Reverse(ReverseElement.Rear, ReverseSource.Rear);
        assertFalse(CameraProbeActivity.resetProductionProfileSettings(
                preferences, reverse, CommandId.ResetProfilePlacement));
        assertEquals(before, preferences.getAll());
        CameraProfileId unsupportedReverse = new CameraProfileId.Reverse(
                ReverseElement.Background, ReverseSource.Rear);
        assertFalse(CameraProbeActivity.resetProductionProfileSettings(
                preferences, unsupportedReverse, CommandId.ResetProfileOriginal));
        assertEquals(before, preferences.getAll());
        assertFalse(CameraProbeActivity.resetProductionProfileSettings(
                preferences, null, CommandId.ResetProfileOriginal));
        assertEquals(before, preferences.getAll());

        CameraProfileId blind = new CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left);
        assertFalse(CameraProbeActivity.resetProductionProfileSettings(preferences, blind, null));
        assertEquals(before, preferences.getAll());
    }

    @Test
    public void transferLabelsFollowCalibrationMirrorTargets() {
        CameraProfileId blindSource = new CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left);
        CameraProfileId blindTarget = CameraProbeActivity.oppositeProductionProfile(blindSource);
        assertNotNull(blindTarget);
        assertEquals(CameraProfile.REAR_RIGHT, cameraProfileIndex((CameraProfileId.Blind) blindTarget));
        assertEquals(CameraCalibrationPreset.cameraMirrorTarget(CameraProfile.of(CameraProfile.REAR_LEFT)),
                cameraProfileIndex((CameraProfileId.Blind) blindTarget));
        assertEquals("Задня права", CameraProbeActivity.productionProfileLabel(blindTarget, false));
        assertEquals("Rear right", CameraProbeActivity.productionProfileLabel(blindTarget, true));

        CameraProfileId parkingSource = new CameraProfileId.Parking(
                com.byd.extend.ui.ParkingView.FrontLeft);
        CameraProfileId parkingTarget = CameraProbeActivity.oppositeProductionProfile(parkingSource);
        assertNotNull(parkingTarget);
        int parkingExpected = CameraCalibrationPreset.parkingMirrorTarget(
                ParkingCameraProfile.of(ParkingCameraProfile.FL));
        assertEquals(parkingExpected, ((CameraProfileId.Parking) parkingTarget).getView().ordinal());
        assertEquals("Перед-право", CameraProbeActivity.productionProfileLabel(parkingTarget, false));
        assertEquals("Front right", CameraProbeActivity.productionProfileLabel(parkingTarget, true));

        for (ReverseSource source : new ReverseSource[]{ReverseSource.Rear, ReverseSource.Front}) {
            CameraProfileId reverseSource = new CameraProfileId.Reverse(ReverseElement.RearLeft, source);
            CameraProfileId reverseTarget = CameraProbeActivity.oppositeProductionProfile(reverseSource);
            assertNotNull(reverseTarget);
            assertEquals(ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX,
                    reverseElementIndex(((CameraProfileId.Reverse) reverseTarget).getElement()));
            assertEquals(ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX,
                    CameraCalibrationPreset.reverseMirrorTarget(
                            ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX));
            if (source == ReverseSource.Rear) {
                assertEquals("Задня права", CameraProbeActivity.productionProfileLabel(reverseTarget, false));
                assertEquals("Rear right", CameraProbeActivity.productionProfileLabel(reverseTarget, true));
            } else {
                assertEquals("Передня права", CameraProbeActivity.productionProfileLabel(reverseTarget, false));
                assertEquals("Front right", CameraProbeActivity.productionProfileLabel(reverseTarget, true));
            }
        }

        CameraProfileId centralRear = new CameraProfileId.Reverse(
                ReverseElement.Rear, ReverseSource.Rear);
        CameraProfileId centralFront = CameraProbeActivity.oppositeProductionProfile(centralRear);
        assertNotNull(centralFront);
        assertEquals(ReverseElement.Rear,
                ((CameraProfileId.Reverse) centralFront).getElement());
        assertEquals(ReverseSource.Front,
                ((CameraProfileId.Reverse) centralFront).getSource());
        assertEquals("Передня", CameraProbeActivity.productionProfileLabel(centralFront, false));
        assertEquals("Front", CameraProbeActivity.productionProfileLabel(centralFront, true));
        assertEquals(null, CameraProbeActivity.oppositeProductionProfile(centralFront));
    }

    @Test
    public void staleMusicWriteCannotReplaceCurrentError() {
        java.util.List<String> errors = new java.util.ArrayList<>();
        MusicMetadataRuntime runtime = new MusicMetadataRuntime(null, null,
                (kind, fields) -> {
                    if ("music_metadata_error".equals(kind)) errors.add((String) fields[1]);
                }, (reason, action) -> action.run(), null, null);
        MusicMetadataRuntime.Snapshot song =
                new MusicMetadataRuntime.Snapshot("player", "Song", "Artist", 60_000, 0, false);
        MusicMetadataRuntime.WriteRequest current =
                MusicMetadataRuntime.WriteRequest.publish(0, "current", song, false, null);
        MusicMetadataRuntime.WriteRequest old =
                MusicMetadataRuntime.WriteRequest.publish(-1, "old", song, false, null);

        runtime.finishWrite(current, "current failure", null);
        assertEquals(java.util.List.of("metadata_write: current failure"), errors);
        runtime.finishWrite(old, "obsolete failure", null);
        runtime.finishWrite(old, "", null);
        // Repeating the same current failure must stay deduplicated: neither stale
        // success nor stale failure may clear/replace the actual retained error.
        runtime.finishWrite(current, "current failure", null);
        assertEquals(java.util.List.of("metadata_write: current failure"), errors);

        runtime.finishWrite(current, "", null);
        runtime.finishWrite(current, "current failure", null);
        assertEquals(2, errors.size());
        // No write is submitted, so the lazy writer executor starts no thread.
    }

    @Test
    public void dropdownSelectionKeepsFeedbackOnPersistentFieldWithoutDelayingAction() throws Exception {
        String primitives = readMain("kotlin/com/byd/extend/ui/UiPrimitives.kt");
        String choice = primitives.substring(primitives.indexOf("internal fun ChoiceField("),
                primitives.indexOf("internal fun NumericSetting("));
        String select = choice.substring(choice.indexOf("val choose = rememberVisualFirstClick {"),
                choice.indexOf("Box(Modifier.fillMaxWidth().height(40.dp)", choice.indexOf("val choose =")));
        assertTrue(select.contains("fieldPress.interactionSource.tryEmit(selectionPress)"));
        assertTrue(select.contains("fieldPress.interactionSource.tryEmit(PressInteraction.Release(selectionPress))"));
        assertTrue(select.indexOf("PressInteraction.Release(selectionPress)") >= 0
                && select.indexOf("onSelect(index)") > select.indexOf("PressInteraction.Release(selectionPress)"));
        assertTrue(select.indexOf("onSelect(index)") >= 0
                && select.indexOf("expanded = false") > select.indexOf("onSelect(index)"));
        assertFalse(select.contains("delay("));
    }

    @Test
    public void productionSourceKeepsLongTopForegroundToastAndOwnerGuards() throws Exception {
        Path main = resolveMainRoot();
        boolean foundShort = false;
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString().toLowerCase(Locale.US);
                        return name.endsWith(".java") || name.endsWith(".kt");
                    })::iterator) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (source.contains("Toast.LENGTH_SHORT")) foundShort = true;
            }
        }
        assertFalse("all Production toasts must use LENGTH_LONG", foundShort);

        String activity = readMain("java/com/byd/extend/CameraProbeActivity.java");
        String toast = activity.substring(activity.indexOf("private void showProductionTopToast"),
                activity.indexOf("private void resetProductionReverseLayout"));
        assertTrue(toast.contains("if (activityDestroyed || !activityResumed) return;"));
        assertTrue(toast.contains("toast.setDuration(Toast.LENGTH_LONG)"));
        assertTrue(toast.contains("toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL"));
        assertTrue(activity.contains("resetProductionReverseLayout(action.getReverseElement())"));
        assertTrue(activity.contains("reverseCompositionStatusProfile()"));
        assertTrue(activity.contains("selectedTab == TAB_REVERSE_CAMERAS"));
        assertTrue(activity.contains("inputGenerations.length == 4 || inputGenerations.length == 5"));

        String helper = readMain("java/com/byd/extend/CameraHelperMain.java");
        assertTrue(helper.contains("updateActivityReverseVisibility("));
        assertTrue(helper.contains("reverse_visibility_update_queued"));
        assertTrue(helper.contains("reverse visibility request is stale"));
        assertTrue(helper.contains("requestId, generations, visibilityMask, widgetVisible"));
        String queuedVisibility = activity.substring(activity.indexOf(
                "private void requestReverseVisibilityUpdate()"),
                activity.indexOf("public String onProductionUiPreview"));
        assertTrue(queuedVisibility.contains("current != helper || !requestedOpen"));
        assertTrue(queuedVisibility.contains("requestId != activeActivityCameraRequestId"));
        assertTrue(queuedVisibility.contains("bundleGenerations != activeActivityInputGenerations"));
        String mirror = activity.substring(activity.indexOf("private TextureView createProductionMirror"),
                activity.indexOf("private CameraDewarpConfig loadProductionCalibrationDewarp"));
        assertTrue(mirror.contains("if (!calibrationHostBundle.acceptMirror(owner, mirror, raw)) return;"));
        assertTrue(mirror.contains("owner.refreshMirrorBuffer(texture, raw)"));

        String visibility = activity.substring(activity.indexOf(
                "public void onProductionReverseVisibilityChanged"),
                activity.indexOf("private void syncProductionReverseCompositionHost"));
        assertTrue(visibility.contains("if (reverseCameraPreview != null)"));
        assertTrue(visibility.contains("reverseCameraPreview.applyVisibility("));
        assertTrue(visibility.contains("reverseCameraPreview.setWidgetVisible("));
        assertTrue(visibility.contains("requestReverseVisibilityUpdate()"));

        String reverseScreen = readMain("kotlin/com/byd/extend/ui/ReverseScreen.kt");
        assertTrue(reverseScreen.contains(
                "val compositionStatusProfile = CameraProfileId.Reverse(ReverseElement.Rear, state.selectedSource)"));
        assertTrue(reverseScreen.contains("profileStatus = compositionStatus"));
        assertTrue(reverseScreen.contains("profileStatus = profile.operation.status"));

    }

    private static String parkingPlacementPrefix(ParkingCameraProfile profile) {
        return "parking_camera_" + profile.wireName.toLowerCase(Locale.US) + "_";
    }

    private static int cameraProfileIndex(CameraProfileId.Blind profile) {
        if (profile.getGroup() == CameraGroup.Rear) {
            return profile.getSide() == CameraSide.Left ? CameraProfile.REAR_LEFT : CameraProfile.REAR_RIGHT;
        }
        return profile.getSide() == CameraSide.Left ? CameraProfile.FRONT_LEFT : CameraProfile.FRONT_RIGHT;
    }

    private static int reverseElementIndex(ReverseElement element) {
        if (element == ReverseElement.Rear) return ReverseCameraLayout.REAR_CAMERA_INDEX;
        if (element == ReverseElement.RearLeft) return ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX;
        if (element == ReverseElement.RearRight) return ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;
        return element == ReverseElement.Widget
                ? ReverseCameraLayout.WIDGET_PANE_ID : ReverseCameraLayout.BACKGROUND_PANE_ID;
    }

    private static String readMain(String relative) throws Exception {
        Path path = Paths.get("src/main", relative);
        if (!Files.exists(path)) path = Paths.get("app/src/main", relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path resolveMainRoot() {
        Path root = Paths.get("src/main");
        return Files.exists(root) ? root : Paths.get("app/src/main");
    }
}
