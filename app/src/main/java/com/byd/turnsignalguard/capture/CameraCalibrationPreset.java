package com.byd.turnsignalguard.capture;

import android.content.SharedPreferences;

/** One explicit rollback point per camera and calibration context. */
final class CameraCalibrationPreset {
    private static final int VERSION = 1;

    private CameraCalibrationPreset() {}

    static boolean hasCamera(SharedPreferences preferences, CameraProfile profile) {
        try {
            return preferences.getInt(cameraPrefix(profile) + "version", 0) == VERSION;
        } catch (RuntimeException invalidPreset) {
            return false;
        }
    }

    static void saveCamera(SharedPreferences preferences, CameraProfile profile) {
        CameraValue value = activeCamera(preferences, profile);
        SharedPreferences.Editor editor = preferences.edit();
        writeCamera(editor, cameraPrefix(profile), value);
        editor.putInt(cameraPrefix(profile) + "version", VERSION).apply();
    }

    static boolean loadCamera(SharedPreferences preferences, CameraProfile profile) {
        if (!hasCamera(preferences, profile)) return false;
        try {
            applyCamera(preferences, profile,
                    readCamera(preferences, cameraPrefix(profile),
                            CameraDewarpConfig.lensFor(profile)));
            return true;
        } catch (RuntimeException invalidPreset) {
            return false;
        }
    }

    static int cameraMirrorTarget(CameraProfile profile) {
        return profile.right() ? profile.id - 1 : profile.id + 1;
    }

    static void mirrorCamera(SharedPreferences preferences, CameraProfile source) {
        CameraProfile target = CameraProfile.of(cameraMirrorTarget(source));
        CameraValue value = activeCamera(preferences, source);
        applyCamera(preferences, target, new CameraValue(
                value.raw.mirrored(), value.corrected.mirrored(),
                CameraDewarpConfig.of(CameraDewarpConfig.lensFor(target),
                        value.dewarp.enabled, value.dewarp.fovDegrees,
                        value.dewarp.projection)));
    }

    static boolean hasReverse(SharedPreferences preferences, int cameraIndex) {
        try {
            return preferences.getInt(reversePrefix(cameraIndex) + "version", 0) == VERSION;
        } catch (RuntimeException invalidPreset) {
            return false;
        }
    }

    static void saveReverse(SharedPreferences preferences, int cameraIndex) {
        ReverseValue value = activeReverse(preferences, cameraIndex);
        SharedPreferences.Editor editor = preferences.edit();
        writeReverse(editor, reversePrefix(cameraIndex), value);
        editor.putInt(reversePrefix(cameraIndex) + "version", VERSION).apply();
    }

    static boolean loadReverse(SharedPreferences preferences, int cameraIndex) {
        if (!hasReverse(preferences, cameraIndex)) return false;
        try {
            applyReverse(preferences, cameraIndex,
                    readReverse(preferences, reversePrefix(cameraIndex),
                            CameraDewarpConfig.lensForReverseCamera(cameraIndex)));
            return true;
        } catch (RuntimeException invalidPreset) {
            return false;
        }
    }

    static int reverseMirrorTarget(int cameraIndex) {
        if (cameraIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX) {
            return ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;
        }
        if (cameraIndex == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX) {
            return ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX;
        }
        return -1;
    }

    static boolean mirrorReverse(SharedPreferences preferences, int sourceCameraIndex) {
        int targetCameraIndex = reverseMirrorTarget(sourceCameraIndex);
        if (targetCameraIndex < 0) return false;
        ReverseValue value = activeReverse(preferences, sourceCameraIndex);
        applyReverse(preferences, targetCameraIndex, new ReverseValue(
                mirror(value.destination, true), mirror(value.raw, false),
                mirror(value.corrected, false), -value.rotationDegrees,
                value.displayMode,
                CameraDewarpConfig.of(
                        CameraDewarpConfig.lensForReverseCamera(targetCameraIndex),
                        value.dewarp.enabled, value.dewarp.fovDegrees,
                        value.dewarp.projection)));
        return true;
    }

    private static CameraValue activeCamera(
            SharedPreferences preferences, CameraProfile profile) {
        DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
        return new CameraValue(raw,
                DirectCameraCrop.loadCorrected(preferences, profile, raw),
                CameraDewarpConfig.load(preferences, CameraDewarpConfig.lensFor(profile)));
    }

    private static void applyCamera(
            SharedPreferences preferences, CameraProfile profile, CameraValue value) {
        SharedPreferences.Editor editor = preferences.edit();
        DirectCameraCrop.write(editor, profile, value.raw);
        DirectCameraCrop.writeCorrected(editor, profile, value.corrected);
        CameraDewarpConfig.write(editor, CameraDewarpConfig.of(
                CameraDewarpConfig.lensFor(profile), value.dewarp.enabled,
                value.dewarp.fovDegrees, value.dewarp.projection));
        editor.apply();
    }

    private static ReverseValue activeReverse(
            SharedPreferences preferences, int cameraIndex) {
        ReverseCameraLayout.Pane pane = ReverseCameraController
                .loadRawLayout(preferences).pane(cameraIndex);
        ReverseCameraLayout.Rect corrected = ReverseCameraController
                .loadCorrectedSourceCrop(preferences, cameraIndex,
                        ReverseCameraLayout.centeredSourceCrop(pane.sourceCrop));
        return new ReverseValue(pane.destination, pane.sourceCrop, corrected,
                pane.rotationDegrees, pane.displayMode,
                CameraDewarpConfig.load(preferences,
                        CameraDewarpConfig.lensForReverseCamera(cameraIndex)));
    }

    private static void applyReverse(
            SharedPreferences preferences, int cameraIndex, ReverseValue value) {
        SharedPreferences.Editor editor = preferences.edit()
                .putFloat(ReverseCameraController.paneSettingKey(cameraIndex, "left"),
                        value.destination.left)
                .putFloat(ReverseCameraController.paneSettingKey(cameraIndex, "top"),
                        value.destination.top)
                .putFloat(ReverseCameraController.paneSettingKey(cameraIndex, "width"),
                        value.destination.width)
                .putFloat(ReverseCameraController.paneSettingKey(cameraIndex, "height"),
                        value.destination.height)
                .putInt(ReverseCameraController.paneSettingKey(
                        cameraIndex, "rotation_degrees"), value.rotationDegrees)
                .putInt(ReverseCameraController.displayModeKey(cameraIndex), value.displayMode);
        ReverseCameraController.writeSourceCrop(editor, cameraIndex, value.raw, false);
        ReverseCameraController.writeSourceCrop(editor, cameraIndex, value.corrected, true);
        CameraDewarpConfig.write(editor, CameraDewarpConfig.of(
                CameraDewarpConfig.lensForReverseCamera(cameraIndex),
                value.dewarp.enabled, value.dewarp.fovDegrees, value.dewarp.projection));
        editor.apply();
    }

    private static void writeCamera(
            SharedPreferences.Editor editor, String prefix, CameraValue value) {
        writeCrop(editor, prefix + "raw_", value.raw);
        writeRect(editor, prefix + "corrected_", value.corrected.left,
                value.corrected.top, value.corrected.width, value.corrected.height);
        writeDewarp(editor, prefix, value.dewarp);
    }

    private static CameraValue readCamera(
            SharedPreferences preferences, String prefix, int lens) {
        int aspect = preferences.getInt(prefix + "raw_aspect", -1);
        int rotation = preferences.getInt(prefix + "raw_rotation", Integer.MIN_VALUE);
        int mode = preferences.getInt(prefix + "raw_mode", -1);
        DirectCameraCrop raw = DirectCameraCrop.requireNormalized(
                readFloat(preferences, prefix + "raw_x"),
                readFloat(preferences, prefix + "raw_y"),
                readFloat(preferences, prefix + "raw_w"),
                readFloat(preferences, prefix + "raw_h"),
                aspect, rotation, mode);
        DirectCameraCrop corrected = DirectCameraCrop.requireNormalized(
                readFloat(preferences, prefix + "corrected_x"),
                readFloat(preferences, prefix + "corrected_y"),
                readFloat(preferences, prefix + "corrected_w"),
                readFloat(preferences, prefix + "corrected_h"),
                aspect, rotation, mode);
        return new CameraValue(raw, corrected, readDewarp(preferences, prefix, lens));
    }

    private static void writeReverse(
            SharedPreferences.Editor editor, String prefix, ReverseValue value) {
        writeRect(editor, prefix + "destination_", value.destination.left,
                value.destination.top, value.destination.width, value.destination.height);
        writeRect(editor, prefix + "raw_", value.raw.left, value.raw.top,
                value.raw.width, value.raw.height);
        writeRect(editor, prefix + "corrected_", value.corrected.left,
                value.corrected.top, value.corrected.width, value.corrected.height);
        editor.putInt(prefix + "rotation", value.rotationDegrees)
                .putInt(prefix + "mode", value.displayMode);
        writeDewarp(editor, prefix, value.dewarp);
    }

    private static ReverseValue readReverse(
            SharedPreferences preferences, String prefix, int lens) {
        ReverseCameraLayout.Rect destination = readRect(
                preferences, prefix + "destination_", true);
        ReverseCameraLayout.Rect raw = readRect(preferences, prefix + "raw_", false);
        ReverseCameraLayout.Rect corrected = readRect(
                preferences, prefix + "corrected_", false);
        int rotation = preferences.getInt(prefix + "rotation", Integer.MIN_VALUE);
        int mode = preferences.getInt(prefix + "mode", -1);
        if (!CameraRotation.isValid(rotation)
                || !ReverseCameraLayout.isValidDisplayMode(mode)) {
            throw new IllegalArgumentException("invalid reverse output transform");
        }
        return new ReverseValue(destination, raw, corrected, rotation, mode,
                readDewarp(preferences, prefix, lens));
    }

    private static void writeCrop(
            SharedPreferences.Editor editor, String prefix, DirectCameraCrop crop) {
        writeRect(editor, prefix, crop.left, crop.top, crop.width, crop.height);
        editor.putInt(prefix + "aspect", crop.aspectMode)
                .putInt(prefix + "rotation", crop.rotationDegrees)
                .putInt(prefix + "mode", crop.rotationMode);
    }

    private static void writeRect(
            SharedPreferences.Editor editor, String prefix,
            float x, float y, float width, float height) {
        editor.putFloat(prefix + "x", x).putFloat(prefix + "y", y)
                .putFloat(prefix + "w", width).putFloat(prefix + "h", height);
    }

    private static ReverseCameraLayout.Rect readRect(
            SharedPreferences preferences, String prefix, boolean destination) {
        float x = readFloat(preferences, prefix + "x");
        float y = readFloat(preferences, prefix + "y");
        float width = readFloat(preferences, prefix + "w");
        float height = readFloat(preferences, prefix + "h");
        if (destination) {
            if (x < 0.0f || y < 0.0f || width <= 0.0f || height <= 0.0f
                    || x + width > 1.0f || y + height > 1.0f
                    || width < ReverseCameraLayout.MIN_DESTINATION_SIZE
                    || height < ReverseCameraLayout.MIN_DESTINATION_SIZE) {
                throw new IllegalArgumentException("invalid preset geometry");
            }
        } else {
            SourceCropPolicy.requireValid(x, y, width, height);
        }
        return destination
                ? ReverseCameraLayout.destination(x, y, width, height)
                : ReverseCameraLayout.sourceCrop(x, y, width, height);
    }

    private static ReverseCameraLayout.Rect mirror(
            ReverseCameraLayout.Rect rect, boolean destination) {
        float x = 1.0f - rect.left - rect.width;
        return destination
                ? ReverseCameraLayout.destination(x, rect.top, rect.width, rect.height)
                : ReverseCameraLayout.sourceCrop(x, rect.top, rect.width, rect.height);
    }

    private static void writeDewarp(
            SharedPreferences.Editor editor, String prefix, CameraDewarpConfig value) {
        editor.putBoolean(prefix + "correction", value.enabled)
                .putInt(prefix + "fov", value.fovDegrees)
                .putInt(prefix + "projection", value.projection);
    }

    private static CameraDewarpConfig readDewarp(
            SharedPreferences preferences, String prefix, int lens) {
        if (!preferences.contains(prefix + "correction")) {
            throw new IllegalArgumentException("missing preset correction");
        }
        int fov = preferences.getInt(prefix + "fov", -1);
        int projection = preferences.getInt(prefix + "projection", -1);
        if (fov < CameraDewarpConfig.MIN_FOV_DEGREES
                || fov > CameraDewarpConfig.MAX_FOV_DEGREES
                || !CameraDewarpConfig.isValidProjection(projection)) {
            throw new IllegalArgumentException("invalid preset correction");
        }
        return CameraDewarpConfig.of(lens,
                preferences.getBoolean(prefix + "correction", false), fov, projection);
    }

    private static float readFloat(SharedPreferences preferences, String key) {
        if (!preferences.contains(key)) throw new IllegalArgumentException("missing " + key);
        float value = preferences.getFloat(key, Float.NaN);
        if (!Float.isFinite(value)) throw new IllegalArgumentException("invalid " + key);
        return value;
    }

    private static String cameraPrefix(CameraProfile profile) {
        return "camera_calibration_preset_v1_" + profile.wireName + "_";
    }

    private static String reversePrefix(int cameraIndex) {
        CameraDewarpConfig.lensForReverseCamera(cameraIndex);
        return "reverse_calibration_preset_v1_" + cameraIndex + "_";
    }

    private static final class CameraValue {
        final DirectCameraCrop raw;
        final DirectCameraCrop corrected;
        final CameraDewarpConfig dewarp;

        CameraValue(
                DirectCameraCrop raw, DirectCameraCrop corrected,
                CameraDewarpConfig dewarp) {
            this.raw = raw;
            this.corrected = corrected;
            this.dewarp = dewarp;
        }
    }

    private static final class ReverseValue {
        final ReverseCameraLayout.Rect destination;
        final ReverseCameraLayout.Rect raw;
        final ReverseCameraLayout.Rect corrected;
        final int rotationDegrees;
        final int displayMode;
        final CameraDewarpConfig dewarp;

        ReverseValue(
                ReverseCameraLayout.Rect destination, ReverseCameraLayout.Rect raw,
                ReverseCameraLayout.Rect corrected, int rotationDegrees,
                int displayMode, CameraDewarpConfig dewarp) {
            this.destination = destination;
            this.raw = raw;
            this.corrected = corrected;
            this.rotationDegrees = rotationDegrees;
            this.displayMode = displayMode;
            this.dewarp = dewarp;
        }
    }
}
