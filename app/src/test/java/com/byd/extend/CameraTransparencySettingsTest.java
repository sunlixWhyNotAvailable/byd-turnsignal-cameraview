package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CameraTransparencySettingsTest {
    @Test
    public void missingPreferenceMigratesToOpaqueDefault() {
        TestSharedPreferences settings = new TestSharedPreferences();

        BlindSpotOverlayController.migrateOverlayPreferences(settings);

        assertEquals(0, settings.getInt(
                BlindSpotOverlayController.PREF_TRANSPARENCY_PERCENT, -1));
        assertEquals(0, BlindSpotOverlayController.readTransparencyPercent(settings));
    }

    @Test
    public void readerClampsAndRepairsMalformedValues() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putInt(BlindSpotOverlayController.PREF_TRANSPARENCY_PERCENT, 140);
        assertEquals(100, BlindSpotOverlayController.readTransparencyPercent(settings));

        settings.putInt(BlindSpotOverlayController.PREF_TRANSPARENCY_PERCENT, -20);
        assertEquals(0, BlindSpotOverlayController.readTransparencyPercent(settings));

        settings.putString(BlindSpotOverlayController.PREF_TRANSPARENCY_PERCENT, "bad");
        assertEquals(0, BlindSpotOverlayController.readTransparencyPercent(settings));
    }

    @Test
    public void blindAndReverseBuildersPropagateGlobalTransparency() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putInt(BlindSpotOverlayController.PREF_TRANSPARENCY_PERCENT, 37);

        CameraShellProtocol.OverlaySpec blind = BlindSpotOverlayController.buildOverlaySpec(
                settings, CameraProfile.of(CameraProfile.REAR_LEFT), 1,
                CameraDisplayTarget.TABLET,
                1920, 990, 16, 36, 88);
        CameraShellProtocol.ReverseOverlaySpec reverse =
                ReverseCameraController.buildOverlaySpec(settings, 2);

        assertEquals(37, blind.transparencyPercent);
        assertEquals(37, reverse.transparencyPercent);
    }
}
