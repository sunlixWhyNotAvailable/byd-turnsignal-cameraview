package com.byd.extend;

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

    /** Final-buffer demand only; the full-frame snapshot retains bufferSizeForPane(). */
    static int[] outputSizeForPane(int paneWidth, int paneHeight,
            int sourceWidth, int sourceHeight, int quality, int cropNativeHeight) {
        if (cropNativeHeight <= 0 || cropNativeHeight > sourceHeight)
            throw new IllegalArgumentException("invalid crop buffer demand");
        int[] preset = bufferSizeForPane(
                paneWidth, paneHeight, sourceWidth, sourceHeight, quality);
        if (paneWidth <= 1 || paneHeight <= 1) return preset;
        int screenHeight = (int) Math.ceil(Math.max(paneHeight,
                paneWidth * (double) sourceHeight / sourceWidth));
        int height = Math.min(preset[1], Math.max(cropNativeHeight, screenHeight));
        return new int[]{Math.min(preset[0], Math.max(1,
                Math.round(height * (float) sourceWidth / sourceHeight))), height};
    }

    /** Position and mirroring do not affect allocation, including transient steering offsets. */
    static int cropNativeHeight(int sourceWidth, int sourceHeight,
            float cropWidth, float cropHeight, int rotationDegrees) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || !Float.isFinite(cropWidth)
                || !Float.isFinite(cropHeight) || cropWidth <= 0 || cropHeight <= 0
                || cropWidth > 1.0001f || cropHeight > 1.0001f
                || !CameraRotation.isValid(rotationDegrees))
            throw new IllegalArgumentException("invalid source crop dimensions");
        if (cropWidth >= 1f && cropHeight >= 1f) return sourceHeight;
        double angle = Math.toRadians(rotationDegrees);
        double cosine = Math.abs(Math.cos(angle)), sine = Math.abs(Math.sin(angle));
        double width = cropWidth * (double) sourceWidth;
        double height = cropHeight * (double) sourceHeight;
        double rows = sine * width + cosine * height;
        double columns = cosine * width + sine * height;
        double demand = Math.max(rows, columns * sourceHeight / sourceWidth);
        return Math.min(sourceHeight, Math.max(100, (int) Math.ceil(demand / 100.0) * 100));
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
