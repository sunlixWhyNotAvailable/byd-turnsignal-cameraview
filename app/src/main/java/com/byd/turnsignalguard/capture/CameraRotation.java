package com.byd.turnsignalguard.capture;

import android.graphics.Matrix;
import android.graphics.RectF;

final class CameraRotation {
    static final int MIN_DEGREES = -180;
    static final int MAX_DEGREES = 180;
    static final int DEFAULT_DEGREES = 0;
    static final int MODE_FIT = 0;
    static final int MODE_FILL = 1;
    static final int MODE_ALIGNED = 2;

    private CameraRotation() {}

    static int clamp(int degrees) {
        return Math.max(MIN_DEGREES, Math.min(MAX_DEGREES, degrees));
    }

    static boolean isValid(int degrees) {
        return degrees >= MIN_DEGREES && degrees <= MAX_DEGREES;
    }

    static boolean isValidMode(int mode) {
        return mode >= MODE_FIT && mode <= MODE_ALIGNED;
    }

    static String modeLabel(int mode) {
        if (mode == MODE_FILL) return "Fill";
        if (mode == MODE_ALIGNED) return "Вирівняний";
        return "Fit";
    }

    static void setSourceCropTransform(
            Matrix transform,
            RectF sourceCrop,
            RectF destination,
            int degrees,
            int mode,
            RectF sourceBounds,
            boolean mirrorHorizontally) {
        int safeDegrees = clamp(degrees);
        if (mode == MODE_ALIGNED) {
            float[] source = rotatedCorners(sourceCrop, safeDegrees);
            float[] target = new float[]{
                    destination.left, destination.top,
                    destination.right, destination.top,
                    destination.right, destination.bottom,
                    destination.left, destination.bottom
            };
            transform.setPolyToPoly(source, 0, target, 0, 4);
        } else {
            RectF effectiveCrop = mode == MODE_FILL
                    ? fillCrop(sourceCrop, sourceBounds, safeDegrees)
                    : sourceCrop;
            transform.setRectToRect(
                    effectiveCrop, destination, Matrix.ScaleToFit.FILL);
            transform.preRotate(
                    safeDegrees, effectiveCrop.centerX(), effectiveCrop.centerY());
        }
        if (mirrorHorizontally) {
            transform.postScale(-1.0f, 1.0f,
                    destination.centerX(), destination.centerY());
        }
    }

    static float[] rotatedCorners(RectF rect, int degrees) {
        float cx = rect.centerX();
        float cy = rect.centerY();
        float[] points = new float[]{
                rect.left, rect.top,
                rect.right, rect.top,
                rect.right, rect.bottom,
                rect.left, rect.bottom
        };
        Matrix rotation = new Matrix();
        rotation.setRotate(clamp(degrees), cx, cy);
        rotation.mapPoints(points);
        return points;
    }

    private static RectF fillCrop(RectF crop, RectF bounds, int degrees) {
        if (bounds == null || degrees == 0) return crop;
        double radians = Math.toRadians(degrees);
        double cosine = Math.abs(Math.cos(radians));
        double sine = Math.abs(Math.sin(radians));
        double extentX = cosine * crop.width() / 2.0d + sine * crop.height() / 2.0d;
        double extentY = sine * crop.width() / 2.0d + cosine * crop.height() / 2.0d;
        if (extentX <= 0.0d || extentY <= 0.0d) return crop;
        double scale = Math.min(1.0d, Math.min(
                Math.min((crop.centerX() - bounds.left) / extentX,
                        (bounds.right - crop.centerX()) / extentX),
                Math.min((crop.centerY() - bounds.top) / extentY,
                        (bounds.bottom - crop.centerY()) / extentY)));
        if (scale >= 0.999999d) return crop;
        float halfWidth = (float) (crop.width() * Math.max(0.0d, scale) / 2.0d);
        float halfHeight = (float) (crop.height() * Math.max(0.0d, scale) / 2.0d);
        return new RectF(
                crop.centerX() - halfWidth, crop.centerY() - halfHeight,
                crop.centerX() + halfWidth, crop.centerY() + halfHeight);
    }
}
