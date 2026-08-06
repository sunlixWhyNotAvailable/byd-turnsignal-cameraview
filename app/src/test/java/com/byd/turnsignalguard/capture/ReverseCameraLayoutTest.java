package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReverseCameraLayoutTest {
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
        layout = ReverseCameraLayout.sendToBack(
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
    public void coverCropKeepsCenterAndMatchesDestinationAspect() {
        ReverseCameraLayout.Rect crop = ReverseCameraLayout.sourceCrop(
                0.1f, 0.2f, 0.8f, 0.6f);
        ReverseCameraLayout.Rect covered = ReverseCameraLayout.coverSourceCrop(
                crop, 1600, 600,
                ReverseCameraCompositionView.SOURCE_WIDTH,
                ReverseCameraCompositionView.SOURCE_HEIGHT);
        float coveredAspect = covered.width * ReverseCameraCompositionView.SOURCE_WIDTH
                / (covered.height * ReverseCameraCompositionView.SOURCE_HEIGHT);
        assertEquals(1600.0f / 600.0f, coveredAspect, 0.0001f);
        assertEquals(crop.left + crop.width / 2.0f,
                covered.left + covered.width / 2.0f, 0.0001f);
        assertEquals(crop.top + crop.height / 2.0f,
                covered.top + covered.height / 2.0f, 0.0001f);
        assertTrue(covered.width <= crop.width);
        assertTrue(covered.height <= crop.height);
    }
}
