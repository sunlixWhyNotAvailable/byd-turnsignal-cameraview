package com.byd.turnsignalguard.capture;

import android.content.SharedPreferences;

final class CameraBufferQuality {
    private static final int REFERENCE_PANE_HEIGHT = 357;

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

    static int[] bufferSizeForPane(
            int paneWidth, int paneHeight,
            int sourceWidth, int sourceHeight,
            int quality) {
        int scalePercent = scalePercent(quality);
        if (quality == ORIGINAL || paneWidth <= 1 || paneHeight <= 1) {
            return new int[]{sourceWidth, sourceHeight};
        }

        int width = (int) Math.min(sourceWidth,
                Math.max(1L, Math.round(paneWidth * scalePercent / 100.0)));
        int height = (int) Math.min(sourceHeight,
                Math.max(1L, Math.round(paneHeight * scalePercent / 100.0)));
        if ((long) width * sourceHeight <= (long) height * sourceWidth) {
            height = Math.min(height, Math.max(1,
                    Math.round(width * (float) sourceHeight / sourceWidth)));
        } else {
            width = Math.min(width, Math.max(1,
                    Math.round(height * (float) sourceWidth / sourceHeight)));
        }

        int minimumHeight = Math.min(sourceHeight,
                Math.max(1, Math.round(REFERENCE_PANE_HEIGHT * scalePercent / 100.0f)));
        height = Math.max(height, minimumHeight);
        width = Math.min(sourceWidth, Math.max(1,
                Math.round(height * (float) sourceWidth / sourceHeight)));
        return new int[]{width, height};
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
