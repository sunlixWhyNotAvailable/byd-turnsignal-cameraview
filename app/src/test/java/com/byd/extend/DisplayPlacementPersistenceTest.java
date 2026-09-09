package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DisplayPlacementPersistenceTest {
    @Test
    public void slotKeyContractIsStableAndDisplayExplicit() {
        assertEquals("mirror_cluster_x", RearviewMirrorSettings.placementKey(
                CameraDisplayTarget.CLUSTER, RearviewMirrorSettings.PLACEMENT_X));
        assertEquals("mirror_tablet_height", RearviewMirrorSettings.placementKey(
                CameraDisplayTarget.TABLET, RearviewMirrorSettings.PLACEMENT_HEIGHT));
        assertEquals("camera_front_left_tablet_width",
                BlindSpotOverlayController.placementKey(
                        CameraProfile.of(CameraProfile.FRONT_LEFT),
                        CameraDisplayTarget.TABLET,
                        BlindSpotOverlayController.PLACEMENT_WIDTH));
    }

    @Test
    public void freshInstallMaterializesOnlyCenteredClusterFactorySlots() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        DisplayPlacementPersistence.initialize(preferences, false,
                1920, 1080, 16, 36, 88);

        assertTrue(preferences.getBoolean(DisplayPlacementPersistence.PREF_INITIALIZED, false));
        assertFalse(preferences.contains(RearviewMirrorSettings.placementKey(
                CameraDisplayTarget.TABLET, RearviewMirrorSettings.PLACEMENT_X)));
        assertFalse(preferences.contains(RearviewMirrorSettings.PREF_TARGET));
        assertFalse(preferences.contains(RearviewMirrorSettings.PREF_ORIGINAL_X));
        assertEquals(RearviewMirrorSettings.defaultPlacement(CameraDisplayTarget.CLUSTER),
                RearviewMirrorSettings.placement(preferences, CameraDisplayTarget.CLUSTER));
        for (CameraProfile profile : CameraProfile.values()) {
            CameraPlacement expected = BlindSpotOverlayController.defaultPlacement(profile,
                    CameraDisplayTarget.CLUSTER, 1920, 1080, 16, 36, 88);
            assertEquals(expected, BlindSpotOverlayController.readPlacement(preferences, profile,
                    CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0));
            assertFalse(preferences.contains(BlindSpotOverlayController.placementKey(
                    profile, CameraDisplayTarget.TABLET,
                    BlindSpotOverlayController.PLACEMENT_X)));
            assertFalse(preferences.contains(BlindSpotOverlayController.targetKey(profile)));
            assertFalse(preferences.contains(DirectCameraCrop.preferenceKey(profile, 0)));
        }
    }

    @Test
    public void existingInstallMaterializesNothingAndPreservesLegacyFallback() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putBoolean("guard_enabled", false);
        DisplayPlacementPersistence.initialize(preferences, true,
                1920, 1080, 16, 36, 88);
        assertTrue(preferences.getBoolean(DisplayPlacementPersistence.PREF_INITIALIZED, false));
        assertFalse(preferences.contains(RearviewMirrorSettings.placementKey(
                CameraDisplayTarget.CLUSTER, RearviewMirrorSettings.PLACEMENT_X)));
        for (CameraProfile profile : CameraProfile.values()) {
            assertFalse(preferences.contains(BlindSpotOverlayController.placementKey(
                    profile, CameraDisplayTarget.CLUSTER,
                    BlindSpotOverlayController.PLACEMENT_X)));
        }
    }

    @Test
    public void initializationMarkerMakesDecisionStable() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        DisplayPlacementPersistence.initialize(preferences, true,
                1920, 1080, 16, 36, 88);
        DisplayPlacementPersistence.initialize(preferences, false,
                1920, 1080, 16, 36, 88);
        assertFalse(preferences.contains(RearviewMirrorSettings.placementKey(
                CameraDisplayTarget.CLUSTER, RearviewMirrorSettings.PLACEMENT_X)));
    }
}
