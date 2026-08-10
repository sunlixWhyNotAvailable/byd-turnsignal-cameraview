package com.byd.turnsignalguard.capture;

/** Shared normalized source-crop validation and active-value migration. */
final class SourceCropPolicy {
    static final float MIN_SIZE = 0.01f;

    private SourceCropPolicy() {}

    static void requireValid(float left, float top, float width, float height) {
        requireFinite(left, top, width, height);
        if (left < 0.0f || top < 0.0f
                || width < MIN_SIZE || height < MIN_SIZE
                || left + width > 1.0f || top + height > 1.0f) {
            throw new IllegalArgumentException("invalid normalized source crop");
        }
    }

    static void requireMinimumSize(float width, float height) {
        requireFinite(width, height);
        if (width < MIN_SIZE || height < MIN_SIZE) {
            throw new IllegalArgumentException("source crop size must be at least 1%");
        }
    }

    static boolean needsMigration(float width, float height) {
        return Float.isFinite(width) && Float.isFinite(height)
                && width > 0.0f && height > 0.0f
                && (width < MIN_SIZE || height < MIN_SIZE);
    }

    static float[] migrate(float left, float top, float width, float height) {
        requireFinite(left, top, width, height);
        if (width <= 0.0f || height <= 0.0f) {
            throw new IllegalArgumentException("source crop size must be positive");
        }
        float oldWidth = Math.min(width, 1.0f);
        float oldHeight = Math.min(height, 1.0f);
        float oldLeft = clamp(left, 0.0f, 1.0f - oldWidth);
        float oldTop = clamp(top, 0.0f, 1.0f - oldHeight);
        float scale = Math.max(1.0f,
                Math.max(MIN_SIZE / oldWidth, MIN_SIZE / oldHeight));
        float newWidth = Math.min(1.0f, Math.max(MIN_SIZE, oldWidth * scale));
        float newHeight = Math.min(1.0f, Math.max(MIN_SIZE, oldHeight * scale));
        float centerX = oldLeft + oldWidth / 2.0f;
        float centerY = oldTop + oldHeight / 2.0f;
        return new float[]{
                clamp(centerX - newWidth / 2.0f, 0.0f, 1.0f - newWidth),
                clamp(centerY - newHeight / 2.0f, 0.0f, 1.0f - newHeight),
                newWidth,
                newHeight
        };
    }

    private static void requireFinite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("source crop must be finite");
            }
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
