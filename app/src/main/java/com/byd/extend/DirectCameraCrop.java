package com.byd.extend;

import android.content.SharedPreferences;

final class DirectCameraCrop {
    static final String PREF_LEFT_X = "direct_crop_left_x";
    static final String PREF_LEFT_Y = "direct_crop_left_y";
    static final String PREF_LEFT_WIDTH = "direct_crop_left_width";
    static final String PREF_LEFT_HEIGHT = "direct_crop_left_height";
    static final String PREF_LEFT_ASPECT = "direct_crop_left_aspect";
    static final String PREF_LEFT_ROTATION = "direct_crop_left_rotation";
    static final String PREF_LEFT_ROTATION_MODE = "direct_crop_left_rotation_mode";
    static final String PREF_LEFT_MIRROR = "direct_crop_left_mirror";
    static final String PREF_RIGHT_X = "direct_crop_right_x";
    static final String PREF_RIGHT_Y = "direct_crop_right_y";
    static final String PREF_RIGHT_WIDTH = "direct_crop_right_width";
    static final String PREF_RIGHT_HEIGHT = "direct_crop_right_height";
    static final String PREF_RIGHT_ASPECT = "direct_crop_right_aspect";
    static final String PREF_RIGHT_ROTATION = "direct_crop_right_rotation";
    static final String PREF_RIGHT_ROTATION_MODE = "direct_crop_right_rotation_mode";
    static final String PREF_RIGHT_MIRROR = "direct_crop_right_mirror";
    static final String PREF_FRONT_LEFT_X = "direct_crop_front_left_x";
    static final String PREF_FRONT_LEFT_Y = "direct_crop_front_left_y";
    static final String PREF_FRONT_LEFT_WIDTH = "direct_crop_front_left_width";
    static final String PREF_FRONT_LEFT_HEIGHT = "direct_crop_front_left_height";
    static final String PREF_FRONT_LEFT_ASPECT = "direct_crop_front_left_aspect";
    static final String PREF_FRONT_LEFT_ROTATION = "direct_crop_front_left_rotation";
    static final String PREF_FRONT_LEFT_ROTATION_MODE =
            "direct_crop_front_left_rotation_mode";
    static final String PREF_FRONT_LEFT_MIRROR = "direct_crop_front_left_mirror";
    static final String PREF_FRONT_RIGHT_X = "direct_crop_front_right_x";
    static final String PREF_FRONT_RIGHT_Y = "direct_crop_front_right_y";
    static final String PREF_FRONT_RIGHT_WIDTH = "direct_crop_front_right_width";
    static final String PREF_FRONT_RIGHT_HEIGHT = "direct_crop_front_right_height";
    static final String PREF_FRONT_RIGHT_ASPECT = "direct_crop_front_right_aspect";
    static final String PREF_FRONT_RIGHT_ROTATION = "direct_crop_front_right_rotation";
    static final String PREF_FRONT_RIGHT_ROTATION_MODE =
            "direct_crop_front_right_rotation_mode";
    static final String PREF_FRONT_RIGHT_MIRROR = "direct_crop_front_right_mirror";

    static final int ASPECT_FOUR_THREE = 0;
    static final int ASPECT_SIXTEEN_NINE = 1;
    static final int ASPECT_ONE_ONE = 2;
    static final int ASPECT_FREE = 3;

    static final int EDGE_LEFT = 1;
    static final int EDGE_TOP = 2;
    static final int EDGE_RIGHT = 4;
    static final int EDGE_BOTTOM = 8;

    static final float SOURCE_WIDTH = 1920.0f;
    static final float SOURCE_HEIGHT = 1300.0f;
    static final float OUTPUT_ASPECT = 4.0f / 3.0f;
    private static final float LEGACY_MIN_SIZE = Float.MIN_NORMAL;
    private static final float TOUCH_MIN_WIDTH = 0.18f;
    private static final float TOUCH_MIN_HEIGHT = 0.18f;

    final float left;
    final float top;
    final float width;
    final float height;
    final int aspectMode;
    final int rotationDegrees;
    final int rotationMode;
    final boolean mirrorHorizontally;

    private DirectCameraCrop(
            float left, float top, float width, float height, int aspectMode,
            int rotationDegrees, int rotationMode) {
        this(left, top, width, height, aspectMode, rotationDegrees, rotationMode, false);
    }

    private DirectCameraCrop(
            float left, float top, float width, float height, int aspectMode,
            int rotationDegrees, int rotationMode, boolean mirrorHorizontally) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.aspectMode = aspectMode;
        this.rotationDegrees = rotationDegrees;
        this.rotationMode = rotationMode;
        this.mirrorHorizontally = mirrorHorizontally;
    }

    static DirectCameraCrop defaultFor(boolean rightCamera) {
        return defaultFor(rightCamera, ASPECT_FOUR_THREE);
    }

    static DirectCameraCrop defaultFor(CameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        switch (profile.id) {
            case CameraProfile.REAR_LEFT:
                return of(0.07071858f, 0.1937456f, 0.6358514f, 0.61250883f,
                        ASPECT_FREE, -30, CameraRotation.MODE_ALIGNED)
                        .withMirrorHorizontally(true);
            case CameraProfile.REAR_RIGHT:
                return of(0.29343003f, 0.1937456f, 0.6358514f, 0.61250883f,
                        ASPECT_FREE, 30, CameraRotation.MODE_ALIGNED)
                        .withMirrorHorizontally(true);
            case CameraProfile.FRONT_LEFT:
                return of(0.4807051f, 0.32135904f, 0.46608025f, 0.5074271f,
                        ASPECT_FREE, 45, CameraRotation.MODE_ALIGNED);
            case CameraProfile.FRONT_RIGHT:
                return of(0.053414617f, 0.32259566f, 0.46438393f, 0.5072246f,
                        ASPECT_FREE, -45, CameraRotation.MODE_ALIGNED);
            default:
                throw new IllegalArgumentException("invalid camera profile");
        }
    }

    static DirectCameraCrop defaultCorrectedFor(
            CameraProfile profile, DirectCameraCrop raw) {
        if (profile == null || raw == null) {
            throw new IllegalArgumentException("camera crop required");
        }
        switch (profile.id) {
            case CameraProfile.REAR_LEFT:
                return raw.withGeometry(of(
                        0.10472285f, 0.17163458f, 0.53600174f, 0.54995716f,
                        ASPECT_FREE));
            case CameraProfile.REAR_RIGHT:
                return raw.withGeometry(of(
                        0.35927543f, 0.17163458f, 0.53600174f, 0.54995716f,
                        ASPECT_FREE));
            case CameraProfile.FRONT_LEFT:
                return raw.withGeometry(of(
                        0.29754817f, 0.29489756f, 0.41666844f, 0.4414549f,
                        ASPECT_FREE));
            case CameraProfile.FRONT_RIGHT:
                return raw.withGeometry(of(
                        0.2795728f, 0.29326266f, 0.41497213f, 0.44125235f,
                        ASPECT_FREE));
            default:
                throw new IllegalArgumentException("invalid camera profile");
        }
    }

    /** Default geometry for a logical parking view.  Corner views inherit the
     * corresponding side-camera default; central views use the direct default. */
    static DirectCameraCrop defaultFor(ParkingCameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("parking profile required");
        switch (profile.id) {
            case ParkingCameraProfile.FL:
                return defaultFor(true);
            case ParkingCameraProfile.FR:
                return defaultFor(false);
            case ParkingCameraProfile.RR:
                return defaultFor(true);
            case ParkingCameraProfile.RL:
                return defaultFor(false);
            case ParkingCameraProfile.FRONT:
            case ParkingCameraProfile.LEFT:
            case ParkingCameraProfile.RIGHT:
                return of(0.0f, 0.0f, 1.0f, 1.0f, ASPECT_FREE,
                        CameraRotation.DEFAULT_DEGREES).withMirrorHorizontally(false);
            case ParkingCameraProfile.REAR:
                return of(0.0f, 0.0f, 1.0f, 1.0f, ASPECT_FREE,
                        CameraRotation.DEFAULT_DEGREES).withMirrorHorizontally(true);
            default:
                throw new IllegalArgumentException("invalid parking profile");
        }
    }

    static String preferenceKey(CameraProfile profile, int field) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        if (profile.rear()) {
            if (field == 0) return profile.right() ? PREF_RIGHT_X : PREF_LEFT_X;
            if (field == 1) return profile.right() ? PREF_RIGHT_Y : PREF_LEFT_Y;
            if (field == 2) return profile.right() ? PREF_RIGHT_WIDTH : PREF_LEFT_WIDTH;
            if (field == 3) return profile.right() ? PREF_RIGHT_HEIGHT : PREF_LEFT_HEIGHT;
            if (field == 4) return profile.right() ? PREF_RIGHT_ASPECT : PREF_LEFT_ASPECT;
            if (field == 5) return profile.right() ? PREF_RIGHT_ROTATION : PREF_LEFT_ROTATION;
            if (field == 6) return profile.right()
                    ? PREF_RIGHT_ROTATION_MODE : PREF_LEFT_ROTATION_MODE;
            if (field == 7) return profile.right() ? PREF_RIGHT_MIRROR : PREF_LEFT_MIRROR;
        } else {
            if (field == 0) return profile.right() ? PREF_FRONT_RIGHT_X : PREF_FRONT_LEFT_X;
            if (field == 1) return profile.right() ? PREF_FRONT_RIGHT_Y : PREF_FRONT_LEFT_Y;
            if (field == 2) return profile.right()
                    ? PREF_FRONT_RIGHT_WIDTH : PREF_FRONT_LEFT_WIDTH;
            if (field == 3) return profile.right()
                    ? PREF_FRONT_RIGHT_HEIGHT : PREF_FRONT_LEFT_HEIGHT;
            if (field == 4) return profile.right()
                    ? PREF_FRONT_RIGHT_ASPECT : PREF_FRONT_LEFT_ASPECT;
            if (field == 5) return profile.right()
                    ? PREF_FRONT_RIGHT_ROTATION : PREF_FRONT_LEFT_ROTATION;
            if (field == 6) return profile.right()
                    ? PREF_FRONT_RIGHT_ROTATION_MODE : PREF_FRONT_LEFT_ROTATION_MODE;
            if (field == 7) return profile.right()
                    ? PREF_FRONT_RIGHT_MIRROR : PREF_FRONT_LEFT_MIRROR;
        }
        throw new IllegalArgumentException("invalid crop field: " + field);
    }

    static DirectCameraCrop load(SharedPreferences preferences, CameraProfile profile) {
        DirectCameraCrop fallback = defaultFor(profile);
        try {
            DirectCameraCrop stored = normalized(
                    preferences.getFloat(preferenceKey(profile, 0), fallback.left),
                    preferences.getFloat(preferenceKey(profile, 1), fallback.top),
                    preferences.getFloat(preferenceKey(profile, 2), fallback.width),
                    preferences.getFloat(preferenceKey(profile, 3), fallback.height),
                    preferences.getInt(preferenceKey(profile, 4), fallback.aspectMode),
                    preferences.getInt(preferenceKey(profile, 5),
                            fallback.rotationDegrees),
                    preferences.getInt(preferenceKey(profile, 6),
                            fallback.rotationMode), LEGACY_MIN_SIZE)
                    .withMirrorHorizontally(readMirror(preferences,
                            preferenceKey(profile, 7), fallback.mirrorHorizontally));
            return migrateActive(stored);
        } catch (RuntimeException invalidActiveValue) {
            return fallback;
        }
    }

    static DirectCameraCrop load(SharedPreferences preferences, ParkingCameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("parking profile required");
        DirectCameraCrop fallback = defaultFor(profile);
        String prefix = parkingPrefix(profile);
        try {
            DirectCameraCrop stored = normalized(
                    preferences.getFloat(prefix + "x", fallback.left),
                    preferences.getFloat(prefix + "y", fallback.top),
                    preferences.getFloat(prefix + "width", fallback.width),
                    preferences.getFloat(prefix + "height", fallback.height),
                    preferences.getInt(prefix + "aspect", fallback.aspectMode),
                    preferences.getInt(prefix + "rotation", fallback.rotationDegrees),
                    preferences.getInt(prefix + "rotation_mode", fallback.rotationMode),
                    LEGACY_MIN_SIZE)
                    .withMirrorHorizontally(preferences.getBoolean(
                            prefix + "mirror", fallback.mirrorHorizontally));
            return migrateActive(stored);
        } catch (RuntimeException invalidValue) {
            return fallback;
        }
    }

    static void save(
            SharedPreferences preferences, CameraProfile profile, DirectCameraCrop crop) {
        SharedPreferences.Editor editor = preferences.edit();
        if (!sameGeometry(load(preferences, profile), crop)) {
            BlindSpotOverlayController.pinFrameAspect(editor, preferences, profile);
        }
        write(editor, profile, crop);
        editor.apply();
    }

    static void save(
            SharedPreferences preferences, ParkingCameraProfile profile, DirectCameraCrop crop) {
        SharedPreferences.Editor editor = preferences.edit();
        write(editor, profile, crop);
        editor.apply();
    }

    static void write(
            SharedPreferences.Editor editor, CameraProfile profile, DirectCameraCrop crop) {
        editor.putFloat(preferenceKey(profile, 0), crop.left)
                .putFloat(preferenceKey(profile, 1), crop.top)
                .putFloat(preferenceKey(profile, 2), crop.width)
                .putFloat(preferenceKey(profile, 3), crop.height)
                .putInt(preferenceKey(profile, 4), crop.aspectMode)
                .putInt(preferenceKey(profile, 5), crop.rotationDegrees)
                .putInt(preferenceKey(profile, 6), crop.rotationMode)
                .putBoolean(preferenceKey(profile, 7), crop.mirrorHorizontally);
    }

    static void saveOutputTransform(
            SharedPreferences preferences, CameraProfile profile, DirectCameraCrop crop) {
        SharedPreferences.Editor editor = preferences.edit();
        writeOutputTransform(editor, profile, crop);
        editor.apply();
    }

    static void saveOutputTransform(
            SharedPreferences preferences, ParkingCameraProfile profile, DirectCameraCrop crop) {
        SharedPreferences.Editor editor = preferences.edit();
        writeOutputTransform(editor, profile, crop);
        editor.apply();
    }

    static void writeOutputTransform(
            SharedPreferences.Editor editor, CameraProfile profile, DirectCameraCrop crop) {
        editor.putInt(preferenceKey(profile, 5), crop.rotationDegrees)
                .putInt(preferenceKey(profile, 6), crop.rotationMode)
                .putBoolean(preferenceKey(profile, 7), crop.mirrorHorizontally);
    }

    static void writeOutputTransform(
            SharedPreferences.Editor editor, ParkingCameraProfile profile, DirectCameraCrop crop) {
        String prefix = parkingPrefix(profile);
        editor.putInt(prefix + "rotation", crop.rotationDegrees)
                .putInt(prefix + "rotation_mode", crop.rotationMode)
                .putBoolean(prefix + "mirror", crop.mirrorHorizontally);
    }

    static void write(
            SharedPreferences.Editor editor, ParkingCameraProfile profile, DirectCameraCrop crop) {
        if (profile == null || crop == null) throw new IllegalArgumentException("crop required");
        String prefix = parkingPrefix(profile);
        editor.putFloat(prefix + "x", crop.left)
                .putFloat(prefix + "y", crop.top)
                .putFloat(prefix + "width", crop.width)
                .putFloat(prefix + "height", crop.height)
                .putInt(prefix + "aspect", crop.aspectMode)
                .putInt(prefix + "rotation", crop.rotationDegrees)
                .putInt(prefix + "rotation_mode", crop.rotationMode)
                .putBoolean(prefix + "mirror", crop.mirrorHorizontally);
    }

    static DirectCameraCrop loadCorrected(
            SharedPreferences preferences, CameraProfile profile, DirectCameraCrop raw) {
        String prefix = correctedPrefix(profile);
        if (!preferences.contains(prefix + "left")) {
            return hasRawPreferences(preferences, profile)
                    ? raw.centered() : defaultCorrectedFor(profile, raw);
        }
        try {
            int correctedAspect = readCorrectedAspect(preferences, prefix + "aspect", -1);
            int aspect = correctedAspect >= ASPECT_FOUR_THREE
                    ? correctedAspect : raw.aspectMode;
            DirectCameraCrop stored = normalized(
                    preferences.getFloat(prefix + "left", raw.left),
                    preferences.getFloat(prefix + "top", raw.top),
                    preferences.getFloat(prefix + "width", raw.width),
                    preferences.getFloat(prefix + "height", raw.height),
                    aspect, 0, CameraRotation.MODE_FIT, LEGACY_MIN_SIZE);
            DirectCameraCrop migrated = migrateActive(stored);
            return independentCorrected(migrated, raw);
        } catch (RuntimeException invalidActiveValue) {
            return raw.centered();
        }
    }

    static DirectCameraCrop loadCorrected(
            SharedPreferences preferences, ParkingCameraProfile profile, DirectCameraCrop raw) {
        if (profile == null || raw == null) throw new IllegalArgumentException("crop required");
        String prefix = parkingPrefix(profile) + "corrected_";
        if (!preferences.contains(prefix + "x")) return raw.centered();
        try {
            int correctedAspect = readCorrectedAspect(preferences, prefix + "aspect", -1);
            int aspect = correctedAspect >= ASPECT_FOUR_THREE
                    ? correctedAspect : raw.aspectMode;
            DirectCameraCrop stored = normalized(
                    preferences.getFloat(prefix + "x", raw.left),
                    preferences.getFloat(prefix + "y", raw.top),
                    preferences.getFloat(prefix + "width", raw.width),
                    preferences.getFloat(prefix + "height", raw.height),
                    aspect,
                    correctedAspect == ASPECT_FREE ? 0 : raw.rotationDegrees,
                    correctedAspect == ASPECT_FREE
                            ? CameraRotation.MODE_FIT : raw.rotationMode,
                    LEGACY_MIN_SIZE);
            DirectCameraCrop migrated = migrateActive(stored);
            return independentCorrected(migrated, raw);
        } catch (RuntimeException invalidValue) {
            return raw.centered();
        }
    }

    static void saveCorrected(
            SharedPreferences preferences, CameraProfile profile, DirectCameraCrop crop) {
        SharedPreferences.Editor editor = preferences.edit();
        if (!sameGeometry(loadCorrected(preferences, profile, load(preferences, profile)), crop)) {
            BlindSpotOverlayController.pinFrameAspect(editor, preferences, profile);
        }
        writeCorrected(editor, profile, crop);
        editor.apply();
    }

    static void saveCorrected(
            SharedPreferences preferences, ParkingCameraProfile profile, DirectCameraCrop crop) {
        SharedPreferences.Editor editor = preferences.edit();
        writeCorrected(editor, profile, crop);
        editor.apply();
    }

    /** Saves a corrected-stage geometry edit and records its independent FREE aspect atomically. */
    static DirectCameraCrop saveCorrectedGeometryEdit(
            SharedPreferences preferences, CameraProfile profile, DirectCameraCrop crop) {
        if (preferences == null || profile == null || crop == null) {
            throw new IllegalArgumentException("crop required");
        }
        DirectCameraCrop accepted = requireUiGeometry(crop.left, crop.top, crop.width,
                crop.height, crop.rotationDegrees, crop.rotationMode)
                .withMirrorHorizontally(crop.mirrorHorizontally);
        SharedPreferences.Editor editor = preferences.edit();
        BlindSpotOverlayController.pinFrameAspect(editor, preferences, profile);
        writeCorrected(editor, profile, accepted);
        editor.putInt(correctedAspectKey(profile), ASPECT_FREE);
        editor.apply();
        return accepted;
    }

    /** Parking equivalent of {@link #saveCorrectedGeometryEdit(SharedPreferences, CameraProfile, DirectCameraCrop)}. */
    static DirectCameraCrop saveCorrectedGeometryEdit(
            SharedPreferences preferences, ParkingCameraProfile profile, DirectCameraCrop crop) {
        if (preferences == null || profile == null || crop == null) {
            throw new IllegalArgumentException("crop required");
        }
        DirectCameraCrop accepted = requireUiGeometry(crop.left, crop.top, crop.width,
                crop.height, crop.rotationDegrees, crop.rotationMode)
                .withMirrorHorizontally(crop.mirrorHorizontally);
        SharedPreferences.Editor editor = preferences.edit();
        writeCorrected(editor, profile, accepted);
        editor.putInt(correctedAspectKey(profile), ASPECT_FREE);
        editor.apply();
        return accepted;
    }

    /**
     * Saves the first explicit RAW geometry edit without reshaping the other dimension.
     * RAW is switched to FREE while the untouched effective corrected ROI is pinned before
     * a missing/legacy corrected stage could start inheriting the new RAW geometry.
     */
    static DirectCameraCrop saveRawGeometryEdit(
            SharedPreferences preferences, CameraProfile profile, DirectCameraCrop crop) {
        if (preferences == null || profile == null || crop == null) {
            throw new IllegalArgumentException("crop required");
        }
        DirectCameraCrop accepted = requireUiGeometry(crop.left, crop.top, crop.width,
                crop.height, crop.rotationDegrees, crop.rotationMode)
                .withMirrorHorizontally(crop.mirrorHorizontally);
        DirectCameraCrop priorRaw = load(preferences, profile);
        DirectCameraCrop priorCorrected = loadCorrected(preferences, profile, priorRaw);
        SharedPreferences.Editor editor = preferences.edit();
        BlindSpotOverlayController.pinFrameAspect(editor, preferences, profile);
        if (readCorrectedAspect(preferences, correctedAspectKey(profile), -1) != ASPECT_FREE) {
            DirectCameraCrop pinned = requireUiGeometry(priorCorrected.left,
                    priorCorrected.top, priorCorrected.width, priorCorrected.height,
                    priorCorrected.rotationDegrees, priorCorrected.rotationMode)
                    .withMirrorHorizontally(priorCorrected.mirrorHorizontally);
            writeCorrected(editor, profile, pinned);
            editor.putInt(correctedAspectKey(profile), ASPECT_FREE);
        }
        write(editor, profile, accepted);
        editor.apply();
        return accepted;
    }

    /** Alias kept for host callers that describe the operation as an atomic save. */
    static DirectCameraCrop saveRawGeometry(
            SharedPreferences preferences, CameraProfile profile, DirectCameraCrop crop) {
        return saveRawGeometryEdit(preferences, profile, crop);
    }

    /** Parking equivalent of {@link #saveRawGeometryEdit(SharedPreferences, CameraProfile, DirectCameraCrop)}. */
    static DirectCameraCrop saveRawGeometryEdit(
            SharedPreferences preferences, ParkingCameraProfile profile, DirectCameraCrop crop) {
        if (preferences == null || profile == null || crop == null) {
            throw new IllegalArgumentException("crop required");
        }
        DirectCameraCrop accepted = requireUiGeometry(crop.left, crop.top, crop.width,
                crop.height, crop.rotationDegrees, crop.rotationMode)
                .withMirrorHorizontally(crop.mirrorHorizontally);
        DirectCameraCrop priorRaw = load(preferences, profile);
        DirectCameraCrop priorCorrected = loadCorrected(preferences, profile, priorRaw);
        SharedPreferences.Editor editor = preferences.edit();
        if (readCorrectedAspect(preferences, correctedAspectKey(profile), -1) != ASPECT_FREE) {
            DirectCameraCrop pinned = requireUiGeometry(priorCorrected.left,
                    priorCorrected.top, priorCorrected.width, priorCorrected.height,
                    priorCorrected.rotationDegrees, priorCorrected.rotationMode)
                    .withMirrorHorizontally(priorCorrected.mirrorHorizontally);
            writeCorrected(editor, profile, pinned);
            editor.putInt(correctedAspectKey(profile), ASPECT_FREE);
        }
        write(editor, profile, accepted);
        editor.apply();
        return accepted;
    }

    /** Parking alias kept for host callers that describe the operation as an atomic save. */
    static DirectCameraCrop saveRawGeometry(
            SharedPreferences preferences, ParkingCameraProfile profile, DirectCameraCrop crop) {
        return saveRawGeometryEdit(preferences, profile, crop);
    }

    /** Strict normalized geometry for UI numeric and gesture edits. */
    static DirectCameraCrop requireUiGeometry(
            float left, float top, float width, float height,
            int rotationDegrees, int rotationMode) {
        SourceCropPolicy.requireValid(left, top, width, height);
        if (!CameraRotation.isValid(rotationDegrees)
                || !CameraRotation.isValidMode(rotationMode)) {
            throw new IllegalArgumentException("invalid rotation");
        }
        return new DirectCameraCrop(left, top, width, height, ASPECT_FREE,
                rotationDegrees, rotationMode);
    }

    /** Produces a FREE geometry edit while retaining output transform and mirror. */
    DirectCameraCrop withIndependentGeometry(
            float left, float top, float width, float height) {
        DirectCameraCrop result = requireUiGeometry(left, top, width, height,
                rotationDegrees, rotationMode);
        return result.withMirrorHorizontally(mirrorHorizontally);
    }

    DirectCameraCrop moveIndependent(float dx, float dy) {
        return withIndependentGeometry(left + dx, top + dy, width, height);
    }

    DirectCameraCrop resizeIndependent(int edges, float dx, float dy) {
        boolean dragLeft = (edges & EDGE_LEFT) != 0;
        boolean dragRight = (edges & EDGE_RIGHT) != 0;
        boolean dragTop = (edges & EDGE_TOP) != 0;
        boolean dragBottom = (edges & EDGE_BOTTOM) != 0;
        if (!(dragLeft || dragRight || dragTop || dragBottom)) {
            return moveIndependent(dx, dy);
        }
        float nextLeft = left + (dragLeft ? dx : 0.0f);
        float nextTop = top + (dragTop ? dy : 0.0f);
        float nextRight = right() + (dragRight ? dx : 0.0f);
        float nextBottom = bottom() + (dragBottom ? dy : 0.0f);
        return withIndependentGeometry(nextLeft, nextTop,
                dragLeft || dragRight ? nextRight - nextLeft : width,
                dragTop || dragBottom ? nextBottom - nextTop : height);
    }

    DirectCameraCrop withRotationStrict(int degrees) {
        int safeDegrees = CameraRotation.clamp(degrees);
        DirectCameraCrop strict = requireUiGeometry(left, top, width, height,
                safeDegrees, rotationMode);
        return new DirectCameraCrop(left, top, width, height, aspectMode,
                strict.rotationDegrees, rotationMode, mirrorHorizontally);
    }

    static void writeCorrected(
            SharedPreferences.Editor editor, CameraProfile profile, DirectCameraCrop crop) {
        String prefix = correctedPrefix(profile);
        editor.putFloat(prefix + "left", crop.left)
                .putFloat(prefix + "top", crop.top)
                .putFloat(prefix + "width", crop.width)
                .putFloat(prefix + "height", crop.height);
    }

    static void writeCorrected(
            SharedPreferences.Editor editor, ParkingCameraProfile profile, DirectCameraCrop crop) {
        if (profile == null || crop == null) throw new IllegalArgumentException("crop required");
        String prefix = parkingPrefix(profile) + "corrected_";
        editor.putFloat(prefix + "x", crop.left)
                .putFloat(prefix + "y", crop.top)
                .putFloat(prefix + "width", crop.width)
                .putFloat(prefix + "height", crop.height);
    }

    /** Keeps corrected ROI coordinates exact when RAW metadata/output rotation changes. */
    private static DirectCameraCrop independentCorrected(
            DirectCameraCrop corrected, DirectCameraCrop raw) {
        return new DirectCameraCrop(corrected.left, corrected.top,
                corrected.width, corrected.height, corrected.aspectMode,
                raw.rotationDegrees, raw.rotationMode, raw.mirrorHorizontally);
    }

    DirectCameraCrop withGeometry(DirectCameraCrop geometry) {
        return new DirectCameraCrop(
                geometry.left, geometry.top, geometry.width, geometry.height,
                geometry.aspectMode, rotationDegrees, rotationMode,
                mirrorHorizontally).requireFinalGeometry(SourceCropPolicy.MIN_SIZE);
    }

    DirectCameraCrop withOutputTransform(int degrees, int mode) {
        return new DirectCameraCrop(left, top, width, height, aspectMode,
                CameraRotation.clamp(degrees),
                CameraRotation.isValidMode(mode) ? mode : CameraRotation.MODE_FIT,
                mirrorHorizontally)
                .requireFinalGeometry(SourceCropPolicy.MIN_SIZE);
    }

    /** Applies output-only defaults without changing the calibrated geometry. */
    DirectCameraCrop withOutputTransformPreservingGeometry(
            int degrees, int mode, boolean mirror) {
        return new DirectCameraCrop(left, top, width, height, aspectMode,
                CameraRotation.clamp(degrees),
                CameraRotation.isValidMode(mode) ? mode : CameraRotation.MODE_FIT,
                mirror);
    }

    DirectCameraCrop geometryOnly() {
        return new DirectCameraCrop(left, top, width, height, aspectMode,
                0, CameraRotation.MODE_FIT, mirrorHorizontally);
    }

    private static String correctedPrefix(CameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        return "direct_crop_v3_corrected_" + profile.id + "_";
    }

    static String correctedAspectKey(CameraProfile profile) {
        return correctedPrefix(profile) + "aspect";
    }

    static String correctedAspectKey(ParkingCameraProfile profile) {
        return parkingPrefix(profile) + "corrected_aspect";
    }

    private static int readCorrectedAspect(
            SharedPreferences preferences, String key, int fallback) {
        if (!preferences.contains(key)) return fallback;
        try {
            int value = preferences.getInt(key, fallback);
            return value >= ASPECT_FOUR_THREE && value <= ASPECT_FREE ? value : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean hasRawPreferences(
            SharedPreferences preferences, CameraProfile profile) {
        for (int field = 0; field < 4; field++) {
            if (preferences.contains(preferenceKey(profile, field))) return true;
        }
        return false;
    }

    private static String parkingPrefix(ParkingCameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("parking profile required");
        return "parking_direct_crop_v1_" + profile.wireName.toLowerCase(java.util.Locale.US) + "_";
    }

    static DirectCameraCrop defaultFor(boolean rightCamera, int aspectMode) {
        float width = 0.65f;
        float height = width * heightPerWidth(
                aspectMode == ASPECT_FREE ? ASPECT_FOUR_THREE : aspectMode);
        return of(rightCamera ? 0.35f : 0.0f, 0.04f, width, height, aspectMode);
    }

    static DirectCameraCrop of(float left, float top, float width) {
        return of(left, top, width, width * heightPerWidth(ASPECT_FOUR_THREE),
                ASPECT_FOUR_THREE);
    }

    static DirectCameraCrop of(
            float left, float top, float width, float height, int aspectMode) {
        return of(left, top, width, height, aspectMode, CameraRotation.DEFAULT_DEGREES);
    }

    static DirectCameraCrop of(
            float left, float top, float width, float height, int aspectMode,
            int rotationDegrees) {
        return of(left, top, width, height, aspectMode, rotationDegrees,
                CameraRotation.MODE_FIT);
    }

    static DirectCameraCrop of(
            float left, float top, float width, float height, int aspectMode,
            int rotationDegrees, int rotationMode) {
        return normalized(left, top, width, height, aspectMode,
                rotationDegrees, rotationMode, SourceCropPolicy.MIN_SIZE);
    }

    private static DirectCameraCrop normalized(
            float left, float top, float width, float height, int aspectMode,
            int rotationDegrees, int rotationMode, float minimumSize) {
        int safeMode = sanitizeAspectMode(aspectMode);
        int safeRotation = CameraRotation.clamp(rotationDegrees);
        int safeRotationMode = CameraRotation.isValidMode(rotationMode)
                ? rotationMode : CameraRotation.MODE_FIT;
        float safeWidth = clamp(finite(width) && width > 0.0f ? width : 0.65f,
                minimumSize, 1.0f);
        float safeHeight = clamp(finite(height) && height > 0.0f ? height
                : safeWidth * heightPerWidth(ASPECT_FOUR_THREE), minimumSize, 1.0f);
        return new DirectCameraCrop(
                clamp(finite(left) ? left : 0.0f, 0.0f, 1.0f - safeWidth),
                clamp(finite(top) ? top : 0.04f, 0.0f, 1.0f - safeHeight),
                safeWidth, safeHeight, safeMode, safeRotation,
                safeRotationMode).requireFinalGeometry(minimumSize);
    }

    static DirectCameraCrop parsePercent(
            String x, String y, String width, String height, int aspectMode,
            int rotationDegrees, int rotationMode) {
        float left = parsePercentValue(x, "X");
        float top = parsePercentValue(y, "Y");
        float parsedWidth = parsePercentValue(width, "W");
        float parsedHeight = parsePercentValue(height, "H");
        return requireNormalized(left, top, parsedWidth, parsedHeight, aspectMode,
                rotationDegrees, rotationMode);
    }

    static DirectCameraCrop requireNormalized(
            float left, float top, float width, float height, int aspectMode,
            int rotationDegrees, int rotationMode) {
        SourceCropPolicy.requireValid(left, top, width, height);
        if (aspectMode < ASPECT_FOUR_THREE || aspectMode > ASPECT_FREE
                || !CameraRotation.isValid(rotationDegrees)
                || !CameraRotation.isValidMode(rotationMode)) {
            throw new IllegalArgumentException("Некоректний aspect/rotation mode");
        }
        return new DirectCameraCrop(
                left, top, width, height, aspectMode, rotationDegrees, rotationMode)
                .requireFinalGeometry(SourceCropPolicy.MIN_SIZE);
    }

    private static DirectCameraCrop migrateActive(DirectCameraCrop stored) {
        if (!SourceCropPolicy.needsMigration(stored.width, stored.height)) return stored;
        float[] geometry = SourceCropPolicy.migrate(
                stored.left, stored.top, stored.width, stored.height);
        return of(geometry[0], geometry[1], geometry[2], geometry[3],
                stored.aspectMode, stored.rotationDegrees, stored.rotationMode)
                .withMirrorHorizontally(stored.mirrorHorizontally);
    }

    DirectCameraCrop mirrored() {
        return new DirectCameraCrop(
                1.0f - left - width, top, width, height, aspectMode,
                -rotationDegrees, rotationMode, mirrorHorizontally);
    }

    DirectCameraCrop withMirrorHorizontally(boolean mirror) {
        return mirror == mirrorHorizontally ? this
                : new DirectCameraCrop(left, top, width, height, aspectMode,
                        rotationDegrees, rotationMode, mirror);
    }

    DirectCameraCrop withAspectMode(int mode) {
        int safeMode = sanitizeAspectMode(mode);
        if (safeMode == aspectMode) return this;
        return of(left, top, width,
                safeMode == ASPECT_FREE ? height : width * heightPerWidth(safeMode),
                safeMode, rotationDegrees, rotationMode)
                .withMirrorHorizontally(mirrorHorizontally);
    }

    DirectCameraCrop withRotation(int degrees) {
        int safeDegrees = CameraRotation.clamp(degrees);
        return safeDegrees == rotationDegrees ? this
                : new DirectCameraCrop(left, top, width, height, aspectMode,
                        safeDegrees, rotationMode, mirrorHorizontally).requireFinalGeometry(SourceCropPolicy.MIN_SIZE);
    }

    DirectCameraCrop withRotationMode(int mode) {
        int safeMode = CameraRotation.isValidMode(mode) ? mode : CameraRotation.MODE_FIT;
        return safeMode == rotationMode ? this
                : new DirectCameraCrop(left, top, width, height, aspectMode,
                        rotationDegrees, safeMode, mirrorHorizontally).requireFinalGeometry(SourceCropPolicy.MIN_SIZE);
    }

    DirectCameraCrop centered() {
        return new DirectCameraCrop(
                (1.0f - width) / 2.0f,
                (1.0f - height) / 2.0f,
                width, height, aspectMode, rotationDegrees, rotationMode,
                mirrorHorizontally);
    }

    DirectCameraCrop move(float dx, float dy) {
        return of(left + dx, top + dy, width, height, aspectMode,
                rotationDegrees, rotationMode).withMirrorHorizontally(mirrorHorizontally);
    }

    DirectCameraCrop resize(int edges, float dx, float dy) {
        if (aspectMode == ASPECT_FREE) return resizeFree(edges, dx, dy);

        boolean dragLeft = (edges & EDGE_LEFT) != 0;
        boolean dragRight = (edges & EDGE_RIGHT) != 0;
        boolean dragTop = (edges & EDGE_TOP) != 0;
        boolean dragBottom = (edges & EDGE_BOTTOM) != 0;
        if (!(dragLeft || dragRight || dragTop || dragBottom)) return move(dx, dy);

        float ratio = heightPerWidth(aspectMode);
        if ((dragLeft || dragRight) && (dragTop || dragBottom)) {
            float anchorX = dragLeft ? right() : left;
            float anchorY = dragTop ? bottom() : top;
            float movingX = (dragLeft ? left : right()) + dx;
            float movingY = (dragTop ? top : bottom()) + dy;
            float widthFromX = Math.abs(anchorX - movingX);
            float widthFromY = Math.abs(anchorY - movingY) / ratio;
            float requestedWidth = Math.abs(dx * SOURCE_WIDTH)
                    >= Math.abs(dy * SOURCE_HEIGHT) ? widthFromX : widthFromY;
            float maxWidthX = dragLeft ? anchorX : 1.0f - anchorX;
            float maxHeight = dragTop ? anchorY : 1.0f - anchorY;
            float safeWidth = clamp(requestedWidth, TOUCH_MIN_WIDTH,
                    Math.min(maxWidthX, maxHeight / ratio));
            return new DirectCameraCrop(
                    dragLeft ? anchorX - safeWidth : anchorX,
                    dragTop ? anchorY - safeWidth * ratio : anchorY,
                    safeWidth, safeWidth * ratio, aspectMode,
                    rotationDegrees, rotationMode, mirrorHorizontally).requireFinalGeometry(SourceCropPolicy.MIN_SIZE);
        }

        if (dragLeft || dragRight) {
            float anchorX = dragLeft ? right() : left;
            float movingX = (dragLeft ? left : right()) + dx;
            float centerY = top + height / 2.0f;
            float maxWidthX = dragLeft ? anchorX : 1.0f - anchorX;
            float maxWidthY = 2.0f * Math.min(centerY, 1.0f - centerY) / ratio;
            float safeWidth = clamp(Math.abs(anchorX - movingX), TOUCH_MIN_WIDTH,
                    Math.min(maxWidthX, maxWidthY));
            return new DirectCameraCrop(dragLeft ? anchorX - safeWidth : anchorX,
                    centerY - safeWidth * ratio / 2.0f,
                    safeWidth, safeWidth * ratio, aspectMode,
                    rotationDegrees, rotationMode, mirrorHorizontally).requireFinalGeometry(SourceCropPolicy.MIN_SIZE);
        }

        float anchorY = dragTop ? bottom() : top;
        float movingY = (dragTop ? top : bottom()) + dy;
        float centerX = left + width / 2.0f;
        float maxHeight = dragTop ? anchorY : 1.0f - anchorY;
        float maxWidthX = 2.0f * Math.min(centerX, 1.0f - centerX);
        float safeWidth = clamp(Math.abs(anchorY - movingY) / ratio,
                TOUCH_MIN_WIDTH, Math.min(maxWidthX, maxHeight / ratio));
        return new DirectCameraCrop(centerX - safeWidth / 2.0f,
                dragTop ? anchorY - safeWidth * ratio : anchorY,
                safeWidth, safeWidth * ratio, aspectMode,
                rotationDegrees, rotationMode, mirrorHorizontally).requireFinalGeometry(SourceCropPolicy.MIN_SIZE);
    }

    private DirectCameraCrop resizeFree(int edges, float dx, float dy) {
        boolean dragLeft = (edges & EDGE_LEFT) != 0;
        boolean dragRight = (edges & EDGE_RIGHT) != 0;
        boolean dragTop = (edges & EDGE_TOP) != 0;
        boolean dragBottom = (edges & EDGE_BOTTOM) != 0;
        if (!(dragLeft || dragRight || dragTop || dragBottom)) return move(dx, dy);

        float nextLeft = left;
        float nextTop = top;
        float nextRight = right();
        float nextBottom = bottom();
        if (dragLeft) {
            nextLeft = clamp(left + dx, 0.0f, nextRight - TOUCH_MIN_WIDTH);
        }
        if (dragRight) {
            nextRight = clamp(right() + dx, nextLeft + TOUCH_MIN_WIDTH, 1.0f);
        }
        if (dragTop) {
            nextTop = clamp(top + dy, 0.0f, nextBottom - TOUCH_MIN_HEIGHT);
        }
        if (dragBottom) {
            nextBottom = clamp(bottom() + dy, nextTop + TOUCH_MIN_HEIGHT, 1.0f);
        }
        return new DirectCameraCrop(nextLeft, nextTop,
                dragLeft || dragRight ? nextRight - nextLeft : width,
                dragTop || dragBottom ? nextBottom - nextTop : height, ASPECT_FREE,
                rotationDegrees, rotationMode, mirrorHorizontally).requireFinalGeometry(SourceCropPolicy.MIN_SIZE);
    }

    private DirectCameraCrop requireFinalGeometry(float minimumSize) {
        if (minimumSize >= SourceCropPolicy.MIN_SIZE) {
            SourceCropPolicy.requireValid(left, top, width, height);
        } else if (!finite(left) || !finite(top) || !finite(width) || !finite(height)
                || left < 0.0f || top < 0.0f
                || width < minimumSize || height < minimumSize
                || right() > 1.0f || bottom() > 1.0f) {
            throw new IllegalArgumentException("invalid normalized source crop");
        }
        return this;
    }

    float outputAspect() {
        return width * SOURCE_WIDTH / (height * SOURCE_HEIGHT);
    }

    private static boolean sameGeometry(DirectCameraCrop first, DirectCameraCrop second) {
        return first.left == second.left && first.top == second.top
                && first.width == second.width && first.height == second.height;
    }

    float right() {
        return left + width;
    }

    float bottom() {
        return top + height;
    }

    static String aspectLabel(int mode) {
        switch (sanitizeAspectMode(mode)) {
            case ASPECT_SIXTEEN_NINE: return "16:9";
            case ASPECT_ONE_ONE: return "1:1";
            case ASPECT_FREE: return "Вільний";
            default: return "4:3";
        }
    }

    private static int sanitizeAspectMode(int mode) {
        return mode >= ASPECT_FOUR_THREE && mode <= ASPECT_FREE
                ? mode : ASPECT_FOUR_THREE;
    }

    private static float heightPerWidth(int mode) {
        float outputAspect;
        switch (sanitizeAspectMode(mode)) {
            case ASPECT_SIXTEEN_NINE:
                outputAspect = 16.0f / 9.0f;
                break;
            case ASPECT_ONE_ONE:
                outputAspect = 1.0f;
                break;
            default:
                outputAspect = OUTPUT_ASPECT;
                break;
        }
        return SOURCE_WIDTH / (SOURCE_HEIGHT * outputAspect);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean readMirror(
            SharedPreferences preferences, String key, boolean fallback) {
        try {
            return preferences.getBoolean(key, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static float parsePercentValue(String value, String field) {
        try {
            float parsed = Float.parseFloat(value.trim().replace(',', '.')) / 100.0f;
            if (!finite(parsed)) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(field + " має бути числом");
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        if (maximum < minimum) return maximum;
        return Math.max(minimum, Math.min(maximum, value));
    }

}
