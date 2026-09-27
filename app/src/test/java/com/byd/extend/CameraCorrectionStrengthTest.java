package com.byd.extend;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class CameraCorrectionStrengthTest {
    private static final double ASPECT = 1920.0 / 1300.0;
    private static CameraDewarpConfig selected() {
        return CameraDewarpConfig.of(1, true, 100).withStrength(37).withHorizontalFov(103.456f);
    }

    @Test public void legacyReadsNeverWriteOrActivateHistoricalStrengthKeys() {
        TestSharedPreferences p = new TestSharedPreferences();
        p.putInt("camera_dewarp_v2_left_fov", 127);
        p.putInt("camera_dewarp_v2_left_strength", 12);
        Map<String, ?> before = p.getAll();
        for (CameraProfile profile : CameraProfile.values()) {
            CameraDewarpConfig value = CameraDewarpConfig.loadForProfile(p, profile);
            assertEquals(100, value.strengthPercent);
            assertTrue(Float.isNaN(value.preciseFovDegrees));
        }
        for (ParkingCameraProfile profile : ParkingCameraProfile.values())
            assertEquals(100, CameraDewarpConfig.loadForParking(p, profile).strengthPercent);
        for (int index = 1; index <= 3; index++) {
            CameraDewarpConfig.loadForReverse(p, index);
            CameraDewarpConfig.loadForReverseFront(p, index);
        }
        new RearviewMirrorSettings(p).load();
        assertEquals(before, p.getAll());
    }

    @Test public void defaultEndpointUsesLegacyRaysAndUnsupportedStrengthIsInert() {
        for (int lens = 1; lens <= 4; lens++) {
            for (int fov : new int[]{60, 100, 130, 170}) {
                CameraDewarpConfig full = CameraDewarpConfig.of(lens, true, fov);
                for (double x : new double[]{0, .3, .5, 1})
                    assertArrayEquals(CameraFisheyeMapping.mapOutputToSource(lens, fov, 1920, 1300, x, .2),
                            CameraFisheyeMapping.mapOutputToSource(full, 1920, 1300, x, .2), 0.0);
            }
        }
        CameraDewarpConfig value = selected();
        for (CameraDewarpConfig inert : new CameraDewarpConfig[]{value.withEnabled(false),
                value.withProjection(1)}) {
            assertMeshEquals(inert.withStrength(100), inert);
        }
        CameraDewarpConfig wide = value.withHorizontalFov(170);
        assertEquals(37, CameraCorrectionGeometry.effectiveStrength(wide));
        assertFalse(java.util.Arrays.equals(CameraFisheyeMapping.buildMesh(wide, 1920, 1300).vertices,
                CameraFisheyeMapping.buildMesh(wide.withStrength(100), 1920, 1300).vertices));
    }

    @Test public void inverseMatchesOemRadialEquationNotUvInterpolation() {
        for (int strength : new int[]{1, 10, 37, 50, 75, 99, 100}) {
            CameraDewarpConfig config = selected().withStrength(strength);
            double alpha = Math.toRadians(CameraCorrectionGeometry.diagonalFov(config.horizontalFovDegrees(), ASPECT)) / 2;
            double theta = alpha * .53;
            double s = strength / 100.0;
            double radius = (1 - s) * lensRadius(theta) / lensRadius(alpha)
                    + s * Math.tan(theta) / Math.tan(alpha);
            double[] actual = CameraFisheyeMapping.mapOutputToSource(config, 1920, 1300,
                    .5 + radius / 2, .5 + radius / 2);
            double scale = 433 * lensRadius(theta) / Math.hypot(1920, 1300);
            assertArrayEquals(new double[]{960 + scale * 1920, 650 + scale * 1300}, actual, .002);
            for (float entry : CameraFisheyeMapping.buildMesh(config.withRoiCenter(.58f, .55f), 960, 650).vertices)
                assertTrue(Float.isFinite(entry));
        }
    }

    @Test public void formerDiagonalBoundaryDoesNotChangeSelectedStrength() {
        double horizontalAt160Diagonal = Math.toDegrees(2 * Math.atan(
                Math.tan(Math.toRadians(80)) / Math.hypot(1, 1 / ASPECT)));
        assertEquals(160, CameraCorrectionGeometry.diagonalFov(horizontalAt160Diagonal, ASPECT), .00001);
        CameraDewarpConfig value = selected().withStrength(10);
        for (double horizontal : new double[]{
                horizontalAt160Diagonal - .01,
                horizontalAt160Diagonal,
                horizontalAt160Diagonal + .01,
                170
        }) {
            CameraDewarpConfig updated = value.withHorizontalFov((float) horizontal);
            assertEquals(10, updated.strengthPercent);
            assertEquals(10, CameraCorrectionGeometry.effectiveStrength(updated));
        }
        assertTrue(CameraCorrectionGeometry.diagonalFov(170, ASPECT) > 170);
        assertTrue(CameraCorrectionGeometry.strengthEditable(true, CameraDewarpConfig.PROJECTION_RECTILINEAR));
        assertFalse(CameraCorrectionGeometry.strengthEditable(false, CameraDewarpConfig.PROJECTION_RECTILINEAR));
        assertFalse(CameraCorrectionGeometry.strengthEditable(true, CameraDewarpConfig.PROJECTION_CYLINDRICAL));
        assertEquals(100, CameraCorrectionGeometry.effectiveStrength(value.withEnabled(false)));
        assertEquals(100, CameraCorrectionGeometry.effectiveStrength(value.withProjection(
                CameraDewarpConfig.PROJECTION_CYLINDRICAL)));
        assertThrows(IllegalArgumentException.class, () -> value.withHorizontalFov(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> value.withStrength(0));
    }

    @Test public void wideHorizontalRangeHasFiniteMonotonicMeshesForEveryStrength() {
        int[][] outputSizes = {
                {1920, 1300}, {1920, 1080}, {1600, 900},
                {1280, 1024}, {1024, 768}, {800, 600}
        };
        int[] fovs = {60, 150, 155, 156, 170};
        int[] strengths = {1, 10, 50, 99, 100};
        for (int[] size : outputSizes) {
            for (int fov : fovs) {
                for (int strength : strengths) {
                    CameraDewarpConfig config = CameraDewarpConfig.of(
                            CameraDewarpConfig.LENS_LEFT, true, fov,
                            CameraDewarpConfig.PROJECTION_RECTILINEAR).withStrength(strength);
                    assertEquals(strength, CameraCorrectionGeometry.effectiveStrength(config));
                    CameraFisheyeMapping.Mesh mesh = CameraFisheyeMapping.buildMesh(
                            config, size[0], size[1]);
                    boolean finite = true;
                    for (float vertex : mesh.vertices) finite &= Float.isFinite(vertex);
                    assertTrue(finite);

                    double previousRadius = 0;
                    for (int sample = 1; sample <= 4; sample++) {
                        int column = 48 + sample * 12;
                        int row = 32 + sample * 8;
                        int offset = (row * CameraFisheyeMapping.MESH_COLUMNS + column) * 4;
                        double sourceX = mesh.vertices[offset + 2] * CameraFisheyeMapping.SOURCE_WIDTH;
                        double sourceY = (1 - mesh.vertices[offset + 3]) * CameraFisheyeMapping.SOURCE_HEIGHT;
                        assertTrue(Double.isFinite(sourceX));
                        assertTrue(Double.isFinite(sourceY));
                        double radius = Math.hypot(sourceX - 960, sourceY - 650);
                        assertTrue(Double.isFinite(radius));
                        assertTrue(radius > previousRadius);
                        previousRadius = radius;
                    }
                }
            }
        }
    }

    @Test public void wireRoundTripAndMappingIdentityIncludeNewControls() {
        CameraDewarpConfig value = selected();
        assertControls(value, CameraShellProtocol.decodeDewarp(CameraShellProtocol.encodeDewarp(value)));
        assertEquals(100, CameraShellProtocol.decodeDewarp(new int[]{1, 1, 100, 0}).strengthPercent);
        assertFalse(value.sameMapping(value.withStrength(38)));
        assertFalse(value.sameMapping(value.withHorizontalFov(103.457f)));
        assertTrue(value.withProjection(1).sameMapping(value.withStrength(38).withProjection(1)));
        int[] invalid = CameraShellProtocol.encodeDewarp(value);
        invalid[4] = 101;
        assertThrows(IllegalArgumentException.class, () -> CameraShellProtocol.decodeDewarp(invalid));
        invalid[4] = 37;
        invalid[5] = Float.floatToIntBits(Float.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> CameraShellProtocol.decodeDewarp(invalid));
    }

    @Test public void localPresetsCopiesAndExplicitResetPreserveIndependentControls() {
        TestSharedPreferences p = new TestSharedPreferences();
        CameraDewarpConfig value = selected();
        CameraProfile left = CameraProfile.of(CameraProfile.REAR_LEFT);
        CameraDewarpConfig.saveForProfile(p, left, value);
        CameraCalibrationPreset.saveCamera(p, left);
        CameraDewarpConfig.saveForProfile(p, left, value.withStrength(71));
        assertTrue(CameraCalibrationPreset.loadCamera(p, left));
        assertControls(value, CameraDewarpConfig.loadForProfile(p, left));
        CameraCalibrationPreset.mirrorCamera(p, left);
        assertControls(value, CameraDewarpConfig.loadForProfile(p, CameraProfile.of(CameraProfile.REAR_RIGHT)));
        CameraDewarpConfig.saveForParking(p, ParkingCameraProfile.of(ParkingCameraProfile.LEFT), value);
        CameraCalibrationPreset.saveParking(p, ParkingCameraProfile.of(ParkingCameraProfile.LEFT));
        CameraDewarpConfig.saveForParking(p, ParkingCameraProfile.of(ParkingCameraProfile.LEFT), value.withStrength(71));
        assertTrue(CameraCalibrationPreset.loadParking(p, ParkingCameraProfile.of(ParkingCameraProfile.LEFT)));
        assertTrue(CameraCalibrationPreset.mirrorParking(p, ParkingCameraProfile.of(ParkingCameraProfile.LEFT)));
        assertControls(value, CameraDewarpConfig.loadForParking(p, ParkingCameraProfile.of(ParkingCameraProfile.RIGHT)));
        for (int index = 1; index <= 3; index++) {
            CameraDewarpConfig rear = CameraDewarpConfig.of(CameraDewarpConfig.lensForReverseCamera(index), true, 103, 0, 37, 103.456f);
            CameraDewarpConfig front = CameraDewarpConfig.of(CameraDewarpConfig.lensForReverseFrontCamera(index), true, 103, 0, 37, 103.456f);
            CameraDewarpConfig.saveForReverse(p, index, rear);
            CameraCalibrationPreset.saveReverse(p, index);
            CameraDewarpConfig.saveForReverse(p, index, rear.withStrength(71));
            assertTrue(CameraCalibrationPreset.loadReverse(p, index));
            assertControls(value, CameraDewarpConfig.loadForReverse(p, index));
            CameraDewarpConfig.saveForReverseFront(p, index, front);
            CameraCalibrationPreset.saveReverseFront(p, index);
            CameraDewarpConfig.saveForReverseFront(p, index, front.withStrength(71));
            assertTrue(CameraCalibrationPreset.loadReverseFront(p, index));
            assertControls(value, CameraDewarpConfig.loadForReverseFront(p, index));
        }
        assertTrue(CameraCalibrationPreset.copyCentralReverseRearToFront(p));
        assertControls(value, CameraDewarpConfig.loadForReverseFront(p, 1));
        CameraCalibrationPreset.resetCameraToDefault(p, left, CameraDisplayTarget.TABLET, 1920, 1300, 0, 0, 0);
        assertEquals(100, CameraDewarpConfig.loadForProfile(p, left).strengthPercent);
        assertTrue(Float.isNaN(CameraDewarpConfig.loadForProfile(p, left).preciseFovDegrees));
    }

    @Test public void mirrorEditsCopyAndPresetsKeepStrengthAndPrecision() {
        TestSharedPreferences p = new TestSharedPreferences();
        RearviewMirrorSettings.Calibration c = RearviewMirrorSettings.calibration(p, false).withControls(selected());
        RearviewMirrorSettings.writeCalibration(p, false, c);
        RearviewMirrorSettings.writePreset(p, false, c);
        RearviewMirrorSettings.copyCalibration(p, false, true);
        assertControls(selected(), RearviewMirrorSettings.dewarp(p, true));
        assertEquals(37, RearviewMirrorSettings.preset(p, false).strengthPercent);
        c = CameraProbeActivity.mergeMirrorCalibration(c, com.byd.extend.ui.ProfileNumber.Rotation, null, "17");
        assertControls(selected(), c.dewarp(3));
        RearviewMirrorSettings.Calibration disabled = c.withControls(selected().withHorizontalFov(170));
        assertThrows(IllegalArgumentException.class, () -> CameraProbeActivity.mergeMirrorCalibration(disabled,
                com.byd.extend.ui.ProfileNumber.Strength, null, "50"));
    }

    @Test public void v5ExportAndOldImportsRetainCompatibilityWithoutReadTimeWrites() {
        TestSharedPreferences p = new TestSharedPreferences();
        CameraProfile left = CameraProfile.of(CameraProfile.REAR_LEFT);
        CameraDewarpConfig.saveForProfile(p, left, selected());
        RearviewMirrorSettings.writeCalibration(p, true, RearviewMirrorSettings.calibration(p, true).withControls(selected()));
        Map<String, ?> before = p.getAll();
        CameraSettingsTransfer.GeometryResolver geometry = target -> new CameraSettingsTransfer.DisplayGeometry(1920, 720, 0, 0, 0);
        Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(CameraSettingsTransfer.exportCameraPreset(p, geometry));
        assertEquals(5, parsed.get("version"));
        assertEquals(before, p.getAll());
        TestSharedPreferences out = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(out, parsed);
        assertControls(selected(), CameraDewarpConfig.loadForProfile(out, left));
        assertControls(selected(), RearviewMirrorSettings.dewarp(out, true));
        for (int version = 1; version <= 4; version++) {
            Map<String, Object> legacy = new HashMap<>(parsed);
            Map<String, Object> settings = new HashMap<>((Map<String, Object>) parsed.get("settings"));
            settings.keySet().removeIf(k -> k.endsWith("_strength_percent") || k.endsWith("_fov_precise"));
            // v4 requires frame keys; omitted-Mirror compatibility predates that schema.
            if (version < 4) settings.keySet().removeIf(k -> k.startsWith("mirror_"));
            legacy.put("version", version);
            legacy.put("settings", settings);
            CameraSettingsTransfer.applyCameraPreset(out, legacy);
            assertEquals(100, CameraDewarpConfig.loadForProfile(out, left).strengthPercent);
            assertEquals(103, CameraDewarpConfig.loadForProfile(out, left).horizontalFovDegrees(), 0);
            if (version < 4) assertControls(selected(), RearviewMirrorSettings.dewarp(out, true));
            else assertEquals(100, RearviewMirrorSettings.dewarp(out, true).strengthPercent);
        }
        Map<String, Object> bad = new HashMap<>(parsed);
        Map<String, Object> settings = new HashMap<>((Map<String, Object>) parsed.get("settings"));
        String key = settings.keySet().stream().filter(k -> k.endsWith("_strength_percent")).findFirst().get();
        settings.put(key, 0);
        bad.put("settings", settings);
        Map<String, ?> preserved = out.getAll();
        assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.applyCameraPreset(out, bad));
        assertEquals(preserved, out.getAll());
        Map<String, Object> partial = CameraSettingsTransfer.parseCameraPreset(CameraSettingsTransfer.exportCameraPreset(p));
        Map<String, Object> partialValues = (Map<String, Object>) partial.get("settings");
        partialValues.keySet().removeIf(k -> k.startsWith("mirror_"));
        partialValues.put("mirror_front_strength_percent", 101);
        assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.applyCameraPreset(out, partial));
        partialValues.remove("mirror_front_strength_percent");
        partialValues.put("mirror_front_fov_precise", 180f);
        assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.applyCameraPreset(out, partial));
        assertEquals(preserved, out.getAll());
        assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.parseLegacySettings(
                "<map><int name=\"camera_dewarp_v3_overlay_rear_left_strength_percent\" value=\"0\"/></map>"));
        assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.parseLegacySettings(
                "<map><float name=\"camera_dewarp_v3_overlay_rear_left_fov_precise\" value=\"180\"/></map>"));
    }

    private static double lensRadius(double t) {
        double t2 = t * t;
        return t * (1 + t2 * (.252969 + t2 * (-.096487 + t2 * (.023289 - t2 * .002927))));
    }
    private static void assertMeshEquals(CameraDewarpConfig a, CameraDewarpConfig b) {
        CameraFisheyeMapping.Mesh x = CameraFisheyeMapping.buildMesh(a, 1920, 1300);
        CameraFisheyeMapping.Mesh y = CameraFisheyeMapping.buildMesh(b, 1920, 1300);
        assertArrayEquals(x.vertices, y.vertices, 0);
        assertArrayEquals(x.indices, y.indices);
    }
    private static void assertControls(CameraDewarpConfig expected, CameraDewarpConfig actual) {
        assertEquals(expected.strengthPercent, actual.strengthPercent);
        assertEquals(expected.horizontalFovDegrees(), actual.horizontalFovDegrees(), 0);
    }
}
