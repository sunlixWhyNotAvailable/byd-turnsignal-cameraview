package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
    public void blindDisplaySlotsRetainExactPerDisplayLegacyFallback() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        CameraPlacement tablet = BlindSpotOverlayController.readPlacement(preferences, profile,
                CameraDisplayTarget.TABLET, 1920, 1080, 16, 36, 88);
        CameraPlacement cluster = BlindSpotOverlayController.readPlacement(preferences, profile,
                CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0);
        BlindSpotOverlayController.writePlacement((android.content.SharedPreferences) preferences, profile,
                CameraDisplayTarget.CLUSTER, CameraPlacement.of(.2f, .3f, .4f, .5f));

        assertEquals(tablet, BlindSpotOverlayController.readPlacement(preferences, profile,
                CameraDisplayTarget.TABLET, 1920, 1080, 16, 36, 88));
        assertEquals(CameraPlacement.of(.2f, .3f, .4f, .5f),
                BlindSpotOverlayController.readPlacement(preferences, profile,
                        CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0));
        assertEquals(cluster, CameraPlacement.fromLegacy(1920, 720,
                BlindSpotOverlayController.defaultScale(profile),
                BlindSpotOverlayController.defaultFrameAspect(profile),
                BlindSpotOverlayController.defaultPosition(profile, false),
                BlindSpotOverlayController.defaultPosition(profile, true), 0, 0, 0));
        assertFalse(preferences.contains(
                BlindSpotOverlayController.positionKey(profile, false)));
    }

    @Test
    public void sharedExplicitBlindRectangleIsReadOnlyFallbackForBothDisplays() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.FRONT_LEFT);
        preferences.edit()
                .putFloat(BlindSpotOverlayController.positionKey(profile, false), .1f)
                .putFloat(BlindSpotOverlayController.positionKey(profile, true), .2f)
                .putFloat(BlindSpotOverlayController.placementWidthKey(profile), .3f)
                .putFloat(BlindSpotOverlayController.placementHeightKey(profile), .4f).apply();
        CameraPlacement expected = CameraPlacement.of(.1f, .2f, .3f, .4f);
        assertEquals(expected, BlindSpotOverlayController.readPlacement(preferences, profile,
                CameraDisplayTarget.TABLET, 1920, 1080, 16, 36, 88));
        assertEquals(expected, BlindSpotOverlayController.readPlacement(preferences, profile,
                CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0));
        assertFalse(preferences.contains(BlindSpotOverlayController.placementKey(profile,
                CameraDisplayTarget.TABLET, BlindSpotOverlayController.PLACEMENT_X)));
        assertFalse(preferences.contains(BlindSpotOverlayController.placementKey(profile,
                CameraDisplayTarget.CLUSTER, BlindSpotOverlayController.PLACEMENT_X)));
    }

    @Test
    public void blindClusterFactoryCentersTabletFactorySize() {
        CameraProfile profile = CameraProfile.of(CameraProfile.FRONT_RIGHT);
        CameraPlacement tablet = BlindSpotOverlayController.defaultPlacement(profile,
                CameraDisplayTarget.TABLET, 1920, 1080, 16, 36, 88);
        CameraPlacement cluster = BlindSpotOverlayController.defaultPlacement(profile,
                CameraDisplayTarget.CLUSTER, 1920, 1080, 16, 36, 88);
        assertEquals(tablet.width, cluster.width, 0f);
        assertEquals(tablet.height, cluster.height, 0f);
        assertEquals((1f - cluster.width) / 2f, cluster.x, 0f);
        assertEquals((1f - cluster.height) / 2f, cluster.y, 0f);
    }

    @Test
    public void pureLegacyImportUsesExplicitRectOrExactAnchorConversion() {
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        CameraPlacement explicit = BlindSpotOverlayController.legacyPlacement(profile,
                1920, 720, 0, 0, 0, 30, 1.6f,
                .1f, .2f, .3f, .4f);
        assertEquals(CameraPlacement.of(.1f, .2f, .3f, .4f), explicit);

        CameraPlacement anchor = BlindSpotOverlayController.legacyPlacement(profile,
                1920, 720, 0, 0, 0, 30, 1.6f,
                .1f, .2f, null, null);
        assertEquals(CameraPlacement.fromLegacy(1920, 720, 30, 1.6f,
                .1f, .2f, 0, 0, 0), anchor);
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

    @Test
    public void slotSnapshotPreservesSubFivePercentLegacyRectangleExactly() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        CameraPlacement small = CameraPlacement.fromPixelRect(1, 2, 3, 4, 1000, 1000);
        BlindSpotOverlayController.writePlacement((android.content.SharedPreferences) preferences, profile,
                CameraDisplayTarget.CLUSTER, small);
        assertEquals(small, BlindSpotOverlayController.readPlacement(preferences, profile,
                CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0));
    }
}
