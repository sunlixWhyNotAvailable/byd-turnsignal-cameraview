package com.byd.turnsignalguard.capture;

import android.content.SharedPreferences;

final class CameraDewarpConfig {
    static final int LENS_LEFT = 1;
    static final int LENS_RIGHT = 2;
    static final int LENS_REAR = 3;
    static final int LENS_FRONT = 4;

    static final int MIN_FOV_DEGREES = 60;
    static final int DEFAULT_FOV_DEGREES = 100;
    static final int MAX_FOV_DEGREES = 170;

    static final int PROJECTION_RECTILINEAR = 0;
    static final int PROJECTION_CYLINDRICAL = 1;
    static final int DEFAULT_PROJECTION = PROJECTION_RECTILINEAR;

    final int lens;
    final boolean enabled;
    final int fovDegrees;
    final int projection;
    final float roiCenterX;
    final float roiCenterY;

    private CameraDewarpConfig(
            int lens, boolean enabled, int fovDegrees, int projection,
            float roiCenterX, float roiCenterY) {
        if (!isValidLens(lens)) throw new IllegalArgumentException("invalid camera lens");
        if (!isValidProjection(projection)) {
            throw new IllegalArgumentException("invalid camera projection");
        }
        if (!Float.isFinite(roiCenterX) || !Float.isFinite(roiCenterY)
                || roiCenterX < 0.0f || roiCenterX > 1.0f
                || roiCenterY < 0.0f || roiCenterY > 1.0f) {
            throw new IllegalArgumentException("invalid dewarp ROI center");
        }
        this.lens = lens;
        this.enabled = enabled;
        this.fovDegrees = clamp(fovDegrees, MIN_FOV_DEGREES, MAX_FOV_DEGREES);
        this.projection = projection;
        this.roiCenterX = roiCenterX;
        this.roiCenterY = roiCenterY;
    }

    static CameraDewarpConfig disabled(int lens) {
        return of(lens, false, DEFAULT_FOV_DEGREES, DEFAULT_PROJECTION);
    }

    static CameraDewarpConfig of(int lens, boolean enabled, int fovDegrees) {
        return of(lens, enabled, fovDegrees, DEFAULT_PROJECTION);
    }

    static CameraDewarpConfig of(
            int lens, boolean enabled, int fovDegrees, int projection) {
        return new CameraDewarpConfig(
                lens, enabled, fovDegrees, projection, 0.5f, 0.5f);
    }

    static CameraDewarpConfig load(SharedPreferences preferences, int lens) {
        String prefix = prefix(lens);
        try {
            int fov = preferences.getInt(prefix + "fov", DEFAULT_FOV_DEGREES);
            int projection = preferences.getInt(prefix + "projection", DEFAULT_PROJECTION);
            if (fov < MIN_FOV_DEGREES || fov > MAX_FOV_DEGREES
                    || !isValidProjection(projection)) {
                return disabled(lens);
            }
            return of(lens, preferences.getBoolean(prefix + "enabled", false),
                    fov, projection);
        } catch (RuntimeException invalidPreferences) {
            return disabled(lens);
        }
    }

    static CameraDewarpConfig loadForProfile(
            SharedPreferences preferences, CameraProfile profile) {
        return loadScoped(preferences, lensFor(profile), profilePrefix(profile));
    }

    static CameraDewarpConfig loadForReverse(
            SharedPreferences preferences, int cameraIndex) {
        return loadScoped(preferences, lensForReverseCamera(cameraIndex),
                reversePrefix(cameraIndex));
    }

    private static CameraDewarpConfig loadScoped(
            SharedPreferences preferences, int lens, String scopedPrefix) {
        String legacyPrefix = prefix(lens);
        String enabledKey = scopedPrefix + "enabled";
        String fovKey = scopedPrefix + "fov";
        String projectionKey = scopedPrefix + "projection";
        boolean complete = preferences.contains(enabledKey)
                && preferences.contains(fovKey)
                && preferences.contains(projectionKey);
        boolean hasStoredValue = preferences.contains(enabledKey)
                || preferences.contains(fovKey)
                || preferences.contains(projectionKey)
                || preferences.contains(legacyPrefix + "enabled")
                || preferences.contains(legacyPrefix + "fov")
                || preferences.contains(legacyPrefix + "projection");
        try {
            boolean enabled = preferences.getBoolean(
                    preferences.contains(enabledKey) ? enabledKey : legacyPrefix + "enabled",
                    false);
            int fov = preferences.getInt(
                    preferences.contains(fovKey) ? fovKey : legacyPrefix + "fov",
                    DEFAULT_FOV_DEGREES);
            int projection = preferences.getInt(
                    preferences.contains(projectionKey)
                            ? projectionKey : legacyPrefix + "projection",
                    DEFAULT_PROJECTION);
            if (fov < MIN_FOV_DEGREES || fov > MAX_FOV_DEGREES
                    || !isValidProjection(projection)) {
                return disabled(lens);
            }
            CameraDewarpConfig value = of(lens, enabled, fov, projection);
            if (!complete && hasStoredValue) {
                writeScoped(preferences.edit(), lens, scopedPrefix, value).apply();
            }
            return value;
        } catch (RuntimeException invalidPreferences) {
            return disabled(lens);
        }
    }

    static void save(SharedPreferences preferences, CameraDewarpConfig value) {
        SharedPreferences.Editor editor = preferences.edit();
        write(editor, value);
        editor.apply();
    }

    static void saveForProfile(
            SharedPreferences preferences, CameraProfile profile, CameraDewarpConfig value) {
        SharedPreferences.Editor editor = preferences.edit();
        writeForProfile(editor, profile, value);
        editor.apply();
    }

    static void saveForReverse(
            SharedPreferences preferences, int cameraIndex, CameraDewarpConfig value) {
        SharedPreferences.Editor editor = preferences.edit();
        writeForReverse(editor, cameraIndex, value);
        editor.apply();
    }

    static void write(SharedPreferences.Editor editor, CameraDewarpConfig value) {
        String prefix = prefix(value.lens);
        editor.putBoolean(prefix + "enabled", value.enabled)
                .putInt(prefix + "fov", value.fovDegrees)
                .putInt(prefix + "projection", value.projection);
    }

    static void writeForProfile(
            SharedPreferences.Editor editor, CameraProfile profile,
            CameraDewarpConfig value) {
        writeScoped(editor, lensFor(profile), profilePrefix(profile), value);
    }

    static void writeForReverse(
            SharedPreferences.Editor editor, int cameraIndex,
            CameraDewarpConfig value) {
        writeScoped(editor, lensForReverseCamera(cameraIndex),
                reversePrefix(cameraIndex), value);
    }

    static int lensFor(CameraProfile profile) {
        return profile.right() ? LENS_RIGHT : LENS_LEFT;
    }

    static int lensForReverseCamera(int cameraIndex) {
        if (cameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX) return LENS_REAR;
        if (cameraIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX) return LENS_LEFT;
        if (cameraIndex == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX) return LENS_RIGHT;
        throw new IllegalArgumentException("invalid reverse camera index");
    }

    static boolean isValidLens(int lens) {
        return lens >= LENS_LEFT && lens <= LENS_FRONT;
    }

    static boolean isValidProjection(int projection) {
        return projection >= PROJECTION_RECTILINEAR
                && projection <= PROJECTION_CYLINDRICAL;
    }

    static String projectionLabel(int projection) {
        return projection == PROJECTION_CYLINDRICAL ? "Cylindrical" : "Rectilinear";
    }

    boolean usesGpu() {
        return enabled;
    }

    CameraDewarpConfig withEnabled(boolean value) {
        return new CameraDewarpConfig(
                lens, value, fovDegrees, projection, roiCenterX, roiCenterY);
    }

    CameraDewarpConfig withFov(int value) {
        return new CameraDewarpConfig(
                lens, enabled, value, projection, roiCenterX, roiCenterY);
    }

    CameraDewarpConfig withProjection(int value) {
        return new CameraDewarpConfig(
                lens, enabled, fovDegrees, value, roiCenterX, roiCenterY);
    }

    CameraDewarpConfig withRoiCenter(float x, float y) {
        return new CameraDewarpConfig(lens, enabled, fovDegrees, projection, x, y);
    }

    boolean sameMapping(CameraDewarpConfig other) {
        if (other == null || lens != other.lens || enabled != other.enabled) return false;
        if (!enabled) return true;
        return fovDegrees == other.fovDegrees
                && projection == other.projection
                && Float.floatToIntBits(roiCenterX)
                        == Float.floatToIntBits(other.roiCenterX)
                && Float.floatToIntBits(roiCenterY)
                        == Float.floatToIntBits(other.roiCenterY);
    }

    private static String prefix(int lens) {
        if (lens == LENS_LEFT) return "camera_dewarp_v2_left_";
        if (lens == LENS_RIGHT) return "camera_dewarp_v2_right_";
        if (lens == LENS_REAR) return "camera_dewarp_v2_rear_";
        if (lens == LENS_FRONT) return "camera_dewarp_v2_front_";
        throw new IllegalArgumentException("invalid camera lens");
    }

    private static SharedPreferences.Editor writeScoped(
            SharedPreferences.Editor editor, int lens, String scopedPrefix,
            CameraDewarpConfig value) {
        if (value.lens != lens) throw new IllegalArgumentException("dewarp lens mismatch");
        return editor.putBoolean(scopedPrefix + "enabled", value.enabled)
                .putInt(scopedPrefix + "fov", value.fovDegrees)
                .putInt(scopedPrefix + "projection", value.projection);
    }

    private static String profilePrefix(CameraProfile profile) {
        return "camera_dewarp_v3_overlay_" + profile.wireName + "_";
    }

    private static String reversePrefix(int cameraIndex) {
        lensForReverseCamera(cameraIndex);
        return "camera_dewarp_v3_reverse_" + cameraIndex + "_";
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
