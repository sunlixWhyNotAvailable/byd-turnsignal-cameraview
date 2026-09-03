package com.byd.extend;

import android.content.SharedPreferences;

/** Persistence boundary for the optional Reverse Widget steering-button binding. */
public final class ReverseSteeringButtonPreferences {
    public static final String KEY_CODE = "reverse_widget_steering_key_code";
    public static final int UNASSIGNED = -1;

    private ReverseSteeringButtonPreferences() {
    }

    /** Reads only the raw Android keyCode; a negative or wrong-typed value is unassigned. */
    public static int load(SharedPreferences preferences) {
        if (preferences == null) return UNASSIGNED;
        int value;
        try {
            value = preferences.getInt(KEY_CODE, UNASSIGNED);
        } catch (ClassCastException malformed) {
            return UNASSIGNED;
        }
        return value < 0 ? UNASSIGNED : value;
    }

    public static void save(SharedPreferences preferences, int keyCode) {
        if (preferences == null) return;
        preferences.edit().putInt(KEY_CODE, keyCode < 0 ? UNASSIGNED : keyCode).apply();
    }

    public static void reset(SharedPreferences preferences) {
        save(preferences, UNASSIGNED);
    }
}
