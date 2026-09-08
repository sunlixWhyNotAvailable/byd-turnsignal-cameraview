package com.byd.extend;

import android.content.SharedPreferences;

/**
 * Independent persisted rearview-mirror state.  Mirror settings intentionally
 * do not share blind, parking, or reverse calibration slots.
 */
public final class RearviewMirrorSettings {
    public static final int TARGET_TABLET = 0;
    public static final int TARGET_CLUSTER = 1;
    public static final int REAR_CAMERA_INDEX = ReverseCameraLayout.REAR_CAMERA_INDEX;
    public static final int DEFAULT_BORDER_ARGB = 0xFF000000;
    public static final int MIN_BORDER_DP = 0;
    public static final int MAX_BORDER_DP = 16;

    // These names are shared with the Compose UI contract.  Placement values
    // are percentages on disk for UI compatibility; the Java model exposes
    // fractions of the complete target display.
    public static final String PREF_ENABLED = "mirror_enabled";
    public static final String PREF_SUPPRESS_WHILE_PANORAMA =
            "mirror_suppress_while_panorama";
    public static final String PREF_TARGET = "mirror_target";
    public static final String PREF_X = "mirror_x";
    public static final String PREF_Y = "mirror_y";
    public static final String PREF_WIDTH = "mirror_width";
    public static final String PREF_HEIGHT = "mirror_height";
    public static final String PREF_BORDER_DP = "mirror_border_width";
    public static final String PREF_BORDER_ARGB = "mirror_border_color";
    public static final String PREF_MANUAL_HIDDEN = "mirror_hidden";
    public static final String PREF_PRESET_PRESENT = "mirror_preset_available";
    // UI-contract aliases keep Java and Compose on one preference namespace.
    public static final String PREF_HIDDEN = PREF_MANUAL_HIDDEN;
    public static final String PREF_BORDER_WIDTH = PREF_BORDER_DP;
    public static final String PREF_BORDER_COLOR = PREF_BORDER_ARGB;
    public static final String PREF_PRESET_AVAILABLE = PREF_PRESET_PRESENT;
    public static final String PREF_ORIGINAL_X = "mirror_original_x";
    public static final String PREF_ORIGINAL_Y = "mirror_original_y";
    public static final String PREF_ORIGINAL_WIDTH = "mirror_original_width";
    public static final String PREF_ORIGINAL_HEIGHT = "mirror_original_height";
    public static final String PREF_CORRECTION = "mirror_correction";
    public static final String PREF_FOV = "mirror_fov";
    public static final String PREF_PROJECTION = "mirror_projection";
    public static final String PREF_CORRECTED_X = "mirror_corrected_x";
    public static final String PREF_CORRECTED_Y = "mirror_corrected_y";
    public static final String PREF_CORRECTED_WIDTH = "mirror_corrected_width";
    public static final String PREF_CORRECTED_HEIGHT = "mirror_corrected_height";
    public static final String PREF_MIRRORED = "mirror_mirrored";
    public static final String PREF_OUTPUT_MODE = "mirror_output_mode";
    public static final String PREF_ROTATION = "mirror_rotation";
    private static final String ORIGINAL_PREFIX = "mirror_original_";
    private static final String CORRECTED_PREFIX = "mirror_corrected_";
    private static final String PRESET_PREFIX = "mirror_preset_";

    private final SharedPreferences preferences;

    public RearviewMirrorSettings(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        this.preferences = preferences;
    }

    public Settings load() {
        Settings fallback = Settings.defaults();
        boolean enabled = readBoolean(PREF_ENABLED, fallback.enabled);
        int target = readTarget(fallback.target);
        CameraPlacement placement = readPlacement(fallback.placement);
        Calibration calibration = readCalibration(fallback.calibration, ORIGINAL_PREFIX);
        Calibration preset = preferences.getBoolean(PREF_PRESET_PRESENT, false)
                ? readCalibration(null, PRESET_PREFIX) : null;
        int border = clamp(readInt(PREF_BORDER_DP, fallback.borderDp), MIN_BORDER_DP, MAX_BORDER_DP);
        int color = readInt(PREF_BORDER_ARGB, fallback.borderArgb) | 0xFF000000;
        boolean hidden = readBoolean(PREF_MANUAL_HIDDEN, fallback.manualHidden);
        return new Settings(enabled, target, placement, calibration, preset,
                border, color, hidden);
    }

    public Settings read() { return load(); }

    public void save(Settings value) {
        if (value == null) throw new IllegalArgumentException("mirror settings are null");
        Settings safe = value.normalized();
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(PREF_ENABLED, safe.enabled)
                .putString(PREF_TARGET, safe.target == TARGET_CLUSTER ? "Cluster" : "Tablet")
                .putFloat(PREF_X, safe.placement.x * 100.0f)
                .putFloat(PREF_Y, safe.placement.y * 100.0f)
                .putFloat(PREF_WIDTH, safe.placement.width * 100.0f)
                .putFloat(PREF_HEIGHT, safe.placement.height * 100.0f)
                .putInt(PREF_BORDER_DP, safe.borderDp)
                .putInt(PREF_BORDER_ARGB, safe.borderArgb)
                .putBoolean(PREF_MANUAL_HIDDEN, safe.manualHidden)
                .putBoolean(PREF_PRESET_PRESENT, safe.preset != null);
        writeCalibration(editor, ORIGINAL_PREFIX, safe.calibration);
        if (safe.preset == null) removeCalibration(editor, PRESET_PREFIX);
        else writeCalibration(editor, PRESET_PREFIX, safe.preset);
        editor.apply();
    }

    public void write(Settings value) { save(value); }

    public void reset() { save(Settings.defaults()); }

    public static Settings defaults() { return Settings.defaults(); }

    private int readTarget(int fallback) {
        Object value = preferences.getAll().get(PREF_TARGET);
        if (value instanceof String) {
            if ("Cluster".equalsIgnoreCase((String) value)) return TARGET_CLUSTER;
            if ("Tablet".equalsIgnoreCase((String) value)) return TARGET_TABLET;
        }
        // Read the type before accessing it: getString throws for the temporary integer form.
        if (value instanceof Integer) {
            int legacy = (Integer) value;
            if (legacy == TARGET_TABLET || legacy == TARGET_CLUSTER) return legacy;
        }
        return fallback;
    }

    /** Small static seam for runtime owners that already hold shared preferences. */
    public static boolean enabled(SharedPreferences preferences) {
        return new RearviewMirrorSettings(preferences).load().enabled;
    }

    public static boolean suppressWhilePanorama(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        try {
            return preferences.getBoolean(PREF_SUPPRESS_WHILE_PANORAMA, true);
        } catch (RuntimeException invalidPreference) {
            return true;
        }
    }

    public static boolean hidden(SharedPreferences preferences) {
        return new RearviewMirrorSettings(preferences).load().manualHidden;
    }

    public static void setHidden(SharedPreferences preferences, boolean hidden) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        preferences.edit().putBoolean(PREF_MANUAL_HIDDEN, hidden).apply();
    }

    public static int target(SharedPreferences preferences) {
        return new RearviewMirrorSettings(preferences).load().target;
    }

    public static CameraPlacement placement(SharedPreferences preferences) {
        return new RearviewMirrorSettings(preferences).load().placement;
    }

    public static DirectCameraCrop raw(SharedPreferences preferences) {
        Settings value = new RearviewMirrorSettings(preferences).load();
        return toCrop(value.calibration.raw, value.calibration.mirrored,
                value.calibration.rotationDegrees, value.calibration.rotationMode);
    }

    public static DirectCameraCrop corrected(SharedPreferences preferences) {
        Settings value = new RearviewMirrorSettings(preferences).load();
        return toCrop(value.calibration.corrected, value.calibration.mirrored,
                value.calibration.rotationDegrees, value.calibration.rotationMode);
    }

    public static CameraDewarpConfig dewarp(SharedPreferences preferences) {
        Settings value = new RearviewMirrorSettings(preferences).load();
        return CameraDewarpConfig.of(CameraDewarpConfig.LENS_REAR,
                value.calibration.enabled, value.calibration.fovDegrees,
                value.calibration.projection);
    }

    public static int borderDp(SharedPreferences preferences) {
        return new RearviewMirrorSettings(preferences).load().borderDp;
    }

    public static int borderArgb(SharedPreferences preferences) {
        return new RearviewMirrorSettings(preferences).load().borderArgb;
    }

    public static void writePlacement(
            SharedPreferences preferences, float x, float y, float width, float height) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        CameraPlacement value = CameraPlacement.bounded(x, y, width, height);
        preferences.edit().putFloat(PREF_X, value.x * 100.0f)
                .putFloat(PREF_Y, value.y * 100.0f)
                .putFloat(PREF_WIDTH, value.width * 100.0f)
                .putFloat(PREF_HEIGHT, value.height * 100.0f).apply();
    }

    public static Calibration preset(SharedPreferences preferences) {
        return new RearviewMirrorSettings(preferences).load().preset;
    }

    public static void writePreset(SharedPreferences preferences, Calibration value) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(PREF_PRESET_PRESENT, value != null);
        if (value == null) removeCalibration(editor, PRESET_PREFIX);
        else writeCalibration(editor, PRESET_PREFIX, value);
        editor.apply();
    }

    private static DirectCameraCrop toCrop(
            CameraPlacement value, boolean mirror, int rotation, int mode) {
        return DirectCameraCrop.of(value.x, value.y, value.width, value.height,
                DirectCameraCrop.ASPECT_FREE, rotation,
                CameraRotation.isValidMode(mode) ? mode : CameraRotation.MODE_FIT)
                .withMirrorHorizontally(mirror);
    }

    private CameraPlacement readPlacement(CameraPlacement fallback) {
        try {
            if (!preferences.contains(PREF_WIDTH) || !preferences.contains(PREF_HEIGHT)) {
                return fallback;
            }
            return CameraPlacement.bounded(
                    preferences.getFloat(PREF_X, fallback.x * 100.0f) / 100.0f,
                    preferences.getFloat(PREF_Y, fallback.y * 100.0f) / 100.0f,
                    preferences.getFloat(PREF_WIDTH, fallback.width * 100.0f) / 100.0f,
                    preferences.getFloat(PREF_HEIGHT, fallback.height * 100.0f) / 100.0f);
        } catch (RuntimeException invalid) { return fallback; }
    }

    private Calibration readCalibration(Calibration fallback, String prefix) {
        try {
            if (!preferences.contains(prefix + "x")) return fallback;
            String geometryPrefix = prefix;
            String correctedPrefix = ORIGINAL_PREFIX.equals(prefix)
                    ? CORRECTED_PREFIX : prefix + "corrected_";
            String valuePrefix = ORIGINAL_PREFIX.equals(prefix) ? "mirror_" : prefix;
            CameraPlacement raw = CameraPlacement.source(
                    preferences.getFloat(geometryPrefix + "x", 0.0f) / 100.0f,
                    preferences.getFloat(geometryPrefix + "y", 0.0f) / 100.0f,
                    preferences.getFloat(geometryPrefix + "width", 100.0f) / 100.0f,
                    preferences.getFloat(geometryPrefix + "height", 100.0f) / 100.0f);
            CameraPlacement corrected = CameraPlacement.source(
                    preferences.getFloat(correctedPrefix + "x", 0.0f) / 100.0f,
                    preferences.getFloat(correctedPrefix + "y", 0.0f) / 100.0f,
                    preferences.getFloat(correctedPrefix + "width", 100.0f) / 100.0f,
                    preferences.getFloat(correctedPrefix + "height", 100.0f) / 100.0f);
            boolean correction = preferences.getBoolean(valuePrefix + "correction", false);
            int fov = clamp(preferences.getInt(valuePrefix + "fov", 100), 60, 170);
            int projection = clamp(preferences.getInt(valuePrefix + "projection", 0), 0, 1);
            boolean mirrored = preferences.getBoolean(valuePrefix + "mirrored", false);
            int rotation = CameraRotation.clamp(preferences.getInt(valuePrefix + "rotation", 0));
            int mode = preferences.getInt(valuePrefix + "output_mode", CameraRotation.MODE_FIT);
            return new Calibration(raw, corrected,
                    correction, fov, projection, mirrored, rotation,
                    CameraRotation.isValidMode(mode) ? mode : CameraRotation.MODE_FIT);
        } catch (RuntimeException invalid) { return fallback; }
    }

    private static void writeCalibration(
            SharedPreferences.Editor editor, String prefix, Calibration value) {
        String correctedPrefix = ORIGINAL_PREFIX.equals(prefix)
                ? CORRECTED_PREFIX : prefix + "corrected_";
        String valuePrefix = ORIGINAL_PREFIX.equals(prefix) ? "mirror_" : prefix;
        editor.putFloat(prefix + "x", value.raw.x * 100.0f)
                .putFloat(prefix + "y", value.raw.y * 100.0f)
                .putFloat(prefix + "width", value.raw.width * 100.0f)
                .putFloat(prefix + "height", value.raw.height * 100.0f)
                .putFloat(correctedPrefix + "x", value.corrected.x * 100.0f)
                .putFloat(correctedPrefix + "y", value.corrected.y * 100.0f)
                .putFloat(correctedPrefix + "width", value.corrected.width * 100.0f)
                .putFloat(correctedPrefix + "height", value.corrected.height * 100.0f)
                .putBoolean(valuePrefix + "correction", value.enabled)
                .putInt(valuePrefix + "fov", value.fovDegrees)
                .putInt(valuePrefix + "projection", value.projection)
                .putBoolean(valuePrefix + "mirrored", value.mirrored)
                .putInt(valuePrefix + "rotation", value.rotationDegrees)
                .putInt(valuePrefix + "output_mode", value.rotationMode);
    }

    private static void removeCalibration(SharedPreferences.Editor editor, String prefix) {
        for (String suffix : new String[]{"x", "y", "width", "height", "corrected_x",
                "corrected_y", "corrected_width", "corrected_height", "correction", "fov",
                "projection", "mirrored", "rotation", "output_mode"}) editor.remove(prefix + suffix);
    }

    private boolean readBoolean(String key, boolean fallback) {
        try { return preferences.getBoolean(key, fallback); }
        catch (RuntimeException invalid) { return fallback; }
    }

    private int readInt(String key, int fallback) {
        try { return preferences.getInt(key, fallback); }
        catch (RuntimeException invalid) { return fallback; }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class Settings {
        public final boolean enabled;
        public final int target;
        public final CameraPlacement placement;
        public final Calibration calibration;
        public final Calibration preset;
        public final int borderDp;
        public final int borderArgb;
        public final boolean manualHidden;

        public Settings(boolean enabled, int target, CameraPlacement placement,
                Calibration calibration, Calibration preset,
                int borderDp, int borderArgb, boolean manualHidden) {
            if (placement == null || calibration == null) {
                throw new IllegalArgumentException("mirror geometry is required");
            }
            this.enabled = enabled;
            this.target = target == TARGET_CLUSTER ? TARGET_CLUSTER : TARGET_TABLET;
            this.placement = placement;
            this.calibration = calibration;
            this.preset = preset;
            this.borderDp = clamp(borderDp, MIN_BORDER_DP, MAX_BORDER_DP);
            this.borderArgb = borderArgb | 0xFF000000;
            this.manualHidden = manualHidden;
        }

        static Settings defaults() {
            CameraPlacement full = CameraPlacement.of(0.0f, 0.0f, 1.0f, 1.0f);
            Calibration calibration = new Calibration(full, full, false, 100, 0,
                    false, 0, CameraRotation.MODE_FIT);
            return new Settings(false, TARGET_TABLET, CameraPlacement.mirrorDemo(),
                    calibration, null, 0, DEFAULT_BORDER_ARGB, false);
        }

        public Settings normalized() {
            return new Settings(enabled, target, CameraPlacement.bounded(
                    placement.x, placement.y, placement.width, placement.height),
                    calibration, preset, borderDp, borderArgb, manualHidden);
        }

        public Settings withEnabled(boolean value) {
            return new Settings(value, target, placement, calibration, preset,
                    borderDp, borderArgb, manualHidden);
        }

        public Settings withPlacement(CameraPlacement value) {
            return new Settings(enabled, target, value, calibration, preset,
                    borderDp, borderArgb, manualHidden);
        }

        public Settings withManualHidden(boolean value) {
            return new Settings(enabled, target, placement, calibration, preset,
                    borderDp, borderArgb, value);
        }
    }

    public static final class Calibration {
        public final CameraPlacement raw;
        public final CameraPlacement corrected;
        public final boolean enabled;
        public final int fovDegrees;
        public final int projection;
        public final boolean mirrored;
        public final int rotationDegrees;
        public final int rotationMode;

        public Calibration(CameraPlacement raw, CameraPlacement corrected, boolean enabled,
                int fovDegrees, int projection, boolean mirrored,
                int rotationDegrees, int rotationMode) {
            if (raw == null || corrected == null) throw new IllegalArgumentException("crop required");
            CameraPlacement.source(raw.x, raw.y, raw.width, raw.height);
            CameraPlacement.source(corrected.x, corrected.y,
                    corrected.width, corrected.height);
            this.raw = raw;
            this.corrected = corrected;
            this.enabled = enabled;
            this.fovDegrees = clamp(fovDegrees, 60, 170);
            this.projection = projection == 1 ? 1 : 0;
            this.mirrored = mirrored;
            this.rotationDegrees = CameraRotation.clamp(rotationDegrees);
            this.rotationMode = CameraRotation.isValidMode(rotationMode)
                    ? rotationMode : CameraRotation.MODE_FIT;
        }
    }
}
