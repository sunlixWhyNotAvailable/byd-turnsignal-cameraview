package com.byd.extend;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CameraProfileTest {
    @Test
    public void frontAndRearPoliciesStayIndependent() {
        int rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT).bit();
        int frontLeft = CameraProfile.of(CameraProfile.FRONT_LEFT).bit();

        assertEquals(rearLeft | frontLeft, CameraProfile.desiredMask(
                true, 2, 10.0f, 10.0f,
                true, 10, 300, true, 0, 10, true, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                false, 2, 30.0f, 0.0f,
                true, 10, 300, true, 0, 10, false, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                true, 2, Float.NaN, 0.0f,
                true, 10, 300, true, 0, 10, false, 10.0f));
    }

    @Test
    public void panoramaSuppressionAppliesOnlyToKnownVisibleTabletTargets() {
        for (int target : new int[]{CameraDisplayTarget.TABLET, CameraDisplayTarget.CLUSTER}) {
            for (int state = 0; state < 4; state++) {
                boolean known = (state & 1) != 0;
                boolean visible = (state & 2) != 0;
                boolean expected = target == CameraDisplayTarget.TABLET && known && visible;
                assertEquals("target=" + target + " state=" + state, expected,
                        BlindSpotOverlayController.panoramaSuppresses(
                                target, known, visible, true));
                assertFalse(BlindSpotOverlayController.panoramaSuppresses(
                        target, known, visible, false));
            }
        }

        TestSharedPreferences settings = new TestSharedPreferences();
        assertTrue(BlindSpotOverlayController.readPanoramaSuppression(settings, false));
        assertTrue(BlindSpotOverlayController.readPanoramaSuppression(settings, true));
        BlindSpotOverlayController.migrateOverlayPreferences(settings);
        assertTrue(settings.getBoolean(
                BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA, false));
        assertTrue(settings.getBoolean(
                BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA, false));
        settings.putBoolean(
                BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA, false);
        assertFalse(BlindSpotOverlayController.readPanoramaSuppression(settings, false));
        assertTrue(BlindSpotOverlayController.readPanoramaSuppression(settings, true));
    }

    @Test
    public void mixedPaneTargetsUseIndependentFrontAndRearOptions() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraProfile rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT);
        CameraProfile rearRight = CameraProfile.of(CameraProfile.REAR_RIGHT);
        CameraProfile frontLeft = CameraProfile.of(CameraProfile.FRONT_LEFT);
        CameraProfile frontRight = CameraProfile.of(CameraProfile.FRONT_RIGHT);
        settings.putInt(BlindSpotOverlayController.targetKey(rearLeft), CameraDisplayTarget.TABLET);
        settings.putInt(BlindSpotOverlayController.targetKey(rearRight), CameraDisplayTarget.CLUSTER);
        settings.putInt(BlindSpotOverlayController.targetKey(frontLeft), CameraDisplayTarget.CLUSTER);
        settings.putInt(BlindSpotOverlayController.targetKey(frontRight), CameraDisplayTarget.TABLET);
        settings.putBoolean(BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA, true);
        settings.putBoolean(BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA, false);

        assertTrue(suppressed(settings, rearLeft));
        assertFalse(suppressed(settings, rearRight));
        assertFalse(suppressed(settings, frontLeft));
        assertFalse(suppressed(settings, frontRight));

        settings.putBoolean(BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA, false);
        settings.putBoolean(BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA, true);
        assertFalse(suppressed(settings, rearLeft));
        assertFalse(suppressed(settings, rearRight));
        assertFalse(suppressed(settings, frontLeft));
        assertTrue(suppressed(settings, frontRight));
    }

    @Test
    public void targetChangeWhilePanoramaIsOpenReevaluatesWithoutRewritingOptions() {
        TestSharedPreferences settings = new TestSharedPreferences();
        CameraProfile rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT);
        settings.putBoolean(BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA, true);
        settings.putBoolean(BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA, false);
        settings.putInt(BlindSpotOverlayController.targetKey(rearLeft), CameraDisplayTarget.TABLET);
        assertTrue(suppressed(settings, rearLeft));

        settings.putInt(BlindSpotOverlayController.targetKey(rearLeft), CameraDisplayTarget.CLUSTER);
        assertFalse(suppressed(settings, rearLeft));
        settings.putInt(BlindSpotOverlayController.targetKey(rearLeft), CameraDisplayTarget.TABLET);
        assertTrue(suppressed(settings, rearLeft));
        assertTrue(settings.getBoolean(
                BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA, false));
        assertFalse(settings.getBoolean(
                BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA, true));
    }

    @Test
    public void evaluateUsesEachPreparedPaneTargetForPanoramaSuppression() throws Exception {
        String source = sourceText("java/com/byd/extend/BlindSpotOverlayController.java");
        int evaluate = source.indexOf("private void evaluate() {");
        int desired = source.indexOf("int desired = desiredCameraMask(", evaluate);
        int hardBlock = source.indexOf("if (isHardBlocked()) desired = 0;", desired);
        String desiredInputs = source.substring(desired, hardBlock);
        assertTrue(desiredInputs.contains("settings.getBoolean(PREF_ENABLED, false),\n"));
        assertTrue(desiredInputs.contains("settings.getBoolean(PREF_FRONT_ENABLED, false),\n"));
        assertFalse(desiredInputs.contains("panoramaSuppresses"));
        assertFalse(desiredInputs.contains("PanoramaSuppression"));
        assertFalse(desiredInputs.contains("rearSuppressed"));
        assertFalse(desiredInputs.contains("frontSuppressed"));
        int panes = source.indexOf("for (PaneState pane : panes) {", evaluate);
        int target = source.indexOf("!panoramaSuppresses(pane.target,", panes);
        int group = source.indexOf("pane.profile.rear()", target);
        int options = source.indexOf(
                "? rearPanoramaSuppression : frontPanoramaSuppression", group);
        int visible = source.indexOf("setVisible(pane, requested,", options);
        assertTrue(evaluate >= 0 && desired > evaluate && hardBlock > desired
                && panes > hardBlock && target > panes
                && group > target && options > group && visible > options);
    }

    @Test
    public void speedRangesAreInclusiveAndSharedBoundaryAllowsBothGroups() {
        int rearLeft = CameraProfile.of(CameraProfile.REAR_LEFT).bit();
        int frontLeft = CameraProfile.of(CameraProfile.FRONT_LEFT).bit();
        assertEquals(rearLeft | frontLeft, CameraProfile.desiredMask(
                true, 2, 10.0f, 10.0f,
                true, 10, 300, true, 0, 10, true, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                true, 2, 301.0f, 20.0f,
                true, 10, 300, true, 0, 10, true, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                true, 2, 10.0f, 20.0f,
                true, 20, 10, true, 20, 10, true, 10.0f));
    }

    @Test
    public void frontCameraRequiresFreshSignedAngle() {
        int frontLeft = CameraProfile.of(CameraProfile.FRONT_LEFT).bit();
        int frontRight = CameraProfile.of(CameraProfile.FRONT_RIGHT).bit();
        assertEquals(frontLeft, CameraProfile.desiredMask(
                true, 1, 5.0f, 12.0f,
                false, 10, 300, true, 0, 10, false, 10.0f));
        assertEquals(frontRight, CameraProfile.desiredMask(
                true, 1, 5.0f, -12.0f,
                false, 10, 300, true, 0, 10, false, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                true, 2, 5.0f, -12.0f,
                false, 10, 300, true, 0, 10, true, 10.0f));
        assertEquals(0, CameraProfile.desiredMask(
                true, 2, 5.0f, Float.NaN,
                false, 10, 300, true, 0, 10, true, 10.0f));
    }

    @Test
    public void cameraMappingsAndCropKeysAreFixed() {
        assertEquals(2, CameraProfile.of(CameraProfile.REAR_LEFT).previewIndex);
        assertEquals(2, CameraProfile.of(CameraProfile.FRONT_LEFT).previewIndex);
        assertEquals(3, CameraProfile.of(CameraProfile.REAR_RIGHT).previewIndex);
        assertEquals(3, CameraProfile.of(CameraProfile.FRONT_RIGHT).previewIndex);
        assertEquals(DirectCameraCrop.PREF_LEFT_X,
                DirectCameraCrop.preferenceKey(CameraProfile.of(CameraProfile.REAR_LEFT), 0));
        assertEquals(DirectCameraCrop.PREF_FRONT_LEFT_X,
                DirectCameraCrop.preferenceKey(CameraProfile.of(CameraProfile.FRONT_LEFT), 0));
        assertEquals(DirectCameraCrop.PREF_FRONT_LEFT_ROTATION,
                DirectCameraCrop.preferenceKey(CameraProfile.of(CameraProfile.FRONT_LEFT), 5));
        assertEquals(DirectCameraCrop.PREF_FRONT_LEFT_ROTATION_MODE,
                DirectCameraCrop.preferenceKey(CameraProfile.of(CameraProfile.FRONT_LEFT), 6));
    }

    private static boolean suppressed(TestSharedPreferences settings, CameraProfile profile) {
        return BlindSpotOverlayController.panoramaSuppresses(
                BlindSpotOverlayController.readTarget(settings, profile), true, true,
                BlindSpotOverlayController.readPanoramaSuppression(settings, profile.front()));
    }

    private static String sourceText(String relative) throws java.io.IOException {
        Path path = Path.of("src/main", relative);
        if (!Files.exists(path)) path = Path.of("app").resolve(path);
        return new String(Files.readAllBytes(path),
                java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
