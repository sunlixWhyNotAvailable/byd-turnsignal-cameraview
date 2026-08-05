package com.byd.turnsignalguard.capture;

import android.content.SharedPreferences;

final class CameraDewarpConfig {
    static final int LENS_LEFT = 1;
    static final int LENS_RIGHT = 2;
    static final int LENS_REAR = 3;
    static final int LENS_FRONT = 4;

    static final int MIN_FOV_DEGREES = 60;
    static final int DEFAULT_FOV_DEGREES = 100;
    static final int MAX_FOV_DEGREES = 140;

    final int lens;
    final boolean enabled;
    final int fovDegrees;

    private CameraDewarpConfig(int lens, boolean enabled, int fovDegrees) {
        if (!isValidLens(lens)) throw new IllegalArgumentException("invalid camera lens");
        this.lens = lens;
        this.enabled = enabled;
        this.fovDegrees = clamp(fovDegrees, MIN_FOV_DEGREES, MAX_FOV_DEGREES);
    }

    static CameraDewarpConfig disabled(int lens) {
        return of(lens, false, DEFAULT_FOV_DEGREES);
    }

    static CameraDewarpConfig of(int lens, boolean enabled, int fovDegrees) {
        return new CameraDewarpConfig(lens, enabled, fovDegrees);
    }

    static CameraDewarpConfig load(SharedPreferences preferences, int lens) {
        String prefix = prefix(lens);
        try {
            int fov = preferences.getInt(prefix + "fov", DEFAULT_FOV_DEGREES);
            if (fov < MIN_FOV_DEGREES || fov > MAX_FOV_DEGREES) {
                return disabled(lens);
            }
            return of(lens, preferences.getBoolean(prefix + "enabled", false), fov);
        } catch (RuntimeException invalidPreferences) {
            return disabled(lens);
        }
    }

    static void save(SharedPreferences preferences, CameraDewarpConfig value) {
        String prefix = prefix(value.lens);
        preferences.edit()
                .putBoolean(prefix + "enabled", value.enabled)
                .putInt(prefix + "fov", value.fovDegrees)
                .apply();
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

    static int lensForDirectCamera(int cameraIndex) {
        if (cameraIndex == 1) return LENS_REAR;
        if (cameraIndex == 2) return LENS_LEFT;
        if (cameraIndex == 3) return LENS_RIGHT;
        if (cameraIndex == 4) return LENS_FRONT;
        throw new IllegalArgumentException("invalid direct camera index");
    }

    static boolean isValidLens(int lens) {
        return lens >= LENS_LEFT && lens <= LENS_FRONT;
    }

    boolean usesGpu() {
        return enabled;
    }

    CameraDewarpConfig withEnabled(boolean value) {
        return of(lens, value, fovDegrees);
    }

    CameraDewarpConfig withFov(int value) {
        return of(lens, enabled, value);
    }

    boolean sameMapping(CameraDewarpConfig other) {
        return other != null
                && lens == other.lens
                && enabled == other.enabled
                && fovDegrees == other.fovDegrees;
    }

    private static String prefix(int lens) {
        if (lens == LENS_LEFT) return "camera_dewarp_v2_left_";
        if (lens == LENS_RIGHT) return "camera_dewarp_v2_right_";
        if (lens == LENS_REAR) return "camera_dewarp_v2_rear_";
        if (lens == LENS_FRONT) return "camera_dewarp_v2_front_";
        throw new IllegalArgumentException("invalid camera lens");
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
