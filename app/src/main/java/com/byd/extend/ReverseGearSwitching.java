package com.byd.extend;

import android.content.SharedPreferences;
import java.util.Map;

/** Saved destination is independent of the currently integrated Extend sources. */
public final class ReverseGearSwitching {
    public static final String KEY = "reverse_camera_gear_switch_mode";
    static final String LEGACY_KEY = "reverse_camera_switch_by_gear";
    public static final int NONE = 0, NATIVE = 1, OURS = 2, BOTH = 3;

    private ReverseGearSwitching() {}

    public static int read(SharedPreferences preferences) {
        try {
            if (preferences.contains(KEY)) return validate(preferences.getInt(KEY, NONE));
            return preferences.getBoolean(LEGACY_KEY, false) ? OURS : NONE;
        } catch (RuntimeException failure) {
            android.util.Log.e("ReverseGearSwitching", "read gear-switch destination", failure);
            return NONE;
        }
    }

    static void migrate(SharedPreferences preferences) {
        if (!preferences.contains(KEY)) preferences.edit().putInt(KEY, read(preferences))
                .remove(LEGACY_KEY).apply();
    }

    public static int validate(int mode) {
        if (mode < NONE || mode > BOTH) throw new IllegalArgumentException("Invalid gear-switch mode: " + mode);
        return mode;
    }

    public static boolean own(int mode, boolean frontIntegrated) {
        return frontIntegrated && (mode == OURS || mode == BOTH);
    }

    public static boolean nativeView(int mode) { return mode == NATIVE || mode == BOTH; }

    static boolean followsPanorama(int mode, boolean frontIntegrated) {
        return nativeView(mode) || own(mode, frontIntegrated);
    }

    static void normalizeImport(Map<String, Object> values) {
        if (values.containsKey(KEY)) {
            Object mode = values.get(KEY);
            if (!(mode instanceof Integer)) throw new IllegalArgumentException("Integer required: " + KEY);
            validate((Integer) mode);
        } else if (values.containsKey(LEGACY_KEY)) {
            Object legacy = values.get(LEGACY_KEY);
            if (!(legacy instanceof Boolean)) throw new IllegalArgumentException("Boolean required: " + LEGACY_KEY);
            values.put(KEY, (Boolean) legacy ? OURS : NONE);
        }
        values.remove(LEGACY_KEY);
    }
}
