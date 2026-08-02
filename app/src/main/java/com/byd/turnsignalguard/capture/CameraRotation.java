package com.byd.turnsignalguard.capture;

final class CameraRotation {
    static final int MIN_DEGREES = -180;
    static final int MAX_DEGREES = 180;
    static final int DEFAULT_DEGREES = 0;

    private CameraRotation() {}

    static int clamp(int degrees) {
        return Math.max(MIN_DEGREES, Math.min(MAX_DEGREES, degrees));
    }

    static boolean isValid(int degrees) {
        return degrees >= MIN_DEGREES && degrees <= MAX_DEGREES;
    }

    static float rotatedAspect(float contentAspect, int degrees) {
        if (!Float.isFinite(contentAspect) || contentAspect <= 0.0f) return 1.0f;
        double radians = Math.toRadians(clamp(degrees));
        double cosine = Math.abs(Math.cos(radians));
        double sine = Math.abs(Math.sin(radians));
        return (float) ((contentAspect * cosine + sine)
                / (contentAspect * sine + cosine));
    }

    static float[] scaleToRotatedBounds(
            int width, int height, float contentAspect, int degrees) {
        if (width <= 0 || height <= 0
                || !Float.isFinite(contentAspect) || contentAspect <= 0.0f) {
            return new float[]{1.0f, 1.0f};
        }
        double radians = Math.toRadians(clamp(degrees));
        double cosine = Math.abs(Math.cos(radians));
        double sine = Math.abs(Math.sin(radians));
        double boundWidth = contentAspect * cosine + sine;
        double boundHeight = contentAspect * sine + cosine;
        double scale = Math.max(width / boundWidth, height / boundHeight);
        return new float[]{
                (float) (scale * contentAspect / width),
                (float) (scale / height)
        };
    }

    static int[] rotatedBounds(int width, int height, int degrees) {
        if (width <= 0 || height <= 0) return new int[]{1, 1};
        double radians = Math.toRadians(clamp(degrees));
        double cosine = Math.abs(Math.cos(radians));
        double sine = Math.abs(Math.sin(radians));
        return new int[]{
                Math.max(1, (int) Math.ceil(width * cosine + height * sine)),
                Math.max(1, (int) Math.ceil(width * sine + height * cosine))
        };
    }
}
