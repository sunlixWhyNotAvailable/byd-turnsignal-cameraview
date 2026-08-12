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
            transform.setValues(proportionalTransformValues(
                    sourceCrop.left, sourceCrop.top, sourceCrop.right, sourceCrop.bottom,
                    destination.left, destination.top, destination.right, destination.bottom,
                    safeDegrees, mode, false));
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

    /**
     * Pure geometry seam for the proportional FIT/FILL transform. It intentionally uses only
     * primitive values so JVM tests do not need to execute Android's Matrix implementation.
     */
    static float[] proportionalTransformValues(
            float sourceLeft, float sourceTop, float sourceRight, float sourceBottom,
            float destinationLeft, float destinationTop,
            float destinationRight, float destinationBottom,
            int degrees, int mode, boolean mirrorHorizontally) {
        double cropWidth = sourceRight - sourceLeft;
        double cropHeight = sourceBottom - sourceTop;
        double destinationWidth = destinationRight - destinationLeft;
        double destinationHeight = destinationBottom - destinationTop;
        if (!(cropWidth > 0.0d) || !(cropHeight > 0.0d)
                || !(destinationWidth > 0.0d) || !(destinationHeight > 0.0d)) {
            throw new IllegalArgumentException("positive crop and destination are required");
        }

        double radians = Math.toRadians(degrees);
        double cosine = Math.abs(Math.cos(radians));
        double sine = Math.abs(Math.sin(radians));
        double rotatedWidth = cosine * cropWidth + sine * cropHeight;
        double rotatedHeight = sine * cropWidth + cosine * cropHeight;
        double scale = mode == MODE_FILL
                ? Math.max(destinationWidth / rotatedWidth, destinationHeight / rotatedHeight)
                : Math.min(destinationWidth / rotatedWidth, destinationHeight / rotatedHeight);
        double sourceCenterX = (sourceLeft + sourceRight) / 2.0d;
        double sourceCenterY = (sourceTop + sourceBottom) / 2.0d;
        double destinationCenterX = (destinationLeft + destinationRight) / 2.0d;
        double destinationCenterY = (destinationTop + destinationBottom) / 2.0d;
        double signedHorizontal = mirrorHorizontally ? -1.0d : 1.0d;
        double m00 = signedHorizontal * scale * Math.cos(radians);
        double m01 = signedHorizontal * -scale * Math.sin(radians);
        double m10 = scale * Math.sin(radians);
        double m11 = scale * Math.cos(radians);
        return new float[]{
                (float) m00,
                (float) m01,
                (float) (destinationCenterX - m00 * sourceCenterX - m01 * sourceCenterY),
                (float) m10,
                (float) m11,
                (float) (destinationCenterY - m10 * sourceCenterX - m11 * sourceCenterY),
                0.0f, 0.0f, 1.0f
        };
    }
}
