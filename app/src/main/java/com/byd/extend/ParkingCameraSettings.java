package com.byd.extend;

import android.content.SharedPreferences;

/** Validated shared preferences for the parking camera rules. */
public final class ParkingCameraSettings {
    public static final String PREF_PREFIX = "parking_camera_";
    public static final String PREF_MAX_SPEED_KPH = PREF_PREFIX + "max_speed_kph";
    public static final String PREF_ALLOW_DURING_REVERSE =
            PREF_PREFIX + "allow_during_reverse";
    public static final int DEFAULT_DISTANCE_CM = 30;
    public static final int MIN_DISTANCE_CM = 0;
    public static final int MAX_DISTANCE_CM = 150;
    public static final int DEFAULT_MAX_SPEED_KPH = 10;
    public static final int MIN_MAX_SPEED_KPH = 0;
    public static final int MAX_MAX_SPEED_KPH = 300;

    private final SharedPreferences preferences;

    public ParkingCameraSettings(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        this.preferences = preferences;
        migrate(preferences);
    }

    public Rule rule(ParkingCameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile is null");
        return readRule(preferences, profile);
    }

    public Rule rule(int profileId) {
        return rule(ParkingCameraProfile.of(profileId));
    }

    public int maxSpeedKph() {
        return readMaxSpeed(preferences);
    }

    public boolean allowDuringReverse() {
        return readAllowDuringReverse(preferences);
    }

    public void setRule(ParkingCameraProfile profile, Rule rule) {
        if (profile == null || rule == null) throw new IllegalArgumentException("rule is null");
        Rule safe = normalize(rule);
        preferences.edit()
                .putBoolean(enabledKey(profile), safe.enabled)
                .putInt(distanceKey(profile), safe.distanceCm)
                .putBoolean(addCentralKey(profile), safe.addCentral)
                .apply();
    }

    public void setMaxSpeedKph(int value) {
        preferences.edit().putInt(PREF_MAX_SPEED_KPH, clampSpeed(value)).apply();
    }

    public void setAllowDuringReverse(boolean value) {
        preferences.edit().putBoolean(PREF_ALLOW_DURING_REVERSE, value).apply();
    }

    /** Enables or disables every parking view while preserving each rule's other values. */
    public void setAllEnabled(boolean enabled) {
        SharedPreferences.Editor editor = preferences.edit();
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            editor.putBoolean(enabledKey(profile), enabled);
        }
        editor.apply();
    }

    public static Rule defaults(ParkingCameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile is null");
        return new Rule(false, DEFAULT_DISTANCE_CM, false);
    }

    public static Rule readRule(SharedPreferences preferences, ParkingCameraProfile profile) {
        if (preferences == null || profile == null) throw new IllegalArgumentException("null argument");
        Rule fallback = defaults(profile);
        boolean enabled = readBoolean(preferences, enabledKey(profile), fallback.enabled);
        int distance = readInt(preferences, distanceKey(profile), fallback.distanceCm);
        boolean addCentral = readBoolean(preferences, addCentralKey(profile), fallback.addCentral);
        return new Rule(enabled, clampDistanceCm(distance), addCentral);
    }

    public static Rule[] readRules(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        Rule[] result = new Rule[ParkingCameraProfile.COUNT];
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            result[profile.id] = readRule(preferences, profile);
        }
        return result;
    }

    public static int readMaxSpeed(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        return clampSpeed(readInt(preferences, PREF_MAX_SPEED_KPH, DEFAULT_MAX_SPEED_KPH));
    }

    public static boolean readAllowDuringReverse(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        return readBoolean(preferences, PREF_ALLOW_DURING_REVERSE, false);
    }

    /** Writes missing keys and repairs values outside the shared contract. */
    public static void migrate(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            Rule rule = readRule(preferences, profile);
            if (!preferences.contains(enabledKey(profile))) {
                editor.putBoolean(enabledKey(profile), rule.enabled);
                changed = true;
            }
            if (!preferences.contains(distanceKey(profile))) {
                editor.putInt(distanceKey(profile), rule.distanceCm);
                changed = true;
            } else if (readInt(preferences, distanceKey(profile), rule.distanceCm)
                    != rule.distanceCm) {
                editor.putInt(distanceKey(profile), rule.distanceCm);
                changed = true;
            }
            if (!preferences.contains(addCentralKey(profile))) {
                editor.putBoolean(addCentralKey(profile), rule.addCentral);
                changed = true;
            }
        }
        int speed = readMaxSpeed(preferences);
        if (!preferences.contains(PREF_MAX_SPEED_KPH)
                || readInt(preferences, PREF_MAX_SPEED_KPH, speed) != speed) {
            editor.putInt(PREF_MAX_SPEED_KPH, speed);
            changed = true;
        }
        if (!preferences.contains(PREF_ALLOW_DURING_REVERSE)) {
            editor.putBoolean(PREF_ALLOW_DURING_REVERSE, false);
            changed = true;
        }
        if (changed) editor.apply();
    }

    public static int clampDistanceCm(int value) {
        return Math.max(MIN_DISTANCE_CM, Math.min(MAX_DISTANCE_CM, value));
    }

    public static int clampSpeed(int value) {
        return Math.max(MIN_MAX_SPEED_KPH, Math.min(MAX_MAX_SPEED_KPH, value));
    }

    public static String enabledKey(ParkingCameraProfile profile) {
        return PREF_PREFIX + profile.wireName.toLowerCase(java.util.Locale.US) + "_enabled";
    }

    public static String distanceKey(ParkingCameraProfile profile) {
        return PREF_PREFIX + profile.wireName.toLowerCase(java.util.Locale.US) + "_distance_cm";
    }

    public static String addCentralKey(ParkingCameraProfile profile) {
        return PREF_PREFIX + profile.wireName.toLowerCase(java.util.Locale.US) + "_add_central";
    }

    private static boolean readBoolean(SharedPreferences p, String key, boolean fallback) {
        try { return p.getBoolean(key, fallback); } catch (Throwable ignored) { return fallback; }
    }

    private static int readInt(SharedPreferences p, String key, int fallback) {
        try { return p.getInt(key, fallback); } catch (Throwable ignored) { return fallback; }
    }

    private static Rule normalize(Rule rule) {
        return new Rule(rule.enabled, clampDistanceCm(rule.distanceCm), rule.addCentral);
    }

    public static final class Rule {
        public final boolean enabled;
        public final int distanceCm;
        public final boolean addCentral;

        public Rule(boolean enabled, int distanceCm, boolean addCentral) {
            this.enabled = enabled;
            this.distanceCm = clampDistanceCm(distanceCm);
            this.addCentral = addCentral;
        }

        public Rule withEnabled(boolean value) { return new Rule(value, distanceCm, addCentral); }
        public Rule withDistanceCm(int value) { return new Rule(enabled, value, addCentral); }
        public Rule withAddCentral(boolean value) { return new Rule(enabled, distanceCm, value); }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Rule)) return false;
            Rule value = (Rule) other;
            return enabled == value.enabled && distanceCm == value.distanceCm
                    && addCentral == value.addCentral;
        }

        @Override
        public int hashCode() {
            int result = enabled ? 1 : 0;
            result = 31 * result + distanceCm;
            return 31 * result + (addCentral ? 1 : 0);
        }
    }
}
