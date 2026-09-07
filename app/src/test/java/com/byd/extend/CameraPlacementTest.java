package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CameraPlacementTest {
    @Test
    public void movingLegacyRectangleNeverResizesItAndStaysOnTheTenthsGrid() {
        CameraPlacement original = CameraPlacement.of(.01234f, .12345f, .023456f, .045678f);
        CameraPlacement moved = original.positionTenths(1f, 1f);
        assertEquals(original.width, moved.width, 0f);
        assertEquals(original.height, moved.height, 0f);
        assertEquals(.976f, moved.x, .000001f);
        assertEquals(.954f, moved.y, .000001f);
        CameraPlacement rawMove = original.move(.1f, .2f);
        assertEquals(original.width, rawMove.width, 0f);
        assertEquals(original.height, rawMove.height, 0f);
    }

    @Test
    public void legacyMigrationUsesExactEffectivePixelRectangle() {
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        CameraPlacement actual = BlindSpotOverlayController.readPlacement(
                new TestSharedPreferences(), profile, 1920, 1080, 16, 36, 88);
        int[] pixels = BlindSpotOverlayController.overlayGeometry(
                1920, 1080, BlindSpotOverlayController.DEFAULT_SCALE_PERCENT,
                BlindSpotOverlayController.defaultFrameAspect(profile),
                BlindSpotOverlayController.defaultPosition(profile, false),
                BlindSpotOverlayController.defaultPosition(profile, true),
                16, 36, 88);
        int[] migrated = actual.toPixelRect(1920, 1080);
        assertEquals(pixels[0], migrated[0]);
        assertEquals(pixels[1], migrated[1]);
        assertEquals(pixels[2], migrated[2]);
        assertEquals(pixels[3], migrated[3]);
    }

    @Test
    public void explicitPlacementRoundTripsAsWholeDisplayGeometry() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.FRONT_LEFT);
        CameraPlacement expected = CameraPlacement.of(0.123f, 0.234f, 0.345f, 0.456f);
        BlindSpotOverlayController.writePlacement(preferences, profile, expected);
        CameraPlacement actual = BlindSpotOverlayController.readPlacement(
                preferences, profile, 1920, 1080, 16, 36, 88);
        assertEquals(expected, actual);
    }

    @Test
    public void explicitEditGridIsPointOnePercentAndResizeKeepsOppositeCorner() {
        CameraPlacement placement = CameraPlacement.of(0.1234f, 0.2345f, 0.3456f, 0.4567f);
        CameraPlacement rounded = placement.roundedTenths();
        assertEquals(0.123f, rounded.x, 0.00001f);
        assertEquals(0.235f, rounded.y, 0.00001f);
        CameraPlacement resized = rounded.resizeCorner(0.1f, 0.1f, CameraPlacement.BOTTOM_RIGHT);
        assertEquals(rounded.x, resized.x, 0.00001f);
        assertEquals(rounded.y, resized.y, 0.00001f);
    }

    @Test
    public void sourceMigrationPreservesSmallLegacyRectangles() {
        CameraPlacement small = CameraPlacement.fromPixelRect(1, 2, 3, 4, 1000, 1000);
        assertEquals(0.003f, small.width, 0.00001f);
        assertEquals(0.004f, small.height, 0.00001f);
    }
}
