package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class CameraBorderSettingsTest {
    @Test
    public void cameraScopesAreIndependentAndValidateWholePairs() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraBorderSettings.Border blue = new CameraBorderSettings.Border(4, 0xFF1234AB);
        CameraBorderSettings.writeBlind(preferences, CameraProfile.REAR_LEFT, blue);
        CameraBorderSettings.writeParking(preferences, ParkingCameraProfile.FRONT,
                new CameraBorderSettings.Border(16, 0xFFFFFFFF));
        CameraBorderSettings.writeReverse(preferences, 1, false,
                new CameraBorderSettings.Border(7, 0xFFABCDEF));
        CameraBorderSettings.writeReverse(preferences, 1, true,
                new CameraBorderSettings.Border(8, 0xFF010203));
        CameraBorderSettings.writeReverseElement(preferences,
                ReverseCameraLayout.BACKGROUND_PANE_ID,
                new CameraBorderSettings.Border(9, 0xFF102030));

        assertBorder(blue, CameraBorderSettings.forBlind(preferences, CameraProfile.REAR_LEFT));
        assertBorder(CameraBorderSettings.defaults(),
                CameraBorderSettings.forBlind(preferences, CameraProfile.REAR_RIGHT));
        assertEquals(16, CameraBorderSettings.forParking(
                preferences, ParkingCameraProfile.FRONT).borderDp);
        assertEquals(7, CameraBorderSettings.forReverse(preferences, 1, false).borderDp);
        assertEquals(8, CameraBorderSettings.forReverse(preferences, 1, true).borderDp);
        assertEquals(9, CameraBorderSettings.forReverseElement(preferences,
                ReverseCameraLayout.BACKGROUND_PANE_ID).borderDp);
        assertEquals(0, CameraBorderSettings.forReverseElement(preferences,
                ReverseCameraLayout.WIDGET_PANE_ID).borderDp);
        assertThrows(IllegalArgumentException.class,
                () -> new CameraBorderSettings.Border(17, 0xFF000000));
        assertThrows(IllegalArgumentException.class,
                () -> new CameraBorderSettings.Border(2, 0x7F000000));

        String prefix = CameraBorderSettings.blindPrefix(
                CameraProfile.of(CameraProfile.FRONT_LEFT));
        preferences.putInt(CameraBorderSettings.widthKey(prefix), 5);
        assertBorder(CameraBorderSettings.defaults(),
                CameraBorderSettings.forBlind(preferences, CameraProfile.FRONT_LEFT));
    }

    @Test
    public void legacyMirrorPairMigratesBothWithoutOverwritingExplicitSource() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.putInt(RearviewMirrorSettings.PREF_BORDER_DP, 3);
        preferences.putInt(RearviewMirrorSettings.PREF_BORDER_ARGB, 0xFF445566);
        CameraBorderSettings.writeMirror(preferences, true,
                new CameraBorderSettings.Border(6, 0xFF778899));

        assertEquals(3, CameraBorderSettings.forMirror(preferences, false).borderDp);
        assertEquals(0xFF445566,
                CameraBorderSettings.forMirror(preferences, false).borderArgb);
        assertEquals(6, CameraBorderSettings.forMirror(preferences, true).borderDp);
        assertEquals(0xFF778899,
                CameraBorderSettings.forMirror(preferences, true).borderArgb);

        preferences.putInt(RearviewMirrorSettings.PREF_BORDER_DP, 12);
        preferences.putInt(RearviewMirrorSettings.PREF_BORDER_ARGB, 0xFF000001);
        assertEquals(3, CameraBorderSettings.forMirror(preferences, false).borderDp);
        assertEquals(6, CameraBorderSettings.forMirror(preferences, true).borderDp);
    }

    @Test
    public void mirrorPresetRestoresFrameAndOldPresetPreservesCurrentFrame() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraBorderSettings.writeMirror(preferences, false,
                new CameraBorderSettings.Border(5, 0xFF102030));
        CameraBorderSettings.saveMirrorPreset(preferences, false);
        CameraBorderSettings.writeMirror(preferences, false,
                new CameraBorderSettings.Border(1, 0xFF405060));
        assertTrue(CameraBorderSettings.loadMirrorPreset(preferences, false));
        assertEquals(5, CameraBorderSettings.forMirror(preferences, false).borderDp);

        assertFalse(CameraBorderSettings.loadMirrorPreset(preferences, true));
        assertEquals(0, CameraBorderSettings.forMirror(preferences, true).borderDp);
    }

    private static void assertBorder(
            CameraBorderSettings.Border expected, CameraBorderSettings.Border actual) {
        assertEquals(expected.borderDp, actual.borderDp);
        assertEquals(expected.borderArgb, actual.borderArgb);
    }
}
