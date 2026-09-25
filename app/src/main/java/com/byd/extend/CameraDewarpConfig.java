package com.byd.extend;

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
    final int strengthPercent;
    // NaN means the legacy integer FOV has never been explicitly edited with precision.
    final float preciseFovDegrees;
    final float roiCenterX;
    final float roiCenterY;

    private CameraDewarpConfig(
            int lens, boolean enabled, int fovDegrees, int projection,
            float roiCenterX, float roiCenterY, int strengthPercent, float preciseFovDegrees) {
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
        if (strengthPercent < 1 || strengthPercent > 100) {
            throw new IllegalArgumentException("invalid correction strength");
        }
        if (!Float.isNaN(preciseFovDegrees) && (!Float.isFinite(preciseFovDegrees)
                || preciseFovDegrees < MIN_FOV_DEGREES || preciseFovDegrees > MAX_FOV_DEGREES)) {
            throw new IllegalArgumentException("invalid precise horizontal FOV");
        }
        this.strengthPercent = strengthPercent;
        this.preciseFovDegrees = preciseFovDegrees;
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
                lens, enabled, fovDegrees, projection, 0.5f, 0.5f, 100, Float.NaN);
    }

    static CameraDewarpConfig of(int lens, boolean enabled, int fovDegrees, int projection,
            int strengthPercent, float preciseFovDegrees) {
        return new CameraDewarpConfig(lens, enabled, fovDegrees, projection,
                0.5f, 0.5f, strengthPercent, preciseFovDegrees);
    }

    float horizontalFovDegrees() {
        return Float.isNaN(preciseFovDegrees) ? fovDegrees : preciseFovDegrees;
    }

    static CameraDewarpConfig readControls(SharedPreferences p, String prefix, CameraDewarpConfig value) {
        int strength = 100;
        float precise = Float.NaN;
        try { strength = p.getInt(prefix + "strength_percent", 100); } catch (ClassCastException ignored) { }
        try { precise = p.getFloat(prefix + "fov_precise", Float.NaN); } catch (ClassCastException ignored) { }
        if (strength < 1 || strength > 100) strength = 100;
        if (!Float.isFinite(precise) || precise < MIN_FOV_DEGREES || precise > MAX_FOV_DEGREES) precise = Float.NaN;
        return of(value.lens, value.enabled, value.fovDegrees, value.projection, strength, precise);
    }

    static void writeControls(SharedPreferences.Editor editor, String prefix, CameraDewarpConfig value) {
        editor.putInt(prefix + "strength_percent", value.strengthPercent);
        if (Float.isNaN(value.preciseFovDegrees)) editor.remove(prefix + "fov_precise");
        else editor.putFloat(prefix + "fov_precise", value.preciseFovDegrees);
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
            return readControls(preferences, prefix, of(lens, preferences.getBoolean(prefix + "enabled", false),
                    fov, projection));
        } catch (RuntimeException invalidPreferences) {
            return disabled(lens);
        }
    }

    static CameraDewarpConfig loadForProfile(
            SharedPreferences preferences, CameraProfile profile) {
        return loadScoped(preferences, lensFor(profile), profilePrefix(profile),
                defaultForProfile(profile));
    }

    static CameraDewarpConfig loadForParking(
            SharedPreferences preferences, ParkingCameraProfile profile) {
        int lens = lensFor(profile);
        CameraDewarpConfig fallback = defaultForParking(profile);
        String scopedPrefix = parkingPrefix(profile);
        try {
            int fov = preferences.getInt(scopedPrefix + "fov", fallback.fovDegrees);
            int projection = preferences.getInt(scopedPrefix + "projection", fallback.projection);
            if (fov < MIN_FOV_DEGREES || fov > MAX_FOV_DEGREES
                    || !isValidProjection(projection)) return disabled(lens);
            return readControls(preferences, scopedPrefix, of(lens, preferences.getBoolean(scopedPrefix + "enabled", fallback.enabled),
                    fov, projection));
        } catch (RuntimeException invalidPreferences) {
            return disabled(lens);
        }
    }

    static CameraDewarpConfig loadForReverse(
            SharedPreferences preferences, int cameraIndex) {
        return loadScoped(preferences, lensForReverseCamera(cameraIndex),
                reversePrefix(cameraIndex), defaultForReverse(cameraIndex));
    }

    static CameraDewarpConfig loadForReverseFront(
            SharedPreferences preferences, int cameraIndex) {
        int lens = lensForReverseFrontCamera(cameraIndex);
        CameraDewarpConfig fallback = defaultForReverseFront(cameraIndex);
        String prefix = reverseFrontPrefix(cameraIndex);
        try {
            int fov = preferences.getInt(prefix + "fov", fallback.fovDegrees);
            int projection = preferences.getInt(prefix + "projection", fallback.projection);
            if (fov < MIN_FOV_DEGREES || fov > MAX_FOV_DEGREES
                    || !isValidProjection(projection)) return disabled(lens);
            return readControls(preferences, prefix, of(lens, preferences.getBoolean(prefix + "enabled", fallback.enabled),
                    fov, projection));
        } catch (RuntimeException invalidPreferences) {
            return disabled(lens);
        }
    }

    static CameraDewarpConfig defaultForProfile(CameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        switch (profile.id) {
            case CameraProfile.REAR_LEFT:
            case CameraProfile.REAR_RIGHT:
                return of(lensFor(profile), true, 165, PROJECTION_CYLINDRICAL);
            case CameraProfile.FRONT_LEFT:
            case CameraProfile.FRONT_RIGHT:
                return of(lensFor(profile), true, 130, PROJECTION_RECTILINEAR);
            default:
                throw new IllegalArgumentException("invalid camera profile");
        }
    }

    /** Exact per-camera parking correction baseline; missing keys use this value. */
    static CameraDewarpConfig defaultForParking(ParkingCameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("parking profile required");
        return CameraDefaults.parkingDewarp(profile);
    }

    static CameraDewarpConfig defaultForReverse(int cameraIndex) {
        int lens = lensForReverseCamera(cameraIndex);
        if (cameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX) {
            return of(lens, false, 170, PROJECTION_CYLINDRICAL);
        }
        return of(lens, true, 163, PROJECTION_CYLINDRICAL);
    }

    static CameraDewarpConfig defaultForReverseFront(int cameraIndex) {
        if (cameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX) {
            return of(LENS_FRONT, false, DEFAULT_FOV_DEGREES, DEFAULT_PROJECTION);
        }
        return defaultForProfile(frontProfileForReverseSide(cameraIndex));
    }

    private static CameraDewarpConfig loadScoped(
            SharedPreferences preferences, int lens, String scopedPrefix,
            CameraDewarpConfig fallback) {
        String legacyPrefix = prefix(lens);
        String enabledKey = scopedPrefix + "enabled";
        String fovKey = scopedPrefix + "fov";
        String projectionKey = scopedPrefix + "projection";
        try {
            String enabledSource = preferences.contains(enabledKey) ? enabledKey
                    : preferences.contains(legacyPrefix + "enabled")
                            ? legacyPrefix + "enabled" : null;
            String fovSource = preferences.contains(fovKey) ? fovKey
                    : preferences.contains(legacyPrefix + "fov")
                            ? legacyPrefix + "fov" : null;
            String projectionSource = preferences.contains(projectionKey) ? projectionKey
                    : preferences.contains(legacyPrefix + "projection")
                            ? legacyPrefix + "projection" : null;
            boolean enabled = enabledSource == null
                    ? fallback.enabled : preferences.getBoolean(enabledSource, fallback.enabled);
            int fov = fovSource == null
                    ? fallback.fovDegrees : preferences.getInt(fovSource, fallback.fovDegrees);
            int projection = projectionSource == null
                    ? fallback.projection : preferences.getInt(projectionSource, fallback.projection);
            if (fov < MIN_FOV_DEGREES || fov > MAX_FOV_DEGREES
                    || !isValidProjection(projection)) {
                return disabled(lens);
            }
            // Legacy fallback stays read-only; an explicit scoped edit writes all three fields.
            return readControls(preferences, scopedPrefix, of(lens, enabled, fov, projection));
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
        if (loadForProfile(preferences, profile).enabled != value.enabled) {
            BlindSpotOverlayController.pinFrameAspect(editor, preferences, profile);
        }
        writeForProfile(editor, profile, value);
        editor.apply();
    }

    static void saveForParking(
            SharedPreferences preferences, ParkingCameraProfile profile,
            CameraDewarpConfig value) {
        SharedPreferences.Editor editor = preferences.edit();
        writeForParking(editor, profile, value);
        editor.apply();
    }

    static void saveForReverse(
            SharedPreferences preferences, int cameraIndex, CameraDewarpConfig value) {
        SharedPreferences.Editor editor = preferences.edit();
        writeForReverse(editor, cameraIndex, value);
        editor.apply();
    }

    static void saveForReverseFront(
            SharedPreferences preferences, int cameraIndex, CameraDewarpConfig value) {
        SharedPreferences.Editor editor = preferences.edit();
        writeForReverseFront(editor, cameraIndex, value);
        editor.apply();
    }

    static void write(SharedPreferences.Editor editor, CameraDewarpConfig value) {
        String prefix = prefix(value.lens);
        writeControls(editor, prefix, value);
        editor.putBoolean(prefix + "enabled", value.enabled)
                .putInt(prefix + "fov", value.fovDegrees)
                .putInt(prefix + "projection", value.projection);
    }

    static void writeForProfile(
            SharedPreferences.Editor editor, CameraProfile profile,
            CameraDewarpConfig value) {
        writeScoped(editor, lensFor(profile), profilePrefix(profile), value);
    }

    static void writeForParking(
            SharedPreferences.Editor editor, ParkingCameraProfile profile,
            CameraDewarpConfig value) {
        writeScoped(editor, lensFor(profile), parkingPrefix(profile), value);
    }

    static void writeForReverse(
            SharedPreferences.Editor editor, int cameraIndex,
            CameraDewarpConfig value) {
        writeScoped(editor, lensForReverseCamera(cameraIndex),
                reversePrefix(cameraIndex), value);
    }

    static void writeForReverseFront(
            SharedPreferences.Editor editor, int cameraIndex,
            CameraDewarpConfig value) {
        writeScoped(editor, lensForReverseFrontCamera(cameraIndex),
                reverseFrontPrefix(cameraIndex), value);
    }

    static int lensFor(CameraProfile profile) {
        return profile.right() ? LENS_RIGHT : LENS_LEFT;
    }

    static int lensFor(ParkingCameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("parking profile required");
        if (profile.id == ParkingCameraProfile.FRONT) return LENS_FRONT;
        if (profile.id == ParkingCameraProfile.REAR) return LENS_REAR;
        return "right".equals(profile.lens) ? LENS_RIGHT : LENS_LEFT;
    }

    static int lensForReverseCamera(int cameraIndex) {
        if (cameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX) return LENS_REAR;
        if (cameraIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX) return LENS_LEFT;
        if (cameraIndex == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX) return LENS_RIGHT;
        throw new IllegalArgumentException("invalid reverse camera index");
    }

    static int lensForReverseSideCamera(int cameraIndex) {
        if (cameraIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX) return LENS_LEFT;
        if (cameraIndex == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX) return LENS_RIGHT;
        throw new IllegalArgumentException("invalid reverse side camera index");
    }

    static int lensForReverseFrontCamera(int cameraIndex) {
        if (cameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX) return LENS_FRONT;
        return lensForReverseSideCamera(cameraIndex);
    }

    static CameraProfile frontProfileForReverseSide(int cameraIndex) {
        if (cameraIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX) {
            return CameraProfile.of(CameraProfile.FRONT_LEFT);
        }
        if (cameraIndex == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX) {
            return CameraProfile.of(CameraProfile.FRONT_RIGHT);
        }
        throw new IllegalArgumentException("invalid reverse side camera index");
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
                lens, value, fovDegrees, projection, roiCenterX, roiCenterY, strengthPercent, preciseFovDegrees);
    }

    CameraDewarpConfig withFov(int value) {
        return new CameraDewarpConfig(
                lens, enabled, value, projection, roiCenterX, roiCenterY, strengthPercent, Float.NaN);
    }

    CameraDewarpConfig withProjection(int value) {
        return new CameraDewarpConfig(
                lens, enabled, fovDegrees, value, roiCenterX, roiCenterY, strengthPercent, preciseFovDegrees);
    }

    CameraDewarpConfig withRoiCenter(float x, float y) {
        return new CameraDewarpConfig(lens, enabled, fovDegrees, projection, x, y, strengthPercent, preciseFovDegrees);
    }

    CameraDewarpConfig withStrength(int value) {
        return new CameraDewarpConfig(lens, enabled, fovDegrees, projection,
                roiCenterX, roiCenterY, value, preciseFovDegrees);
    }

    CameraDewarpConfig withHorizontalFov(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("invalid precise FOV");
        return new CameraDewarpConfig(lens, enabled, Math.round(value), projection,
                roiCenterX, roiCenterY, strengthPercent, value);
    }

    boolean sameMapping(CameraDewarpConfig other) {
        if (other == null || lens != other.lens || enabled != other.enabled) return false;
        if (!enabled) return true;
        return Float.floatToIntBits(horizontalFovDegrees()) == Float.floatToIntBits(other.horizontalFovDegrees())
                && (projection != PROJECTION_RECTILINEAR || strengthPercent == other.strengthPercent)
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
        writeControls(editor, scopedPrefix, value);
        return editor.putBoolean(scopedPrefix + "enabled", value.enabled)
                .putInt(scopedPrefix + "fov", value.fovDegrees)
                .putInt(scopedPrefix + "projection", value.projection);
    }

    private static String profilePrefix(CameraProfile profile) {
        return "camera_dewarp_v3_overlay_" + profile.wireName + "_";
    }

    private static String parkingPrefix(ParkingCameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("parking profile required");
        return "camera_dewarp_v3_parking_"
                + profile.wireName.toLowerCase(java.util.Locale.US) + "_";
    }

    private static String reversePrefix(int cameraIndex) {
        lensForReverseCamera(cameraIndex);
        return "camera_dewarp_v3_reverse_" + cameraIndex + "_";
    }

    private static String reverseFrontPrefix(int cameraIndex) {
        lensForReverseFrontCamera(cameraIndex);
        return "camera_dewarp_v3_reverse_front_" + cameraIndex + "_";
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
