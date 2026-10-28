package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
public final class CameraFactoryDefaultsTest {
    private Context app;
    private SharedPreferences settings;

    @Before public void clearSettings() {
        app = RuntimeEnvironment.getApplication();
        settings = preferences("settings");
    }

    @Test public void freshInstallSeedsApprovedGeometryWithoutChangingFunctionalDefaults()
            throws Exception {
        CameraFactoryDefaults.initialize(settings, false);
        assertTrue(settings.getBoolean(CameraFactoryDefaults.PREF_INITIALIZED, false));

        CameraProfile rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT);
        DirectCameraCrop blindRaw = DirectCameraCrop.load(settings, rearLeft);
        assertCropGeometry(blindRaw, 0.08894998f, 0.21130778f, 0.59938854f, 0.5773845f);
        assertEquals(DirectCameraCrop.ASPECT_FREE, blindRaw.aspectMode);
        assertEquals(32, blindRaw.rotationDegrees);
        assertEquals(CameraRotation.MODE_FILL, blindRaw.rotationMode);
        assertTrue(blindRaw.mirrorHorizontally);
        assertCropGeometry(DirectCameraCrop.loadCorrected(settings, rearLeft, blindRaw),
                0.0022686953f, 0.07893269f, 0.6590216f, 0.72866434f);
        assertDewarp(CameraDewarpConfig.loadForProfile(settings, rearLeft),
                true, 165, CameraDewarpConfig.PROJECTION_CYLINDRICAL, 100, Float.NaN);
        assertEquals(1.6173527f,
                BlindSpotOverlayController.readFrameAspect(settings, rearLeft), 0.00001f);
        CameraProfile rearRight = CameraProfile.of(CameraProfile.REAR_RIGHT);
        assertEquals(-32, DirectCameraCrop.load(settings, rearRight).rotationDegrees);
        CameraProfile frontLeft = CameraProfile.of(CameraProfile.FRONT_LEFT);
        assertDewarp(CameraDewarpConfig.loadForProfile(settings, frontLeft),
                true, 130, CameraDewarpConfig.PROJECTION_RECTILINEAR, 100, Float.NaN);

        assertPlacement(0.0125f, 0.09166667f, 0.3f, 0.32962963f,
                BlindSpotOverlayController.readPlacement(settings, rearLeft,
                        CameraDisplayTarget.TABLET, 1920, 1080, 24, 54, 132));
        assertPlacement(0.0f, 0.25f, 0.3f, 0.5f,
                BlindSpotOverlayController.readPlacement(settings, rearLeft,
                        CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0));
        assertEquals(CameraDisplayTarget.TABLET,
                BlindSpotOverlayController.readTarget(settings, rearLeft));

        RearviewMirrorSettings.Settings mirror = new RearviewMirrorSettings(settings).load();
        assertEquals(RearviewMirrorSettings.TARGET_TABLET, mirror.target);
        assertPlacement(0.36f, 0.091f, 0.274f, 0.234f, mirror.placement);
        assertPlacement(0.169f, 0.216f, 0.64200005f, 0.62800003f,
                RearviewMirrorSettings.placement(settings, CameraDisplayTarget.CLUSTER));
        assertEquals(36.0f, settings.getFloat(RearviewMirrorSettings.PREF_X, -1.0f), 0.00001f);
        assertEquals(9.099999f, settings.getFloat(RearviewMirrorSettings.PREF_Y, -1.0f), 0.00001f);
        assertEquals(27.4f, settings.getFloat(RearviewMirrorSettings.PREF_WIDTH, -1.0f), 0.00001f);
        assertEquals(23.4f, settings.getFloat(RearviewMirrorSettings.PREF_HEIGHT, -1.0f), 0.00001f);
        assertMirrorCalibration(mirror.calibration,
                0.12207357f, 0.005077227f, 0.7709032f, 0.56080025f,
                false, 140, CameraDewarpConfig.PROJECTION_RECTILINEAR, true,
                CameraRotation.MODE_FILL);
        assertPlacement(0.0f, 0.0f, 0.9931455f, 0.99580345f,
                mirror.calibration.corrected);
        assertMirrorCalibration(mirror.frontCalibration,
                0.12207357f, 0.09530277f, 0.7709032f, 0.7262138f,
                false, 103, CameraDewarpConfig.PROJECTION_CYLINDRICAL, false,
                CameraRotation.MODE_FILL);
        assertPlacement(0.0f, 0.0f, 0.9931455f, 0.99580345f,
                mirror.frontCalibration.corrected);

        ReverseCameraLayout rearLayout = ReverseCameraController.loadRawLayout(settings);
        assertRect(0.43f, 0.0f, 0.57f, 0.79f,
                rearLayout.rectFor(ReverseCameraLayout.REAR_CAMERA_INDEX,
                        CameraDisplayTarget.TABLET));
        assertRect(0.21756601f, 0.11205655f, 0.564868f, 0.7758869f,
                rearLayout.rectFor(ReverseCameraLayout.REAR_CAMERA_INDEX,
                        CameraDisplayTarget.CLUSTER));
        assertRect(0.42398763f, 0.0f, 0.5745265f, 1.0f, rearLayout.background);
        assertRect(0.6855f, 0.7960598f, 0.05931156f, 0.20394021f, rearLayout.widget);
        assertRect(0.46f, 0.22f, 0.08f, 0.63f,
                rearLayout.rectFor(ReverseCameraLayout.WIDGET_PANE_ID,
                        CameraDisplayTarget.CLUSTER));
        assertEquals(CameraDisplayTarget.TABLET,
                rearLayout.targetFor(ReverseCameraLayout.REAR_CAMERA_INDEX));
        assertRect(0.06f, 0.0f, 0.88f, 0.85f,
                rearLayout.pane(ReverseCameraLayout.REAR_CAMERA_INDEX).sourceCrop);
        assertDewarp(CameraDewarpConfig.loadForReverse(settings,
                        ReverseCameraLayout.REAR_CAMERA_INDEX),
                false, 170, CameraDewarpConfig.PROJECTION_CYLINDRICAL, 100, Float.NaN);
        ReverseCameraLayout frontLayout = ReverseCameraController.loadFrontRawLayout(settings);
        assertRect(0.06f, 0.0f, 0.88f, 0.85f,
                frontLayout.pane(ReverseCameraLayout.REAR_CAMERA_INDEX).sourceCrop);
        assertDewarp(CameraDewarpConfig.loadForReverseFront(settings,
                        ReverseCameraLayout.REAR_CAMERA_INDEX),
                false, 170, CameraDewarpConfig.PROJECTION_CYLINDRICAL, 100, Float.NaN);
        assertDewarp(CameraDewarpConfig.loadForReverseFront(settings,
                        ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX),
                true, 130, CameraDewarpConfig.PROJECTION_RECTILINEAR, 50, Float.NaN);
        assertRect(2.9802322e-8f, 0.25f, 0.384127f, 0.55f,
                rearLayout.pane(ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX).sourceCrop);
        assertDewarp(CameraDewarpConfig.loadForReverse(settings,
                        ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX),
                true, 163, CameraDewarpConfig.PROJECTION_CYLINDRICAL, 100, 163.0f);

        assertFalse(settings.contains(BlindSpotOverlayController.targetKey(
                CameraProfile.of(CameraProfile.FRONT_LEFT))));
        assertEquals(CameraDisplayTarget.TABLET,
                BlindSpotOverlayController.readTarget(settings,
                        CameraProfile.of(CameraProfile.FRONT_LEFT)));
        assertFalse(settings.contains(RearviewMirrorSettings.PREF_TARGET));
        assertFalse(settings.contains(ReverseCameraController.displayTargetKey(
                ReverseCameraLayout.REAR_CAMERA_INDEX)));
        assertFalse(settings.contains(CameraBufferQuality.PREF_QUALITY));
        assertEquals(CameraBufferQuality.PERFORMANCE, CameraBufferQuality.load(settings));
        assertFalse(settings.contains(BlindSpotOverlayController.PREF_ENABLED));
        assertFalse(settings.contains(BlindSpotOverlayController.PREF_FRONT_ENABLED));
        assertEquals(BlindSpotOverlayController.DEFAULT_MIN_SPEED_KPH,
                settings.getInt(BlindSpotOverlayController.PREF_MIN_SPEED,
                        BlindSpotOverlayController.DEFAULT_MIN_SPEED_KPH));
        assertTrue(settings.getBoolean(BlindSpotOverlayController.PREF_FRONT_TURN_REQUIRED, true));
        assertFalse(settings.contains(ReverseCameraController.PREF_ENABLED));
        assertFalse(mirror.enabled);
        assertFalse(mirror.frontIntegrated);
        assertEquals(0, mirror.borderDp);
        assertFalse(settings.getBoolean("parking_camera_scale_sync", false));
        assertTrue(settings.getAll().keySet().stream()
                .noneMatch(key -> key.startsWith("parking_")));

        settings.edit().putFloat(DirectCameraCrop.PREF_LEFT_X, 0.2f).commit();
        Map<String, ?> edited = new HashMap<>(settings.getAll());
        CameraFactoryDefaults.initialize(settings, false);
        assertEquals(0.2f, DirectCameraCrop.load(settings, rearLeft).left, 0.00001f);
        assertEquals(edited, settings.getAll());
    }

    @Test public void persistedFactoryAndUserEditsSurviveAnUncachedPreferencesReload()
            throws Exception {
        CameraFactoryDefaults.initialize(settings, false);
        settings.edit().putFloat(DirectCameraCrop.PREF_LEFT_X, 0.21f).commit();
        File file = new File(app.getApplicationInfo().dataDir, "shared_prefs/settings.xml");
        assertTrue(file.isFile());
        Constructor<?> constructor = Class.forName("android.app.SharedPreferencesImpl")
                .getDeclaredConstructor(File.class, int.class);
        constructor.setAccessible(true);
        SharedPreferences reopened = (SharedPreferences) constructor.newInstance(file, 0);
        assertEquals(settings.getAll(), reopened.getAll());
        CameraFactoryDefaults.initialize(reopened, false);
        assertEquals(settings.getAll(), reopened.getAll());
        assertEquals(0.21f, DirectCameraCrop.load(reopened,
                CameraProfile.of(CameraProfile.REAR_LEFT)).left, 0.00001f);
    }

    @Test public void existingEmptySparseAndFullStoresKeepTheirOldEffectiveDefaults()
            throws Exception {
        SharedPreferences empty = preferences("existing-empty");
        CameraFactoryDefaults.initialize(empty, true);
        assertEquals(1, empty.getAll().size());
        assertOldFallbacks(empty);

        SharedPreferences sparse = preferences("existing-sparse");
        CameraProfile rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT);
        sparse.edit().putFloat(DirectCameraCrop.preferenceKey(rearLeft, 0), 0.2f).commit();
        CameraFactoryDefaults.initialize(sparse, true);
        assertEquals(0.2f, DirectCameraCrop.load(sparse, rearLeft).left, 0.00001f);
        assertEquals(-30, DirectCameraCrop.load(sparse, rearLeft).rotationDegrees);
        assertFalse(sparse.contains(DirectCameraCrop.preferenceKey(rearLeft, 5)));
        assertOldFallbacks(sparse);

        SharedPreferences full = preferences("existing-full");
        JSONObject oldV6 = new JSONObject(CameraSettingsTransfer.exportCameraPreset(
                full, geometry(1920, 1080, 1920, 720))).put("version", 6);
        oldV6.getJSONObject("settings").remove(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY);
        CameraSettingsTransfer.applyCameraPreset(full,
                CameraSettingsTransfer.parseCameraPreset(oldV6.toString()));
        Map<String, ?> before = new HashMap<>(full.getAll());
        CameraFactoryDefaults.initialize(full, true);
        assertEquals(before.size() + 1, full.getAll().size());
        for (Map.Entry<String, ?> entry : before.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue(), full.getAll().get(entry.getKey()));
        }
        assertOldFallbacks(full);

        full.edit().putInt(DirectCameraCrop.preferenceKey(rearLeft, 5), 17).commit();
        Map<String, ?> afterEdit = new HashMap<>(full.getAll());
        CameraFactoryDefaults.initialize(full, false);
        assertEquals(afterEdit, full.getAll());
    }

    @Test public void versionSixTransferKeepsNormalizedGeometryAcrossDisplaySizes()
            throws Exception {
        SharedPreferences source = preferences("v6-source");
        CameraFactoryDefaults.initialize(source, false);
        JSONObject oldV6 = new JSONObject(CameraSettingsTransfer.exportCameraPreset(
                source, geometry(1920, 1080, 1920, 720))).put("version", 6);
        oldV6.getJSONObject("settings").remove(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY);

        SharedPreferences imported = preferences("v6-imported");
        imported.edit().putBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, true).commit();
        CameraSettingsTransfer.applyCameraPreset(imported,
                CameraSettingsTransfer.parseCameraPreset(oldV6.toString()));
        imported = app.getSharedPreferences("v6-imported", Context.MODE_PRIVATE);
        assertTrue(imported.getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, false));

        JSONObject first = new JSONObject(CameraSettingsTransfer.exportCameraPreset(
                imported, geometry(1920, 1080, 1920, 720))).getJSONObject("settings");
        JSONObject resized = new JSONObject(CameraSettingsTransfer.exportCameraPreset(
                imported, geometry(1024, 768, 1280, 480))).getJSONObject("settings");
        for (String key : new String[]{
                "camera_left_tablet_x", "camera_left_tablet_y",
                "camera_left_tablet_width", "camera_left_tablet_height",
                "camera_left_cluster_x", "camera_left_cluster_y",
                "camera_left_cluster_width", "camera_left_cluster_height",
                "camera_front_left_tablet_x", "camera_front_left_cluster_y",
                "mirror_tablet_x", "mirror_tablet_y", "mirror_cluster_x",
                "mirror_cluster_width", "reverse_camera_1_left",
                "reverse_camera_1_width", "reverse_camera_element_1_cluster_left",
                "reverse_camera_element_1_cluster_height"}) {
            assertEquals(key, first.getDouble(key), resized.getDouble(key), 0.00001d);
        }
        assertEquals(36.0d, first.getDouble("mirror_x"), 0.00001d);
        assertEquals(16.9d, first.getDouble("mirror_cluster_x"), 0.00001d);
        assertEquals("Tablet", first.getString(RearviewMirrorSettings.PREF_TARGET));
        assertEquals(CameraDisplayTarget.TABLET,
                first.getInt(BlindSpotOverlayController.targetKey(
                        CameraProfile.of(CameraProfile.FRONT_LEFT))));
        assertEquals(103, first.getInt("mirror_front_fov"));
        assertEquals(CameraDewarpConfig.PROJECTION_CYLINDRICAL,
                first.getInt("mirror_front_projection"));
        assertFalse(first.getBoolean("mirror_front_correction"));
        assertEquals(170, first.getInt("camera_dewarp_v3_reverse_front_1_fov"));
        assertEquals(CameraDewarpConfig.PROJECTION_CYLINDRICAL,
                first.getInt("camera_dewarp_v3_reverse_front_1_projection"));
        assertFalse(first.getBoolean("camera_dewarp_v3_reverse_front_1_enabled"));
        assertEquals(7, new JSONObject(CameraSettingsTransfer.exportCameraPreset(
                imported, geometry(1024, 768, 1280, 480))).getInt("version"));
    }

    @Test public void helperInitialSnapshotAndReattachUseRealFactoryPreferences() {
        CameraFactoryDefaults.initialize(settings, false);
        CameraRuntimePreferences helper = new CameraRuntimePreferences();
        helper.reconcile(CameraRuntimePreferences.snapshot(settings), true);
        CameraProfile rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT);
        assertEquals(0.08894998f, DirectCameraCrop.load(helper, rearLeft).left, 0.00001f);

        helper.edit().putFloat(DirectCameraCrop.PREF_LEFT_X, 0.21f).apply();
        helper.reconcile(CameraRuntimePreferences.snapshot(settings), false);
        assertEquals(0.21f, DirectCameraCrop.load(helper, rearLeft).left, 0.00001f);
        assertEquals(0.08894998f, DirectCameraCrop.load(settings, rearLeft).left, 0.00001f);
    }

    @Test public void blindResetStagesResetOnlyRequestedCalibrationAndProfile() {
        CameraFactoryDefaults.initialize(settings, false);
        DirectCameraCrop parkingRaw = seedParkingSentinel(settings);
        for (CameraProfile profile : CameraProfile.values()) {
            for (CameraCalibrationPreset.Stage stage : CameraCalibrationPreset.Stage.values()) {
                DirectCameraCrop rawUser = customCrop(0.2f, 0.15f, 0.5f, 0.6f,
                        7, CameraRotation.MODE_ALIGNED, false);
                DirectCameraCrop correctedUser = DirectCameraCrop.of(0.1f, 0.16f, 0.7f, 0.7f,
                        DirectCameraCrop.ASPECT_FREE, rawUser.rotationDegrees, rawUser.rotationMode)
                        .withMirrorHorizontally(rawUser.mirrorHorizontally);
                CameraDewarpConfig dewarpUser = CameraDewarpConfig.of(
                        CameraDewarpConfig.lensFor(profile), false, 100,
                        CameraDewarpConfig.PROJECTION_RECTILINEAR, 63, 112.5f);
                SharedPreferences.Editor userEditor = settings.edit();
                DirectCameraCrop.write(userEditor, profile, rawUser);
                DirectCameraCrop.writeCorrected(userEditor, profile, correctedUser);
                userEditor.putInt(DirectCameraCrop.correctedAspectKey(profile), correctedUser.aspectMode);
                CameraDewarpConfig.writeForProfile(userEditor, profile, dewarpUser);
                userEditor.apply();
                BlindSpotOverlayController.writePlacement(settings, profile,
                        CameraDisplayTarget.TABLET, CameraPlacement.of(0.1f, 0.1f, 0.3f, 0.3f));
                BlindSpotOverlayController.writePlacement(settings, profile,
                        CameraDisplayTarget.CLUSTER, CameraPlacement.of(0.6f, 0.2f, 0.3f, 0.4f));
                settings.edit().putInt(BlindSpotOverlayController.targetKey(profile),
                                CameraDisplayTarget.CLUSTER)
                        .putInt(BlindSpotOverlayController.PREF_MIN_SPEED, 42)
                        .putBoolean(BlindSpotOverlayController.PREF_ENABLED, true)
                        .apply();
                CameraCalibrationPreset.saveCamera(settings, profile);
                Map<String, Object> savedPreset = withPrefix(settings,
                        "camera_calibration_preset_v1_" + profile.wireName + "_");

                CameraProfile other = CameraProfile.of((profile.id + 1) % CameraProfile.COUNT);
                DirectCameraCrop otherRaw = customCrop(0.3f, 0.2f, 0.5f, 0.6f,
                        -9, CameraRotation.MODE_FIT, true);
                SharedPreferences.Editor otherEditor = settings.edit();
                DirectCameraCrop.write(otherEditor, other, otherRaw);
                otherEditor.apply();

                CameraCalibrationPreset.resetCameraStage(settings, profile, stage);
                assertEquals(savedPreset, withPrefix(settings,
                        "camera_calibration_preset_v1_" + profile.wireName + "_"));
                DirectCameraCrop rawAfter = DirectCameraCrop.load(settings, profile);
                DirectCameraCrop correctedAfter = DirectCameraCrop.loadCorrected(
                        settings, profile, rawAfter);
                CameraDewarpConfig dewarpAfter = CameraDewarpConfig.loadForProfile(settings, profile);
                DirectCameraCrop factoryRaw = CameraFactoryDefaults.blindRaw(profile);
                DirectCameraCrop factoryCorrected = CameraFactoryDefaults.blindCorrected(profile);
                CameraDewarpConfig factoryDewarp = CameraFactoryDefaults.blindDewarp(profile);

                if (stage == CameraCalibrationPreset.Stage.ORIGINAL) {
                    assertCropGeometry(rawAfter, factoryRaw.left, factoryRaw.top,
                            factoryRaw.width, factoryRaw.height);
                    assertCropTransform(rawAfter, rawUser);
                    assertCrop(correctedAfter, correctedUser);
                    assertDewarp(dewarpAfter, dewarpUser);
                } else if (stage == CameraCalibrationPreset.Stage.CORRECTION) {
                    assertCrop(rawAfter, rawUser);
                    assertCropGeometry(correctedAfter, factoryCorrected.left,
                            factoryCorrected.top, factoryCorrected.width, factoryCorrected.height);
                    assertEquals(DirectCameraCrop.ASPECT_FREE, correctedAfter.aspectMode);
                    assertDewarp(dewarpAfter, factoryDewarp);
                    assertFalse(settings.contains("camera_dewarp_v3_overlay_"
                            + profile.wireName + "_fov_precise"));
                } else {
                    assertCropGeometry(rawAfter, rawUser.left, rawUser.top,
                            rawUser.width, rawUser.height);
                    assertCropTransform(rawAfter, factoryRaw);
                    assertCropGeometry(correctedAfter, correctedUser.left, correctedUser.top,
                            correctedUser.width, correctedUser.height);
                    assertEquals(correctedUser.aspectMode, correctedAfter.aspectMode);
                    assertCropTransform(correctedAfter, factoryRaw);
                    assertDewarp(dewarpAfter, dewarpUser);
                }
                assertEquals(profile + " other profile", otherRaw.left,
                        DirectCameraCrop.load(settings, other).left, 0.00001f);
                assertPlacement(0.1f, 0.1f, 0.3f, 0.3f,
                        BlindSpotOverlayController.readPlacement(settings, profile,
                                CameraDisplayTarget.TABLET, 1920, 1080, 24, 54, 132));
                assertPlacement(0.6f, 0.2f, 0.3f, 0.4f,
                        BlindSpotOverlayController.readPlacement(settings, profile,
                                CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0));
                assertEquals(CameraDisplayTarget.CLUSTER,
                        BlindSpotOverlayController.readTarget(settings, profile));
                assertEquals(42, settings.getInt(BlindSpotOverlayController.PREF_MIN_SPEED, -1));
                assertTrue(settings.getBoolean(BlindSpotOverlayController.PREF_ENABLED, false));
                assertTrue(CameraCalibrationPreset.hasCamera(settings, profile));
                assertParkingSentinel(settings, parkingRaw);
            }
        }
    }

    @Test public void blindPlacementResetUsesOnlyTheSelectedDisplaySlot() {
        CameraFactoryDefaults.initialize(settings, false);
        DirectCameraCrop parkingRaw = seedParkingSentinel(settings);
        for (CameraProfile profile : CameraProfile.values()) {
            for (int target : new int[]{CameraDisplayTarget.TABLET, CameraDisplayTarget.CLUSTER}) {
                int otherTarget = target == CameraDisplayTarget.TABLET
                        ? CameraDisplayTarget.CLUSTER : CameraDisplayTarget.TABLET;
                BlindSpotOverlayController.writePlacement(settings, profile,
                        target, CameraPlacement.of(0.1f, 0.1f, 0.4f, 0.4f));
                BlindSpotOverlayController.writePlacement(settings, profile,
                        otherTarget, CameraPlacement.of(0.5f, 0.2f, 0.4f, 0.5f));
                settings.edit().putInt(BlindSpotOverlayController.targetKey(profile), target)
                        .apply();

                CameraCalibrationPreset.resetCameraPlacement(settings, profile, target);
                CameraPlacement expected = CameraFactoryDefaults.blindPlacement(profile, target);
                assertPlacement(expected.x, expected.y, expected.width, expected.height,
                        BlindSpotOverlayController.readPlacement(settings, profile, target,
                                1920, target == CameraDisplayTarget.TABLET ? 1080 : 720,
                                target == CameraDisplayTarget.TABLET ? 24 : 0,
                                target == CameraDisplayTarget.TABLET ? 54 : 0,
                                target == CameraDisplayTarget.TABLET ? 132 : 0));
                assertPlacement(0.5f, 0.2f, 0.4f, 0.5f,
                        BlindSpotOverlayController.readPlacement(settings, profile, otherTarget,
                                1920, 1080, 24, 54, 132));
                assertEquals(target, BlindSpotOverlayController.readTarget(settings, profile));
                assertParkingSentinel(settings, parkingRaw);
            }
        }
    }

    @Test public void mirrorResetStagesAndPlacementPreserveTheOtherSourceAndDisplaySlot() {
        CameraFactoryDefaults.initialize(settings, false);
        DirectCameraCrop parkingRaw = seedParkingSentinel(settings);
        for (boolean front : new boolean[]{false, true}) {
            for (CameraCalibrationPreset.Stage stage : CameraCalibrationPreset.Stage.values()) {
                RearviewMirrorSettings.Calibration current = customMirrorCalibration();
                RearviewMirrorSettings.Calibration otherCurrent = customMirrorCalibration();
                RearviewMirrorSettings.writeCalibration(settings, front, current);
                RearviewMirrorSettings.writeCalibration(settings, !front, otherCurrent);
                RearviewMirrorSettings.writePreset(settings, front, current);
                String presetPrefix = front ? "mirror_front_preset_" : "mirror_preset_";
                Map<String, Object> savedPreset = withPrefix(settings, presetPrefix);
                CameraPlacement tablet = CameraPlacement.of(0.1f, 0.1f, 0.4f, 0.4f);
                CameraPlacement cluster = CameraPlacement.of(0.5f, 0.2f, 0.4f, 0.5f);
                SharedPreferences.Editor placementEditor = settings.edit();
                RearviewMirrorSettings.writePlacement(placementEditor,
                        CameraDisplayTarget.TABLET, tablet);
                RearviewMirrorSettings.writePlacement(placementEditor,
                        CameraDisplayTarget.CLUSTER, cluster);
                placementEditor.apply();
                settings.edit().putString(RearviewMirrorSettings.PREF_TARGET, "Cluster")
                        .putBoolean(RearviewMirrorSettings.PREF_ENABLED, true)
                        .apply();

                RearviewMirrorSettings.Calibration reset = CameraCalibrationPreset.resetMirrorStage(
                        RearviewMirrorSettings.calibration(settings, front), front, stage);
                RearviewMirrorSettings.writeCalibration(settings, front, reset);
                assertEquals(savedPreset, withPrefix(settings, presetPrefix));
                RearviewMirrorSettings.Calibration after =
                        RearviewMirrorSettings.calibration(settings, front);
                RearviewMirrorSettings.Calibration defaults =
                        CameraFactoryDefaults.mirrorCalibration(front);
                if (stage == CameraCalibrationPreset.Stage.ORIGINAL) {
                    assertPlacement(defaults.raw.x, defaults.raw.y,
                            defaults.raw.width, defaults.raw.height, after.raw);
                    assertPlacement(current.corrected.x, current.corrected.y,
                            current.corrected.width, current.corrected.height, after.corrected);
                    assertMirrorOutput(after, current);
                } else if (stage == CameraCalibrationPreset.Stage.CORRECTION) {
                    assertPlacement(current.raw.x, current.raw.y,
                            current.raw.width, current.raw.height, after.raw);
                    assertPlacement(defaults.corrected.x, defaults.corrected.y,
                            defaults.corrected.width, defaults.corrected.height, after.corrected);
                    assertEquals(defaults.enabled, after.enabled);
                    assertEquals(defaults.fovDegrees, after.fovDegrees);
                    assertEquals(defaults.projection, after.projection);
                    assertEquals(defaults.strengthPercent, after.strengthPercent);
                    assertTrue(Float.isNaN(after.preciseFovDegrees));
                    assertFalse(settings.contains((front ? "mirror_front_" : "mirror_")
                            + "fov_precise"));
                    assertMirrorTransform(after, current);
                } else {
                    assertPlacement(current.raw.x, current.raw.y,
                            current.raw.width, current.raw.height, after.raw);
                    assertPlacement(current.corrected.x, current.corrected.y,
                            current.corrected.width, current.corrected.height, after.corrected);
                    assertMirrorControls(after, current);
                    assertMirrorTransform(after, defaults);
                }
                assertPlacement(0.1f, 0.1f, 0.4f, 0.4f,
                        RearviewMirrorSettings.placement(settings, CameraDisplayTarget.TABLET));
                assertPlacement(0.5f, 0.2f, 0.4f, 0.5f,
                        RearviewMirrorSettings.placement(settings, CameraDisplayTarget.CLUSTER));
                assertEquals("Cluster", settings.getString(RearviewMirrorSettings.PREF_TARGET, ""));
                assertTrue(settings.getBoolean(RearviewMirrorSettings.PREF_ENABLED, false));
                assertTrue(RearviewMirrorSettings.preset(settings, front) != null);
                RearviewMirrorSettings.Calibration otherAfter =
                        RearviewMirrorSettings.calibration(settings, !front);
                assertPlacement(otherCurrent.raw.x, otherCurrent.raw.y,
                        otherCurrent.raw.width, otherCurrent.raw.height, otherAfter.raw);
                assertPlacement(otherCurrent.corrected.x, otherCurrent.corrected.y,
                        otherCurrent.corrected.width, otherCurrent.corrected.height,
                        otherAfter.corrected);
                assertMirrorOutput(otherAfter, otherCurrent);
                assertParkingSentinel(settings, parkingRaw);
            }
        }

        for (int target : new int[]{CameraDisplayTarget.TABLET, CameraDisplayTarget.CLUSTER}) {
            int otherTarget = target == CameraDisplayTarget.TABLET
                    ? CameraDisplayTarget.CLUSTER : CameraDisplayTarget.TABLET;
            CameraPlacement other = CameraPlacement.of(0.2f, 0.1f, 0.4f, 0.4f);
            SharedPreferences.Editor placementEditor = settings.edit();
            RearviewMirrorSettings.writePlacement(placementEditor, otherTarget, other);
            placementEditor.apply();
            settings.edit().putString(RearviewMirrorSettings.PREF_TARGET,
                    target == CameraDisplayTarget.CLUSTER ? "Cluster" : "Tablet").apply();
            RearviewMirrorSettings.Settings before = new RearviewMirrorSettings(settings).load();
            RearviewMirrorSettings.Settings updated = new RearviewMirrorSettings.Settings(
                    before.enabled, target, CameraCalibrationPreset.resetMirrorPlacement(target),
                    before.calibration, before.preset, before.borderDp, before.borderArgb,
                    before.manualHidden, before.frontIntegrated, before.showFront,
                    before.frontCalibration, before.frontPreset);
            new RearviewMirrorSettings(settings).save(updated);
            CameraPlacement expected = CameraFactoryDefaults.mirrorPlacement(target);
            assertPlacement(expected.x, expected.y, expected.width, expected.height,
                    RearviewMirrorSettings.placement(settings, target));
            assertPlacement(other.x, other.y, other.width, other.height,
                    RearviewMirrorSettings.placement(settings, otherTarget));
            assertParkingSentinel(settings, parkingRaw);
        }
    }

    @Test public void reverseResetStagesCoverRearAndFrontWithoutChangingComposition() {
        CameraFactoryDefaults.initialize(settings, false);
        DirectCameraCrop parkingRaw = seedParkingSentinel(settings);
        for (boolean front : new boolean[]{false, true}) {
            for (int index = ReverseCameraLayout.REAR_CAMERA_INDEX;
                    index <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX; index++) {
                for (CameraCalibrationPreset.Stage stage : CameraCalibrationPreset.Stage.values()) {
                    ReverseCameraLayout layout = ReverseCameraController.loadRawLayout(settings);
                    layout = ReverseCameraLayout.withRect(layout, index,
                            CameraDisplayTarget.TABLET,
                            ReverseCameraLayout.destination(0.1f, 0.1f, 0.3f, 0.4f));
                    layout = ReverseCameraLayout.withRect(layout, index,
                            CameraDisplayTarget.CLUSTER,
                            ReverseCameraLayout.destination(0.6f, 0.2f, 0.3f, 0.5f));
                    ReverseCameraController.saveCompositionLayout(settings, layout);
                    settings.edit().putBoolean(ReverseCameraController.PREF_ENABLED, true)
                            .putBoolean(ReverseCameraController.visibilityKey(index), false)
                            .putInt(ReverseCameraController.paneSettingKey(index,
                                    "rotation_degrees"), 9)
                            .putInt(ReverseCameraController.frontPaneSettingKey(index,
                                    "rotation_degrees"), 11)
                            .apply();
                    if (front) seedReverseFront(settings, index);
                    else seedReverseRear(settings, index);
                    if (front) CameraCalibrationPreset.saveReverseFront(settings, index);
                    else CameraCalibrationPreset.saveReverse(settings, index);
                    String presetPrefix = front
                            ? "reverse_front_calibration_preset_v1_" + index + "_"
                            : "reverse_calibration_preset_v1_" + index + "_";
                    Map<String, Object> savedPreset = withPrefix(settings, presetPrefix);

                    ReverseCameraController.saveElementTarget(settings, index,
                            CameraDisplayTarget.CLUSTER);
                    ReverseCameraLayout before = ReverseCameraController.loadRawLayout(settings);
                    CameraCalibrationPreset.resetReverseStage(settings, index, front, stage);
                    assertEquals(savedPreset, withPrefix(settings, presetPrefix));
                    ReverseCameraLayout after = ReverseCameraController.loadRawLayout(settings);
                    assertRect(before.rectFor(index, CameraDisplayTarget.TABLET),
                            after.rectFor(index, CameraDisplayTarget.TABLET));
                    assertRect(before.rectFor(index, CameraDisplayTarget.CLUSTER),
                            after.rectFor(index, CameraDisplayTarget.CLUSTER));
                    assertEquals(CameraDisplayTarget.CLUSTER, after.targetFor(index));
                    assertFalse(ReverseCameraController.loadVisibility(settings, index));
                    assertTrue(settings.getBoolean(ReverseCameraController.PREF_ENABLED, false));
                    assertTrue(front ? CameraCalibrationPreset.hasReverseFront(settings, index)
                            : CameraCalibrationPreset.hasReverse(settings, index));
                    assertReverseStage(settings, index, front, stage);
                    assertParkingSentinel(settings, parkingRaw);
                }
            }
        }
    }

    @Test public void reverseLayoutResetUsesSelectedDisplayAndLeavesOtherSlotUntouched() {
        CameraFactoryDefaults.initialize(settings, false);
        DirectCameraCrop parkingRaw = seedParkingSentinel(settings);
        int[] panes = {ReverseCameraLayout.BACKGROUND_PANE_ID,
                ReverseCameraLayout.WIDGET_PANE_ID, ReverseCameraLayout.REAR_CAMERA_INDEX,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX};
        String[] elements = {"Background", "Widget", "Rear", "RearLeft", "RearRight"};
        for (int i = 0; i < panes.length; i++) {
            for (int target : new int[]{CameraDisplayTarget.TABLET, CameraDisplayTarget.CLUSTER}) {
                int otherTarget = target == CameraDisplayTarget.TABLET
                        ? CameraDisplayTarget.CLUSTER : CameraDisplayTarget.TABLET;
                ReverseCameraLayout layout = ReverseCameraController.loadRawLayout(settings);
                layout = ReverseCameraLayout.withRect(layout, panes[i], target,
                        compositionRect(panes[i], 0.1f, 0.1f));
                layout = ReverseCameraLayout.withRect(layout, panes[i], otherTarget,
                        compositionRect(panes[i], 0.5f, 0.2f));
                ReverseCameraController.saveCompositionLayout(settings, layout);
                if (panes[i] < 0) CameraCalibrationPreset.saveReverseElement(settings, panes[i]);
                else CameraCalibrationPreset.saveReverse(settings, panes[i]);
                Map<String, Object> savedPresets = withPrefix(settings,
                        "reverse_calibration_preset_v1_");
                ReverseCameraController.saveElementTarget(settings, panes[i], target);
                ReverseCameraLayout before = ReverseCameraController.loadRawLayout(settings);
                ReverseCameraController.resetSelectedLayout(settings, elements[i], target);
                assertEquals(savedPresets, withPrefix(settings,
                        "reverse_calibration_preset_v1_"));
                assertTrue(panes[i] < 0
                        ? CameraCalibrationPreset.hasReverseElement(settings, panes[i])
                        : CameraCalibrationPreset.hasReverse(settings, panes[i]));
                ReverseCameraLayout after = ReverseCameraController.loadRawLayout(settings);
                ReverseCameraLayout.Rect expected = panes[i] == ReverseCameraLayout.BACKGROUND_PANE_ID
                        ? CameraFactoryDefaults.reverseBackground(target)
                        : panes[i] == ReverseCameraLayout.WIDGET_PANE_ID
                        ? CameraFactoryDefaults.reverseWidget(target)
                        : CameraFactoryDefaults.reverseDestination(panes[i], target);
                assertRect(expected, after.rectFor(panes[i], target));
                assertRect(before.rectFor(panes[i], otherTarget),
                        after.rectFor(panes[i], otherTarget));
                assertParkingSentinel(settings, parkingRaw);
            }
        }
    }

    private SharedPreferences preferences(String name) {
        SharedPreferences result = app.getSharedPreferences(name, Context.MODE_PRIVATE);
        result.edit().clear().commit();
        return result;
    }

    private static DirectCameraCrop seedParkingSentinel(SharedPreferences preferences) {
        ParkingCameraProfile profile = ParkingCameraProfile.of(ParkingCameraProfile.FL);
        DirectCameraCrop raw = customCrop(0.1f, 0.12f, 0.6f, 0.7f,
                11, CameraRotation.MODE_ALIGNED, true);
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean("parking_camera_scale_sync", true);
        DirectCameraCrop.write(editor, profile, raw);
        editor.apply();
        return raw;
    }

    private static void assertParkingSentinel(
            SharedPreferences preferences, DirectCameraCrop expectedRaw) {
        ParkingCameraProfile profile = ParkingCameraProfile.of(ParkingCameraProfile.FL);
        assertCrop(expectedRaw, DirectCameraCrop.load(preferences, profile));
        assertTrue(preferences.getBoolean("parking_camera_scale_sync", false));
    }

    private static Map<String, Object> withPrefix(
            SharedPreferences preferences, String prefix) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix)) result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static void assertOldFallbacks(SharedPreferences preferences) {
        CameraProfile rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT);
        assertEquals(-30, DirectCameraCrop.load(preferences, rearLeft).rotationDegrees);
        assertEquals(100, RearviewMirrorSettings.calibration(preferences, true).fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_RECTILINEAR,
                RearviewMirrorSettings.calibration(preferences, true).projection);
        assertEquals(100, CameraDewarpConfig.loadForReverseFront(preferences,
                ReverseCameraLayout.REAR_CAMERA_INDEX).fovDegrees);
        assertEquals(CameraDewarpConfig.PROJECTION_RECTILINEAR,
                CameraDewarpConfig.loadForReverseFront(preferences,
                        ReverseCameraLayout.REAR_CAMERA_INDEX).projection);
        assertRect(0.43216026f, 0.0015433729f, 0.564868f, 0.7758869f,
                ReverseCameraController.loadRawLayout(preferences).rectFor(
                        ReverseCameraLayout.REAR_CAMERA_INDEX, CameraDisplayTarget.TABLET));
    }

    private static CameraSettingsTransfer.GeometryResolver geometry(
            int tabletWidth, int tabletHeight, int clusterWidth, int clusterHeight) {
        return target -> target == CameraDisplayTarget.TABLET
                ? new CameraSettingsTransfer.DisplayGeometry(
                        tabletWidth, tabletHeight, 24, 54, 132)
                : new CameraSettingsTransfer.DisplayGeometry(
                        clusterWidth, clusterHeight, 0, 0, 0);
    }

    private static void assertReverseStage(SharedPreferences preferences,
            int index, boolean front, CameraCalibrationPreset.Stage stage) {
        ReverseCameraLayout layout = front
                ? ReverseCameraController.loadFrontRawLayout(preferences)
                : ReverseCameraController.loadRawLayout(preferences);
        ReverseCameraLayout.Rect raw = layout.pane(index).sourceCrop;
        ReverseCameraLayout.Rect corrected = front
                ? ReverseCameraController.loadFrontCorrectedSourceCrop(preferences, index)
                : ReverseCameraController.loadCorrectedSourceCrop(preferences, index, raw);
        CameraDewarpConfig dewarp = front
                ? CameraDewarpConfig.loadForReverseFront(preferences, index)
                : CameraDewarpConfig.loadForReverse(preferences, index);
        int rotation = front
                ? preferences.getInt(ReverseCameraController.frontPaneSettingKey(
                        index, "rotation_degrees"), 0)
                : preferences.getInt(ReverseCameraController.paneSettingKey(
                        index, "rotation_degrees"), 0);
        int mode = front
                ? preferences.getInt(ReverseCameraController.frontPaneSettingKey(
                        index, "display_mode"), -1)
                : preferences.getInt(ReverseCameraController.displayModeKey(index), -1);
        boolean mirror = front
                ? preferences.getBoolean(ReverseCameraController.frontPaneSettingKey(index,
                        "mirror"), false)
                : preferences.getBoolean(ReverseCameraController.mirrorKey(index), false);
        ReverseCameraLayout.Rect rawUser = source(0.15f, 0.16f, 0.6f, 0.65f);
        ReverseCameraLayout.Rect correctedUser = source(0.11f, 0.12f, 0.7f, 0.7f);
        String prefix = front ? "camera_dewarp_v3_reverse_front_"
                : "camera_dewarp_v3_reverse_";
        CameraDewarpConfig dewarpUser = CameraDewarpConfig.of(
                front ? CameraDewarpConfig.lensForReverseFrontCamera(index)
                        : CameraDewarpConfig.lensForReverseCamera(index),
                false, 100, CameraDewarpConfig.PROJECTION_RECTILINEAR, 61, 111.5f);
        if (stage == CameraCalibrationPreset.Stage.ORIGINAL) {
            assertRect(CameraFactoryDefaults.reverseRaw(index, front), raw);
            assertRect(correctedUser, corrected);
            assertDewarp(dewarp, dewarpUser);
            assertEquals(front ? 11 : 9, rotation);
            assertEquals(ReverseCameraLayout.DISPLAY_MODE_STRETCH, mode);
            assertEquals(!front, mirror);
        } else if (stage == CameraCalibrationPreset.Stage.CORRECTION) {
            assertRect(rawUser, raw);
            assertRect(CameraFactoryDefaults.reverseCorrected(index, front), corrected);
            assertDewarp(dewarp, CameraFactoryDefaults.reverseDewarp(index, front));
            assertEquals(!Float.isNaN(CameraFactoryDefaults.reverseDewarp(index, front).preciseFovDegrees),
                    preferences.contains(prefix + index + "_fov_precise"));
            assertEquals(front ? 11 : 9, rotation);
            assertEquals(ReverseCameraLayout.DISPLAY_MODE_STRETCH, mode);
            assertEquals(!front, mirror);
        } else {
            assertRect(rawUser, raw);
            assertRect(correctedUser, corrected);
            assertDewarp(dewarp, dewarpUser);
            assertEquals(CameraFactoryDefaults.reverseRotationDegrees(index, front), rotation);
            assertEquals(CameraFactoryDefaults.reverseDisplayMode(index, front), mode);
            assertEquals(CameraFactoryDefaults.reverseMirrored(index, front), mirror);
        }
    }

    private static void seedReverseRear(
            SharedPreferences preferences, int index) {
        SharedPreferences.Editor editor = preferences.edit();
        ReverseCameraController.writeSourceCrop(editor, index,
                source(0.15f, 0.16f, 0.6f, 0.65f), false);
        ReverseCameraController.writeSourceCrop(editor, index,
                source(0.11f, 0.12f, 0.7f, 0.7f), true);
        editor.putInt(ReverseCameraController.paneSettingKey(index,
                        "rotation_degrees"), 9)
                .putInt(ReverseCameraController.displayModeKey(index),
                        ReverseCameraLayout.DISPLAY_MODE_STRETCH)
                .putBoolean(ReverseCameraController.mirrorKey(index), true);
        CameraDewarpConfig.writeForReverse(editor, index, CameraDewarpConfig.of(
                CameraDewarpConfig.lensForReverseCamera(index), false, 100,
                CameraDewarpConfig.PROJECTION_RECTILINEAR, 61, 111.5f));
        editor.apply();
    }

    private static void seedReverseFront(
            SharedPreferences preferences, int index) {
        SharedPreferences.Editor editor = preferences.edit();
        ReverseCameraController.writeFrontSourceCrop(editor, index,
                source(0.15f, 0.16f, 0.6f, 0.65f), false);
        ReverseCameraController.writeFrontSourceCrop(editor, index,
                source(0.11f, 0.12f, 0.7f, 0.7f), true);
        editor.putInt(ReverseCameraController.frontPaneSettingKey(index,
                        "rotation_degrees"), 11)
                .putInt(ReverseCameraController.frontPaneSettingKey(index,
                        "display_mode"), ReverseCameraLayout.DISPLAY_MODE_STRETCH)
                .putBoolean(ReverseCameraController.frontPaneSettingKey(index,
                        "mirror"), false);
        CameraDewarpConfig.writeForReverseFront(editor, index, CameraDewarpConfig.of(
                CameraDewarpConfig.lensForReverseFrontCamera(index), false, 100,
                CameraDewarpConfig.PROJECTION_RECTILINEAR, 61, 111.5f));
        editor.apply();
    }

    private static ReverseCameraLayout.Rect compositionRect(int paneId, float x, float y) {
        return paneId == ReverseCameraLayout.WIDGET_PANE_ID
                ? ReverseCameraLayout.widgetDestination(x, y, 0.2f, 0.3f)
                : ReverseCameraLayout.destination(x, y, 0.2f, 0.3f);
    }

    private static RearviewMirrorSettings.Calibration customMirrorCalibration() {
        return new RearviewMirrorSettings.Calibration(
                CameraPlacement.source(0.1f, 0.1f, 0.7f, 0.7f),
                CameraPlacement.source(0.12f, 0.14f, 0.65f, 0.7f),
                true, 111, CameraDewarpConfig.PROJECTION_RECTILINEAR,
                false, 21, CameraRotation.MODE_ALIGNED, 47, 111.5f);
    }

    private static void assertMirrorCalibration(RearviewMirrorSettings.Calibration value,
            float x, float y, float width, float height, boolean enabled,
            int fov, int projection, boolean mirrored, int outputMode) {
        assertPlacement(x, y, width, height, value.raw);
        assertEquals(enabled, value.enabled);
        assertEquals(fov, value.fovDegrees);
        assertEquals(projection, value.projection);
        assertEquals(mirrored, value.mirrored);
        assertEquals(0, value.rotationDegrees);
        assertEquals(100, value.strengthPercent);
        assertEquals(outputMode, value.rotationMode);
    }

    private static void assertMirrorOutput(RearviewMirrorSettings.Calibration actual,
            RearviewMirrorSettings.Calibration expected) {
        assertMirrorTransform(actual, expected);
        assertMirrorControls(actual, expected);
    }

    private static void assertMirrorControls(RearviewMirrorSettings.Calibration actual,
            RearviewMirrorSettings.Calibration expected) {
        assertEquals(expected.enabled, actual.enabled);
        assertEquals(expected.fovDegrees, actual.fovDegrees);
        assertEquals(expected.projection, actual.projection);
        assertEquals(expected.strengthPercent, actual.strengthPercent);
        assertEquals(expected.preciseFovDegrees, actual.preciseFovDegrees, 0.00001f);
    }

    private static void assertMirrorTransform(RearviewMirrorSettings.Calibration actual,
            RearviewMirrorSettings.Calibration expected) {
        assertEquals(expected.mirrored, actual.mirrored);
        assertEquals(expected.rotationDegrees, actual.rotationDegrees);
        assertEquals(expected.rotationMode, actual.rotationMode);
    }

    private static DirectCameraCrop customCrop(float left, float top,
            float width, float height, int rotation, int mode, boolean mirror) {
        return DirectCameraCrop.of(left, top, width, height,
                DirectCameraCrop.ASPECT_FOUR_THREE, rotation, mode)
                .withMirrorHorizontally(mirror);
    }

    private static ReverseCameraLayout.Rect source(
            float left, float top, float width, float height) {
        return ReverseCameraLayout.sourceCrop(left, top, width, height);
    }

    private static void assertCrop(DirectCameraCrop actual, DirectCameraCrop expected) {
        assertCropGeometry(actual, expected.left, expected.top, expected.width, expected.height);
        assertEquals(expected.aspectMode, actual.aspectMode);
        assertCropTransform(actual, expected);
    }

    private static void assertCropGeometry(DirectCameraCrop actual,
            float left, float top, float width, float height) {
        assertEquals(left, actual.left, 0.00001f);
        assertEquals(top, actual.top, 0.00001f);
        assertEquals(width, actual.width, 0.00001f);
        assertEquals(height, actual.height, 0.00001f);
    }

    private static void assertCropTransform(DirectCameraCrop actual, DirectCameraCrop expected) {
        assertEquals(expected.rotationDegrees, actual.rotationDegrees);
        assertEquals(expected.rotationMode, actual.rotationMode);
        assertEquals(expected.mirrorHorizontally, actual.mirrorHorizontally);
    }

    private static void assertDewarp(CameraDewarpConfig actual,
            CameraDewarpConfig expected) {
        assertDewarp(actual, expected.enabled, expected.fovDegrees, expected.projection,
                expected.strengthPercent, expected.preciseFovDegrees);
    }

    private static void assertDewarp(CameraDewarpConfig actual, boolean enabled,
            int fov, int projection, int strength, float preciseFov) {
        assertEquals(enabled, actual.enabled);
        assertEquals(fov, actual.fovDegrees);
        assertEquals(projection, actual.projection);
        assertEquals(strength, actual.strengthPercent);
        if (Float.isNaN(preciseFov)) assertTrue(Float.isNaN(actual.preciseFovDegrees));
        else assertEquals(preciseFov, actual.preciseFovDegrees, 0.00001f);
    }

    private static void assertPlacement(float x, float y, float width, float height,
            CameraPlacement actual) {
        assertEquals(x, actual.x, 0.00001f);
        assertEquals(y, actual.y, 0.00001f);
        assertEquals(width, actual.width, 0.00001f);
        assertEquals(height, actual.height, 0.00001f);
    }

    private static void assertRect(float left, float top, float width, float height,
            ReverseCameraLayout.Rect actual) {
        assertEquals(left, actual.left, 0.00001f);
        assertEquals(top, actual.top, 0.00001f);
        assertEquals(width, actual.width, 0.00001f);
        assertEquals(height, actual.height, 0.00001f);
    }

    private static void assertRect(ReverseCameraLayout.Rect expected,
            ReverseCameraLayout.Rect actual) {
        assertRect(expected.left, expected.top, expected.width, expected.height, actual);
    }
}
