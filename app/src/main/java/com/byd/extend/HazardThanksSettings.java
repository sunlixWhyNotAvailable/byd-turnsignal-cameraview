package com.byd.extend;

import android.content.SharedPreferences;

/** Persisted thank-you hazard settings; the sequence itself is runtime-only. */
public final class HazardThanksSettings {
    public static final String ENABLED = "hazard_thanks_enabled";
    public static final String FLASH_COUNT = "hazard_thanks_flashes";
    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_FLASH_COUNT = 2;

    private HazardThanksSettings() {}

    public static boolean enabled(SharedPreferences preferences) {
        return preferences != null && preferences.getBoolean(ENABLED, DEFAULT_ENABLED);
    }

    public static int flashes(SharedPreferences preferences) {
        int value = preferences == null
                ? DEFAULT_FLASH_COUNT : preferences.getInt(FLASH_COUNT, DEFAULT_FLASH_COUNT);
        return value >= 1 && value <= 3 ? value : DEFAULT_FLASH_COUNT;
    }
}
