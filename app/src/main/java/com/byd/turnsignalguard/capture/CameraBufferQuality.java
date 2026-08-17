package com.byd.turnsignalguard.capture;

import android.content.SharedPreferences;

final class CameraBufferQuality {
    static final String PREF_QUALITY = "camera_buffer_quality";

    static final int PERFORMANCE = 0;
    static final int BALANCED = 1;
    static final int QUALITY = 2;
    static final int ORIGINAL = 3;
    static final int DEFAULT = PERFORMANCE;

    private CameraBufferQuality() {}

    static int load(SharedPreferences preferences) {
        try {
            int value = preferences.getInt(PREF_QUALITY, DEFAULT);
            return isValid(value) ? value : DEFAULT;
        } catch (ClassCastException error) {
            return DEFAULT;
        }
    }

    static boolean isValid(int value) {
        return value >= PERFORMANCE && value <= ORIGINAL;
    }

    static int scalePercent(int value) {
        switch (value) {
            case PERFORMANCE:
                return 100;
            case BALANCED:
                return 150;
            case QUALITY:
                return 200;
            case ORIGINAL:
                return 0;
            default:
                throw new IllegalArgumentException("invalid camera buffer quality");
        }
    }

    static String label(int value) {
        switch (value) {
            case PERFORMANCE:
                return "performance";
            case BALANCED:
                return "balanced";
            case QUALITY:
                return "quality";
            case ORIGINAL:
                return "original";
            default:
                return "invalid";
        }
    }
}
