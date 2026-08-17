package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public final class ReverseCameraLayoutTest {
    @Test
    public void editorSelectionSurvivesRecreationAndRejectsInvalidValues() {
        TestSharedPreferences settings = new TestSharedPreferences();
        assertEquals(ReverseCameraLayout.REAR_CAMERA_INDEX,
                ReverseCameraController.loadEditorSelection(settings));

        ReverseCameraController.saveEditorSelection(
                settings, ReverseCameraLayout.BACKGROUND_PANE_ID);
        assertEquals(ReverseCameraLayout.BACKGROUND_PANE_ID,
                ReverseCameraController.loadEditorSelection(settings));
        ReverseCameraController.saveEditorSelection(
                settings, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        assertEquals(ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraController.loadEditorSelection(settings));

        settings.putInt(ReverseCameraController.PREF_EDITOR_SELECTION, 99);
        assertEquals(ReverseCameraLayout.REAR_CAMERA_INDEX,
                ReverseCameraController.loadEditorSelection(settings));
        settings.putString(ReverseCameraController.PREF_EDITOR_SELECTION, "invalid");
        assertEquals(ReverseCameraLayout.REAR_CAMERA_INDEX,
                ReverseCameraController.loadEditorSelection(settings));
        assertThrows(IllegalArgumentException.class,
                () -> ReverseCameraController.saveEditorSelection(settings, 99));
    }

    @Test
    public void visibilityMaskUsesFourStableBitsAndDefaultsOn() {
        assertEquals(ReverseCameraLayout.VISIBILITY_ALL,
                ReverseCameraController.loadVisibilityMask(new TestSharedPreferences()));
        assertEquals(ReverseCameraLayout.VISIBILITY_BACKGROUND,
                ReverseCameraLayout.visibilityBitForPane(
                        ReverseCameraLayout.BACKGROUND_PANE_ID));
        assertEquals(ReverseCameraLayout.VISIBILITY_REAR,
                ReverseCameraLayout.visibilityBitForPane(ReverseCameraLayout.REAR_CAMERA_INDEX));
        assertEquals(ReverseCameraLayout.VISIBILITY_REAR_LEFT,
                ReverseCameraLayout.visibilityBitForPane(
                        ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX));
        assertEquals(ReverseCameraLayout.VISIBILITY_REAR_RIGHT,
                ReverseCameraLayout.visibilityBitForPane(
                        ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX));
        assertTrue(ReverseCameraLayout.isValidVisibilityMask(0));
        assertTrue(ReverseCameraLayout.isVisible(
                ReverseCameraLayout.VISIBILITY_ALL, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX));
        assertFalse(ReverseCameraLayout.isVisible(0, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX));
        assertThrows(IllegalArgumentException.class,
                () -> ReverseCameraLayout.requireVisibilityMask(16));
        assertThrows(IllegalArgumentException.class,
                () -> ReverseCameraLayout.visibilityBitForPane(0));
    }

    @Test
    public void visibilityWritesOnePaneAndResetRestoresAllOn() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraController.saveVisibility(
                settings, ReverseCameraLayout.BACKGROUND_PANE_ID, false);
        ReverseCameraController.saveVisibility(
                settings, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, false);
        assertFalse(ReverseCameraController.loadVisibility(
                settings, ReverseCameraLayout.BACKGROUND_PANE_ID));
        assertFalse(ReverseCameraController.loadVisibility(
                settings, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX));
        assertTrue(ReverseCameraController.loadVisibility(
                settings, ReverseCameraLayout.REAR_CAMERA_INDEX));
        assertEquals(ReverseCameraLayout.VISIBILITY_REAR
                        | ReverseCameraLayout.VISIBILITY_REAR_RIGHT,
                ReverseCameraController.loadVisibilityMask(settings));

        ReverseCameraController.resetLayout(settings);
        assertEquals(ReverseCameraLayout.VISIBILITY_ALL,
                ReverseCameraController.loadVisibilityMask(settings));
    }

    @Test
    public void sourceCropUsesOnePercentFloorWithoutChangingDestinationMinimum() {
        assertThrows(IllegalArgumentException.class, () ->
                ReverseCameraLayout.sourceCrop(0.0f, 0.0f, 0.0099f, 0.01f));
        assertThrows(IllegalArgumentException.class, () ->
                ReverseCameraLayout.sourceCrop(0.0f, 0.0f, 0.01f, 0.0099f));
        ReverseCameraLayout.Rect accepted = ReverseCameraLayout.sourceCrop(
                0.99f, 0.99f, 0.01f, 0.01f);
        assertEquals(0.01f, accepted.width, 0.0f);
        assertEquals(0.01f, accepted.height, 0.0f);

        ReverseCameraLayout.Rect destination = ReverseCameraLayout.destination(
                0.5f, 0.5f, 0.01f, 0.01f);
        assertEquals(ReverseCameraLayout.MIN_DESTINATION_SIZE,
                destination.width, 0.0f);
        assertEquals(ReverseCameraLayout.MIN_DESTINATION_SIZE,
                destination.height, 0.0f);
    }

    @Test
    public void activeReverseRawAndCorrectedCropsMigrateIdempotently() {
        TestSharedPreferences settings = new TestSharedPreferences();
        int cameraIndex = ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX;
        settings.putFloat(ReverseCameraController.sourceCropKey(
                cameraIndex, "left", false), 0.4f);
        settings.putFloat(ReverseCameraController.sourceCropKey(
                cameraIndex, "top", false), 0.4f);
        settings.putFloat(ReverseCameraController.sourceCropKey(
                cameraIndex, "width", false), 0.005f);
        settings.putFloat(ReverseCameraController.sourceCropKey(
                cameraIndex, "height", false), 0.0025f);

        ReverseCameraLayout.Rect raw = ReverseCameraController
                .loadRawLayout(settings).pane(cameraIndex).sourceCrop;
        assertEquals(0.4025f, raw.left + raw.width / 2.0f, 0.0001f);
        assertEquals(0.40125f, raw.top + raw.height / 2.0f, 0.0001f);
        assertEquals(0.02f, raw.width, 0.0001f);
        assertEquals(0.01f, raw.height, 0.0001f);

        String correctedPrefix = "reverse_camera_" + cameraIndex
                + "_corrected_v3_crop_";
        settings.putFloat(correctedPrefix + "left", 0.7f);
        settings.putFloat(correctedPrefix + "top", 0.3f);
        settings.putFloat(correctedPrefix + "width", 0.004f);
        settings.putFloat(correctedPrefix + "height", 0.008f);
        ReverseCameraLayout.Rect corrected = ReverseCameraController
                .loadCorrectedSourceCrop(settings, cameraIndex, raw);
        assertEquals(0.702f, corrected.left + corrected.width / 2.0f, 0.0001f);
        assertEquals(0.304f, corrected.top + corrected.height / 2.0f, 0.0001f);
        assertEquals(0.01f, corrected.width, 0.0001f);
        assertEquals(0.02f, corrected.height, 0.0001f);

        Map<String, ?> afterFirstLoad = new HashMap<>(settings.getAll());
        ReverseCameraController.loadRawLayout(settings);
        ReverseCameraController.loadCorrectedSourceCrop(
                settings, cameraIndex, raw);
        assertEquals(afterFirstLoad, settings.getAll());
    }

    @Test
    public void backgroundStaysBelowIndependentCameraOrdering() {
        ReverseCameraLayout layout = ReverseCameraLayout.defaults();
        assertEquals(0.0f, layout.background.left, 0.0f);
        assertEquals(0.0f, layout.background.top, 0.0f);
        assertEquals(1.0f, layout.background.width, 0.0f);
        assertEquals(1.0f, layout.background.height, 0.0f);

        ReverseCameraLayout moved = ReverseCameraLayout.withBackground(layout,
                ReverseCameraLayout.destination(0.1f, 0.2f, 0.7f, 0.6f));
        assertEquals(0.1f, moved.background.left, 0.0001f);
        assertEquals(0.2f, moved.background.top, 0.0001f);
        assertEquals(layout.rear.zOrder, moved.rear.zOrder);
        assertEquals(layout.rearLeft.zOrder, moved.rearLeft.zOrder);
        assertEquals(layout.rearRight.zOrder, moved.rearRight.zOrder);
    }

    @Test
    public void allReverseCamerasAreMirrored() {
        assertTrue(ReverseCameraLayout.mirrorHorizontally(
                ReverseCameraLayout.REAR_CAMERA_INDEX));
        assertTrue(ReverseCameraLayout.mirrorHorizontally(
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX));
        assertTrue(ReverseCameraLayout.mirrorHorizontally(
                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX));
    }

    @Test
    public void nudgeMovesSelectedPaneAndClampsToCanvas() {
        ReverseCameraLayout layout = ReverseCameraLayout.defaults();
        ReverseCameraLayout moved = ReverseCameraLayout.move(layout,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 0.01f, -0.01f);
        assertEquals(0.01f, moved.rearLeft.destination.left, 0.0001f);
        assertEquals(0.49f, moved.rearLeft.destination.top, 0.0001f);

        ReverseCameraLayout clamped = ReverseCameraLayout.move(moved,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 2.0f, 2.0f);
        assertEquals(0.5f, clamped.rearLeft.destination.left, 0.0001f);
        assertEquals(0.5f, clamped.rearLeft.destination.top, 0.0001f);
    }

    @Test
    public void rotationIsPerPaneAndSurvivesGeometryChanges() {
        ReverseCameraLayout layout = ReverseCameraLayout.withRotation(
                ReverseCameraLayout.defaults(),
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, -37);
        ReverseCameraLayout moved = ReverseCameraLayout.move(layout,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 0.01f, 0.0f);
        assertEquals(-37, moved.rearLeft.rotationDegrees);
        assertEquals(0, moved.rear.rotationDegrees);
        assertEquals(180, ReverseCameraLayout.withRotation(
                moved, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 999)
                .rearLeft.rotationDegrees);
    }

    @Test
    public void displayModeDefaultsAndInvalidValuesFallBackToFit() {
        ReverseCameraLayout layout = ReverseCameraLayout.defaults();
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FIT, layout.rear.displayMode);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FIT, layout.rearLeft.displayMode);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FIT, layout.rearRight.displayMode);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FIT,
                ReverseCameraLayout.normalizeDisplayMode(-1));
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FIT,
                ReverseCameraLayout.normalizeDisplayMode(99));
        assertTrue(ReverseCameraLayout.isValidDisplayMode(
                ReverseCameraLayout.DISPLAY_MODE_STRETCH));
        assertFalse(ReverseCameraLayout.isValidDisplayMode(99));

        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putInt(ReverseCameraController.displayModeKey(
                ReverseCameraLayout.REAR_CAMERA_INDEX),
                ReverseCameraLayout.DISPLAY_MODE_STRETCH);
        settings.putInt(ReverseCameraController.displayModeKey(
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX), 99);
        settings.putString(ReverseCameraController.displayModeKey(
                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX), "invalid");
        layout = ReverseCameraController.loadLayout(settings);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_STRETCH, layout.rear.displayMode);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FIT, layout.rearLeft.displayMode);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FIT, layout.rearRight.displayMode);
    }

    @Test
    public void displayModeSurvivesEveryLayoutMutation() {
        ReverseCameraLayout layout = ReverseCameraLayout.defaults();
        layout = ReverseCameraLayout.withDisplayMode(
                layout, ReverseCameraLayout.REAR_CAMERA_INDEX,
                ReverseCameraLayout.DISPLAY_MODE_FILL);
        layout = ReverseCameraLayout.withDisplayMode(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.DISPLAY_MODE_STRETCH);
        layout = ReverseCameraLayout.withDisplayMode(
                layout, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX,
                ReverseCameraLayout.DISPLAY_MODE_FILL);

        layout = ReverseCameraLayout.withPane(layout,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.destination(0.1f, 0.4f, 0.4f, 0.4f),
                ReverseCameraLayout.sourceCrop(0.1f, 0.1f, 0.7f, 0.7f));
        layout = ReverseCameraLayout.withRotation(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 45);
        layout = ReverseCameraLayout.move(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX, 0.05f, 0.02f);
        layout = ReverseCameraLayout.bringToFront(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        layout = ReverseCameraLayout.raise(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        layout = ReverseCameraLayout.lower(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        layout = ReverseCameraLayout.withBackground(layout,
                ReverseCameraLayout.destination(0.05f, 0.05f, 0.9f, 0.9f));

        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FILL, layout.rear.displayMode);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_STRETCH, layout.rearLeft.displayMode);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FILL, layout.rearRight.displayMode);
    }

    @Test
    public void displayModeRoundTripsAndResetWritesFit() {
        TestSharedPreferences settings = new TestSharedPreferences();
        ReverseCameraLayout layout = ReverseCameraLayout.defaults();
        layout = ReverseCameraLayout.withDisplayMode(
                layout, ReverseCameraLayout.REAR_CAMERA_INDEX,
                ReverseCameraLayout.DISPLAY_MODE_FILL);
        layout = ReverseCameraLayout.withDisplayMode(
                layout, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.DISPLAY_MODE_STRETCH);
        ReverseCameraController.saveLayout(settings, layout);
        ReverseCameraLayout restored = ReverseCameraController.loadLayout(settings);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FILL, restored.rear.displayMode);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_STRETCH, restored.rearLeft.displayMode);
        assertEquals(ReverseCameraLayout.DISPLAY_MODE_FIT, restored.rearRight.displayMode);

        ReverseCameraController.resetLayout(settings);
        restored = ReverseCameraController.loadLayout(settings);
        for (ReverseCameraLayout.Pane pane : restored.panes()) {
            assertEquals(ReverseCameraLayout.DISPLAY_MODE_FIT, pane.displayMode);
            assertEquals(ReverseCameraLayout.DISPLAY_MODE_FIT,
                    settings.getInt(ReverseCameraController.displayModeKey(pane.cameraIndex), -1));
        }
    }

    @Test
    public void correctedCropMigratesFromCenteredRawAndLegacyKeysStayUntouched() {
        TestSharedPreferences settings = new TestSharedPreferences();
        int cameraIndex = ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX;
        settings.putFloat(ReverseCameraController.sourceCropKey(
                cameraIndex, "left", false), 0.12f);
        settings.putFloat(ReverseCameraController.sourceCropKey(
                cameraIndex, "top", false), 0.22f);
        settings.putFloat(ReverseCameraController.sourceCropKey(
                cameraIndex, "width", false), 0.42f);
        settings.putFloat(ReverseCameraController.sourceCropKey(
                cameraIndex, "height", false), 0.52f);
        settings.putFloat(ReverseCameraController.sourceCropKey(
                cameraIndex, "left", true), 0.77f);
        CameraDewarpConfig.save(settings, CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 120));

        ReverseCameraLayout layout = ReverseCameraController.loadLayout(settings);
        ReverseCameraLayout rawLayout = ReverseCameraController.loadRawLayout(settings);
        assertEquals((1.0f - 0.42f) / 2.0f,
                layout.rearLeft.sourceCrop.left, 0.0001f);
        assertEquals((1.0f - 0.52f) / 2.0f,
                layout.rearLeft.sourceCrop.top, 0.0001f);
        assertEquals(0.42f, layout.rearLeft.sourceCrop.width, 0.0001f);
        assertEquals(0.52f, layout.rearLeft.sourceCrop.height, 0.0001f);
        assertEquals(0.12f, rawLayout.rearLeft.sourceCrop.left, 0.0001f);
        assertEquals(0.22f, rawLayout.rearLeft.sourceCrop.top, 0.0001f);

        ReverseCameraLayout.Rect corrected = ReverseCameraLayout.sourceCrop(
                0.31f, 0.19f, 0.38f, 0.44f);
        ReverseCameraController.saveSourceCrop(settings, cameraIndex, corrected, true);
        layout = ReverseCameraController.loadLayout(settings);
        assertEquals(0.31f, layout.rearLeft.sourceCrop.left, 0.0001f);
        assertEquals(0.19f, layout.rearLeft.sourceCrop.top, 0.0001f);

        ReverseCameraController.saveLayout(settings, layout);
        assertEquals(0.77f, settings.getFloat(ReverseCameraController.sourceCropKey(
                cameraIndex, "left", true), -1.0f), 0.0f);
    }

    @Test
    public void fitAndFillUseUniformRotatedCropAtZeroFortyFiveAndNinetyDegrees() {
        ReverseCameraLayout.Rect crop = ReverseCameraLayout.sourceCrop(
                0.1f, 0.2f, 0.8f, 0.6f);
        int paneWidth = 1600;
        int paneHeight = 900;
        int[] rotations = {0, 45, 90};
        for (int rotation : rotations) {
            ReverseCameraLayout.PixelRect fitted = ReverseCameraLayout.fitSourceCrop(
                    crop, paneWidth, paneHeight,
                    ReverseCameraCompositionView.SOURCE_WIDTH,
                    ReverseCameraCompositionView.SOURCE_HEIGHT, rotation);
            assertEquals(rotatedAspect(crop, rotation),
                    (float) fitted.width / fitted.height, 0.002f);

            float[] fit = ReverseCameraLayout.rotatedSourceCropTransform(
                    crop, fitted.width, fitted.height,
                    ReverseCameraCompositionView.SOURCE_WIDTH,
                    ReverseCameraCompositionView.SOURCE_HEIGHT, rotation, false);
            assertUniformPhysicalScale(fit, fitted.width, fitted.height);
            assertCropFits(fit, crop, fitted.width, fitted.height);

            float[] fill = ReverseCameraLayout.rotatedSourceCropTransform(
                    crop, paneWidth, paneHeight,
                    ReverseCameraCompositionView.SOURCE_WIDTH,
                    ReverseCameraCompositionView.SOURCE_HEIGHT, rotation, true);
            assertUniformPhysicalScale(fill, paneWidth, paneHeight);
            assertPaneCovered(fill, crop, paneWidth, paneHeight);
        }

        float scaleAt29 = physicalScale(ReverseCameraLayout.rotatedSourceCropTransform(
                crop, paneWidth, paneHeight,
                ReverseCameraCompositionView.SOURCE_WIDTH,
                ReverseCameraCompositionView.SOURCE_HEIGHT, 29, true), paneWidth);
        float scaleAt30 = physicalScale(ReverseCameraLayout.rotatedSourceCropTransform(
                crop, paneWidth, paneHeight,
                ReverseCameraCompositionView.SOURCE_WIDTH,
                ReverseCameraCompositionView.SOURCE_HEIGHT, 30, true), paneWidth);
        assertEquals(scaleAt29, scaleAt30, 0.05f);
    }

    @Test
    public void capturedFortyByFiftyCropsAtFortyTwoDegreesStayBounded() {
        ReverseCameraLayout.Rect[] crops = {
                ReverseCameraLayout.sourceCrop(0.0f, 0.25f, 0.4f, 0.5f),
                ReverseCameraLayout.sourceCrop(0.6f, 0.25f, 0.4f, 0.5f)
        };
        int[] rotations = {42, -42};
        float referenceScale = -1.0f;
        for (int i = 0; i < crops.length; i++) {
            ReverseCameraLayout.Rect crop = crops[i];
            ReverseCameraLayout.PixelRect fitted = ReverseCameraLayout.fitSourceCrop(
                    crop, 960, 495,
                    ReverseCameraCompositionView.SOURCE_WIDTH,
                    ReverseCameraCompositionView.SOURCE_HEIGHT, rotations[i]);
            assertEquals(499, fitted.width);
            assertEquals(495, fitted.height);
            float[] fit = ReverseCameraLayout.rotatedSourceCropTransform(
                    crop, fitted.width, fitted.height,
                    ReverseCameraCompositionView.SOURCE_WIDTH,
                    ReverseCameraCompositionView.SOURCE_HEIGHT, rotations[i], false);
            assertCropFits(fit, crop, fitted.width, fitted.height);

            float[] fill = ReverseCameraLayout.rotatedSourceCropTransform(
                    crop, 960, 495,
                    ReverseCameraCompositionView.SOURCE_WIDTH,
                    ReverseCameraCompositionView.SOURCE_HEIGHT, rotations[i], true);
            for (float value : fill) assertTrue(Float.isFinite(value));
            assertPaneCovered(fill, crop, 960, 495);
            float scale = physicalScale(fill, 960);
            assertEquals(1.5542f, scale, 0.001f);
            assertTrue(scale < 2.0f);
            if (referenceScale < 0.0f) referenceScale = scale;
            else assertEquals(referenceScale, scale, 0.0001f);

            float[] mirrored = mirrorHorizontally(fill, 960);
            assertTrue(determinant(fill) * determinant(mirrored) < 0.0f);
            float centerX = (crop.left + crop.width / 2.0f) * 960;
            float centerY = (crop.top + crop.height / 2.0f) * 495;
            assertEquals(480.0f, map(mirrored, centerX, centerY)[0], 0.01f);
            assertEquals(247.5f, map(mirrored, centerX, centerY)[1], 0.01f);

            ReverseCameraLayout.Rect centered =
                    ReverseCameraLayout.centeredSourceCrop(crop);
            assertEquals(0.3f, centered.left, 0.0001f);
            assertEquals(0.25f, centered.top, 0.0001f);
            assertEquals(crop.width, centered.width, 0.0f);
            assertEquals(crop.height, centered.height, 0.0f);
        }
    }

    private static void assertCropFits(
            float[] transform, ReverseCameraLayout.Rect crop, int width, int height) {
        float left = crop.left * width;
        float top = crop.top * height;
        float right = crop.right() * width;
        float bottom = crop.bottom() * height;
        float[][] corners = {
                map(transform, left, top), map(transform, right, top),
                map(transform, right, bottom), map(transform, left, bottom)
        };
        for (float[] corner : corners) {
            assertTrue(corner[0] >= -1.0f && corner[0] <= width + 1.0f);
            assertTrue(corner[1] >= -1.0f && corner[1] <= height + 1.0f);
        }
        assertEquals(width / 2.0f,
                map(transform, (left + right) / 2.0f, (top + bottom) / 2.0f)[0], 0.01f);
        assertEquals(height / 2.0f,
                map(transform, (left + right) / 2.0f, (top + bottom) / 2.0f)[1], 0.01f);
    }

    private static void assertPaneCovered(
            float[] transform, ReverseCameraLayout.Rect crop, int width, int height) {
        float[][] corners = {
                inverseMap(transform, 0, 0), inverseMap(transform, width, 0),
                inverseMap(transform, width, height), inverseMap(transform, 0, height)
        };
        for (float[] corner : corners) {
            assertTrue(corner[0] >= crop.left * width - 0.01f);
            assertTrue(corner[0] <= crop.right() * width + 0.01f);
            assertTrue(corner[1] >= crop.top * height - 0.01f);
            assertTrue(corner[1] <= crop.bottom() * height + 0.01f);
        }
    }

    private static void assertUniformPhysicalScale(
            float[] transform, int width, int height) {
        float xScale = physicalScale(transform, width);
        float yScale = (float) Math.hypot(
                transform[1] * height / ReverseCameraCompositionView.SOURCE_HEIGHT,
                transform[4] * height / ReverseCameraCompositionView.SOURCE_HEIGHT);
        assertEquals(xScale, yScale, 0.0001f);
    }

    private static float physicalScale(float[] transform, int width) {
        return (float) Math.hypot(
                transform[0] * width / ReverseCameraCompositionView.SOURCE_WIDTH,
                transform[3] * width / ReverseCameraCompositionView.SOURCE_WIDTH);
    }

    private static float[] map(float[] transform, float x, float y) {
        return new float[]{
                transform[0] * x + transform[1] * y + transform[2],
                transform[3] * x + transform[4] * y + transform[5]
        };
    }

    private static float[] inverseMap(float[] transform, float x, float y) {
        float translatedX = x - transform[2];
        float translatedY = y - transform[5];
        float determinant = transform[0] * transform[4] - transform[1] * transform[3];
        return new float[]{
                (transform[4] * translatedX - transform[1] * translatedY) / determinant,
                (-transform[3] * translatedX + transform[0] * translatedY) / determinant
        };
    }

    private static float[] mirrorHorizontally(float[] transform, int width) {
        return new float[]{
                -transform[0], -transform[1], width - transform[2],
                transform[3], transform[4], transform[5],
                transform[6], transform[7], transform[8]
        };
    }

    private static float determinant(float[] transform) {
        return transform[0] * transform[4] - transform[1] * transform[3];
    }

    private static float rotatedAspect(ReverseCameraLayout.Rect crop, int degrees) {
        double radians = Math.toRadians(degrees);
        double cosine = Math.abs(Math.cos(radians));
        double sine = Math.abs(Math.sin(radians));
        double width = crop.width * ReverseCameraCompositionView.SOURCE_WIDTH;
        double height = crop.height * ReverseCameraCompositionView.SOURCE_HEIGHT;
        return (float) ((cosine * width + sine * height)
                / (sine * width + cosine * height));
    }
}
