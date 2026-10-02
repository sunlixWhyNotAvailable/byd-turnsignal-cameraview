package com.byd.extend;

import android.content.SharedPreferences;

/** Preferences for the central reverse camera's independent rear/front guidance options. */
public final class ReverseGuidanceSettings {
    public static final String PREF_REAR_DIRECTION_GUIDELINES =
            "reverse_camera_rear_direction_guidelines";
    public static final String PREF_REAR_STEERING_SHIFT =
            "reverse_camera_rear_steering_shift";
    public static final String PREF_FRONT_DIRECTION_GUIDELINES =
            "reverse_camera_front_direction_guidelines";
    public static final String PREF_FRONT_STEERING_SHIFT =
            "reverse_camera_front_steering_shift";

    private ReverseGuidanceSettings() { }

    public static boolean isDirectionGuidelinesEnabled(
            SharedPreferences preferences, boolean front) {
        return preferences.getBoolean(directionGuidelinesKey(front), true);
    }

    public static boolean isCameraShiftWithSteeringEnabled(
            SharedPreferences preferences, boolean front) {
        return preferences.getBoolean(steeringShiftKey(front), true);
    }

    public static void setDirectionGuidelinesEnabled(
            SharedPreferences preferences, boolean front, boolean enabled) {
        preferences.edit().putBoolean(directionGuidelinesKey(front), enabled).apply();
    }

    public static void setCameraShiftWithSteeringEnabled(
            SharedPreferences preferences, boolean front, boolean enabled) {
        preferences.edit().putBoolean(steeringShiftKey(front), enabled).apply();
    }

    private static String directionGuidelinesKey(boolean front) {
        return front ? PREF_FRONT_DIRECTION_GUIDELINES : PREF_REAR_DIRECTION_GUIDELINES;
    }

    private static String steeringShiftKey(boolean front) {
        return front ? PREF_FRONT_STEERING_SHIFT : PREF_REAR_STEERING_SHIFT;
    }
}
