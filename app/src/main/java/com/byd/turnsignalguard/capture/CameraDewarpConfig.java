package com.byd.turnsignalguard.capture;

import android.content.SharedPreferences;

final class CameraDewarpConfig {
    static final int LENS_LEFT = 1;
    static final int LENS_RIGHT = 2;
    static final int LENS_REAR = 3;

    static final int DEFAULT_STRENGTH = 25;
    static final int DEFAULT_CENTER = 50;
    static final int DEFAULT_ZOOM = 115;

    final boolean enabled;
    final int strength;
    final int centerX;
    final int centerY;
    final int zoom;

    private CameraDewarpConfig(
            boolean enabled, int strength, int centerX, int centerY, int zoom) {
        this.enabled = enabled;
        this.strength = clamp(strength, 0, 100);
        this.centerX = clamp(centerX, 0, 100);
        this.centerY = clamp(centerY, 0, 100);
        this.zoom = clamp(zoom, 100, 160);
    }

    static CameraDewarpConfig disabled() {
        return of(false, DEFAULT_STRENGTH, DEFAULT_CENTER, DEFAULT_CENTER, DEFAULT_ZOOM);
    }

    static CameraDewarpConfig of(
            boolean enabled, int strength, int centerX, int centerY, int zoom) {
        return new CameraDewarpConfig(enabled, strength, centerX, centerY, zoom);
    }

    static CameraDewarpConfig load(SharedPreferences preferences, int lens) {
        String prefix = prefix(lens);
        return of(
                preferences.getBoolean(prefix + "enabled", false),
                preferences.getInt(prefix + "strength", DEFAULT_STRENGTH),
                preferences.getInt(prefix + "center_x", DEFAULT_CENTER),
                preferences.getInt(prefix + "center_y", DEFAULT_CENTER),
                preferences.getInt(prefix + "zoom", DEFAULT_ZOOM));
    }

    static void save(SharedPreferences preferences, int lens, CameraDewarpConfig value) {
        String prefix = prefix(lens);
        preferences.edit()
                .putBoolean(prefix + "enabled", value.enabled)
                .putInt(prefix + "strength", value.strength)
                .putInt(prefix + "center_x", value.centerX)
                .putInt(prefix + "center_y", value.centerY)
                .putInt(prefix + "zoom", value.zoom)
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

    boolean usesGpu() {
        return enabled;
    }

    CameraDewarpConfig withEnabled(boolean value) {
        return of(value, strength, centerX, centerY, zoom);
    }

    CameraDewarpConfig withValues(int nextStrength, int nextCenterX, int nextCenterY, int nextZoom) {
        return of(enabled, nextStrength, nextCenterX, nextCenterY, nextZoom);
    }

    private static String prefix(int lens) {
        if (lens == LENS_LEFT) return "camera_dewarp_left_";
        if (lens == LENS_RIGHT) return "camera_dewarp_right_";
        if (lens == LENS_REAR) return "camera_dewarp_rear_";
        throw new IllegalArgumentException("invalid camera lens");
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
