package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HazardThanksSettingsTest {
    @Test
    public void settingsUseOffAndTwoFlashDefaultsAndRestrictFlashCount() {
        CameraRuntimePreferences preferences = new CameraRuntimePreferences();
        assertFalse(HazardThanksSettings.enabled(preferences));
        assertEquals(2, HazardThanksSettings.flashes(preferences));

        preferences.edit().putBoolean(HazardThanksSettings.ENABLED, true)
                .putInt(HazardThanksSettings.FLASH_COUNT, 1).commit();
        assertTrue(HazardThanksSettings.enabled(preferences));
        assertEquals(1, HazardThanksSettings.flashes(preferences));

        for (int invalid : new int[]{0, 4}) {
            preferences.edit().putInt(HazardThanksSettings.FLASH_COUNT, invalid).commit();
            assertEquals(2, HazardThanksSettings.flashes(preferences));
        }

        preferences.edit().putInt(HazardThanksSettings.FLASH_COUNT, 3).commit();
        assertEquals(3, HazardThanksSettings.flashes(preferences));
    }
}
