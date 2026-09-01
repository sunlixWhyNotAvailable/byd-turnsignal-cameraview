package com.byd.extend;

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
        if (mode == MODE_ALIGNED) return "Stretch";
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
            // Stretch keeps the destination pane axis aligned.  Rotate the transient source
            // geometry instead; the persisted ROI remains the original axis-aligned rectangle.
            float[] source = rotatedSourceCorners(sourceCrop, safeDegrees);
            float[] target = axisDestinationCorners(destination);
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

    /**
     * Maps a normalized crop from the physical camera source into an input view or bitmap.
     * The input may use a pane-sized buffer, but rotation and display mode are always calculated
     * from the physical 1920x1300-style source instead of already-distorted view coordinates.
     */
    static void setSourceCropTransformForInput(
            Matrix transform,
            float cropLeft, float cropTop, float cropWidth, float cropHeight,
            RectF destination, int degrees, int mode,
            int sourceWidth, int sourceHeight, int inputWidth, int inputHeight,
            boolean mirrorHorizontally) {
        if (transform == null || destination == null
                || sourceWidth <= 0 || sourceHeight <= 0
                || inputWidth <= 0 || inputHeight <= 0
                || !(cropWidth > 0.0f) || !(cropHeight > 0.0f)) {
            throw new IllegalArgumentException("positive source and crop bounds are required");
        }
        int safeDegrees = clamp(degrees);
        if (mode == MODE_ALIGNED) {
            float[] source = rotatedSourceCorners(
                    cropLeft, cropTop, cropWidth, cropHeight,
                    safeDegrees, sourceWidth, sourceHeight, inputWidth, inputHeight);
            float[] target = axisDestinationCorners(destination);
            transform.setPolyToPoly(source, 0, target, 0, 4);
        } else {
            float[] values = sourceAwareProportionalTransformValues(
                    cropLeft, cropTop, cropWidth, cropHeight,
                    destination.left, destination.top, destination.right, destination.bottom,
                    safeDegrees, mode, sourceWidth, sourceHeight,
                    inputWidth, inputHeight, mirrorHorizontally);
            transform.setValues(values);
        }
        if (mode == MODE_ALIGNED && mirrorHorizontally) {
            transform.postScale(-1.0f, 1.0f,
                    destination.centerX(), destination.centerY());
        }
    }

    /**
     * Returns the output-space polygon occupied by the selected source ROI after the same
     * transform used by a live camera view.  Consumers draw this polygon as a black-outside
     * mask; the texture matrix alone cannot prevent samples outside an ROI after rotation.
     */
    static float[] transformedCropCornersForInput(
            float cropLeft, float cropTop, float cropWidth, float cropHeight,
            RectF destination, int degrees, int mode,
            int sourceWidth, int sourceHeight, int inputWidth, int inputHeight,
            boolean mirrorHorizontally) {
        if (destination == null) throw new IllegalArgumentException("destination is required");
        if (mode == MODE_ALIGNED) {
            if (sourceWidth <= 0 || sourceHeight <= 0
                    || inputWidth <= 0 || inputHeight <= 0
                    || !(cropWidth > 0.0f) || !(cropHeight > 0.0f)) {
                throw new IllegalArgumentException("positive source and crop bounds are required");
            }
            // Stretch maps the rotated transient source polygon onto the fixed pane rectangle,
            // so its visible crop mask is the pane itself rather than the unrotated source ROI.
            return fixedOutputCorners(mode, destination.left, destination.top,
                    destination.right, destination.bottom);
        }
        Matrix transform = new Matrix();
        setSourceCropTransformForInput(
                transform, cropLeft, cropTop, cropWidth, cropHeight, destination,
                degrees, mode, sourceWidth, sourceHeight, inputWidth, inputHeight,
                mirrorHorizontally);
        float[] corners = axisSourceCorners(
                cropLeft, cropTop, cropWidth, cropHeight,
                sourceWidth, sourceHeight, inputWidth, inputHeight);
        transform.mapPoints(corners);
        return corners;
    }

    static float[] fixedOutputCorners(
            int mode, float left, float top, float right, float bottom) {
        if (mode != MODE_ALIGNED) return null;
        return new float[]{left, top, right, top, right, bottom, left, bottom};
    }

    static float[] axisSourceCorners(
            float cropLeft, float cropTop, float cropWidth, float cropHeight,
            int sourceWidth, int sourceHeight, int inputWidth, int inputHeight) {
        float scaleX = (float) inputWidth / sourceWidth;
        float scaleY = (float) inputHeight / sourceHeight;
        float left = cropLeft * sourceWidth * scaleX;
        float top = cropTop * sourceHeight * scaleY;
        float right = (cropLeft + cropWidth) * sourceWidth * scaleX;
        float bottom = (cropTop + cropHeight) * sourceHeight * scaleY;
        return new float[]{left, top, right, top, right, bottom, left, bottom};
    }

    private static float[] axisDestinationCorners(RectF destination) {
        return fixedOutputCorners(MODE_ALIGNED, destination.left, destination.top,
                destination.right, destination.bottom);
    }

    private static float[] rotatedSourceCorners(RectF source, int degrees) {
        float[] corners = axisDestinationCorners(source);
        Matrix rotation = new Matrix();
        rotation.setRotate(clamp(degrees), source.centerX(), source.centerY());
        rotation.mapPoints(corners);
        return corners;
    }

    private static float[] rotatedSourceCorners(
            float cropLeft, float cropTop, float cropWidth, float cropHeight,
            int degrees, int sourceWidth, int sourceHeight,
            int inputWidth, int inputHeight) {
        return alignedSourceCorners(
                cropLeft, cropTop, cropWidth, cropHeight,
                degrees, sourceWidth, sourceHeight, inputWidth, inputHeight);
    }

    static float[] alignedSourceCorners(
            float cropLeft, float cropTop, float cropWidth, float cropHeight,
            int degrees, int sourceWidth, int sourceHeight,
            int inputWidth, int inputHeight) {
        float left = cropLeft * sourceWidth;
        float top = cropTop * sourceHeight;
        float right = (cropLeft + cropWidth) * sourceWidth;
        float bottom = (cropTop + cropHeight) * sourceHeight;
        double radians = Math.toRadians(clamp(degrees));
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        float centerX = (left + right) / 2.0f;
        float centerY = (top + bottom) / 2.0f;
        float[] corners = new float[]{
                left, top, right, top, right, bottom, left, bottom
        };
        for (int i = 0; i < corners.length; i += 2) {
            float x = corners[i] - centerX;
            float y = corners[i + 1] - centerY;
            corners[i] = centerX + x * cosine - y * sine;
            corners[i + 1] = centerY + x * sine + y * cosine;
        }
        float scaleX = (float) inputWidth / sourceWidth;
        float scaleY = (float) inputHeight / sourceHeight;
        for (int i = 0; i < corners.length; i += 2) {
            corners[i] *= scaleX;
            corners[i + 1] *= scaleY;
        }
        return corners;
    }

    static float[] sourceAwareProportionalTransformValues(
            float cropLeft, float cropTop, float cropWidth, float cropHeight,
            float destinationLeft, float destinationTop,
            float destinationRight, float destinationBottom,
            int degrees, int mode, int sourceWidth, int sourceHeight,
            int inputWidth, int inputHeight, boolean mirrorHorizontally) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || inputWidth <= 0 || inputHeight <= 0) {
            throw new IllegalArgumentException("positive source and input bounds are required");
        }
        double physicalCropWidth = cropWidth * sourceWidth;
        double physicalCropHeight = cropHeight * sourceHeight;
        double destinationWidth = destinationRight - destinationLeft;
        double destinationHeight = destinationBottom - destinationTop;
        if (!(physicalCropWidth > 0.0d) || !(physicalCropHeight > 0.0d)
                || !(destinationWidth > 0.0d) || !(destinationHeight > 0.0d)) {
            throw new IllegalArgumentException("positive crop and destination are required");
        }
        double radians = Math.toRadians(clamp(degrees));
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        double absoluteCosine = Math.abs(cosine);
        double absoluteSine = Math.abs(sine);
        double rotatedWidth = absoluteCosine * physicalCropWidth
                + absoluteSine * physicalCropHeight;
        double rotatedHeight = absoluteSine * physicalCropWidth
                + absoluteCosine * physicalCropHeight;
        double scale = mode == MODE_FILL
                ? Math.max(
                        (absoluteCosine * destinationWidth
                                + absoluteSine * destinationHeight) / physicalCropWidth,
                        (absoluteSine * destinationWidth
                                + absoluteCosine * destinationHeight) / physicalCropHeight)
                : Math.min(
                        destinationWidth / rotatedWidth,
                        destinationHeight / rotatedHeight);
        double sourceCenterX = (cropLeft + cropWidth / 2.0d) * sourceWidth;
        double sourceCenterY = (cropTop + cropHeight / 2.0d) * sourceHeight;
        double destinationCenterX = (destinationLeft + destinationRight) / 2.0d;
        double destinationCenterY = (destinationTop + destinationBottom) / 2.0d;
        double horizontal = mirrorHorizontally ? -1.0d : 1.0d;
        double physicalM00 = horizontal * scale * cosine;
        double physicalM01 = horizontal * -scale * sine;
        double physicalM10 = scale * sine;
        double physicalM11 = scale * cosine;
        return new float[]{
                (float) (physicalM00 * sourceWidth / inputWidth),
                (float) (physicalM01 * sourceHeight / inputHeight),
                (float) (destinationCenterX
                        - physicalM00 * sourceCenterX - physicalM01 * sourceCenterY),
                (float) (physicalM10 * sourceWidth / inputWidth),
                (float) (physicalM11 * sourceHeight / inputHeight),
                (float) (destinationCenterY
                        - physicalM10 * sourceCenterX - physicalM11 * sourceCenterY),
                0.0f, 0.0f, 1.0f
        };
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
